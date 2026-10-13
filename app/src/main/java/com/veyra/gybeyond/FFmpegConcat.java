package com.veyra.gybeyond;

import java.io.File;
import java.util.List;

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
        debug.append("concat 合并完成\n");
    }

    /**
     * native concat：
     * @return null = 成功；非 null = 失败（错误消息字符串，含失败阶段与 rc 细节）。
     */
    private static native String nativeConcat(String[] inputPaths, String[] keys, String outputPath, String tag, String logPath);
}
