// 红果 App 7.3.5.32 新版请求签名（guoapp 同款：简单 XOR 版 X-Gorgon + 每次随机设备）。
// 背景：旧版 drpys 复杂签名 + 固定设备请求 multi_video_model/v1/ 被红果拒绝（Code≠0 播放接口暂不可用），
// 新版签名 + video_model/v1/ 单集接口实测可用（2026-10-13 服务器端验证）。
package com.hongguotv.core

import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.ThreadLocalRandom

object GuoSigner {
    private const val BASE = "https://api5-normal-sinfonlineb.fqnovel.com"
    private const val UA = "com.phoenix.read/73532 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3; Cronet/TTNetVersion:04657795 2026-01-23 QuicVersion:c67e9834 2025-09-08)"
    private val key = byteArrayOf(
        0x44, 0xb9.toByte(), 0xb9.toByte(), 0xd9.toByte(), 0xa4.toByte(), 0xae.toByte(), 0xf9.toByte(), 0xfc.toByte(),
        0xa4.toByte(), 0x93.toByte(), 0xaa.toByte(), 0x75.toByte(), 0x7c.toByte(), 0xa3.toByte(), 0xc2.toByte(),
        0xc4.toByte(), 0xa4.toByte(), 0x96.toByte(), 0x93.toByte(), 0x8f.toByte()
    )

    private fun newDeviceId(): String {
        val value = 1_000_000_000_000_000_000L + Math.floorMod(ThreadLocalRandom.current().nextLong(), 8_000_000_000_000_000_000L)
        return value.toString()
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    private fun hexUpper(bytes: ByteArray) = bytes.joinToString("") { "%02X".format(it) }
    private fun rev8(b: Int): Int {
        var x = b; var r = 0
        for (i in 0 until 8) { r = (r shl 1) or (x and 1); x = x ushr 1 }
        return r
    }
    // 与 Python urllib.urlencode 一致：`*` 不编码（Java URLEncoder 会编成 %2A，红果按未编码 query 签名校验）。
    private fun qenc(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("%2A", "*").replace("%2a", "*").replace("%7E", "~")

    /** 构造与 guoapp hongguoAppRequest 一致的签名请求。path 如 /novel/player/video_model/v1/。 */
    fun sign(path: String, body: ByteArray?): Signer.Request {
        val query = linkedMapOf(
            "aid" to "8662", "app_name" to "novelread", "version_code" to "73532", "version_name" to "7.3.5.32",
            "manifest_version_code" to "73532", "update_version_code" to "73532", "channel" to "update_64",
            "device_platform" to "android", "os" to "android", "ssmix" to "a", "device_type" to "25053RT47C",
            "device_brand" to "Redmi", "language" to "zh", "os_api" to "36", "os_version" to "16",
            "resolution" to "1280*2772", "dpi" to "520", "ac" to "wifi",
            "device_id" to newDeviceId(), "iid" to newDeviceId()
        )
        val queryStr = query.toList().joinToString("&") { (k, v) -> k + "=" + qenc(v) }
        val url = BASE + path + "?" + queryStr
        val ts = (System.currentTimeMillis() / 1000).toInt()
        val md5 = MessageDigest.getInstance("MD5")
        val payload = ByteArray(20)
        md5.digest(queryStr.toByteArray(Charsets.UTF_8)).copyInto(payload, 0, 0, 4)
        var stub: String? = null
        if (body != null) {
            md5.digest(body).copyInto(payload, 4, 0, 4)
            stub = hexUpper(md5.digest(body))
        }
        payload[12] = 0; payload[13] = 6; payload[14] = 11; payload[15] = 28
        payload[16] = (ts ushr 24).toByte(); payload[17] = (ts ushr 16).toByte()
        payload[18] = (ts ushr 8).toByte(); payload[19] = ts.toByte()
        for (i in 0 until 20) payload[i] = (payload[i].toInt() xor key[i].toInt()).toByte()
        for (i in 0 until 20) {
            val b = payload[i].toInt() and 0xff
            val rotated = ((b shl 4) or (b ushr 4)) and 0xff   // bits.RotateLeft8 4
            val mixed = rotated xor (payload[(i + 1) % 20].toInt() and 0xff)
            payload[i] = (rev8(mixed) xor 0xff xor 20).toByte()
        }
        val gorgon = byteArrayOf(0x84.toByte(), 0x04, 0x40, 0x1c, 0, 0) + payload
        val headers = linkedMapOf(
            "User-Agent" to UA, "Accept" to "application/json", "X-XS-From-Web" to "0", "Sdk-Version" to "2",
            "X-Gorgon" to hex(gorgon), "X-Khronos" to ts.toString(),
            "X-SS-Req-Ticket" to System.currentTimeMillis().toString()
        )
        if (stub != null) headers["X-SS-STUB"] = stub
        return Signer.Request(url, headers, body)
    }
}
