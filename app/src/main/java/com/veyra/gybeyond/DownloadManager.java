package com.veyra.gybeyond;

import android.content.ContentValues;
import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import com.hongguotv.core.RemoteVideo;
import com.hongguotv.core.StreamInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;

/**
 * 手机本地下载 + 多集合并。
 *
 * 下载：红果为 CENC 加密流，用 third_party 的 RemoteVideo（Range 拉流 + 解密）写出明文分集；
 *       黄果为普通 HTTPS 直链，直接流式下载。
 * 合并：MediaExtractor + MediaMuxer（分集为同编码同参数的独立 mp4 时 sample 级拷贝合并）：
 *       第 1 集从头 addTrack，后续集视频轨 seekTo(0, SEEK_TO_CLOSEST_SYNC) 定位关键帧，
 *       PTS 平移 = rawPts - basePts + 前序累计时长；成功后删单集，失败删半成品。
 * 落盘：合并产物先写 app 专属目录，完成后经 MediaStore 导出到公共 Download（Android 10+）。
 */
final class DownloadManager {
    private static DownloadManager instance;

    static synchronized DownloadManager get(Context context) {
        if (instance == null) instance = new DownloadManager(context.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final OkHttpClient http;
    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private final List<Task> tasks = Collections.synchronizedList(new ArrayList<>());

    private DownloadManager(Context context) {
        this.context = context;
        this.http = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build();
        loadPersisted();
    }

    static final class Task {
        final String id;
        final String source;   // hongguo | huangguo
        final String title;
        final JSONArray episodes;   // [{index,url,key?}]
        volatile String status = "running";   // running | done | failed | canceled
        volatile int doneCount = 0;
        volatile int totalCount = 0;
        volatile int percent = 0;
        volatile String detail = "";
        volatile String output = "";
        volatile String error = "";
        final long createdAt = System.currentTimeMillis();
        volatile boolean canceled = false;

        Task(String id, String source, String title, JSONArray episodes) {
            this.id = id; this.source = source; this.title = title; this.episodes = episodes;
            this.totalCount = episodes.length();
        }

        JSONObject snapshot() {
            try {
                return new JSONObject()
                        .put("id", id).put("source", source).put("title", title)
                        .put("status", status).put("doneCount", doneCount).put("totalCount", totalCount)
                        .put("percent", percent).put("detail", detail)
                        .put("output", output).put("error", error).put("createdAt", createdAt);
            } catch (Exception e) { return new JSONObject(); }
        }
    }

    // ---- 任务持久化（进程重启后手机下载记录不丢）----
    private void persist() {
        try {
            JSONArray arr = new JSONArray();
            synchronized (tasks) {
                for (Task t : tasks) {
                    arr.put(new JSONObject()
                            .put("id", t.id).put("source", t.source).put("title", t.title)
                            .put("status", t.status).put("percent", t.percent)
                            .put("doneCount", t.doneCount).put("totalCount", t.totalCount)
                            .put("detail", t.detail).put("output", t.output).put("error", t.error)
                            .put("createdAt", t.createdAt));
                }
            }
            try (FileOutputStream fos = context.openFileOutput("downloads.json", Context.MODE_PRIVATE)) {
                fos.write(arr.toString().getBytes("UTF-8"));
            }
        } catch (Exception ignored) { }
    }

    private void loadPersisted() {
        try {
            File f = new File(context.getFilesDir(), "downloads.json");
            if (!f.exists()) return;
            String s = new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8");
            JSONArray arr = new JSONArray(s);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Task t = new Task(o.optString("id"), o.optString("source"), o.optString("title"), new JSONArray());
                boolean wasRunning = "running".equals(o.optString("status"));
                t.status = wasRunning ? "failed" : o.optString("status");
                t.detail = wasRunning ? "已中断（应用重启）" : o.optString("detail", "");
                t.error = wasRunning ? "已中断（应用重启）" : o.optString("error", "");
                t.percent = o.optInt("percent", 0);
                t.doneCount = o.optInt("doneCount", 0);
                t.totalCount = Math.max(o.optInt("totalCount", 0), 1);
                t.output = o.optString("output", "");
                tasks.add(t);
            }
        } catch (Exception ignored) { }
    }

    // ---- 对外 API（供 MainActivity dispatch 调用）----
    JSONObject start(String source, String title, JSONArray episodes) throws Exception {
        if (!"hongguo".equals(source) && !"huangguo".equals(source)) throw new IllegalArgumentException("无效来源");
        if (episodes == null || episodes.length() == 0) throw new IllegalArgumentException("没有可下载的分集");
        if (episodes.length() > 200) throw new IllegalArgumentException("单次最多下载 200 集");
        for (int i = 0; i < episodes.length(); i++) {
            JSONObject ep = episodes.optJSONObject(i);
            if (ep == null || !ep.optString("url").startsWith("http")) throw new IllegalArgumentException("分集播放地址无效");
            String key = ep.optString("key", "");
            if (!key.isEmpty() && !key.matches("[0-9a-f]{32}")) throw new IllegalArgumentException("分集密钥无效");
        }
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Task task = new Task(id, source, title, episodes);
        tasks.add(0, task);
        persist();
        pool.execute(() -> run(task));
        return task.snapshot();
    }

    JSONArray list() {
        JSONArray out = new JSONArray();
        synchronized (tasks) { for (Task t : tasks) out.put(t.snapshot()); }
        return out;
    }

    void cancel(String id) {
        synchronized (tasks) {
            for (Task t : tasks) {
                if (t.id.equals(id)) { t.canceled = true; t.status = "canceled"; t.detail = "已取消"; t.error = "已取消"; }
            }
        }
        persist();
    }

    /** 删除已完成/失败的任务：移除任务记录，并删除已保存的产物（MediaStore 或文件，支持 | 分隔的多产物）。 */
    void delete(String id) {
        synchronized (tasks) {
            for (Task t : tasks) {
                if (t.id.equals(id)) {
                    t.canceled = true;
                    String output = t.output;
                    tasks.remove(t);
                    if (output != null && !output.isEmpty()) {
                        for (String o : output.split("\\|")) {
                            if (o.isEmpty()) continue;
                            try {
                                if (o.startsWith("content://")) {
                                    context.getContentResolver().delete(Uri.parse(o), null, null);
                                } else {
                                    File f = new File(Uri.parse(o).getPath());
                                    if (f.exists()) f.delete();
                                }
                            } catch (Exception ignored) { }
                        }
                    }
                    break;
                }
            }
        }
        persist();
    }

    // ---- 执行 ----
    private void run(Task task) {
        File dir = new File(context.getFilesDir(), "downloads/" + task.id);
        try {
            dir.mkdirs();
            List<File> plain = new ArrayList<>();
            for (int i = 0; i < task.episodes.length(); i++) {
                if (task.canceled) return;
                JSONObject ep = task.episodes.optJSONObject(i);
                String url = ep.optString("url");
                String key = ep.optString("key", "");
                File out = new File(dir, String.format(Locale.US, "ep%03d.mp4", i + 1));
                task.detail = "下载第 " + (i + 1) + "/" + task.episodes.length() + " 集";
                if ("hongguo".equals(task.source)) downloadHongguo(task, url, key, out, i);
                else downloadPlain(task, url, out, i);
                plain.add(out);
                task.doneCount = i + 1;
            }
            if (task.canceled) return;
            // 优先合并成单个文件（v0.7.9 修复 ContainerFixer stco 后分集文件正确，MediaExtractor 可读到正确样本）；
            // 若合并仍失败（个别设备 MediaMuxer 兼容问题），自动回退为分集逐个导出，下载不白费。
            String subDir = task.title.replaceAll("[\\\\/:*?\"<>|]", "_");
            String displayName = task.title.replaceAll("[\\\\/:*?\"<>|]", "_") + ".mp4";
            boolean mergedOk = false;
            try {
                task.detail = "合并分集";
                File merged = new File(dir, "merged.mp4");
                if (plain.size() == 1) copyFile(plain.get(0), merged);
                else mergeMp4(plain, merged, task);
                if (task.canceled) return;
                task.percent = 97;
                task.detail = "保存到手机";
                Uri publicUri = exportToDownloads(merged, subDir, displayName);
                task.output = publicUri != null ? publicUri.toString() : merged.getAbsolutePath();
                mergedOk = true;
            } catch (Exception mergeErr) {
                // 合并失败 → 记录原因，回退分集导出
                task.error = "合并失败：" + (mergeErr.getMessage() == null ? mergeErr.getClass().getSimpleName() : mergeErr.getMessage());
                StringBuilder outList = new StringBuilder();
                for (int i = 0; i < plain.size(); i++) {
                    if (task.canceled) return;
                    task.percent = 90 + Math.min(9, (int) ((i + 1) * 9.0 / Math.max(1, plain.size())));
                    String epName = String.format(Locale.US, "第%02d集.mp4", i + 1);
                    Uri u = exportToDownloads(plain.get(i), subDir, epName);
                    if (u != null) { if (outList.length() > 0) outList.append('|'); outList.append(u); }
                }
                task.output = outList.toString();
            }
            if (task.canceled) return;
            task.status = "done"; task.percent = 100;
            task.detail = mergedOk ? "完成（已合并）" : "完成（合并失败，已保存 " + plain.size() + " 集分集）";
        } catch (Exception e) {
            if (!task.canceled) { task.status = "failed"; task.error = e.getMessage() == null ? "下载失败" : e.getMessage(); task.detail = "失败"; }
        } finally {
            persist();
            // 成功后删分集；失败删半成品
            try { deleteRecursive(dir); } catch (Exception ignored) {}
        }
    }

    private void downloadHongguo(Task task, String url, String keyHex, File out, int idx) throws Exception {
        byte[] key = keyHex.isEmpty() ? null : hexDecode(keyHex);
        File raw = new File(out.getParentFile(), out.getName() + ".raw");
        try (RemoteVideo video = new RemoteVideo(http, new StreamInfo(url, key, "1080P"))) {
            video.prepare();
            long total = video.getTotal();
            long pos = 0;
            try (FileOutputStream fos = new FileOutputStream(raw)) {
                while (pos < total) {
                    if (task.canceled) return;
                    long len = Math.min(512 * 1024, total - pos);
                    byte[] chunk = video.read(pos, (int) len);
                    if (chunk.length == 0) throw new java.io.IOException("视频流意外结束");
                    fos.write(chunk);
                    pos += chunk.length;
                    task.percent = Math.min(90, (int) ((idx + (double) pos / total) * 90.0 / task.totalCount));
                }
            }
        }
        // RemoteVideo 输出的是明文数据，但容器头仍标 encv/senca 加密 → 修复为普通明文 MP4
        if (!task.canceled) {
            ContainerFixer.fix(raw, out);
            if (!raw.delete()) raw.deleteOnExit();
        }
    }

    private void downloadPlain(Task task, String url, File out, int idx) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(60_000);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36");
        try {
            int code = conn.getResponseCode();
            if (code != 200) throw new java.io.IOException("下载失败 HTTP " + code);
            long total = conn.getContentLengthLong();
            try (InputStream in = conn.getInputStream(); FileOutputStream fos = new FileOutputStream(out)) {
                byte[] buffer = new byte[128 * 1024];
                int n; long received = 0;
                while ((n = in.read(buffer)) > 0) {
                    if (task.canceled) return;
                    fos.write(buffer, 0, n);
                    received += n;
                    if (total > 0) task.percent = Math.min(90, (int) ((idx + (double) received / total) * 90.0 / task.totalCount));
                }
            }
        } finally { conn.disconnect(); }
    }

    // ---- MediaExtractor + MediaMuxer 合并 ----
    // ---- MediaExtractor + MediaMuxer 合并（每集全量收集 → 保持解码序 → 整轨写入）----
    // H.264 含 B 帧时，样本必须按解码顺序（extractor 原始顺序）写入 mp4，
    // B 帧的 PTS 偏移由 MediaMuxer 内部写 ctts 处理；绝不能按 PTS 重排样本
    // （会破坏解码引用顺序 → 灰屏/花屏）。mp4 允许视频轨与音频轨样本不交错，
    // 因此每集先整轨写视频、再整轨写音频。
    private static final class Sample {
        final long pts;
        final long size;
        final long flags;
        final ByteBuffer data;
        Sample(long pts, long size, long flags, ByteBuffer data) {
            this.pts = pts; this.size = size; this.flags = flags; this.data = data;
        }
    }

    private static List<Sample> collectSamples(MediaExtractor ex, int track) {
        ex.selectTrack(track);
        List<Sample> out = new ArrayList<>();
        while (ex.advance()) {
            long pts = ex.getSampleTime();
            long size = ex.getSampleSize();
            long flags = ex.getSampleFlags();
            ByteBuffer buf = ByteBuffer.allocate((int) size);
            int n = ex.readSampleData(buf, 0);
            buf.position(0);
            buf.limit(n < 0 ? buf.capacity() : n);
            out.add(new Sample(pts, n < 0 ? size : n, flags, buf));
        }
        try { ex.release(); } catch (Exception ignored) {}
        return out;
    }

    /** 按解码序整轨写入样本，返回本轨最大 PTS（相对 offset）。
     *  H.264 含 B 帧时解码序的 PTS 会摆动（B 帧 PTS < 相邻 P 帧），部分设备 MediaMuxer 要求时间戳单调，
     *  直接写会抛 writeSampleData returned an error。做法：物理顺序保持解码序，只把回退的时间戳
     *  单调化（抬到不早于前一样本）——解码引用顺序不受影响（画面正常），B 帧呈现仅滞后 1µs（无感知）。 */
    private static long writeSamples(MediaMuxer muxer, int track, List<Sample> samples, long base, long offset) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long lastT = Long.MIN_VALUE;
        long maxT = 0;
        for (Sample s : samples) {
            ByteBuffer buffer = s.data.duplicate();
            info.offset = 0;
            info.size = (int) s.size;
            long t = s.pts - base + offset;
            if (t < 0) t = 0;
            if (t <= lastT) t = lastT + 1;   // PTS 单调化（解码序保持，仅修正回退）
            info.presentationTimeUs = t;
            info.flags = (int) s.flags;
            muxer.writeSampleData(track, buffer, info);
            lastT = t;
            if (t > maxT) maxT = t;
        }
        return maxT;
    }

    private void mergeMp4(List<File> inputs, File output, Task task) throws Exception {
        MediaMuxer muxer = null;
        int videoTrack = -1, audioTrack = -1;
        long videoTotal = 0, audioTotal = 0;
        try {
            for (int i = 0; i < inputs.size(); i++) {
                if (task.canceled) return;
                task.detail = "合并中 " + (i + 1) + "/" + inputs.size();
                task.percent = 90 + (int) ((i + 1) * 9.0 / inputs.size());
                MediaExtractor vx = new MediaExtractor(), ax = new MediaExtractor();
                vx.setDataSource(inputs.get(i).getAbsolutePath());
                ax.setDataSource(inputs.get(i).getAbsolutePath());
                int vIdx = -1, aIdx = -1;
                for (int t = 0; t < vx.getTrackCount(); t++) {
                    String mime = vx.getTrackFormat(t).getString(MediaFormat.KEY_MIME);
                    if (mime != null && mime.startsWith("video/")) vIdx = t;
                    else if (mime != null && mime.startsWith("audio/")) aIdx = t;
                }
                if (vIdx < 0) throw new java.io.IOException("分集没有视频轨道");
                MediaFormat vf = vx.getTrackFormat(vIdx);
                MediaFormat af = null;
                if (aIdx >= 0) af = ax.getTrackFormat(aIdx);
                if (i == 0) {
                    muxer = new MediaMuxer(output.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                    videoTrack = muxer.addTrack(vf);
                    if (af != null) audioTrack = muxer.addTrack(af);
                    muxer.start();
                }
                // 后续集定位到关键帧后完整收集；base 取收集到的第一个样本 PTS（避免 seekTo 后 getSampleTime 语义不确定）
                if (i > 0) { vx.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC); }
                if (aIdx >= 0 && i > 0) { ax.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC); }
                List<Sample> vSamples = collectSamples(vx, vIdx);
                List<Sample> aSamples = aIdx >= 0 ? collectSamples(ax, aIdx) : null;
                if (vSamples.isEmpty()) throw new java.io.IOException("分集没有可读样本");
                long vBase = Math.max(0, vSamples.get(0).pts);
                long aBase = (aSamples == null || aSamples.isEmpty()) ? 0 : Math.max(0, aSamples.get(0).pts);
                // 先整轨写视频（解码序），再整轨写音频。
                // 注意：writeSamples 返回的是含 offset 的绝对最大时间戳，天然跨集连续，
                // 累计必须用「=」而不是「+=」（+= 会把上一集总时长再叠加一次 → 每集翻倍 → 59:39:08 虚增根因）
                long vEnd = writeSamples(muxer, videoTrack, vSamples, vBase, videoTotal);
                videoTotal = vEnd;
                if (task.canceled) return;
                if (aSamples != null && !aSamples.isEmpty()) {
                    long aEnd = writeSamples(muxer, audioTrack, aSamples, aBase, audioTotal);
                    audioTotal = aEnd;
                }
                if (task.canceled) return;
            }
            if (muxer != null) muxer.stop();
        } finally {
            if (muxer != null) { try { muxer.release(); } catch (Exception ignored) {} }
        }
    }

    // ---- 导出到公共 Download（MediaStore，API 29+；旧版回退 app 专属目录）----
    private Uri exportToDownloads(File file, String subDir, String displayName) throws Exception {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, displayName);
            values.put(MediaStore.Downloads.MIME_TYPE, "video/mp4");
            String rel = Environment.DIRECTORY_DOWNLOADS + "/Veyra";
            if (subDir != null && !subDir.isEmpty()) rel += "/" + subDir;
            values.put(MediaStore.Downloads.RELATIVE_PATH, rel);
            values.put(MediaStore.Downloads.IS_PENDING, 1);
            Uri uri = context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri != null) {
                try (OutputStream os = context.getContentResolver().openOutputStream(uri);
                     InputStream is = new FileInputStream(file)) {
                    if (os == null) throw new java.io.IOException("无法写入系统下载目录");
                    byte[] buffer = new byte[256 * 1024];
                    int n;
                    while ((n = is.read(buffer)) > 0) os.write(buffer, 0, n);
                }
                ContentValues done = new ContentValues();
                done.put(MediaStore.Downloads.IS_PENDING, 0);
                context.getContentResolver().update(uri, done, null, null);
                MediaScannerConnection.scanFile(context, new String[]{file.getAbsolutePath()}, new String[]{"video/mp4"}, null);
                return uri;
            }
        }
        // API 26-28：无存储权限时回退到 app 专属 Download 目录
        File dir = new File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "Veyra");
        if (subDir != null && !subDir.isEmpty()) dir = new File(dir, subDir);
        if (!dir.exists()) dir.mkdirs();
        File fallback = new File(dir, displayName);
        copyFile(file, fallback);
        MediaScannerConnection.scanFile(context, new String[]{fallback.getAbsolutePath()}, new String[]{"video/mp4"}, null);
        return Uri.fromFile(fallback);
    }

    private static void copyFile(File src, File dst) throws Exception {
        try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dst)) {
            byte[] buffer = new byte[256 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        }
    }

    private static void deleteRecursive(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) { File[] children = file.listFiles(); if (children != null) for (File c : children) deleteRecursive(c); }
        file.delete();
    }

    private static byte[] hexDecode(String s) {
        byte[] data = new byte[s.length() / 2];
        for (int i = 0; i < data.length; i++) data[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        return data;
    }
}
