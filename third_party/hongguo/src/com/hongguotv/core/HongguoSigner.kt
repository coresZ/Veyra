package com.hongguotv.core

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * 红果 fqnovel.com 直连签名器（移植自 Go hongguo-signer）
 * APK 自己直连，不经过后端。
 */
object HongguoSigner {
    private const val UA = "com.phoenix.read/73532 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3; Cronet/TTNetVersion:04657795 2026-01-23 QuicVersion:c67e9834 2025-09-08)"
    private const val BASE = "https://api5-normal-sinfonlineb.fqnovel.com"

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    @Suppress("DEPRECATION")
    fun resolve(vid: String): StreamInfo {        val deviceId = randomDeviceId()
        val installId = randomDeviceId()
        val stamp = (System.currentTimeMillis() / 1000).toInt()

        val queryParams = linkedMapOf(
            "aid" to "8662", "app_name" to "novelread", "version_code" to "73532",
            "version_name" to "7.3.5.32", "manifest_version_code" to "73532",
            "update_version_code" to "73532", "channel" to "update_64",
            "device_platform" to "android", "os" to "android", "ssmix" to "a",
            "device_type" to "25053RT47C", "device_brand" to "Redmi",
            "language" to "zh", "os_api" to "36", "os_version" to "16",
            "resolution" to "1280*2772", "dpi" to "520", "ac" to "wifi",
            "device_id" to deviceId, "iid" to installId,
            "_rticket" to System.currentTimeMillis().toString(),
        )
        val rawQuery = queryParams.entries.joinToString("&") { (k, v) -> "$k=$v" }

        // 手动构造和 Python json.dumps 完全一致的 body（带空格）
        val body = "{\"video_id\": \"$vid\", \"content_type\": 1, \"biz_param\": {\"need_all_video_definition\": true, \"video_platform\": 3}}".toByteArray()

        // X-Gorgon
        val gorgon = makeGorgon(rawQuery, body, stamp)
        val khronos = stamp.toString()

        // X-Argus
        val nonce = ByteArray(11)
        SecureRandom().nextBytes(nonce)
        val argus = makeArgus(rawQuery, body, stamp.toLong(), deviceId, nonce)
        val ladon = makeLadon("8662", stamp.toLong(), nonce.copyOfRange(2, 6))

        // 用 encodedQuery 阻止 OkHttp 对 * 等字符做二次编码
        val httpUrl = okhttp3.HttpUrl.Builder()
            .scheme("https")
            .host("api5-normal-sinfonlineb.fqnovel.com")
            .addPathSegments("novel/player/video_model/v1/")
            .encodedQuery(rawQuery)
            .build()
        val req = Request.Builder()
            .url(httpUrl)
            .header("User-Agent", UA)
            .header("Content-Type", "application/json; charset=utf-8")
            .header("X-Khronos", khronos)
            .header("X-Gorgon", gorgon)
            .header("X-Argus", argus)
            .header("X-Ladon", ladon)
            .post(okhttp3.RequestBody.create(null, body))
            .build()

        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            val headers = resp.headers.joinToString("; ") { "${it.first}=${it.second}" }
            if (text.isBlank()) {
                throw IOException("空响应 HTTP ${resp.code} len=${text.length} headers=[$headers] gorgon=${gorgon.take(20)}...")
            }
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}: ${text.take(300)}")
            val json = try { JSONObject(text) } catch (e: Exception) {
                throw IOException("JSON解析失败 HTTP ${resp.code} body=${text.take(200)}")
            }
            val baseCode = json.optJSONObject("BaseResp")?.optInt("StatusCode", -1) ?: -1
            if (baseCode != 0) throw IOException("BaseResp=$baseCode ${json.optJSONObject("BaseResp")?.optString("StatusMessage","")}")
            val vmStr = json.optJSONObject("data")?.optString("video_model")
                ?: throw IOException("无 video_model: ${text.take(300)}")
            val vm = JSONObject(vmStr)
            val list = vm.optJSONArray("video_list")
                ?: throw IOException("无 video_list")
            val order = mapOf("1080p" to 5, "720p" to 4, "540p" to 3, "480p" to 2, "360p" to 1)
            var best: JSONObject? = null
            var bestQ = -1
            for (i in 0 until list.length()) {
                val item = list.getJSONObject(i)
                val def = item.optJSONObject("video_meta")?.optString("definition", "") ?: ""
                val q = order[def] ?: 0
                if (q > bestQ) { bestQ = q; best = item }
            }
            best ?: throw IOException("无可用清晰度")
            val mainUrl = best.getString("main_url")
            val encryptInfo = best.optJSONObject("encrypt_info")
            val spadeA = encryptInfo?.optString("spade_a", "") ?: ""
            val key = if (spadeA.isNotEmpty()) {
                try { deriveKey(spadeA) } catch (_: Exception) { null }
            } else null
            val def = best.optJSONObject("video_meta")?.optString("definition", "") ?: ""
            val qNum = Regex("[0-9]+").find(def)?.value ?: ""
            return StreamInfo(mainUrl, key, qNum)
        }
    }

    private fun randomDeviceId(): String {
        val r = ByteArray(8)
        SecureRandom().nextBytes(r)
        // 1e18 + random
        return (1000000000000000000L + (bytesToLong(r) and 0x7FFFFFFFFFFFFFFF) % 8000000000000000000L).toString()
    }

    private fun bytesToLong(b: ByteArray): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (b[i].toLong() and 0xFF)
        return v
    }

    private fun md5(data: ByteArray): ByteArray =
        MessageDigest.getInstance("MD5").digest(data)

    private fun md5Hex(data: ByteArray): String =
        md5(data).joinToString("") { "%02x".format(it) }

    private fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    // ---- X-Gorgon ----
    private fun makeGorgon(rawQuery: String, body: ByteArray, stamp: Int): String {
        val queryHash = md5(rawQuery.toByteArray())
        val bodyHash = md5(body)
        val signPayload = ByteArray(20)
        System.arraycopy(queryHash, 0, signPayload, 0, 4)
        System.arraycopy(bodyHash, 0, signPayload, 4, 4)
        signPayload[12] = 0; signPayload[13] = 6; signPayload[14] = 11; signPayload[15] = 28
        // big endian stamp
        signPayload[16] = ((stamp shr 24) and 0xFF).toByte()
        signPayload[17] = ((stamp shr 16) and 0xFF).toByte()
        signPayload[18] = ((stamp shr 8) and 0xFF).toByte()
        signPayload[19] = (stamp and 0xFF).toByte()
        val signKey = byteArrayOf(
            0x44.toByte(), 0xb9.toByte(), 0xb9.toByte(), 0xd9.toByte(),
            0xa4.toByte(), 0xae.toByte(), 0xf9.toByte(), 0xfc.toByte(),
            0xa4.toByte(), 0x93.toByte(), 0xaa.toByte(), 0x75.toByte(),
            0x7c.toByte(), 0xa3.toByte(), 0xc2.toByte(), 0xc4.toByte(),
            0xa4.toByte(), 0x96.toByte(), 0x93.toByte(), 0x8f.toByte()
        )
        for (i in 0 until 20) signPayload[i] = (signPayload[i].toInt() xor signKey[i].toInt()).toByte()
        for (i in 0 until 20) {
            val rotated = rotL8(signPayload[i].toInt(), 4)
            val mixed = rotated xor (signPayload[(i + 1) % 20].toInt() and 0xFF)
            signPayload[i] = (reverse8(mixed) xor 0xFF xor 20).toByte()
        }
        val gorgon = ByteArray(26)
        gorgon[0] = 0x84.toByte(); gorgon[1] = 0x04; gorgon[2] = 0x40; gorgon[3] = 0x1c
        System.arraycopy(signPayload, 0, gorgon, 6, 20)
        return gorgon.joinToString("") { "%02x".format(it) }
    }

    private fun rotL8(v: Int, n: Int): Int {
        val vv = v and 0xFF  // 关键：先 mask 到 8 bit，避免 Kotlin 有符号 Byte 符号扩展破坏 ushr
        return ((vv shl n) or (vv ushr (8 - n))) and 0xFF
    }
    private fun reverse8(v: Int): Int {
        var r = 0
        for (i in 0 until 8) r = (r shl 1) or ((v shr i) and 1)
        return r
    }

    // ---- SM3 (BouncyCastle, 消除手写移植误差) ----
    private fun sm3(input: ByteArray): ByteArray {
        val digest = org.bouncycastle.crypto.digests.SM3Digest()
        digest.update(input, 0, input.size)
        val out = ByteArray(digest.digestSize)
        digest.doFinal(out, 0)
        return out
    }

    // ---- X-Argus ----
    private fun makeArgus(rawQuery: String, body: ByteArray, stamp: Long, deviceId: String, nonce: ByteArray): String {
        val key = hexDecode("ac1adaae95a7af94a5114ab3b3a97dd80050aa0a39314c40528caec95256c28c")
        // protobuf-like message building
        var msg = ByteArray(0)
        msg = appendVarintField(msg, 1, (0x20200929L * 2))
        msg = appendVarintField(msg, 2, 2)
        msg = appendBytesField(msg, 4, "8662".toByteArray())
        msg = appendBytesField(msg, 5, deviceId.toByteArray())
        msg = appendBytesField(msg, 6, "1611921764".toByteArray())
        msg = appendBytesField(msg, 7, "7.3.5.32".toByteArray())
        msg = appendBytesField(msg, 8, "v04.07.01-ml-android".toByteArray())
        msg = appendVarintField(msg, 9, 135135744)
        msg = appendBytesField(msg, 10, ByteArray(8))
        msg = appendVarintField(msg, 11, 0)
        msg = appendVarintField(msg, 12, stamp * 2)
        val bodyStub = md5(body)
        val bodyHash = sm3(bodyStub)
        msg = appendBytesField(msg, 13, bodyHash.copyOfRange(0, 6))
        val queryHash = sm3(rawQuery.toByteArray())
        msg = appendBytesField(msg, 14, queryHash.copyOfRange(0, 6))
        // nested counters: field 1 = 1, field 2 = 1, field 3 = 1, field 7 = 3348294860
        var counters = ByteArray(0)
        counters = appendVarintField(counters, 1, 1)
        counters = appendVarintField(counters, 2, 1)
        counters = appendVarintField(counters, 3, 1)
        counters = appendVarintField(counters, 7, 3348294860)
        msg = appendBytesField(msg, 15, counters)
        msg = appendBytesField(msg, 16, ByteArray(0))
        msg = appendBytesField(msg, 20, "none".toByteArray())
        msg = appendVarintField(msg, 21, 738)
        // environment field 23 (nested)
        var env = ByteArray(0)
        env = appendBytesField(env, 1, "25053RT47C".toByteArray())
        env = appendBytesField(env, 2, "16".toByteArray())
        env = appendBytesField(env, 3, "update_64".toByteArray())
        env = appendVarintField(env, 4, 2147483648)
        msg = appendBytesField(msg, 23, env)
        msg = appendVarintField(msg, 25, 2)

        // PKCS7 pad to 16
        val padded = pkcs7Pad(msg, 16)

        // derive rounds from sm3(key + 0x3c 0xcc 0x56 0x7b + key)
        val seed = ByteArray(key.size + 4 + key.size)
        System.arraycopy(key, 0, seed, 0, key.size)
        seed[key.size] = 0x3c.toByte(); seed[key.size + 1] = 0xcc.toByte(); seed[key.size + 2] = 0x56; seed[key.size + 3] = 0x7b
        System.arraycopy(key, 0, seed, key.size + 4, key.size)
        val derived = sm3(seed)
        val rounds = LongArray(72)
        for (i in 0 until 4) {
            rounds[i] = littleEndianLong(derived, i * 8)
        }
        for (i in 4 until 72) {
            var mixed = rotL64(rounds[i - 1], -3) xor rounds[i - 3]
            mixed = mixed xor rotL64(mixed, -1)
            val bit = ((0x3dc94c3a046d678bL ushr ((i - 4) % 62)) and 1) xor 3L
            rounds[i] = rounds[i - 4].inv() xor mixed xor bit
        }

        // block cipher
        val data = padded.clone()
        for (off in 0 until data.size step 16) {
            var left = littleEndianLong(data, off)
            var right = littleEndianLong(data, off + 8)
            for (round in rounds) {
                val nl = right
                val nr = left xor ((rotL64(right, 1) and rotL64(right, 8)) xor rotL64(right, 2) xor round)
                left = nl; right = nr
            }
            writeLittleEndianLong(data, off, left)
            writeLittleEndianLong(data, off + 8, right)
        }

        // xor with 0xd04ffdff repeated
        val xor = byteArrayOf(0xd0.toByte(), 0x4f, 0xfd.toByte(), 0xff.toByte(), 0xd0.toByte(), 0x4f, 0xfd.toByte(), 0xff.toByte())
        for (i in data.indices) data[i] = (data[i].toInt() xor xor[i % 8].toInt()).toByte()

        // prepend xor, reverse
        var final = ByteArray(xor.size + data.size)
        System.arraycopy(xor, 0, final, 0, xor.size)
        System.arraycopy(data, 0, final, xor.size, data.size)
        final = final.reversedArray()

        // container header
        val container = ByteArray(9 + final.size + 2)
        container[0] = 0xa6.toByte(); container[1] = 0xe7.toByte(); container[2] = 0x83.toByte(); container[3] = 0xee.toByte()
        container[4] = 0x70; container[5] = 1; container[6] = 0x10; container[7] = 9; container[8] = 0x18
        System.arraycopy(final, 0, container, 9, final.size)
        container[9 + final.size] = 0x56; container[9 + final.size + 1] = 0x7b
        val paddedContainer = pkcs7Pad(container, 16)

        // AES-CBC encrypt
        val aesKey = md5(key.copyOfRange(0, 16))
        val aesIv = md5(key.copyOfRange(16, 32))
        val cipher = javax.crypto.Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(aesKey, "AES"),
            javax.crypto.spec.IvParameterSpec(aesIv))
        val encrypted = cipher.doFinal(paddedContainer)

        // prepend 0x3c 0xcc, base64
        val result = ByteArray(2 + encrypted.size)
        result[0] = 0x3c; result[1] = 0xcc.toByte()
        System.arraycopy(encrypted, 0, result, 2, encrypted.size)
        return android.util.Base64.encodeToString(result, android.util.Base64.NO_WRAP)
    }

    // ---- X-Ladon ----
    private fun makeLadon(aid: String, stamp: Long, prefix: ByteArray): String {
        val digest = md5(prefix + aid.toByteArray())
        val hexStr = md5Hex(digest)
        // Go: digest = []byte(hex.EncodeToString(hash)) — ASCII bytes of hex string
        val digestBytes = hexStr.toByteArray(Charsets.US_ASCII)
        var left = littleEndianLong(digestBytes, 0)
        var right = littleEndianLong(digestBytes, 8)
        // FIFO queue: initial [digest[16:], digest[24:]]
        val queue = ArrayDeque<Long>()
        queue.addLast(littleEndianLong(digestBytes, 16))
        queue.addLast(littleEndianLong(digestBytes, 24))
        val keys = LongArray(34)
        for (i in 0 until 34) {
            keys[i] = left
            val mixed = (rotL64(right, -8) + left) xor i.toLong()
            queue.addLast(mixed)
            left = mixed xor rotL64(left, 3)
            right = queue.removeFirst()
        }
        val plain = ("$stamp-1611921764-$aid").toByteArray()
        val padded = pkcs7Pad(plain, 16)
        for (off in 0 until padded.size step 16) {
            var lv = littleEndianLong(padded, off)
            var rv = littleEndianLong(padded, off + 8)
            for (k in keys) {
                rv = k xor (lv + rotL64(rv, -8))
                lv = rv xor rotL64(lv, 3)
            }
            writeLittleEndianLong(padded, off, lv)
            writeLittleEndianLong(padded, off + 8, rv)
        }
        val result = ByteArray(prefix.size + padded.size)
        System.arraycopy(prefix, 0, result, 0, prefix.size)
        System.arraycopy(padded, 0, result, prefix.size, padded.size)
        return android.util.Base64.encodeToString(result, android.util.Base64.NO_WRAP)
    }

    // ---- helpers ----
    private fun appendVarintField(msg: ByteArray, field: Int, value: Long): ByteArray {
        var result = msg + varintEncode(field * 8L)
        result = result + varintEncode(value)
        return result
    }

    private fun varintEncode(v: Long): ByteArray {
        var value = v
        val out = mutableListOf<Byte>()
        while (true) {
            var b = (value and 0x7F).toInt()
            value = value ushr 7
            if (value != 0L) b = b or 0x80
            out.add(b.toByte())
            if (value == 0L) break
        }
        return out.toByteArray()
    }

    private fun appendBytesField(msg: ByteArray, field: Int, value: ByteArray): ByteArray {
        var result = msg + varintEncode((field * 8 + 2).toLong())
        result = result + varintEncode(value.size.toLong())
        return result + value
    }

    private fun pkcs7Pad(data: ByteArray, blockSize: Int): ByteArray {
        val pad = blockSize - data.size % blockSize
        val result = ByteArray(data.size + pad)
        System.arraycopy(data, 0, result, 0, data.size)
        for (i in data.size until result.size) result[i] = pad.toByte()
        return result
    }

    private fun littleEndianLong(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (b[off + i].toLong() and 0xFF)
        return v
    }

    private fun writeLittleEndianLong(b: ByteArray, off: Int, v: Long) {
        for (i in 0 until 8) b[off + i] = ((v ushr (i * 8)) and 0xFF).toByte()
    }

    private fun rotL64(v: Long, n: Int): Long {
        val shifts = ((n % 64) + 64) % 64
        return (v shl shifts) or (v ushr (64 - shifts))
    }

    private fun hexDecode(s: String): ByteArray {
        val out = ByteArray(s.length / 2)
        for (i in out.indices) {
            out[i] = ((Character.digit(s[i * 2], 16) shl 4) or Character.digit(s[i * 2 + 1], 16)).toByte()
        }
        return out
    }

    /** spade_a → AES-128 CENC key */
    private fun deriveKey(spadeA: String): ByteArray {
        val raw = android.util.Base64.decode(spadeA.trim(), android.util.Base64.DEFAULT or android.util.Base64.URL_SAFE)
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
}
