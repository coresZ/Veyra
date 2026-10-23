// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.TimeUnit

/** One playback stream. No listening socket or local HTTP service. */
class RemoteVideo(val info: StreamInfo) : Closeable {
    @Volatile private var closed = false
    private val calls = mutableSetOf<okhttp3.Call>()
    data class Range(val bytes: ByteArray, val total: Long)
    var total: Long = 0; private set
    private lateinit var prepared: MediaCrypto.Prepared
    @Volatile private var prefix = byteArrayOf()

    // Dedicated client with longer timeouts; CDN may stall on mobile networks.
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private fun rangeOnce(start: Long, end: Long): Range {
        val request = Request.Builder().url(info.url)
            .header("User-Agent", VendorConstants.VIDEO_UA)
            .header("Referer", "https://novel.snssdk.com/")
            .header("Range", "bytes=$start-$end")
            .header("Accept-Encoding", "identity")
            .header("Cache-Control", "no-cache")
            .build()
        val call = http.newCall(request)
        synchronized(calls) { if (closed) throw IOException("播放请求已取消"); calls += call }
        try {
            return RequestScope.execute(call) { response ->
                when (response.code) {
                    206 -> {
                        val cr = response.header("Content-Range").orEmpty()
                        var begin = start
                        var finish = end
                        var full = total
                        val m = Regex("bytes\\s+(\\d+)-(\\d+)/(\\d+)").find(cr)
                        if (m != null) {
                            begin = m.groupValues[1].toLong()
                            finish = m.groupValues[2].toLong()
                            full = m.groupValues[3].toLong()
                        }
                        val body = response.body ?: throw IOException("视频分段为空")
                        val expectedLen = (finish - begin + 1).toInt()
                        val bytes = body.source().readByteArray(expectedLen.toLong())
                        if (total == 0L) total = full
                        Range(bytes, full)
                    }
                    200 -> {
                        val body = response.body ?: throw IOException("视频响应为空")
                        val bytes = body.bytes()
                        if (total == 0L) total = bytes.size.toLong()
                        Range(bytes, total)
                    }
                    else -> throw IOException("视频服务器错误 HTTP ${response.code}")
                }
            }
        } finally { synchronized(calls) { calls -= call } }
    }

    private fun range(start: Long, end: Long): Range {
        if (closed) throw IOException("播放请求已取消")
        require(start >= 0 && end >= start && end - start < 8 * 1024 * 1024)
        var lastErr: IOException? = null
        for (attempt in 0 until 5) {
            try {
                return rangeOnce(start, end)
            } catch (e: IOException) {
                lastErr = e
                if (closed) throw e
                // Exponential backoff: 200ms, 400ms, 800ms, 1600ms, 3200ms
                try { Thread.sleep(200L * (1 shl attempt)) } catch (_: InterruptedException) {}
            }
        }
        throw lastErr ?: IOException("分段读取失败")
    }

    @Synchronized fun prepare(): RemoteVideo {
        if (::prepared.isInitialized) return this
        val first = range(0, 256 * 1024 - 1); total = first.total
        prefix = first.bytes
        val end = MediaCrypto.headerEnd(first.bytes, total)
        val header = if (end > first.bytes.size)
            first.bytes + range(first.bytes.size.toLong(), end - 1L).bytes
        else
            first.bytes.copyOf(end)
        prepared = MediaCrypto.prepare(header, info.key)
        if (prepared.samples.any { it.end > total }) throw IOException("视频样本超出资源大小")
        warm()
        return this
    }

    @Synchronized fun warm(): RemoteVideo {
        val size = minOf(total, 4 * 1024 * 1024L).toInt()
        if (prefix.size < size) {
            try {
                prefix = prefix + range(prefix.size.toLong(), size - 1L).bytes
            } catch (_: IOException) { }
        }
        return this
    }

    fun read(start: Long, length: Int): ByteArray {
        require(length in 1..1024 * 1024 && start >= 0)
        if (closed) throw IOException("播放请求已取消")
        if (start >= total) return byteArrayOf()
        val cached = prefix; val end = minOf(total, start + length)
        val available = (minOf(end, cached.size.toLong()) - start).coerceAtLeast(0).toInt()
        val first = if (available > 0) cached.copyOfRange(start.toInt(), start.toInt() + available) else byteArrayOf()
        val raw = if (start + available < end)
            first + range(start + available, end - 1).bytes
        else
            first
        return MediaCrypto.decrypt(raw, start, info.key, prepared)
    }

    override fun close() {
        synchronized(calls) {
            closed = true
            calls.toList().forEach { it.cancel() }
            calls.clear()
        }
    }
}
