package com.hongguotv.core

import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * 红果 fqnovel.com 直连签名器
 * 使用编译好的 Go 原生二进制（assets/hongguo_sign）生成签名，
 * 与 guoapp 参考项目 1:1 一致，避免 Kotlin 翻译偏差。
 */
object HongguoSigner {
    private const val UA = "com.phoenix.read/73532 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3; Cronet/TTNetVersion:04657795 2026-01-23 QuicVersion:c67e9834 2025-09-08)"

    @Volatile private var signBin: File? = null

    private fun ensureBinary(): File {
        signBin?.let { if (it.exists()) return it }
        val ctx = AppContext.get()
        // Android 10+ 禁止从 filesDir 执行自写文件，必须用 nativeLibraryDir
        val out = File(ctx.applicationInfo.nativeLibraryDir, "libhongguo_sign.so")
        if (!out.exists()) throw IOException("原生库未找到: ${out.absolutePath}")
        signBin = out
        return out
    }

    private fun hexToBase64(hex: String): String {
        val bytes = ByteArray(hex.length / 2)
        for (i in bytes.indices) {
            bytes[i] = ((Character.digit(hex[i*2], 16) shl 4) or Character.digit(hex[i*2+1], 16)).toByte()
        }
        return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    }

    private fun callSigner(stamp: Long, deviceId: String, rawQuery: String, body: ByteArray): Map<String, String> {
        val bin = ensureBinary()
        val pb = ProcessBuilder(bin.absolutePath, stamp.toString(), deviceId)
        pb.redirectErrorStream(true)
        val proc = pb.start()
        proc.outputStream.use { os ->
            os.write(rawQuery.toByteArray())
            os.write('\n'.code)
            os.write(body)
        }
        val rawOut = StringBuilder()
        BufferedReader(InputStreamReader(proc.inputStream)).useLines { lines ->
            lines.forEach { rawOut.appendLine(it) }
        }
        val exit = proc.waitFor()
        val result = mutableMapOf<String, String>()
        rawOut.toString().lineSequence().forEach { line ->
            val eq = line.indexOf('=')
            if (eq > 0) result[line.substring(0, eq)] = line.substring(eq + 1)
        }
        if (exit != 0) throw IOException("签名器退出码=$exit 输出=${rawOut.toString().take(300)}")
        if (!result.containsKey("GORGON")) throw IOException("签名器无GORGON 输出=${rawOut.toString().take(300)}")
        return result
    }

    fun resolve(vid: String): StreamInfo {
        val now = System.currentTimeMillis()
        val stamp = now / 1000
        val deviceId = "2720754845237044145"
        val installId = "2684923656717301508"

        val qp = sortedMapOf(
            "ac" to "wifi",
            "aid" to "8662",
            "app_name" to "novelread",
            "channel" to "update_64",
            "device_brand" to "Redmi",
            "device_id" to deviceId,
            "device_platform" to "android",
            "device_type" to "25053RT47C",
            "dpi" to "520",
            "iid" to installId,
            "language" to "zh",
            "manifest_version_code" to "73532",
            "os" to "android",
            "os_api" to "36",
            "os_version" to "16",
            "resolution" to "1280*2772",
            "ssmix" to "a",
            "update_version_code" to "73532",
            "version_code" to "73532",
            "version_name" to "7.3.5.32",
            "_rticket" to now.toString(),
        )
        val rawQuery = qp.entries.joinToString("&") { (k, v) ->
            val ev = URLEncoder.encode(v, "UTF-8")
                .replace("+", "%20")
                .replace("*", "%2A")
            "$k=$ev"
        }

        val body = "{\"biz_param\":{\"need_all_video_definition\":true,\"video_platform\":3},\"content_type\":1,\"video_id\":\"$vid\"}".toByteArray()

        val sig = callSigner(stamp, deviceId, rawQuery, body)
        val gorgon = sig["GORGON"] ?: throw IOException("签名器未返回 Gorgon")
        val argus = hexToBase64(sig["ARGUS"] ?: throw IOException("签名器未返回 Argus"))
        val ladon = hexToBase64(sig["LADON"] ?: throw IOException("签名器未返回 Ladon"))
        val bodyMd5 = MessageDigest.getInstance("MD5").digest(body).joinToString("") { "%02X".format(it) }

        val conn = URL("https://api5-normal-sinfonlineb.fqnovel.com/novel/player/video_model/v1/?$rawQuery").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        conn.doOutput = true
        conn.doInput = true
        conn.instanceFollowRedirects = false
        conn.setRequestProperty("User-Agent", UA)
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("X-XS-From-Web", "0")
        conn.setRequestProperty("Sdk-Version", "2")
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        conn.setRequestProperty("X-Khronos", stamp.toString())
        conn.setRequestProperty("X-Gorgon", gorgon)
        conn.setRequestProperty("X-Argus", argus)
        conn.setRequestProperty("X-Ladon", ladon)
        conn.setRequestProperty("X-SS-STUB", bodyMd5)
        conn.setRequestProperty("X-SS-Req-Ticket", now.toString())
        conn.setFixedLengthStreamingMode(body.size)
        conn.outputStream.use { it.write(body) }

        val code = conn.responseCode
        val text = if (code in 200..299) {
            BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
        } else {
            BufferedReader(InputStreamReader(conn.errorStream ?: conn.inputStream)).use { it.readText() }
        }
        conn.disconnect()

        if (text.isBlank()) throw IOException("空响应 HTTP $code（签名校验失败）")
        if (code !in 200..299) throw IOException("HTTP $code: ${text.take(300)}")
        val json = try { JSONObject(text) } catch (e: Exception) { throw IOException("JSON解析失败 HTTP $code: ${text.take(200)}") }
        val baseCode = json.optJSONObject("BaseResp")?.optInt("StatusCode", -1) ?: -1
        if (baseCode != 0) throw IOException("BaseResp=$baseCode ${json.optJSONObject("BaseResp")?.optString("StatusMessage","")}")
        val vmStr = json.optJSONObject("data")?.optString("video_model") ?: throw IOException("无 video_model: ${text.take(300)}")
        val vm = JSONObject(vmStr)
        val list = vm.optJSONArray("video_list") ?: throw IOException("无 video_list")
        val order = mapOf("1080p" to 5, "720p" to 4, "540p" to 3, "480p" to 2, "360p" to 1)
        var best: JSONObject? = null; var bestQ = -1
        for (i in 0 until list.length()) {
            val item = list.getJSONObject(i)
            val codec = item.optJSONObject("video_meta")?.optString("codec_type","")?.lowercase() ?: ""
            if (codec == "bytevc2") continue
            val q = order[item.optJSONObject("video_meta")?.optString("definition","") ?: ""] ?: 0
            if (q > bestQ) { bestQ = q; best = item }
        }
        best ?: throw IOException("无可用清晰度")
        val mainUrl = best.getString("main_url")
        val spadeA = best.optJSONObject("encrypt_info")?.optString("spade_a","") ?: ""
        val key = if (spadeA.isNotEmpty()) try { MediaCrypto.deriveKey(spadeA) } catch (_:Exception) { null } else null
        val qNum = Regex("[0-9]+").find(best.optJSONObject("video_meta")?.optString("definition","")?:"")?.value ?: ""
        return StreamInfo(mainUrl, key, qNum)
    }
}
