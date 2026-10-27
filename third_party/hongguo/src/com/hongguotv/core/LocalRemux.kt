package com.hongguotv.core

import java.io.File
import java.io.IOException

/**
 * 本地 CENC 密文 MP4 → 明文 MP4 解密重封装（APK 内下载导出用）。
 *
 * 背景：native FFmpegConcat 的"直接 concat"路径打开分集时**不传 decryption_key**（keys 仅用于音频探测），
 * 因此红果下载导出产物是未解密的 CENC 密文 —— 文件大小完整、容器/时长正常，但样本全是密文，任何播放器都播不了。
 * 后端能播是因为后端先 `ffmpeg -decryption_key K -i raw -c copy -f mp4 plain` 解密明文再 concat（server/downloader.js:754）。
 *
 * 本工具等价实现后端"单集解密重封装"：MediaCrypto.prepare（解析 saiz/saio/stsz 样本表 + frma 还原 avc1/hvc1）
 * + MediaCrypto.decrypt（AES-CTR 逐样本解密，per-sample IV），产物为标准明文 MP4，可直接播放/再交给 concat 明文合并。
 * 与播放路径 RemoteVideo 使用同一组函数，解密逻辑 1:1。
 */
object LocalRemux {
    /** 本地密文 mp4 + 16 字节 CENC key → 明文 mp4（等价 ffmpeg -decryption_key -c copy -f mp4） */
    fun decrypt(src: File, dst: File, key: ByteArray?) {
        if (key == null || key.size != 16) throw IOException("CENC key 无效（需 16 字节）")
        val raw = src.readBytes()
        if (raw.size < 64) throw IOException("分集文件过小（${raw.size}B），疑似下载失败")
        val prepared = MediaCrypto.prepare(raw, key)
        val plain = MediaCrypto.decrypt(raw, 0, key, prepared)
        if (plain.size < 64) throw IOException("解密产物异常")
        dst.writeBytes(plain)
    }

    /** hex 32 位 → 16 字节 key；非法返回 null */
    fun keyFromHex(hex: String?): ByteArray? {
        if (hex == null || !hex.matches(Regex("[0-9a-fA-F]{32}"))) return null
        return ByteArray(16) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
