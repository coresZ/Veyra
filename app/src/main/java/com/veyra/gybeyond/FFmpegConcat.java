package com.veyra.gybeyond;

import java.io.File;
import java.util.List;

import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avformat.AVFormatContext;
import org.bytedeco.ffmpeg.avformat.AVIOContext;
import org.bytedeco.ffmpeg.avformat.AVStream;
import org.bytedeco.ffmpeg.avutil.AVDictionary;
import org.bytedeco.ffmpeg.avutil.AVRational;

/**
 * 自写合并引擎：Java 只调一个 native 函数，concat 全部逻辑在 C 层直调 ffmpeg C API，
 * 1:1 复刻后端 server/downloader.js 的 concat 阶段：
 *   -f concat -safe 0 -c copy -video_track_timescale 90000 -movflags +faststart -tag:v avc3/hev1
 * 编译产物 libffconcat.so（NDK aarch64，静态链接 libavformat/libavcodec/libavutil），
 * 单 .so 无外部依赖，无 JavaCPP 依赖闭包/空壳问题。
 * C 层每阶段写 native 阶段日志（logPath），native 崩溃时也能定位到具体阶段。
 */
final class FFmpegConcat {

    /** native 库加载异常缓存（System.loadLibrary 失败时保留，concat() 转 IOException）。 */
    private static Throwable LOAD_ERROR;

    static {
        try {
            System.loadLibrary("ffconcat");
        } catch (Throwable t) {
            LOAD_ERROR = t;
        }
    }

    /** 与 ffmpeg CLI concat 合并 1:1。tag: "hev1" 或 "avc3"。输出失败抛 IOException（带 ffmpeg 阶段信息）。
     *  keys：每集 CENC 解密密钥（hex 字符串，可为空串表示明文源），顺序与 inputs 一一对应。 */
    static void concat(List<File> inputs, List<String> keys, File output, String tag, StringBuilder debug, File nativeLog) throws Exception {
        if (LOAD_ERROR != null) {
            throw new java.io.IOException("ffmpeg 库加载失败: " + LOAD_ERROR, LOAD_ERROR);
        }
        if (inputs == null || inputs.isEmpty()) {
            throw new java.io.IOException("分集数=0，无法合并");
        }
        if (nativeLog != null) {
            try {
                java.nio.file.Files.write(nativeLog.toPath(), new byte[0]);   // 清空 native 阶段日志
            } catch (Exception ignored) { }
        }
        String[] paths = new String[inputs.size()];
        for (int i = 0; i < inputs.size(); i++) paths[i] = inputs.get(i).getAbsolutePath();
        String[] keyArr = new String[inputs.size()];
        for (int i = 0; i < keyArr.length; i++) {
            keyArr[i] = (keys != null && i < keys.size()) ? keys.get(i) : "";
        }
        debug.append("concat 列表: ").append(paths.length).append(" 集 → ").append(output.getAbsolutePath()).append('\n');
        String err = nativeConcat(paths, keyArr, output.getAbsolutePath(), tag,
                nativeLog != null ? nativeLog.getAbsolutePath() : "");
        // 无论成败，把 native 阶段日志（含 [norm] 音轨诊断 / [gap] 跳跃检测）并入 debug，供「复制合并日志」查看
        if (nativeLog != null && nativeLog.exists()) {
            try {
                String nl = new String(java.nio.file.Files.readAllBytes(nativeLog.toPath()), "UTF-8");
                if (!nl.isEmpty()) debug.append("--- native 阶段日志 ---\n").append(nl);
            } catch (Exception ignored) { }
        }
        if (err != null) {
            // C 层返回的错误消息已含「失败阶段 + 异常 + 细节」，直接抛出供详细日志展示
            throw new java.io.IOException(err);
        }
        if (!output.exists() || output.length() == 0) {
            throw new java.io.IOException("合并产物为空");
        }
        verifyAudio(inputs, output, debug);
        debug.append("concat 合并完成\n");
    }

    /**
     * 产物级音频校验：合并完成后回读产物，检查音频轨存在、音频时长与视频/分集总和匹配。
     * 只读容器元数据不遍历帧（快），能捕获「某段无声/丢声」这类规范化诊断看不出的产物缺陷。
     * 校验失败抛异常（分集保留，可重下重试）。
     */
    private static void verifyAudio(List<File> inputs, File output, StringBuilder debug) throws Exception {
        long expectAudioUs = 0;
        for (File f : inputs) {
            android.media.MediaExtractor ex = null;
            try {
                ex = new android.media.MediaExtractor();
                ex.setDataSource(f.getAbsolutePath());
                for (int i = 0; i < ex.getTrackCount(); i++) {
                    android.media.MediaFormat fmt = ex.getTrackFormat(i);
                    String mime = fmt.getString(android.media.MediaFormat.KEY_MIME);
                    if (mime != null && mime.startsWith("audio/") && fmt.containsKey(android.media.MediaFormat.KEY_DURATION))
                        expectAudioUs += fmt.getLong(android.media.MediaFormat.KEY_DURATION);
                }
            } catch (Exception ignored) { } finally { if (ex != null) ex.release(); }
        }
        long outAudioUs = 0, outVideoUs = 0;
        boolean hasAudio = false, hasVideo = false;
        android.media.MediaExtractor out = null;
        try {
            out = new android.media.MediaExtractor();
            out.setDataSource(output.getAbsolutePath());
            for (int i = 0; i < out.getTrackCount(); i++) {
                android.media.MediaFormat fmt = out.getTrackFormat(i);
                String mime = fmt.getString(android.media.MediaFormat.KEY_MIME);
                long d = fmt.containsKey(android.media.MediaFormat.KEY_DURATION) ? fmt.getLong(android.media.MediaFormat.KEY_DURATION) : 0;
                if (mime != null && mime.startsWith("audio/")) { hasAudio = true; outAudioUs = Math.max(outAudioUs, d); }
                else if (mime != null && mime.startsWith("video/")) { hasVideo = true; outVideoUs = Math.max(outVideoUs, d); }
            }
        } catch (Exception e) {
            throw new java.io.IOException("产物音频校验失败: " + e.getMessage(), e);
        } finally { if (out != null) out.release(); }
        debug.append("[校验] 产物音频=").append(outAudioUs / 1000).append("ms 视频=").append(outVideoUs / 1000)
                .append("ms 期望音频(分集和)=").append(expectAudioUs / 1000).append("ms\n");
        if (!hasVideo) throw new java.io.IOException("产物没有视频轨，合并结果异常");
        if (!hasAudio) throw new java.io.IOException("产物没有音频轨（合并结果无声）：请重新下载分集后重试");
        if (outVideoUs > 0 && outAudioUs > 0 && outAudioUs < outVideoUs * 95 / 100)
            throw new java.io.IOException("产物音频不完整（音频 " + (outAudioUs / 1000) + "ms < 视频 " + (outVideoUs / 1000)
                    + "ms 的 95%）：部分段落无声，请重下异常分集后重试");
        if (expectAudioUs > 0 && outAudioUs > 0 && outAudioUs < expectAudioUs * 92 / 100)
            debug.append("[校验] 警告：产物音频明显短于分集总和（可能部分段丢声）\n");
    }

    /**
     * native concat：
     * @return null = 成功；非 null = 失败（错误消息字符串，含失败阶段与 rc 细节）。
     */
    private static native String nativeConcat(String[] inputPaths, String[] keys, String outputPath, String tag, String logPath);

    /** 本地 HLS(m3u8) → MP4 重封装：等价 CLI `ffmpeg -allowed_extensions ALL -i playlist.m3u8 -c copy -f mp4 -y out`。
     *  playlist 与分片/KEY 同目录（相对路径），hls demuxer 原生处理 AES-128 解密。
     *  用于黄果源手机本地下载：APK 拉 m3u8/分片到本地后直接转成可播 mp4，不依赖后端转码。
     *  实现走 JavaCPP ffmpeg（libs/ffmpeg-*.jar 内置 arm64 so）：jniLibs 预编译 libffconcat.so
     *  是 v0.13.33 旧版，只有 nativeConcat 没有 nativeM3u8ToMp4（v0.18.1 引入但从未重编 so），
     *  因此 nativeM3u8ToMp4 调用必崩 UnsatisfiedLinkError——这里改为纯 JavaCPP 实现。 */
    static void m3u8ToMp4(File playlist, File output, StringBuilder debug, File nativeLog) throws Exception {
        if (nativeLog != null) {
            try {
                java.nio.file.Files.write(nativeLog.toPath(), new byte[0]);
            } catch (Exception ignored) { }
        }
        debug.append("m3u8 remux: ").append(playlist.getAbsolutePath()).append(" → ").append(output.getAbsolutePath()).append('\n');
        String err = jcpM3u8ToMp4(playlist.getAbsolutePath(), output.getAbsolutePath(),
                nativeLog != null ? nativeLog.getAbsolutePath() : "");
        if (nativeLog != null && nativeLog.exists()) {
            try {
                String nl = new String(java.nio.file.Files.readAllBytes(nativeLog.toPath()), "UTF-8");
                if (!nl.isEmpty()) debug.append("--- native 阶段日志 ---\n").append(nl);
            } catch (Exception ignored) { }
        }
        if (err != null) throw new java.io.IOException(err);
        if (!output.exists() || output.length() == 0) throw new java.io.IOException("m3u8 转 mp4 产物为空");
    }

    /**
     * JavaCPP ffmpeg 版 m3u8→mp4 重封装：avformat 直调，等价
     *   ffmpeg -allowed_extensions ALL -i playlist.m3u8 -c copy -f mp4 -y out
     * hls demuxer 原生处理 AES-128 解密与相对路径分片（分片/KEY 与 playlist 同目录）。
     * @return null=成功；非 null=失败消息（含失败阶段与 rc）。
     */
    private static String jcpM3u8ToMp4(String playlistPath, String outputPath, String logPath) {
        try {
            // Android 预加载保险：APK lib/ 里的 ffmpeg so 直接 System.loadLibrary 加载，
            // 避免 Loader 从 classpath 资源解压路径在 APK 内失效导致的阻塞/异常（重复加载无害）
            try { System.loadLibrary("avformat"); } catch (Throwable ignored) { }
            try { System.loadLibrary("avcodec"); } catch (Throwable ignored) { }
            try { System.loadLibrary("avutil"); } catch (Throwable ignored) { }
            org.bytedeco.javacpp.Loader.load(org.bytedeco.ffmpeg.global.avformat.class);
            AVDictionary opts = new AVDictionary();
            org.bytedeco.ffmpeg.global.avutil.av_dict_set(opts, "allowed_extensions", "ALL", 0);
            AVFormatContext inCtx = new AVFormatContext();
            int rc = org.bytedeco.ffmpeg.global.avformat.avformat_open_input(inCtx, playlistPath, null, opts);
            if (rc < 0) return "打开 m3u8 失败 rc=" + rc;
            rc = org.bytedeco.ffmpeg.global.avformat.avformat_find_stream_info(inCtx, (AVDictionary) null);
            if (rc < 0) { org.bytedeco.ffmpeg.global.avformat.avformat_free_context(inCtx); return "读取流信息失败 rc=" + rc; }
            AVFormatContext outCtx = new AVFormatContext();
            rc = org.bytedeco.ffmpeg.global.avformat.avformat_alloc_output_context2(outCtx, null, "mp4", outputPath);
            if (rc < 0) { org.bytedeco.ffmpeg.global.avformat.avformat_free_context(inCtx); return "创建输出上下文失败 rc=" + rc; }
            for (int i = 0; i < inCtx.nb_streams(); i++) {
                AVStream is = inCtx.streams(i);
                AVStream os = org.bytedeco.ffmpeg.global.avformat.avformat_new_stream(outCtx, null);
                rc = org.bytedeco.ffmpeg.global.avcodec.avcodec_parameters_copy(os.codecpar(), is.codecpar());
                if (rc < 0) {
                    org.bytedeco.ffmpeg.global.avformat.avformat_free_context(inCtx);
                    org.bytedeco.ffmpeg.global.avformat.avformat_free_context(outCtx);
                    return "复制流参数失败 rc=" + rc;
                }
                os.codecpar().codec_tag(0);
            }
            AVIOContext io = outCtx.pb();
            rc = org.bytedeco.ffmpeg.global.avformat.avio_open(io, outputPath, org.bytedeco.ffmpeg.global.avformat.AVIO_FLAG_WRITE);
            if (rc < 0) {
                org.bytedeco.ffmpeg.global.avformat.avformat_free_context(inCtx);
                org.bytedeco.ffmpeg.global.avformat.avformat_free_context(outCtx);
                return "打开输出文件失败 rc=" + rc;
            }
            rc = org.bytedeco.ffmpeg.global.avformat.avformat_write_header(outCtx, (AVDictionary) null);
            if (rc < 0) {
                org.bytedeco.ffmpeg.global.avformat.avio_close(io);
                org.bytedeco.ffmpeg.global.avformat.avformat_free_context(inCtx);
                org.bytedeco.ffmpeg.global.avformat.avformat_free_context(outCtx);
                return "写文件头失败 rc=" + rc;
            }
            AVPacket pkt = org.bytedeco.ffmpeg.global.avcodec.av_packet_alloc();
            while (true) {
                rc = org.bytedeco.ffmpeg.global.avformat.av_read_frame(inCtx, pkt);
                if (rc < 0) break;
                int si = pkt.stream_index();
                if (si >= 0 && si < inCtx.nb_streams()) {
                    AVRational inTb = inCtx.streams(si).time_base();
                    AVRational outTb = outCtx.streams(si).time_base();
                    pkt.pts(org.bytedeco.ffmpeg.global.avutil.av_rescale_q(pkt.pts(), inTb, outTb));
                    pkt.dts(org.bytedeco.ffmpeg.global.avutil.av_rescale_q(pkt.dts(), inTb, outTb));
                    pkt.duration(org.bytedeco.ffmpeg.global.avutil.av_rescale_q(pkt.duration(), inTb, outTb));
                    pkt.pos(-1);
                    rc = org.bytedeco.ffmpeg.global.avformat.av_interleaved_write_frame(outCtx, pkt);
                    if (rc < 0) {
                        org.bytedeco.ffmpeg.global.avcodec.av_packet_unref(pkt);
                        org.bytedeco.ffmpeg.global.avformat.av_write_trailer(outCtx);
                        org.bytedeco.ffmpeg.global.avformat.avio_close(io);
                        org.bytedeco.ffmpeg.global.avformat.avformat_free_context(inCtx);
                        org.bytedeco.ffmpeg.global.avformat.avformat_free_context(outCtx);
                        return "写帧失败 rc=" + rc;
                    }
                }
                org.bytedeco.ffmpeg.global.avcodec.av_packet_unref(pkt);
            }
            org.bytedeco.ffmpeg.global.avformat.av_write_trailer(outCtx);
            org.bytedeco.ffmpeg.global.avformat.avio_close(io);
            org.bytedeco.ffmpeg.global.avcodec.av_packet_free(pkt);
            org.bytedeco.ffmpeg.global.avformat.avformat_free_context(inCtx);
            org.bytedeco.ffmpeg.global.avformat.avformat_free_context(outCtx);
            return null;
        } catch (Throwable t) {
            return "m3u8 remux 异常: " + (t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName());
        }
    }
}
