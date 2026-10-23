package com.hongguotv.core

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 红果中转取流（guoapp 同款）：https://djapi.999888456.xyz/api/hongguo/play
 * 不直连 fqnovel.com，由中转服务处理签名和风控。
 */
object RelayPlayback {
    private const val DIRECT_API = "https://djapi.999888456.xyz/api/hongguo/play"
    /** Veyra 后端代理地址（由 Java 层设置，如 https://video.225600.xyz:1314/）。非空时走后端转发。 */
    @JvmStatic var proxyBaseUrl: String = ""
    private val http = OkHttpClient.Builder().build()

    fun stream(seriesId: String, vid: String): StreamInfo {
        // 优先 APK 内置签名器直连 fqnovel.com（1080p 全清晰度）
        try {
            return HongguoSigner.resolve(vid)
        } catch (e: Exception) {
            // 直连失败，继续回退
        }
        // 回退中转 API
        try {
            val ref = JSONObject()
                .put("content_type", 1004)
                .put("from_video_id", "")
                .put("series_id", seriesId)
                .put("vid", vid)
                .put("video_platform", 3)
            val b64 = android.util.Base64.encodeToString(ref.toString().toByteArray(), android.util.Base64.NO_WRAP)
            val url = if (proxyBaseUrl.isNotEmpty()) {
                "${proxyBaseUrl.trimEnd('/')}/api/hongguo/relay-play?series_id=$seriesId&vid=$vid"
            } else {
                DIRECT_API + "?id=" + URLEncoder.encode(b64, "UTF-8")
            }
            val req = Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 16; 25053RT47C) AppleWebKit/537.36")
                .header("Referer", "https://hongguoduanju.com/")
                .build()
            val body = http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("中转 HTTP ${resp.code}")
                resp.body?.string() ?: throw IOException("中转响应为空")
            }
            val jsonStr = decryptV2(body)
            val data = JSONObject(jsonStr)
            val keyUrls = data.optJSONArray("key_urls")
            if (keyUrls != null && keyUrls.length() > 0) {
                var best: StreamInfo? = null
                var bestQuality = -1
                for (i in 0 until keyUrls.length()) {
                    val item = keyUrls.getJSONObject(i)
                    val mediaUrl = item.optString("src").trim()
                    if (mediaUrl.isEmpty() || !mediaUrl.startsWith("http")) continue
                    val spadeA = item.optString("spade_a")
                    val name = item.optString("name", "")
                    val quality = Regex("[0-9]+").find(name)?.value ?: ""
                    val key = try { deriveKey(spadeA) } catch (e: Exception) { null }
                    val info = StreamInfo(mediaUrl, key, quality)
                    val qNum = quality.toIntOrNull() ?: 0
                    if (qNum > bestQuality) { best = info; bestQuality = qNum }
                }
                if (best != null) return best
            }
        } catch (e: Exception) {
            // 中转失败，继续网页抓流
        }
        return scrapeWebPlayer(seriesId, vid)
    }

    /** 网页播放页抓流（360p 预览） */
    private fun scrapeWebPlayer(seriesId: String, vid: String): StreamInfo {
        val url = "https://hongguoduanju.com/player/$seriesId/$vid"
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
            .header("Referer", "https://hongguoduanju.com/")
            .build()
        val html = http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("网页播放页 HTTP ${resp.code}")
            resp.body?.string() ?: throw IOException("网页播放页为空")
        }
        // 提取 mp4 地址
        val m = Regex("https?://[^\"'<>\\s]+\\.mp4[^\"'<>\\s]*").find(html)
            ?: throw IOException("网页播放页未找到视频地址")
        return StreamInfo(m.value, null, "360")
    }

    /** v2.<hex>.<base64> 解密 */
    private fun decryptV2(body: String): String {
        val text = body.trim()
        if (!text.startsWith("v2.")) return text
        val parts = text.split(".", limit = 3)
        if (parts.size != 3) throw IOException("中转加密格式错误")
        val hexPart = parts[1]
        if (hexPart.length <= 4) throw IOException("中转密钥长度错误")
        val encoded = hexDecode(hexPart.substring(4))
        val mask = intArrayOf(104,64,70,166,190,168,143,130,225,254,251,217,196,34,45,60,29,20,103,105)
        val material = ByteArray(encoded.size)
        for (i in encoded.indices) {
            val prev = if (i == 0) 109 else (encoded[i-1].toInt() and 0xFF)
            val slot = i % mask.size
            val salt = (mask[slot] xor (90 + 13*slot) xor 85) and 0xFF
            val shifted = ((encoded[i].toInt() and 0xFF) + 215 - 11*i) and 0xFF
            material[i] = (prev xor salt xor rotL8(shifted, 3)).toByte()
        }
        val ciphertext = b64decode(parts[2])
        val key = SecretKeySpec(material.copyOfRange(0, 16), "AES")
        val iv = IvParameterSpec(material.copyOfRange(16, 32))
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, key, iv)
        val plain = cipher.doFinal(ciphertext)
        return String(plain, Charsets.UTF_8)
    }

    /** spade_a → AES-128 CENC key */
    private fun deriveKey(spadeA: String): ByteArray {
        val raw = b64decode(spadeA)
        if (raw.size < 3) throw IOException("密钥数据过短")
        val tagLen = ((raw[0].toInt() and 0xFF) xor (raw[1].toInt() and 0xFF) xor (raw[2].toInt() and 0xFF)) - 48
        val contentLen = raw.size - tagLen - 1
        if (tagLen < 1 || contentLen < 33 || contentLen >= raw.size) throw IOException("密钥结构错误")
        val seed = (raw[raw.size - tagLen - 2].toInt() and 0xFF) xor (raw[raw.size - tagLen - 1].toInt() and 0xFF)
        val decoded = ByteArray(contentLen)
        var prevEven = 250
        var prevOdd = 85
        for (i in 0 until contentLen) {
            val current = raw[1 + i].toInt() and 0xFF
            val prev = if (i % 2 == 0) { prevEven.also { prevEven = current } } else { prevOdd.also { prevOdd = current } }
            decoded[i] = ((prev xor current) - 21 - Integer.bitCount(i)).toByte()
        }
        val hexKey = String(decoded.copyOfRange(1, 33), Charsets.UTF_8)
        return hexDecode(hexKey)
    }

    private fun rotL8(v: Int, n: Int): Int {
        return ((v shl n) or (v ushr (8 - n))) and 0xFF
    }

    private fun hexDecode(s: String): ByteArray {
        val out = ByteArray(s.length / 2)
        for (i in out.indices) out[i] = ((Character.digit(s[i*2],16) shl 4) or Character.digit(s[i*2+1],16)).toByte()
        return out
    }

    private fun b64decode(s: String): ByteArray {
        return android.util.Base64.decode(s.trim(), android.util.Base64.DEFAULT or android.util.Base64.URL_SAFE)
    }
}
