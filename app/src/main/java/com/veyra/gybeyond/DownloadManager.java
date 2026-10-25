package com.veyra.gybeyond;

import android.content.ContentUris;
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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

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
        volatile String status = "running";   // running | ready(已下载待合并) | done | failed | canceled
        volatile int doneCount = 0;
        volatile int totalCount = 0;
        volatile int percent = 0;
        volatile String detail = "";
        volatile String output = "";
        volatile String error = "";
        volatile String debug = "";   // 合并/下载详细排查日志（多行，UI 换行展示）
        volatile String nasTaskId = "";   // 转发模式：NAS 后端任务 id
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
                        .put("nasTaskId", nasTaskId)
                        .put("output", output).put("error", error).put("debug", debug).put("createdAt", createdAt);
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
                            .put("detail", t.detail).put("output", t.output).put("error", t.error).put("debug", t.debug)
                            .put("nasTaskId", t.nasTaskId)
                            .put("episodes", t.episodes)
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
                t.debug = o.optString("debug", "");
                t.nasTaskId = o.optString("nasTaskId", "");
                // 恢复分集列表（episodes 为 final JSONArray，用 put 拷贝），否则重试恢复的任务分集数=0
                JSONArray saved = o.optJSONArray("episodes");
                if (saved != null) {
                    for (int k = 0; k < saved.length(); k++) t.episodes.put(saved.optJSONObject(k));
                }
                tasks.add(t);
            }
        } catch (Exception ignored) { }
        // 孤儿目录清理：downloads/ 与公共 Download/Veyra 下已无任务记录的历史目录
        // （旧版 delete 只删产物不删分集，遗留大量 ep*.mp4 密文分集 + seg_v6 规范化缓存 + merged.mp4，
        //  导致存储膨胀）一次性删除。
        try {
            File base = new File(context.getFilesDir(), "downloads");
            File basePub = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Veyra");
            File[] bases = { base, basePub };
            for (File base2 : bases) {
                File[] dirs = base2.listFiles();
                if (dirs == null) continue;
                HashSet<String> alive = new HashSet<>();
                synchronized (tasks) { for (Task t : tasks) alive.add(t.id); }
                for (File d : dirs) {
                    if (!d.isDirectory()) continue;
                    String name = d.getName();
                    // 公共目录任务目录带 dl_ 前缀，私有目录直接用任务 id
                    String id2 = name.startsWith("dl_") ? name.substring(3) : name;
                    if (!alive.contains(id2)) deleteRecursive(d);
                }
            }
        } catch (Exception ignored) { }
    }

    // ---- 对外 API（供 MainActivity dispatch 调用）----
    JSONObject start(String source, String title, JSONArray episodes) throws Exception {
        if (!"hongguo".equals(source) && !"huangguo".equals(source)) throw new IllegalArgumentException("无效来源");
        if (episodes == null || episodes.length() == 0) throw new IllegalArgumentException("没有可下载的分集");
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

    /** 重试失败/取消的任务：重置状态重新执行 run()；已下载分集自动复用（v0.12.2 起保留分集）。 */
    void retry(String id) {
        Task found = null;
        synchronized (tasks) {
            for (Task t : tasks) {
                if (t.id.equals(id)) { found = t; break; }
            }
        }
        if (found == null) return;
        final Task target = found;
        synchronized (target) {
            target.canceled = false;
            target.status = "running";
            target.percent = 0;
            target.doneCount = 0;
            target.detail = "重新下载";
            target.error = "";
            target.debug = "";
            target.output = "";
        }
        persist();
        pool.execute(() -> run(target));
    }

    /** 删除已完成/失败的任务：移除任务记录，删除已保存的产物（MediaStore 或文件，支持 | 分隔的多产物），
     *  并删除该任务的分集目录（files/downloads/<id>/：密文分集 ep*.mp4 + seg_v6 规范化缓存 + merged.mp4 + 日志），
     *  防止存储膨胀。 */
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
                    // 兜底：删除公共 Downloads/Veyra/<剧名>/ 下该剧全部产物与残留
                    //（含 MediaStore 同名冲突时系统自动加 "_时间戳" 后缀的孤儿文件、空文件夹）
                    purgePublicExports(t);
                    // 删除整个分集/缓存目录（含正在合并的 merged.mp4、seg_v6 缓存、native.log）
                    // 任务目录可能在公共 Download/Veyra 或私有 downloads，两处都清
                    File dir = dlDir(t.id);
                    if (dir.exists()) deleteRecursive(dir);
                    File dirPriv = new File(context.getFilesDir(), "downloads/" + t.id);
                    if (dirPriv.exists()) deleteRecursive(dirPriv);
                    break;
                }
            }
        }
        persist();
    }

    // ---- 执行 ----
    // 并发下载数：一次最多同时下载 N 集（与后端 worker 并发思路一致），跑完一集线程池自动接下一集
    private static final int DL_CONCURRENCY = 10;
    /** m3u8 递归展开总请求计数（防变种列表指数爆炸，每集开始前重置） */
    private int expandCount = 0;

    /** 分集/分片任务目录：优先公共 Download/Veyra（文件管理器实时可见，ts 下多少存多少，
     *  与后端落盘一致）；公共目录不可写（老系统无权限等）时回退应用私有目录。 */
    @SuppressWarnings("deprecation")
    private File dlDir(String id) {
        File pub = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Veyra/dl_" + id);
        try {
            pub.mkdirs();
            if (pub.isDirectory()) return pub;
        } catch (Exception ignored) { }
        return new File(context.getFilesDir(), "downloads/" + id);
    }

    private void run(Task task) {
        File dir = dlDir(task.id);
        boolean mergedOk = false;
        try {
            dir.mkdirs();
            int n = task.episodes.length();
            File[] plainArr = new File[n];
            AtomicLong dlBytes = new AtomicLong();
            AtomicLong totalBytes = new AtomicLong();
            AtomicInteger finished = new AtomicInteger();
            ExecutorService dl = Executors.newFixedThreadPool(DL_CONCURRENCY);
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                final int idx = i;
                futures.add(dl.submit(() -> {
                    if (task.canceled) return;
                    JSONObject ep = task.episodes.optJSONObject(idx);
                    String url = ep.optString("url");
                    String key = ep.optString("key", "");
                    File out = new File(dir, String.format(Locale.US, "ep%03d.mp4", idx + 1));
                    // 重试/复用：仅复用「v0.13.8+ 新格式」分集（dlv=raw-v2，原始密文/明文，合并时由 C 层 ffmpeg
                    // 原生解密重封装保持 CFR）。旧版分集（MediaMuxer 重封装产物，帧率被弄成 VFR）一律不复用，
                    // 强制重新下载，避免"快切鬼畜/MPV 卡顿"翻回来。
                    if (out.exists() && out.length() > 0 && "raw-v2".equals(ep.optString("dlv", ""))) {
                        plainArr[idx] = out;
                        int f = finished.incrementAndGet();
                        task.doneCount = f;
                        task.detail = "复用已下载分集 " + f + "/" + n + "（并发 " + DL_CONCURRENCY + "）";
                        return;
                    }
                    try {
                        if ("hongguo".equals(task.source)) downloadHongguo(task, url, key, out, idx, dlBytes, totalBytes, n);
                        else if (isHuangguoM3u8Url(url)) downloadM3u8(task, url, out, dlBytes, totalBytes, idx + 1);
                        else downloadPlain(task, url, out, dlBytes, totalBytes, n);
                        ep.put("dlv", "raw-v2");   // 标记新格式（随任务持久化，重试时据此判定可复用）
                        plainArr[idx] = out;
                        int f = finished.incrementAndGet();
                        task.doneCount = f;
                        task.detail = "下载中 " + f + "/" + n + " 集完成（并发 " + DL_CONCURRENCY + "）";
                    } catch (Exception e) {
                        task.canceled = true;   // 一集失败 → 停掉其余并发集（保持"失败即停"语义）
                        throw new RuntimeException(e);
                    }
                }));
            }
            Exception firstErr = null;
            for (Future<?> f : futures) {
                try { f.get(); }
                catch (Exception e) {
                    if (firstErr == null) {
                        Throwable cause = e.getCause() != null ? e.getCause() : e;
                        firstErr = cause instanceof Exception ? (Exception) cause : new RuntimeException(cause);
                    }
                }
            }
            dl.shutdown();
            if (task.canceled && firstErr == null) return;   // 用户取消：不合并
            if (firstErr != null) throw firstErr;
            List<File> plain = new ArrayList<>();
            for (File f : plainArr) if (f != null) plain.add(f);
            if (plain.size() != n) throw new java.io.IOException("部分分集下载失败（" + plain.size() + "/" + n + "）");
            if (task.canceled) return;
            // 下载完成后：逐集 ffmpeg 解密归一化并导出到公共 Downloads（文件管理器立即可见可播）
            String subDir = task.title.replaceAll("[\\\\/:*?\"<>|]", "_");
            StringBuilder outList = new StringBuilder();
            for (int i = 0; i < plain.size(); i++) {
                if (task.canceled) return;
                task.percent = 90 + Math.min(9, (int) ((i + 1) * 9.0 / Math.max(1, plain.size())));
                task.detail = "解密导出 第" + (i + 1) + "/" + n + "集";
                task.doneCount = i + 1;
                JSONObject ep = task.episodes.optJSONObject(i);
                String key = ep != null ? ep.optString("key", "") : "";
                try {
                    File norm = new File(dir, "norm_" + (i + 1) + ".mp4");
                    if (!norm.exists() || norm.length() == 0) {
                        List<File> single = new ArrayList<>();
                        single.add(plain.get(i));
                        List<String> singleKey = new ArrayList<>();
                        singleKey.add(key);
                        StringBuilder dbg = new StringBuilder();
                        FFmpegConcat.concat(single, singleKey, norm, "hev1", dbg,
                            new File(context.getFilesDir(), "native.log"));
                    }
                    String epName = String.format(Locale.US, "第%02d集.mp4", i + 1);
                    Uri u = exportToDownloads(norm, subDir, epName);
                    if (u != null) { if (outList.length() > 0) outList.append('|'); outList.append(u); }
                    // 删除临时归一化文件（公共 Downloads 已有副本）
                    norm.delete();
                } catch (Exception ex) {
                    // 单集导出失败不阻塞任务，原始密文仍在 app 私有目录供合并
                }
            }
            task.status = "ready";
            task.percent = 100;
            task.detail = "已下载 " + n + " 集，待合并";
            task.output = outList.toString();
            persist();
            return;
        } catch (Exception e) {
            if (!task.canceled) {
                task.status = "failed";
                // 内部（如合并失败）已写详细 task.error 时保留，不覆盖成短消息
                if (task.error == null || task.error.isEmpty())
                    task.error = e.getMessage() == null ? "下载失败" : e.getMessage();
                String msg = e.getMessage();
                if (msg != null) {
                    int cut = msg.indexOf('\n');
                    if (cut > 0) msg = msg.substring(0, cut);
                    if (msg.length() > 34) msg = msg.substring(0, 34) + "…";
                    task.detail = "失败：" + msg;
                } else task.detail = "失败";
            }
        } finally {
            persist();
            // 调试模式：保留已下载分集（ep*.mp4）与合并产物副本，供换版本后直接重新合并；
            // 只清理合并失败残留的半成品（merged.mp4）与临时 concat 列表。
            try {
                File merged = new File(dir, "merged.mp4");
                if (!mergedOk && merged.exists()) deleteRecursive(merged);
                File[] kids = dir.listFiles();
                if (kids != null) for (File f : kids) {
                    if (f.isFile() && f.getName().startsWith("inputs_") && f.getName().endsWith(".ffconcat")) f.delete();
                }
            } catch (Exception ignored) {}
        }
    }

    /** 转发模式（proxyMode=3）：创建"NAS 下载→手机拉取"任务。文件由 forwardPull 逐集写入
     *  downloads/<id>/epNNN.mp4，拉满自动置 ready，可直接走 merge() 合并导出。 */
    JSONObject forwardPrepare(String title, int count, String nasTaskId) throws Exception {
        if (title == null || title.trim().isEmpty() || count <= 0) throw new IllegalArgumentException("参数无效");
        String id = "fwd-" + Long.toHexString(System.currentTimeMillis());
        JSONArray eps = new JSONArray();
        for (int i = 0; i < count; i++) eps.put(new JSONObject().put("index", i + 1));
        Task t = new Task(id, "huangguo", title, eps);
        t.status = "pulling";
        t.detail = "等待 NAS 下载完成";
        t.percent = 3;
        t.nasTaskId = nasTaskId == null ? "" : nasTaskId;
        synchronized (tasks) { tasks.add(t); }
        persist();
        return t.snapshot();
    }

    /** 转发模式：写入一集从 NAS 拉取的 mp4（epNNN.mp4）。全部拉满置 ready 可合并。 */
    JSONObject forwardPull(String id, int index, byte[] bytes) throws Exception {
        Task found = null;
        synchronized (tasks) { for (Task t : tasks) if (t.id.equals(id)) { found = t; break; } }
        if (found == null) throw new IllegalStateException("任务不存在");
        if (bytes == null || bytes.length == 0) throw new java.io.IOException("拉取内容为空");
        File dir = dlDir(id);
        if (!dir.exists() && !dir.mkdirs()) throw new java.io.IOException("无法创建任务目录");
        File out = new File(dir, String.format(Locale.US, "ep%03d.mp4", index));
        try (FileOutputStream fos = new FileOutputStream(out)) { fos.write(bytes); }
        int n = found.episodes.length();
        int pulled = 0;
        for (int i = 0; i < n; i++) {
            File f = new File(dir, String.format(Locale.US, "ep%03d.mp4", i + 1));
            if (f.exists() && f.length() > 0) pulled++;
        }
        found.detail = "已从 NAS 拉取 " + pulled + "/" + n + " 集";
        found.percent = 8 + Math.round(80f * pulled / n);
        if (pulled == n) {
            found.status = "ready";
            found.detail = "已拉取全部 " + n + " 集，可合并";
            found.percent = 90;
        }
        persist();
        return found.snapshot();
    }

    /** 转发模式：拉取 NAS 合并产物（单文件完整成品）。保存为 <title>_全N集.mp4，任务直接完成。 */
    JSONObject forwardPullMerged(String id, byte[] bytes) throws Exception {
        Task found = null;
        synchronized (tasks) { for (Task t : tasks) if (t.id.equals(id)) { found = t; break; } }
        if (found == null) throw new IllegalStateException("任务不存在");
        if (bytes == null || bytes.length == 0) throw new java.io.IOException("拉取内容为空");
        File dir = dlDir(id);
        if (!dir.exists() && !dir.mkdirs()) throw new java.io.IOException("无法创建任务目录");
        String safe = (found.title == null ? "" : found.title).replaceAll("[\\\\/:*?\"<>|]", "_");
        int n = found.episodes.length();
        File out = new File(dir, safe + "_全" + n + "集.mp4");
        try (FileOutputStream fos = new FileOutputStream(out)) { fos.write(bytes); }
        found.status = "done";
        found.detail = "已从 NAS 拉取完整成品";
        found.percent = 100;
        found.output = out.getAbsolutePath();
        persist();
        return found.snapshot();
    }

    /** 手动合并：等待合并信号后执行（任务须处于 ready/failed 状态；合并失败保留分集可重试）。 */
    JSONObject merge(String id) {
        Task found = null;
        synchronized (tasks) {
            for (Task t : tasks) {
                if (t.id.equals(id)) { found = t; break; }
            }
        }
        if (found == null) throw new IllegalStateException("任务不存在");
        final Task target = found;
        synchronized (target) {
            if (!"ready".equals(target.status) && !"failed".equals(target.status))
                throw new IllegalStateException("当前状态不可合并（需先完成下载）");
            target.status = "running";
            target.error = "";
            target.debug = "";
            target.detail = "合并分集";
            target.percent = 90;
        }
        persist();
        pool.execute(() -> runMerge(target));
        return target.snapshot();
    }

    private void runMerge(Task task) {
        File dir = dlDir(task.id);
        boolean mergedOk = false;
        try {
            int n = task.episodes.length();
            List<File> plain = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                File f = new File(dir, String.format(Locale.US, "ep%03d.mp4", i + 1));
                if (f.exists() && f.length() > 0) plain.add(f);
            }
            if (plain.size() != n) throw new java.io.IOException("分集不完整（" + plain.size() + "/" + n + "），请重新下载");
            // 优先合并成单个文件；若合并仍失败（个别设备 MediaMuxer 兼容问题），自动回退为分集逐个导出，下载不白费。
            String subDir = task.title.replaceAll("[\\\\/:*?\"<>|]", "_");
            String displayName = task.title.replaceAll("[\\\\/:*?\"<>|]", "_") + ".mp4";
            try {
                task.detail = "合并分集";
                File merged = new File(dir, "merged.mp4");
                if (plain.size() == 1) {
                    // 单集也要 C 层规范化（解密+时间轴归零），不能直接复制密文分集
                    List<String> k1 = new ArrayList<>();
                    JSONObject ep0 = task.episodes.optJSONObject(0);
                    k1.add(ep0 != null ? ep0.optString("key", "") : "");
                    StringBuilder dbg = new StringBuilder();
                    FFmpegConcat.concat(plain, k1, merged, "hev1", dbg, new File(context.getFilesDir(), "native.log"));
                    task.debug = dbg.toString();
                } else mergeMp4(plain, merged, task);
                if (task.canceled) return;
                task.percent = 97;
                task.detail = "保存到手机";
                Uri publicUri = exportToDownloads(merged, subDir, displayName);
                task.output = publicUri != null ? publicUri.toString() : merged.getAbsolutePath();
                mergedOk = true;
            } catch (Exception mergeErr) {
                // 合并失败 → 记录详细原因（阶段日志+异常+堆栈），回退分集导出
                StringBuilder eb = new StringBuilder();
                eb.append("合并失败：").append(mergeErr.getMessage() == null ? mergeErr.getClass().getSimpleName() : mergeErr.getMessage());
                if (task.debug != null && !task.debug.isEmpty()) eb.append('\n').append(task.debug);
                eb.append("\n异常类型: ").append(mergeErr.getClass().getName());
                int stCnt = 0;
                for (StackTraceElement st : mergeErr.getStackTrace()) {
                    String cn = st.getClassName();
                    if (cn.contains("gybeyond") || cn.contains("MediaMuxer") || cn.contains("MediaExtractor") || cn.contains("MediaCodec")) {
                        eb.append("\n  at ").append(cn).append('.').append(st.getMethodName())
                          .append('(').append(st.getFileName()).append(':').append(st.getLineNumber()).append(')');
                        if (++stCnt >= 15) break;
                    }
                }
                task.error = eb.toString();
                task.debug = eb.toString();
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
            if (mergedOk) {
                task.status = "done"; task.percent = 100;
                task.detail = "完成（已合并）";
                // 合并成功：删除分集（app 私有 ep*.mp4/norm_* + 公共 Downloads 第XX集.mp4），只保留合并产物
                deleteEpisodesAfterMerge(task, subDir);
            } else {
                // 合并失败：状态置 failed → 下载列表显示「重试」按钮（分集已导出保留，
                // 点重试会复用已下载分集直接合并，无需重新下载）。
                task.status = "failed"; task.percent = 100;
                task.detail = "合并失败（已保存 " + plain.size() + " 集分集，可直接重试合并）";
            }
        } catch (Exception e) {
            if (!task.canceled) { task.status = "failed"; task.error = e.getMessage() == null ? "合并失败" : e.getMessage(); task.detail = "失败"; }
        } finally {
            persist();
            try {
                File merged = new File(dir, "merged.mp4");
                if (!mergedOk && merged.exists()) deleteRecursive(merged);
                File[] kids = dir.listFiles();
                if (kids != null) for (File f : kids) {
                    if (f.isFile() && f.getName().startsWith("inputs_") && f.getName().endsWith(".ffconcat")) f.delete();
                }
            } catch (Exception ignored) {}
        }
    }

    private void downloadHongguo(Task task, String url, String keyHex, File out, int idx,
                                 AtomicLong dlBytes, AtomicLong totalBytes, int n) throws Exception {
        // 与后端 1:1：直接下载原始分集（CENC 密文或明文），解密+重封装统一放到合并阶段由
        // ffmpeg -decryption_key 原生处理（等价后端 downloader.js：下载密文 → ffmpeg 解密重封装）。
        // 彻底绕开 MediaExtractor+MediaMuxer 重封装——它会把源 CFR 时间戳弄成 VFR（MPV 卡顿根因）。
        downloadPlain(task, url, out, dlBytes, totalBytes, n);
    }

    /** 整体下载进度：已下载字节 / 已探测到的总字节（各集下载线程各自累计，90% 封顶，合并阶段再走 90-99）。 */
    private static void updateDownloadProgress(Task task, AtomicLong dlBytes, AtomicLong totalBytes) {
        long tb = totalBytes.get();
        if (tb <= 0) return;
        int p = Math.min(90, (int) (dlBytes.get() * 90.0 / tb));
        if (p > task.percent) task.percent = p;   // 只升不降（总字节渐进探知时避免百分比回跳）
    }

    /** 等价后端 ffmpeg -decryption_key -i <raw> -c copy -f mp4 <out>：把解密产物完整重新封装为标准明文 MP4。
     *  MediaExtractor 读样本 → MediaMuxer 重写（stco/stsz/stss/mdhd 全部由 muxer 正确生成）。
     *  处理 avc3/hev1 缺 csd（流式扫描首关键帧提取 SPS/PPS，HEVC 含 VPS）；
     *  音视频保留源内相对偏移（统一基准 = min(首视频PTS, 首音频PTS)，与后端 concat 行为一致）。
     *  流式两遍（首遍定位/提取，第二遍写），不整集驻留内存。 */
    private static void normalizeEpisode(File in, File out) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        ex.setDataSource(in.getAbsolutePath());
        int vIdx = -1, aIdx = -1;
        for (int t = 0; t < ex.getTrackCount(); t++) {
            String mime = ex.getTrackFormat(t).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("video/")) vIdx = t;
            else if (mime != null && mime.startsWith("audio/")) aIdx = t;
        }
        if (vIdx < 0) throw new java.io.IOException("分集没有视频轨道");
        MediaFormat vf = ex.getTrackFormat(vIdx);
        MediaFormat af = aIdx >= 0 ? ex.getTrackFormat(aIdx) : null;
        ex.release();
        String vmime = vf.getString(MediaFormat.KEY_MIME);
        // 1) avc3/hev1 缺 csd：流式扫描首个关键帧，提取 SPS/PPS（HEVC 含 VPS）
        if (!vf.containsKey("csd-0")) {
            boolean hevc = vmime != null && vmime.startsWith("video/hevc");
            List<byte[]> nal = null;
            MediaExtractor se = new MediaExtractor();
            try {
                se.setDataSource(in.getAbsolutePath());
                se.selectTrack(vIdx);
                while (se.advance()) {
                    if ((se.getSampleFlags() & MediaCodec.BUFFER_FLAG_KEY_FRAME) == 0) continue;
                    long sz = se.getSampleSize();
                    if (sz <= 0 || sz > 4 * 1024 * 1024) continue;
                    ByteBuffer bb = ByteBuffer.allocate((int) sz);
                    int n = se.readSampleData(bb, 0);
                    if (n <= 0) continue;
                    bb.position(0); bb.limit(n);
                    List<byte[]> p = extractParameterSets(bb, hevc);
                    if (!p.isEmpty()) { nal = p; break; }
                }
            } finally { se.release(); }
            if (nal == null) throw new java.io.IOException("无法提取视频参数集(csd)");
            int total = 0; for (byte[] b : nal) total += b.length;
            ByteBuffer csd = ByteBuffer.allocate(total);
            for (byte[] b : nal) csd.put(b);
            csd.position(0);
            vf.setByteBuffer("csd-0", csd);
        }
        // 2) 音视频各自归零基准（等价后端 ffmpeg -c copy 输出从 0 起），并交错写入
        long vFirst = Math.max(0, firstSamplePts(in, vIdx));
        long aFirst = af != null ? Math.max(0, firstSamplePts(in, aIdx)) : vFirst;
        // 3) 流式重写
        MediaMuxer muxer = new MediaMuxer(out.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        try {
            int vt = muxer.addTrack(vf);
            int at = af != null ? muxer.addTrack(af) : -1;
            muxer.start();
            writeInterleavedStream(in, vIdx, aIdx, muxer, vt, at, vFirst, aFirst);
            muxer.stop();
        } finally { muxer.release(); }
    }

    private static long firstSamplePts(File file, int track) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(file.getAbsolutePath());
            ex.selectTrack(track);
            return ex.advance() ? ex.getSampleTime() : -1;
        } finally { ex.release(); }
    }

    /** 整轨流式写入（解码序，PTS 单调化），等价 mergeMp4 的 writeSamples 但按文件边读边写。 */
    private void downloadPlain(Task task, String url, File out, AtomicLong dlBytes, AtomicLong totalBytes, int n) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(60_000);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36");
        try {
            int code = conn.getResponseCode();
            if (code != 200) throw new java.io.IOException("下载失败 HTTP " + code);
            long total = conn.getContentLengthLong();
            totalBytes.addAndGet(Math.max(total, 0));
            try (InputStream in = conn.getInputStream(); FileOutputStream fos = new FileOutputStream(out)) {
                byte[] buffer = new byte[128 * 1024];
                int k; long received = 0;
                while ((k = in.read(buffer)) > 0) {
                    if (task.canceled) return;
                    fos.write(buffer, 0, k);
                    received += k;
                    dlBytes.addAndGet(k);
                    updateDownloadProgress(task, dlBytes, totalBytes);
                }
            }
        } finally { conn.disconnect(); }
    }

    // ---- 黄果源 m3u8 本地下载：APK 直拉 m3u8/分片（走后端代理同播放链路），本地 ffmpeg 封装 mp4 ----
    private boolean isHuangguoM3u8Url(String url) {
        if (url == null) return false;
        return url.contains("/api/huangguo-proxy?")
                || url.contains(".m3u8") || url.contains("m3u8")
                || url.contains("video.225600.xyz");   // 仅直连模式下 episode source 是源站 m3u8 直链，兜底识别
    }

    /** 递归展开 m3u8（代理已把 URI 重写为绝对代理 URL）：收集分片、每段对应 KEY URL 与显式 IV。
     *  每次拉取后刷新 task.detail，让"解析 m3u8 播放列表…"有实时进度（已展开条目/已收集分片），
     *  避免用户面对无进度转圈；fetchBytes 已有 connect/read 超时兜底。 */
    private void expandM3u8(String url, List<String> segs, List<String> segKeys, List<String> segIVs, int depth, Task task, int n) throws Exception {
        if (depth > 6) throw new java.io.IOException("m3u8 层级过深");
        if (++expandCount > 400) throw new java.io.IOException("m3u8 展开条目过多（>400），疑似变种列表循环");
        task.detail = "解析 m3u8 播放列表… 条目 " + expandCount + "、分片 " + segs.size();
        task.debug = "第 " + n + " 集：解析 m3u8… 条目 " + expandCount + "、分片 " + segs.size();
        String text = new String(fetchBytes(url), StandardCharsets.UTF_8);
        String currentKey = null;
        String currentIV = "";
        boolean expectSub = false;
        java.util.regex.Matcher keyMatcher = java.util.regex.Pattern.compile("URI=\"([^\"]+)\"").matcher("");
        java.util.regex.Matcher ivMatcher = java.util.regex.Pattern.compile("IV=([0-9a-fA-Fx]+)").matcher("");
        for (String raw : text.split("\r?\n")) {
            String line = raw.trim();
            if (line.startsWith("#EXT-X-KEY")) {
                keyMatcher.reset(line);
                if (keyMatcher.find()) currentKey = resolveUri(keyMatcher.group(1), url);
                // 源带显式 IV（如 IV=0x...）必须原样保留：本地 playlist 丢 IV 时 hls demuxer 会用
                // 媒体序列号作默认 IV 解密，与源不符 → 解密数据全错 → 产物损坏/合并卡死
                ivMatcher.reset(line);
                currentIV = ivMatcher.find() ? ivMatcher.group(1) : "";
            } else if (line.startsWith("#EXT-X-STREAM-INF")) {
                expectSub = true;
            } else if (line.startsWith("#EXT-X-MEDIA")) {
                // 音轨/字幕声明（自带 URI 属性），不是子流：跳过，避免把下一行分片误当子流递归（拿 ts 当 m3u8 解析必失败）
                expectSub = false;
            } else if (!line.isEmpty() && !line.startsWith("#")) {
                String uri = resolveUri(line, url);
                if (expectSub) {
                    // 防误判：递归请求返回的可能不是播放列表（如误把分片当子流），校验含 #EXT 标签才递归，否则忽略该行
                    String subText = new String(fetchBytes(uri), StandardCharsets.UTF_8);
                    if (!subText.contains("#EXT")) {
                        // 非播放列表：该 URL 实际是分片，直接纳入分片列表
                        segs.add(uri); segKeys.add(currentKey); segIVs.add(currentIV);
                    } else {
                        expandM3u8(uri, segs, segKeys, segIVs, depth + 1, task, n);
                    }
                } else { segs.add(uri); segKeys.add(currentKey); segIVs.add(currentIV); }
                expectSub = false;
            }
        }
    }

    private String resolveUri(String line, String base) throws Exception {
        try { return new URL(line).toString(); }
        catch (Exception e) { return new URL(new URL(base), line).toString(); }
    }

    /** curl/HTTP 客户端兼容：源站分片文件名可能含未编码的 ()[]{}，先补编码再请求。 */
    private static String curlSafeUrl(String url) {
        return url.replace("[", "%5B").replace("]", "%5D")
                .replace("(", "%28").replace(")", "%29")
                .replace("{", "%7B").replace("}", "%7D");
    }

    /** 解析阶段取小文件（master/子流/playlist）：connect/read 超时外再套 25s 硬超时，
     *  DNS 解析/TLS 握手黑洞时 HttpURLConnection 超时不覆盖，硬超时保证快速失败可重试。 */
    private byte[] fetchBytes(String url) throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<byte[]> f = pool.submit(() -> doFetchBytes(url));
            try {
                return f.get(25, TimeUnit.SECONDS);
            } catch (TimeoutException te) {
                f.cancel(true);
                throw new java.io.IOException("请求超时(25s)");
            }
        } finally { pool.shutdownNow(); }
    }

    private byte[] doFetchBytes(String url) throws Exception {
        url = curlSafeUrl(url);
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(20_000);   // 解析阶段都是小文件；过长会掩盖源/代理卡死
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36");
        try {
            int code = conn.getResponseCode();
            if (code != 200) throw new java.io.IOException("下载失败 HTTP " + code);
            byte[] body;
            try (InputStream in = conn.getInputStream(); ByteArrayOutputStream bo = new ByteArrayOutputStream()) {
                byte[] buf = new byte[64 * 1024];
                int k; long total = 0;
                while ((k = in.read(buf)) > 0) {
                    bo.write(buf, 0, k);
                    total += k;
                    if (total > 8 * 1024 * 1024) throw new java.io.IOException("响应体超限（>8MB），疑似非播放列表");
                }
                body = bo.toByteArray();
            }
            String enc = conn.getContentEncoding();
            if (enc != null && enc.toLowerCase().contains("gzip")) {
                // 黄果源 master 常见 gzip 乱码（无编码头），解压后再返回
                try (java.util.zip.GZIPInputStream gz = new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(body))) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buf = new byte[64 * 1024];
                    int k; long total = 0;
                    while ((k = gz.read(buf)) > 0) {
                        out.write(buf, 0, k);
                        total += k;
                        if (total > 8 * 1024 * 1024) throw new java.io.IOException("解压后响应体超限（>8MB）");
                    }
                    return out.toByteArray();
                }
            }
            return body;
        } finally { conn.disconnect(); }
    }

    private void downloadToFile(String url, File out, AtomicLong dlBytes, AtomicLong totalBytes) throws Exception {
        url = curlSafeUrl(url);
        // 单片 90s 硬超时 + 最多 2 次自动重试（换新连接）：源站/代理流式挂起时快速失败报错可重试，不无限卡
        Exception lastErr = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(10_000);
                conn.setReadTimeout(30_000);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36");
                try {
                    int code = conn.getResponseCode();
                    if (code != 200) throw new java.io.IOException("下载失败 HTTP " + code);
                    long total = conn.getContentLengthLong();
                    totalBytes.addAndGet(Math.max(total, 0));
                    try (InputStream in = conn.getInputStream(); FileOutputStream fos = new FileOutputStream(out)) {
                        byte[] buffer = new byte[128 * 1024];
                        int k; long received = 0;
                        long startMs = System.currentTimeMillis();
                        while ((k = in.read(buffer)) > 0) {
                            fos.write(buffer, 0, k);
                            received += k;
                            dlBytes.addAndGet(k);
                            // 单片硬超时：源站/代理流式挂起（持续小数据不触发 readTimeout）时强制放弃重试
                            if (System.currentTimeMillis() - startMs > 90_000) {
                                throw new java.io.IOException("分片下载超时（>90秒）");
                            }
                        }
                    }
                } finally { conn.disconnect(); }
                return;   // 成功
            } catch (Exception e) {
                lastErr = e;
                out.delete();
                if (attempt < 2) Thread.sleep(400L * attempt);
            }
        }
        throw lastErr;
    }

    /** 黄果 m3u8 下载：展开 → 拉 KEY/分片（并发）→ 本地 playlist → ffmpeg remux mp4 → 清理。
     *  临时文件放每集独立子目录（多集并发时不互相覆盖），产物最终落到 out。 */
    private void downloadM3u8(Task task, String url, File out, AtomicLong dlBytes, AtomicLong totalBytes, int n) throws Exception {
        File work = new File(out.getParentFile(), "m3u8-w" + n);
        work.mkdirs();
        boolean segOk = false;
        try {
            List<String> segUris = new ArrayList<>();
            List<String> segKeys = new ArrayList<>();
            List<String> segIVs = new ArrayList<>();
            expandCount = 0;   // 每集重置解析计数
            task.detail = "解析 m3u8 播放列表…";
            task.debug = "第 " + n + " 集：解析 m3u8 播放列表…";
            // 解析放独立子线程 + 90s 硬超时：黄果源 master 是变种列表（子流套子流），
            // 递归展开可能数百个串行请求，无超时保护会永久卡死在"解析 m3u8 播放列表…"
            ExecutorService expandPool = Executors.newSingleThreadExecutor();
            try {
                Future<?> fe = expandPool.submit(() -> {
                    try { expandM3u8(url, segUris, segKeys, segIVs, 0, task, n); }
                    catch (Throwable t) { throw new RuntimeException(t); }
                });
                try {
                    fe.get(90, TimeUnit.SECONDS);
                } catch (TimeoutException te) {
                    fe.cancel(true);
                    throw new java.io.IOException("第 " + n + " 集 m3u8 解析超时（90s），请重试");
                }
            } finally { expandPool.shutdownNow(); }
            if (segUris.isEmpty()) throw new java.io.IOException("m3u8 无分片");
            task.detail = "解析完成，共 " + segUris.size() + " 个分片，开始下载";
            task.debug = "第 " + n + " 集：m3u8 含 " + segUris.size() + " 个分片，开始下载";
            // 去重 KEY → 本地 key 文件 + 每 KEY 对应显式 IV（源带 IV 时 playlist 原样保留）
            LinkedHashMap<String, String> keyFiles = new LinkedHashMap<>();
            LinkedHashMap<String, String> keyIVs = new LinkedHashMap<>();
            {
                int keyIdx = 0;
                for (int i = 0; i < segKeys.size(); i++) {
                    String ku = segKeys.get(i);
                    if (ku == null || keyFiles.containsKey(ku)) continue;
                    File kf = new File(work, "key-" + keyIdx + ".key");
                    // KEY 复用：重试时已存在且非空直接跳过
                    if (!(kf.exists() && kf.length() > 0)) {
                        downloadToFile(ku, kf, dlBytes, totalBytes);
                    }
                    keyFiles.put(ku, "key-" + keyIdx + ".key");
                    if (i < segIVs.size() && segIVs.get(i) != null && !segIVs.get(i).isEmpty())
                        keyIVs.put(ku, segIVs.get(i));
                    keyIdx++;
                }
            }
            // 并发下载分片（12 并发提速）
            final int segN = segUris.size();
            final String[] segFiles = new String[segN];
            final AtomicInteger nextSeg = new AtomicInteger();
            final AtomicInteger segDone = new AtomicInteger();   // 真正完成的片数（进度精确，不再用"已分配"误导）
            final AtomicLong lastBytes = new AtomicLong();
            final AtomicLong lastTime = new AtomicLong(System.currentTimeMillis());
            ExecutorService pool = Executors.newFixedThreadPool(Math.min(12, Math.max(1, segN)));
            List<Future<?>> futs = new ArrayList<>();
            for (int i = 0; i < Math.min(12, segN); i++) {
                futs.add(pool.submit(() -> {
                    int curIdx = -1;
                    try {
                        while (!task.canceled) {
                            int idx = nextSeg.getAndIncrement();
                            if (idx >= segN) break;
                            curIdx = idx;
                            // 先显示"正在下载第 X/N 片"，卡住时用户能看出在等哪一片（避免误判成合并卡）
                            task.detail = "下载中 第 " + (idx + 1) + "/" + segN + " 片…";
                            task.debug = "第 " + n + " 集：正在下载第 " + (idx + 1) + "/" + segN + " 片…";
                            String name = String.format(Locale.US, "seg-%04d.ts", idx);
                            File segFile = new File(work, name);
                            // 分片复用：重试时已存在且非空的分片直接跳过（合并失败保留的 ts 不再重复下载）
                            if (!(segFile.exists() && segFile.length() > 0)) {
                                downloadToFile(segUris.get(idx), segFile, dlBytes, totalBytes);
                            }
                            segFiles[idx] = name;
                            // 进度=真正完成片数（精确），并显示实时下载速度
                            int done = segDone.incrementAndGet();
                            long now = System.currentTimeMillis();
                            long lb = lastBytes.getAndSet(dlBytes.get());
                            long lt = lastTime.getAndSet(now);
                            long dt = now - lt;
                            double mbps = dt > 0 ? (dlBytes.get() - lb) / 1024.0 / 1024.0 / (dt / 1000.0) : 0;
                            task.detail = String.format(Locale.US, "下载中 已完成 %d/%d 片 · %.1fMB/s", done, segN, mbps);
                            task.debug = "第 " + n + " 集：已完成 " + done + "/" + segN + " 片"
                                + String.format(Locale.US, " · %.1fMB/s", mbps);
                        }
                    } catch (Exception e) {
                        throw new RuntimeException("第 " + (curIdx + 1) + "/" + segN + " 片下载失败: " + e.getMessage(), e);
                    }
                }));
            }
            try {
                Exception firstErr = null;
                for (Future<?> f : futs) {
                    try { f.get(); } catch (Exception e) { if (firstErr == null) firstErr = e.getCause() instanceof Exception ? (Exception) e.getCause() : e; }
                }
                if (firstErr != null) throw firstErr;
            } finally {
                pool.shutdownNow();
            }
            if (task.canceled) return;
            for (int i = 0; i < segN; i++) {
                if (segFiles[i] == null) throw new java.io.IOException("分片下载不完整（第 " + (i + 1) + "/" + segN + " 片缺失）");
                File sf = new File(work, segFiles[i]);
                if (!sf.exists() || sf.length() == 0) throw new java.io.IOException("分片文件无效（第 " + (i + 1) + "/" + segN + " 片大小异常）");
            }
            // 本地 playlist（相对路径；每段按需写 KEY 行，ffmpeg 原生 AES-128 解密）
            File playlist = new File(work, "play.m3u8");
            {
                StringBuilder sb = new StringBuilder("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:12\n#EXT-X-MEDIA-SEQUENCE:0\n");
                String lastKeyName = null;
                for (int i = 0; i < segN; i++) {
                    String ku = segKeys.get(i);
                    String kn = ku != null ? keyFiles.get(ku) : null;
                    if (kn != null && !kn.equals(lastKeyName)) {
                        sb.append("#EXT-X-KEY:METHOD=AES-128,URI=\"").append(kn).append("\"");
                        // 源显式 IV 原样保留（IV=0x...），缺失时 hls demuxer 用序列号默认 IV → 解密错
                        String iv = ku != null ? keyIVs.get(ku) : null;
                        if (iv != null && !iv.isEmpty()) sb.append(",IV=").append(iv);
                        sb.append('\n');
                        lastKeyName = kn;
                    }
                    sb.append("#EXTINF:4.0,\n").append(segFiles[i]).append('\n');
                }
                sb.append("#EXT-X-ENDLIST\n");
                java.nio.file.Files.write(playlist.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
            }
            StringBuilder dbg = new StringBuilder();
            File tmpOut = new File(work, "ep.mp4");
            // 方案2（无 ffmpeg）：Cipher 解密 ts → full.ts 字节拼接 → MediaExtractor/MediaMuxer 重封装。
            // 独立子线程 + 120s 硬超时：偶发卡死时任务报"合并超时"可重试，不再永久卡住
            task.detail = "第 " + n + " 集合并中…";
            task.debug = "第 " + n + " 集：解密合并中…";
            int msq = 0;
            try {
                java.util.regex.Matcher ms = java.util.regex.Pattern.compile("#EXT-X-MEDIA-SEQUENCE:\\s*(\\d+)")
                        .matcher(new String(java.nio.file.Files.readAllBytes(playlist.toPath()), StandardCharsets.UTF_8));
                if (ms.find()) msq = Integer.parseInt(ms.group(1));
            } catch (Exception ignored) {}
            final int mediaSeq = msq;
            ExecutorService remuxPool = Executors.newSingleThreadExecutor();
            try {
                Future<?> fr = remuxPool.submit(() -> {
                    try { mergeTsToMp4(work, segFiles, segKeys, segIVs, keyFiles, mediaSeq, tmpOut, dbg); }
                    catch (Throwable t) { throw new RuntimeException(t); }
                });
                try {
                    fr.get(120, TimeUnit.SECONDS);
                } catch (TimeoutException te) {
                    fr.cancel(true);
                    // 超时也要留合并日志，用户能看卡在哪
                    task.error = "第 " + n + " 集 m3u8 合并超时（120s）\n" + dbg;
                    task.debug = "第 " + n + " 集：合并超时（120s），详见合并日志";
                    throw new java.io.IOException("第 " + n + " 集 m3u8 合并超时（120s），请重试");
                } catch (java.util.concurrent.ExecutionException ee) {
                    // 合并失败：把 m3u8ToMp4 详细日志写进任务，不再让 debug 残留下载阶段的"已完成 X/Y 片"
                    Throwable cause = ee.getCause() != null ? ee.getCause() : ee;
                    task.error = "第 " + n + " 集合并失败: " + cause + "\n" + dbg;
                    task.debug = "第 " + n + " 集：合并失败，详见合并日志";
                    throw new RuntimeException(cause);
                }
            } finally { remuxPool.shutdownNow(); }
            task.debug = "第 " + n + " 集：合并完成";
            // 产物移到正式分集路径；移动失败则复制（跨目录 rename 一般可成）
            if (!tmpOut.renameTo(out)) {
                java.nio.file.Files.copy(tmpOut.toPath(), out.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                tmpOut.delete();
            }
            if (!out.exists() || out.length() < 1024) throw new java.io.IOException("m3u8 转 mp4 产物无效");
            segOk = true;   // 本集合并成功，产物已落位
        } finally {
            // 合并成功后清理本集临时目录（ts/KEY/playlist/半成品）；失败保留 ts，
            // 文件管理器可见已下载分片（下多少存多少，与后端落盘一致）
            if (segOk) deleteRecursively(work);
        }
    }

    /** 方案2（彻底移除 ffmpeg/JavaCPP）：Cipher AES-128-CBC 解密每个 ts → 字节级拼 full.ts →
     *  MediaExtractor/MediaMuxer 重封装单集 mp4。HLS 标准：分片 16 字节对齐，NoPadding 解密；
     *  显式 IV 按分片（segIVs），无显式 IV 时用媒体序列号（mediaSeq+i 大端 16B）。
     *  成功后删密文 ts + full.ts；失败保留密文 ts（可重试，文件管理器可见分片）。
     *  视频/音频 csd 缺失走提取/构造兜底（复用 normalizeEpisode 同款逻辑）。 */
    private static void mergeTsToMp4(File work, String[] segFiles, List<String> segKeys, List<String> segIVs,
            Map<String, String> keyFiles, int mediaSeq, File out, StringBuilder dbg) throws Exception {
        long t0 = System.currentTimeMillis();
        dbg.append("方案2：Cipher 解密 + MediaExtractor/MediaMuxer 合并\n");
        // 1) KEY 字节（KEY URL → 16B）
        Map<String, byte[]> keyBytes = new HashMap<>();
        for (Map.Entry<String, String> e : keyFiles.entrySet()) {
            File kf = new File(work, e.getValue());
            if (!kf.exists() || kf.length() == 0) throw new java.io.IOException("KEY 文件缺失: " + e.getValue());
            byte[] kb = java.nio.file.Files.readAllBytes(kf.toPath());
            if (kb.length != 16) throw new java.io.IOException("KEY 文件异常: " + e.getValue() + " len=" + kb.length);
            keyBytes.put(e.getKey(), kb);
        }
        // 2) 逐片解密 → full.ts（字节级拼接，源 pts 天然连续；失败保留密文可重试）
        File full = new File(work, "full.ts");
        try (FileOutputStream fos = new FileOutputStream(full)) {
            for (int i = 0; i < segFiles.length; i++) {
                File seg = new File(work, segFiles[i]);
                if (!seg.exists() || seg.length() == 0) throw new java.io.IOException("分片缺失: " + segFiles[i]);
                byte[] enc = java.nio.file.Files.readAllBytes(seg.toPath());
                byte[] key = keyBytes.get(segKeys.get(i));
                if (key == null) throw new java.io.IOException("缺少分片 KEY: " + segFiles[i]);
                byte[] iv = ivForSeg(segIVs, i, mediaSeq);
                try {
                    Cipher c = Cipher.getInstance("AES/CBC/NoPadding");
                    c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
                    byte[] dec = c.doFinal(enc);
                    fos.write(dec);
                } catch (Exception ce) {
                    throw new java.io.IOException("分片解密失败 " + segFiles[i] + ": " + ce);
                }
            }
        }
        dbg.append("解密拼接完成 ").append(segFiles.length).append(" 片 → full.ts ").append(full.length()).append("B\n");
        // 3) MediaExtractor 打开 full.ts → 音视频轨
        MediaExtractor ex = new MediaExtractor();
        ex.setDataSource(full.getAbsolutePath());
        int vIdx = -1, aIdx = -1;
        for (int t = 0; t < ex.getTrackCount(); t++) {
            String mime = ex.getTrackFormat(t).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("video/")) vIdx = t;
            else if (mime != null && mime.startsWith("audio/")) aIdx = t;
        }
        if (vIdx < 0) { ex.release(); throw new java.io.IOException("合并产物没有视频轨道"); }
        MediaFormat vf = ex.getTrackFormat(vIdx);
        MediaFormat af = aIdx >= 0 ? ex.getTrackFormat(aIdx) : null;
        ex.release();
        // 视频 csd 缺失（avc3/hev1 内联参数集）→ 流式扫描首关键帧提取 SPS/PPS（HEVC 含 VPS）
        String vmime = vf.getString(MediaFormat.KEY_MIME);
        if (!vf.containsKey("csd-0")) {
            List<byte[]> nal = null;
            MediaExtractor se = new MediaExtractor();
            try {
                se.setDataSource(full.getAbsolutePath());
                se.selectTrack(vIdx);
                boolean hevc = vmime != null && vmime.startsWith("video/hevc");
                while (se.advance()) {
                    if ((se.getSampleFlags() & MediaCodec.BUFFER_FLAG_KEY_FRAME) == 0) continue;
                    long sz = se.getSampleSize();
                    if (sz <= 0 || sz > 4 * 1024 * 1024) continue;
                    ByteBuffer bb = ByteBuffer.allocate((int) sz);
                    int n = se.readSampleData(bb, 0);
                    if (n <= 0) continue;
                    bb.position(0); bb.limit(n);
                    List<byte[]> p = extractParameterSets(bb, hevc);
                    if (!p.isEmpty()) { nal = p; break; }
                }
            } finally { se.release(); }
            if (nal == null) throw new java.io.IOException("无法提取视频参数集(csd)");
            int total = 0; for (byte[] b : nal) total += b.length;
            ByteBuffer csd = ByteBuffer.allocate(total);
            for (byte[] b : nal) csd.put(b);
            csd.position(0);
            vf.setByteBuffer("csd-0", csd);
        }
        // 音频 csd 缺失 → 从首个 ADTS 帧构造 AudioSpecificConfig
        if (af != null && !af.containsKey("csd-0")) {
            ByteBuffer asc = extractAacAsc(full, aIdx);
            if (asc == null) throw new java.io.IOException("无法构造音频 csd(ADTS)");
            af.setByteBuffer("csd-0", asc);
        }
        // 4) 音视频各自归零基准 + 流式交错写 mp4（与 normalizeEpisode 一致）
        long vFirst = Math.max(0, firstSamplePts(full, vIdx));
        long aFirst = af != null ? Math.max(0, firstSamplePts(full, aIdx)) : vFirst;
        MediaMuxer muxer = new MediaMuxer(out.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        try {
            int vt = muxer.addTrack(vf);
            int at = af != null ? muxer.addTrack(af) : -1;
            muxer.start();
            writeInterleavedStream(full, vIdx, aIdx, muxer, vt, at, vFirst, aFirst);
            muxer.stop();
        } finally { muxer.release(); }
        dbg.append("合并完成 ").append(out.length()).append("B 耗时 ").append((System.currentTimeMillis() - t0) / 1000).append("s\n");
    }

    /** 分片 IV：显式 IV（segIVs 里 0x…）优先；无显式 IV 时 HLS 默认 = 媒体序列号（mediaSeq+i，16 字节大端）。 */
    private static byte[] ivForSeg(List<String> segIVs, int i, int mediaSeq) {
        String ivs = (i < segIVs.size()) ? segIVs.get(i) : null;
        if (ivs != null && !ivs.isEmpty()) {
            String h = ivs.replace("0x", "").replace("0X", "");
            if (h.length() == 32) {
                byte[] b = new byte[16];
                for (int k = 0; k < 16; k++) b[k] = (byte) Integer.parseInt(h.substring(k * 2, k * 2 + 2), 16);
                return b;
            }
        }
        long seq = ((long) mediaSeq) + i;
        byte[] b = new byte[16];
        for (int k = 15; k >= 0; k--) { b[k] = (byte) (seq & 0xff); seq >>>= 8; }
        return b;
    }

    /** 从音频轨首个 ADTS 帧头构造 AudioSpecificConfig（MediaMuxer addTrack 需要 csd-0）。 */
    private static ByteBuffer extractAacAsc(File file, int track) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(file.getAbsolutePath());
            ex.selectTrack(track);
            while (ex.advance()) {
                long sz = ex.getSampleSize();
                if (sz < 7) continue;
                ByteBuffer bb = ByteBuffer.allocate((int) Math.min(sz, 1024));
                int n = ex.readSampleData(bb, 0);
                if (n < 7) continue;
                byte[] h = new byte[4];
                bb.position(0); bb.get(h);
                if ((h[0] & 0xff) != 0xff || (h[1] & 0xf0) != 0xf0) continue;   // 非 ADTS sync
                int profile = (h[2] & 0xc0) >> 6;                     // objectType - 1
                int sfIdx = (h[2] & 0x3c) >> 2;                        // samplingFrequencyIndex
                int chCfg = ((h[2] & 0x01) << 2) | ((h[3] & 0xc0) >> 6); // channelConfiguration
                int objectType = profile + 1;
                byte[] asc = new byte[2];
                asc[0] = (byte) ((objectType << 3) | (sfIdx >> 1));
                asc[1] = (byte) (((sfIdx & 1) << 7) | (chCfg << 3));
                ByteBuffer out = ByteBuffer.wrap(asc);
                out.position(0);
                return out;
            }
            return null;
        } finally { ex.release(); }
    }

    private static void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;
        File[] children = f.listFiles();
        if (children != null) for (File c : children) deleteRecursively(c);
        f.delete();
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
            if (size <= 0) continue;
            ByteBuffer buf = ByteBuffer.allocate((int) size);
            int n = ex.readSampleData(buf, 0);
            if (n <= 0) continue;   // 读取失败的样本直接跳过，绝不把垃圾数据交给 muxer
            buf.position(0);
            buf.limit(n);
            out.add(new Sample(pts, n, flags, buf));
        }
        try { ex.release(); } catch (Exception ignored) {}
        return out;
    }

    /** 单样本写入 muxer（复用 BufferInfo）。 */
    private static void writeSample(MediaMuxer muxer, int track, long t, long size, long flags, ByteBuffer data, MediaCodec.BufferInfo info) {
        ByteBuffer buffer = data.duplicate();
        info.offset = 0;
        info.size = (int) size;
        info.presentationTimeUs = t;
        info.flags = (int) flags;
        muxer.writeSampleData(track, buffer, info);
    }

    /** 音视频按写入时间交错归并（等价 ffmpeg 输出交错，mdat 内视频/音频块交替排布，seek 友好）。
     *  视频轨保持解码序（列表顺序）不被归并打乱，仅决定与音频的穿插；
     *  音频按 aMaxRel 截断（相对 aBase，对齐视频时长）；传 -1 不截断。
     *  返回 {视频maxT, 音频maxT}（相对各自 offset）。 */
    private static long[] writeInterleaved(MediaMuxer muxer, int vTrack, int aTrack,
            List<Sample> vSamples, List<Sample> aSamples,
            long vBase, long vOffset, long aBase, long aOffset,
            long[] vLastRef, long[] aLastRef, long aMaxRel) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long lastV = vLastRef[0], lastA = aLastRef[0], maxV = 0, maxA = 0;
        int vi = 0, ai = 0;
        while (vi < vSamples.size() || ai < aSamples.size()) {
            boolean takeV;
            if (vi >= vSamples.size()) takeV = false;
            else if (ai >= aSamples.size()) takeV = true;
            else {
                long vT = vSamples.get(vi).pts - vBase + vOffset; if (vT < 0) vT = 0; if (vT <= lastV) vT = lastV + 1;
                long aT = aSamples.get(ai).pts - aBase + aOffset; if (aT < 0) aT = 0; if (aT <= lastA) aT = lastA + 1;
                takeV = vT <= aT;
            }
            if (takeV) {
                Sample s = vSamples.get(vi++);
                long t = s.pts - vBase + vOffset;
                if (t < 0) t = 0;
                if (t <= lastV) t = lastV + 1;
                lastV = t; if (t > maxV) maxV = t;
                writeSample(muxer, vTrack, t, s.size, s.flags, s.data, info);
            } else {
                Sample s = aSamples.get(ai++);
                long rel = s.pts - aBase;
                if (aMaxRel >= 0 && rel > aMaxRel) continue;
                long t = rel + aOffset;
                if (t < 0) t = 0;
                if (t <= lastA) t = lastA + 1;
                lastA = t; if (t > maxA) maxA = t;
                writeSample(muxer, aTrack, t, s.size, s.flags, s.data, info);
            }
        }
        vLastRef[0] = lastV; aLastRef[0] = lastA;
        return new long[]{maxV, maxA};
    }

    /** 流式单轨游标（归并用）：按解码序推进，PTS 单调化，保留当前样本供比较。
     *  注意：构造时必须 selectTrack——MediaExtractor 默认读轨道 0，红果分集第 0 轨常为音频，
     *  不选轨会把音频样本当成视频样本读，导致产物视频轨为空（合并报"分集没有视频轨道"）。 */
    private static final class Cursor {
        final MediaExtractor ex;
        final long base;
        final int muxTrack;
        long t, size, flags;
        ByteBuffer buffer;
        ByteBuffer reusable;
        boolean eof;
        private long lastT = Long.MIN_VALUE;
        Cursor(MediaExtractor ex, int track, long base, int muxTrack) {
            this.ex = ex; this.base = base; this.muxTrack = muxTrack;
            ex.selectTrack(track);
        }
        void advance() throws java.io.IOException {
            if (eof) return;
            while (ex.advance()) {
                long sz = ex.getSampleSize();
                if (sz <= 0) continue;
                if (reusable == null || reusable.capacity() < sz) reusable = ByteBuffer.allocate((int) Math.max(sz, 1 << 18));   // 复用缓冲，避免每样本 allocate
                reusable.clear();
                int n = ex.readSampleData(reusable, 0);
                if (n <= 0) continue;
                reusable.position(0); reusable.limit(n);
                long tt = ex.getSampleTime() - base;
                if (tt < 0) tt = 0;
                if (tt <= lastT) tt = lastT + 1;
                lastT = tt;
                this.t = tt; this.size = n; this.flags = ex.getSampleFlags(); this.buffer = reusable;
                return;
            }
            eof = true;
        }
    }

    /** 音视频各自归零基准 + 按时间交错写入（流式版，normalizeEpisode 用）。
     *  等价 ffmpeg -c copy 单集输出：视频从 0 起、音频从 0 起、mdat 交错。 */
    private static void writeInterleavedStream(File file, int vTrack, int aTrack, MediaMuxer muxer,
            int vMux, int aMux, long vBase, long aBase) throws Exception {
        MediaExtractor vx = new MediaExtractor();
        vx.setDataSource(file.getAbsolutePath());
        Cursor vc = new Cursor(vx, vTrack, vBase, vMux);
        vc.advance();
        Cursor ac = null;
        if (aTrack >= 0) {
            MediaExtractor ax = new MediaExtractor();
            ax.setDataSource(file.getAbsolutePath());
            ac = new Cursor(ax, aTrack, aBase, aMux);
            ac.advance();
        }
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (!vc.eof || (ac != null && !ac.eof)) {
            boolean takeV;
            if (vc.eof) takeV = false;
            else if (ac == null || ac.eof) takeV = true;
            else takeV = vc.t <= ac.t;
            Cursor c = takeV ? vc : ac;
            writeSample(muxer, c.muxTrack, c.t, c.size, c.flags, c.buffer, info);
            c.advance();
        }
        try { vx.release(); } catch (Exception ignored) {}
    }

    /** 按解码序整轨写入样本，返回本轨最大 PTS（相对 offset）。
     *  H.264 含 B 帧时解码序的 PTS 会摆动（B 帧 PTS < 相邻 P 帧），部分设备 MediaMuxer 要求时间戳单调，
     *  直接写会抛 writeSampleData returned an error。做法：物理顺序保持解码序，只把回退的时间戳
     *  单调化（抬到不早于前一样本）——解码引用顺序不受影响（画面正常），B 帧呈现仅滞后 1µs（无感知）。
     *  lastTRef[0] 为跨集持久的上一样本时间戳（每轨独立），保证多集合并时时间戳绝对单调。
     *  maxRel 为本集相对时长上限（相对 base）；样本相对时间超过上限直接跳过——用于音频按视频时长
     *  截断，与后端转码 `-t 视频时长` 对齐：红果每集视频轨比音频轨短（视频首样本 PTS≈533333µs、音频≈0），
     *  若音频不截断，音视频轨时长差会逐集累积，合并产物 seek 后音画错位（画面快进追赶、声音乱码）。
     *  传 -1 表示不截断（视频轨）。 */
    private static long writeSamples(MediaMuxer muxer, int track, List<Sample> samples, long base, long offset, long[] lastTRef, long maxRel) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long lastT = lastTRef[0];
        long maxT = 0;
        for (Sample s : samples) {
            long rel = s.pts - base;
            if (maxRel >= 0 && rel > maxRel) continue;   // 截断（音频对齐视频时长）
            ByteBuffer buffer = s.data.duplicate();
            info.offset = 0;
            info.size = (int) s.size;
            long t = rel + offset;
            if (t < 0) t = 0;
            if (t <= lastT) t = lastT + 1;   // PTS 单调化（解码序保持，仅修正回退）
            info.presentationTimeUs = t;
            info.flags = (int) s.flags;
            muxer.writeSampleData(track, buffer, info);
            lastT = t;
            if (t > maxT) maxT = t;
        }
        lastTRef[0] = lastT;
        return maxT;
    }

    private void mergeMp4(List<File> inputs, File output, Task task) throws Exception {
        StringBuilder debug = new StringBuilder("合并详细日志\n分集数=").append(inputs.size()).append('\n');
        String stage = "初始化";
        try {
            // 0 集保护：分集列表为空（如旧版本任务未持久化 episodes 被重试）直接报明确错误，不进 concat（避免 native 层异常/闪退）
            if (inputs == null || inputs.isEmpty()) {
                throw new java.io.IOException("分集数=0，无法合并。若为重试旧任务：其分集列表未持久化，请删除该任务后重新下载");
            }
            // 探测各集视频编码（majority 判定 → avc3/hev1）与可读性（等价后端 buildPlan 的 majorityKey）
            int hevc = 0, h264 = 0, bad = 0;
            for (int i = 0; i < inputs.size(); i++) {
                if (task.canceled) return;
                stage = "检测第" + (i + 1) + "集编码";
                MediaExtractor ex = new MediaExtractor();
                try {
                    ex.setDataSource(inputs.get(i).getAbsolutePath());
                    String vmime = null;
                    for (int t = 0; t < ex.getTrackCount(); t++) {
                        String mime = ex.getTrackFormat(t).getString(MediaFormat.KEY_MIME);
                        if (mime != null && mime.startsWith("video/")) { vmime = mime; break; }
                    }
                    if (vmime == null) { bad++; debug.append("第").append(i + 1).append("集: 无视频轨道\n"); }
                    else {
                        if (vmime.startsWith("video/hevc")) hevc++;
                        else if (vmime.startsWith("video/avc")) h264++;
                        debug.append("第").append(i + 1).append("集: 视频mime=").append(vmime).append('\n');
                    }
                } catch (Exception e) {
                    // CENC 密文文件部分设备 MediaExtractor 读不了 → 容错（C 层规范化会逐个复检并解密），
                    // 不中断合并；tag 判定由 C 层 majority 兜底
                    debug.append("第").append(i + 1).append("集探测失败(容错，C层将解密复检): ").append(e.getMessage()).append('\n');
                } finally { try { ex.release(); } catch (Exception ignored) {} }
            }
            if (bad > 0) throw new java.io.IOException("有 " + bad + " 集无视频轨道");

            // ffmpeg concat（自写 NDK JNI，直调 C API）：与后端 server/downloader.js 合并命令 1:1
            // （下载密文 → C 层 ffmpeg -decryption_key 原生解密重封装 → concat：
            //   -f concat -safe 0 -c copy + -video_track_timescale + -movflags +faststart
            //   + -tag:v avc3/hev1 in-band SPS/PPS：播放器 seek 任何段按帧内参数解码，杜绝快进花屏/乱码）
            String tag = h264 >= hevc ? "avc3" : "hev1";   // majority 编码（红果短剧一般为 HEVC → hev1）
            List<String> keys = new ArrayList<>();
            for (int i = 0; i < inputs.size(); i++) {
                JSONObject ep = task.episodes.optJSONObject(i);
                keys.add(ep != null ? ep.optString("key", "") : "");
            }
            stage = "运行 ffmpeg concat 合并";
            File nativeLog = new File(context.getFilesDir(), "native.log");
            FFmpegConcat.concat(inputs, keys, output, tag, debug, nativeLog);
            stage = "完成";
            debug.append("完成: 产物=").append(output.length() / 1048576).append("MB tag=").append(tag).append('\n');
            task.debug = debug.toString();
        } catch (Exception e) {
            debug.append("失败阶段: ").append(stage).append('\n');
            debug.append("异常: ").append(e.getClass().getSimpleName()).append(": ").append(e.getMessage()).append('\n');
            task.debug = debug.toString();
            throw e;
        }
    }

    /** 把产物视频轨 sample entry 标签 avc1→avc3、hvc1→hev1（in-band 参数集，等价后端 concat 的 -tag:v avc3/hev1）。
     *  MediaMuxer 强制要求 csd 且默认写 avc1/hvc1，但样本数据仍保留源（avc3/hev1）内联的 SPS/PPS/VPS，
     *  改标签后播放器即按帧内参数集解码：seek 到任何集都用该集自己的参数集，杜绝快进花屏/乱码。
     *  只改 stsd 内的 4 字节标签；moov 在文件末尾（MediaMuxer 无 faststart），读尾部定位足够。 */
    private static void patchInbandTag(File file) throws Exception {
        java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "rw");
        try {
            long len = raf.length();
            long tailSize = Math.min(len, 8L << 20);   // 尾部 8MB（12 集 moov 约 1-2MB）
            long start = len - tailSize;
            byte[] tail = new byte[(int) tailSize];
            raf.seek(start);
            raf.readFully(tail);
            boolean changed = false;
            for (int i = 0; i + 4 <= tail.length; i++) {
                if (tail[i] == 'a' && tail[i + 1] == 'v' && tail[i + 2] == 'c' && tail[i + 3] == '1') {
                    tail[i + 3] = '3'; changed = true;                 // avc1 → avc3
                } else if (tail[i] == 'h' && tail[i + 1] == 'v' && tail[i + 2] == 'c' && tail[i + 3] == '1') {
                    tail[i + 1] = 'e'; tail[i + 2] = 'v'; changed = true;   // hvc1 → hev1
                }
            }
            if (changed) { raf.seek(start); raf.write(tail); }
        } finally { raf.close(); }
    }

    /** 若 format 缺 csd（avc3/hev1 内联参数集场景），从首个关键帧样本提取 SPS/PPS（HEVC 另含 VPS）注入 csd-0。
     *  返回 true=已有 csd 或注入成功；false=未注入（缺 csd 且提取失败）。 */
    private static boolean ensureCodecSpecificData(MediaFormat format, List<Sample> samples, String mime) {
        try {
            if (format == null || samples == null || samples.isEmpty()) return false;
            if (format.containsKey("csd-0")) return true;   // moov 自带 avcC（avc1 等），无需注入
            boolean hevc = mime != null && mime.startsWith("video/hevc");
            int[] want = hevc ? new int[]{32, 33, 34} : new int[]{7, 8};   // hevc: VPS/SPS/PPS；h264: SPS/PPS
            for (Sample s : samples) {
                if ((s.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) == 0) continue;
                List<byte[]> nal = extractParameterSets(s.data, hevc);
                if (nal.isEmpty()) continue;
                int total = 0;
                for (byte[] n : nal) total += n.length;
                ByteBuffer csd = ByteBuffer.allocate(total);
                for (byte[] n : nal) csd.put(n);
                csd.position(0);
                format.setByteBuffer("csd-0", csd);
                return true;
            }
            return false;   // 缺 csd 且关键帧内未提取到参数集
        } catch (Exception ignored) {
            // 提取失败不阻断合并（可能仍有其它设备可写），保留原 format
            return false;
        }
    }

    /** 扫描样本字节，按 H.264/HEVC 的 NAL 起始码切分，返回按序的 SPS/PPS（HEVC 含 VPS），数据不含起始码。
     *  兼容两种样本布局：AnnexB（00 00 01 起始码）与 AVCC length-prefixed（4 字节大端长度）。 */
    private static List<byte[]> extractParameterSets(ByteBuffer sample, boolean hevc) {
        List<byte[]> out = new ArrayList<>();
        byte[] d = new byte[sample.remaining()];
        sample.duplicate().get(d);
        int n = d.length;
        if (n < 5) return out;
        int[] found = new int[]{0, 0, 0};   // [vps, sps, pps]
        boolean annexb = false;
        // 判断首 NAL 分隔方式：00 00 01 / 00 00 00 01 → AnnexB；否则尝试 AVCC 长度前缀
        int b0 = d[0] & 0xFF, b1 = d[1] & 0xFF, b2 = d[2] & 0xFF, b3 = d[3] & 0xFF;
        if (b0 == 0 && b1 == 0 && (b2 == 1 || (b2 == 0 && b3 == 1))) annexb = true;
        if (annexb) {
            int i = 0;
            while (i < n - 4) {
                int start = -1;
                if ((d[i] & 0xFF) == 0 && (d[i + 1] & 0xFF) == 0) {
                    if ((d[i + 2] & 0xFF) == 1) start = i + 3;
                    else if ((d[i + 2] & 0xFF) == 0 && (d[i + 3] & 0xFF) == 1) start = i + 4;
                }
                if (start < 0) { i++; continue; }
                int end = -1;
                for (int j = start; j < n - 3; j++) {
                    if ((d[j] & 0xFF) == 0 && (d[j + 1] & 0xFF) == 0) {
                        if ((d[j + 2] & 0xFF) == 1) { end = j; break; }
                        if ((d[j + 2] & 0xFF) == 0 && (d[j + 3] & 0xFF) == 1) { end = j; break; }
                    }
                }
                int nalEnd = end < 0 ? n : end;
                if (start < nalEnd) {
                    int type = hevc ? ((d[start] & 0xFF) >> 1) & 0x3F : (d[start] & 0xFF) & 0x1F;
                    int idx = hevc ? (type == 32 ? 0 : type == 33 ? 1 : type == 34 ? 2 : -1)
                                   : (type == 7 ? 1 : type == 8 ? 2 : -1);
                    if (idx >= 0 && found[idx] == 0) {
                        found[idx] = 1;
                        out.add(java.util.Arrays.copyOfRange(d, start, nalEnd));
                    }
                }
                if (end < 0) break;
                i = end;
            }
        } else {
            // AVCC length-prefixed：4 字节大端长度 + NAL 数据，循环跳过
            int i = 0;
            while (i + 4 <= n) {
                int len = ((d[i] & 0xFF) << 24) | ((d[i + 1] & 0xFF) << 16)
                        | ((d[i + 2] & 0xFF) << 8) | (d[i + 3] & 0xFF);
                if (len <= 0 || i + 4 + len > n) break;
                int start = i + 4;
                int nalEnd = start + len;
                int type = hevc ? ((d[start] & 0xFF) >> 1) & 0x3F : (d[start] & 0xFF) & 0x1F;
                int idx = hevc ? (type == 32 ? 0 : type == 33 ? 1 : type == 34 ? 2 : -1)
                               : (type == 7 ? 1 : type == 8 ? 2 : -1);
                if (idx >= 0 && found[idx] == 0) {
                    found[idx] = 1;
                    out.add(java.util.Arrays.copyOfRange(d, start, nalEnd));
                }
                i += 4 + len;
            }
        }
        return out;
    }

    // ---- 导出到公共 Download（MediaStore，API 29+；旧版回退 app 专属目录）----

    /** 合并成功后清理分集：删除 app 私有分集（ep*.mp4 / norm_*.mp4）与公共 Downloads 分集（第XX集.mp4），
     *  只保留合并产物。合并失败/未合并不动，保证可重试。 */
    private void deleteEpisodesAfterMerge(Task t, String subDir) {
        try {
            File dir = dlDir(t.id);
            if (dir.exists()) {
                File[] kids = dir.listFiles();
                if (kids != null) for (File f : kids) {
                    String n = f.getName();
                    if (f.isFile() && (n.startsWith("ep") || n.startsWith("norm_"))) f.delete();
                }
            }
            if (Build.VERSION.SDK_INT >= 29) {
                String rel = Environment.DIRECTORY_DOWNLOADS + "/Veyra"
                        + (subDir == null || subDir.isEmpty() ? "" : "/" + subDir);
                try (android.database.Cursor c = context.getContentResolver().query(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        new String[]{MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME},
                        MediaStore.Downloads.RELATIVE_PATH + "=?", new String[]{rel}, null)) {
                    if (c != null) {
                        while (c.moveToNext()) {
                            String name = c.getString(1);
                            if (name != null && name.matches("第\\d+集\\.mp4")) {
                                try {
                                    context.getContentResolver().delete(
                                            ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0)),
                                            null, null);
                                } catch (Exception ignored) { }
                            }
                        }
                    }
                } catch (Exception ignored) { }
            } else {
                File pub = new File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "Veyra/" + subDir);
                File[] kids = pub.listFiles();
                if (kids != null) for (File f : kids) {
                    if (f.isFile() && f.getName().matches("第\\d+集\\.mp4")) f.delete();
                }
            }
        } catch (Exception ignored) { }
    }

    private Uri exportToDownloads(File file, String subDir, String displayName) throws Exception {
        if (Build.VERSION.SDK_INT >= 29) {
            // 先清理同名旧条目：MediaStore insert 遇同名（残留/删除未及时完成）会自动追加 "_时间戳"
            // 后缀生成新文件，导致产物名漂移 + 旧文件残留；先删同名再插入，保证永远用原名覆盖。
            String rel = Environment.DIRECTORY_DOWNLOADS + "/Veyra";
            if (subDir != null && !subDir.isEmpty()) rel += "/" + subDir;
            try (android.database.Cursor c = context.getContentResolver().query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    new String[]{MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME},
                    MediaStore.Downloads.RELATIVE_PATH + "=?", new String[]{rel}, null)) {
                if (c != null) {
                    while (c.moveToNext()) {
                        if (displayName.equals(c.getString(1))) {
                            try {
                                context.getContentResolver().delete(
                                        ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0)),
                                        null, null);
                            } catch (Exception ignored) { }
                        }
                    }
                }
            } catch (Exception ignored) { }
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, displayName);
            values.put(MediaStore.Downloads.MIME_TYPE, "video/mp4");
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

    /** 删除公共 Downloads/Veyra/<剧名>/ 下该剧全部 mp4 产物与残留：
     *  原名（<剧名>.mp4）+ MediaStore 同名冲突自动加后缀的孤儿（<剧名>_<时间戳>.mp4）+ 空文件夹。
     *  与 task.output 指向的 uri/文件互补，确保「APP 删除 = 手机存储彻底删干净」。 */
    private void purgePublicExports(Task t) {
        try {
            String base = t.title == null ? "" : t.title.replaceAll("[\\\\/:*?\"<>|]", "_");
            String displayBase = base + ".mp4";
            if (Build.VERSION.SDK_INT >= 29) {
                String rel = Environment.DIRECTORY_DOWNLOADS + "/Veyra" + (base.isEmpty() ? "" : "/" + base);
                try (android.database.Cursor c = context.getContentResolver().query(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        new String[]{MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME},
                        MediaStore.Downloads.RELATIVE_PATH + "=?", new String[]{rel}, null)) {
                    if (c != null) {
                        while (c.moveToNext()) {
                            String name = c.getString(1);
                            if (name == null) continue;
                            boolean hit = name.equals(displayBase)
                                    || name.matches("第\\d+集\\.mp4")   // 分集（下载后解密导出）
                                    || (!base.isEmpty() && name.length() > displayBase.length()
                                    && name.startsWith(displayBase) && name.charAt(displayBase.length()) == '_'
                                    && name.endsWith(".mp4"));
                            if (hit) {
                                try {
                                    context.getContentResolver().delete(
                                            ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0)),
                                            null, null);
                                } catch (Exception ignored) { }
                            }
                        }
                    }
                } catch (Exception ignored) { }
                // MediaStore 删除后尝试删物理空目录（部分 ROM 删除异步，删不掉就留给系统回收）
                try {
                    File pub = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Veyra/" + base);
                    String[] left = pub.list();
                    if (left != null && left.length == 0) pub.delete();
                } catch (Exception ignored) { }
            } else {
                File dir = new File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "Veyra/" + base);
                if (dir.exists() && dir.isDirectory()) {
                    File[] kids = dir.listFiles();
                    if (kids != null) for (File f : kids) {
                        if (f.isFile() && (f.getName().endsWith(".mp4") || f.getName().matches("第\\d+集\\.mp4"))) f.delete();
                    }
                    String[] left = dir.list();
                    if (left == null || left.length == 0) dir.delete();
                }
            }
        } catch (Exception ignored) { }
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

    /** 音频转码管线：LATM(HE-AAC) → AAC-LC（与后端 server/downloader.js 的 juku 方案一致）。
     *  红果分集音频为 audio/mp4a-latm（HE-AAC，含 SBR/PS），MediaMuxer 直写 LATM 后系统播放器
     *  在快进/seek 时因采样率/声道动态切换而声音乱码；统一转成 AAC-LC（aac_low/192k）后 seek 稳定。
     *  流程：MediaCodec 解码器 → PCM → MediaCodec AAC 编码器 → 直接写 MediaMuxer（不落盘）。
     *  每集一个实例（各集 srcFormat 独立），轨道 csd 由第 1 集 pumpConfig() 产出并在 addTrack 前注入。 */
    private static final class AudioTranscoder {
        final MediaCodec dec, enc;
        final List<Sample> samples;
        final long[] lastRef;
        int si = 0;
        boolean decEos = false, encEos = false;
        final ByteBuffer[] decIn, decOut, encIn, encOut;
        final MediaCodec.BufferInfo decInfo = new MediaCodec.BufferInfo();
        final MediaCodec.BufferInfo encInfo = new MediaCodec.BufferInfo();
        final int srcSr, srcCh;

        AudioTranscoder(MediaFormat srcFmt, List<Sample> sampleList, long[] lastTRef) throws Exception {
            String mime = srcFmt.getString(MediaFormat.KEY_MIME);
            srcSr = srcFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE) ? srcFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 48000;
            srcCh = srcFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? srcFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 2;
            dec = MediaCodec.createDecoderByType(mime);
            dec.configure(srcFmt, null, null, 0);
            dec.start();
            enc = MediaCodec.createEncoderByType("audio/mp4a-latm");
            MediaFormat e = MediaFormat.createAudioFormat("audio/mp4a-latm", srcSr, srcCh);
            e.setInteger(MediaFormat.KEY_BIT_RATE, 192000);
            e.setInteger("aac-profile", 2);            // AAC-LC（KEY_AAC_PROFILE 常量高 SDK 才有，用字面量）
            e.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 65536);
            enc.configure(e, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            enc.start();
            samples = sampleList;
            lastRef = lastTRef;
            decIn = dec.getInputBuffers(); decOut = dec.getOutputBuffers();
            encIn = enc.getInputBuffers(); encOut = enc.getOutputBuffers();
        }

        /** 泵送直到拿到编码器的 codec-config（含 csd-0），返回该 format（用于 addTrack）；失败返回 null。 */
        MediaFormat pumpConfig() throws Exception {
            int spin = 0;
            while (spin++ < 4000) {
                feedDecoder();
                if (pipeDecoderToEncoder()) {
                    int eOut = enc.dequeueOutputBuffer(encInfo, 0);
                    if (eOut >= 0) {
                        if ((encInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            MediaFormat f = enc.getOutputFormat();
                            enc.releaseOutputBuffer(eOut, false);
                            return f;
                        }
                        enc.releaseOutputBuffer(eOut, false);   // 数据帧暂不入 muxer，等轨道注册后再写（队列保留后续帧）
                    }
                }
                if (decEos && si >= samples.size()) break;
            }
            return null;
        }

        /** 泵完剩余全部音频帧，收集为已绝对化 PTS 的样本列表（供与视频交错写入，等价后端转码输出交错）。
         *  自动跳过 codec-config 帧；maxRel 为本集相对时长上限（相对 base）：超过直接丢弃（音频按视频时长截断，对齐后端 -t）。
         *  注意：返回列表的 Sample.pts 已是绝对写入时间（含 offset），归并写入时按 aBase=0/aOffset=0 处理。 */
        List<Sample> pumpAllCollect(long base, long offset, long maxRel) throws Exception {
            List<Sample> out = new ArrayList<>();
            int spin = 0;
            while (!encEos && spin++ < 2000000) {
                feedDecoder();
                pipeDecoderToEncoder();
                int eOut = enc.dequeueOutputBuffer(encInfo, 0);
                if (eOut < 0) continue;
                if ((encInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) { enc.releaseOutputBuffer(eOut, false); continue; }
                long rel = encInfo.presentationTimeUs - base;
                if (maxRel >= 0 && rel > maxRel) { enc.releaseOutputBuffer(eOut, false); continue; }   // 截断
                long t = rel + offset;
                if (t < 0) t = 0;
                if (t <= lastRef[0]) t = lastRef[0] + 1;   // 跨集绝对单调（与 writeSamples 一致）
                lastRef[0] = t;
                ByteBuffer outBuf = encOut[eOut];
                outBuf.position(encInfo.offset);
                outBuf.limit(encInfo.offset + encInfo.size);
                ByteBuffer copy = ByteBuffer.allocate(encInfo.size);
                copy.put(outBuf);
                copy.flip();
                out.add(new Sample(t, encInfo.size, encInfo.flags, copy));
                enc.releaseOutputBuffer(eOut, false);
                if ((encInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) encEos = true;
            }
            if (!encEos && !out.isEmpty()) return out;   // 编码器 EOS 偶发丢失/延迟（解码端 EOS 帧被丢）：用已收集音频，避免整次合并失败
            if (!encEos) throw new java.io.IOException("音频转码未正常结束");
            return out;
        }

        private void feedDecoder() throws Exception {
            int inIdx = dec.dequeueInputBuffer(0);
            if (inIdx < 0) return;
            if (si < samples.size()) {
                Sample s = samples.get(si++);
                ByteBuffer b = decIn[inIdx];
                b.clear();
                if (s.size > b.capacity()) throw new java.io.IOException("音频样本超过解码器输入容量: " + s.size);
                s.data.duplicate().get(b.array(), 0, (int) s.size);
                dec.queueInputBuffer(inIdx, 0, (int) s.size, s.pts, (int) s.flags);
            } else if (!decEos) {
                decEos = true;
                dec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            }
        }

        /** 解码器输出 → 编码器输入；返回是否有可继续取的编码器输出。 */
        private boolean pipeDecoderToEncoder() throws Exception {
            int outIdx = dec.dequeueOutputBuffer(decInfo, 0);
            if (outIdx < 0) return true;
            if ((decInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) { dec.releaseOutputBuffer(outIdx, false); return true; }
            int eIn = enc.dequeueInputBuffer(0);
            if (eIn < 0) { dec.releaseOutputBuffer(outIdx, false); return true; }
            ByteBuffer src = decOut[outIdx];
            src.position(decInfo.offset);
            src.limit(decInfo.offset + decInfo.size);
            ByteBuffer dst = encIn[eIn];
            dst.clear();
            dst.put(src);
            enc.queueInputBuffer(eIn, 0, decInfo.size, decInfo.presentationTimeUs, decInfo.flags);
            dec.releaseOutputBuffer(outIdx, false);
            if ((decInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) decEos = true;
            return true;
        }

        void release() {
            try { enc.stop(); } catch (Exception ignored) {}
            try { dec.stop(); } catch (Exception ignored) {}
            try { enc.release(); } catch (Exception ignored) {}
            try { dec.release(); } catch (Exception ignored) {}
        }
    }
}
