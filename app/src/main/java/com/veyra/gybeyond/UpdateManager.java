package com.veyra.gybeyond;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 更新检测：直连 GitHub Releases API（gybeyond1/Veyra），
 * 读取 latest release 的 tag_name / body / assets[0].browser_download_url 作为新版 APK。
 * 版本比较用语义化版本号（v0.16.0 vs 本地 versionName 0.16.0）。
 */
final class UpdateManager {
    private static final String RELEASE_API = "https://api.github.com/repos/gybeyond1/Veyra/releases/latest";
    private static final String RELEASE_BASE = "https://github.com/gybeyond1/Veyra/releases/download/";
    private static final Pattern VERSION = Pattern.compile("\\d+\\.\\d+\\.\\d+");
    private static final long AUTO_INTERVAL_MS = 12 * 60 * 60 * 1000L;
    private static final long MANUAL_WINDOW_MS = 60 * 60 * 1000L;
    private static final int MANUAL_LIMIT = 5;

    interface Progress { void onPercent(int percent); }

    private final Context context;
    private final SharedPreferences preferences;
    private Candidate candidate;
    private boolean downloading;

    private static final class Candidate {
        String version;
        String url;
        String digest;
        long size;
        String notes;
    }

    UpdateManager(Context context) {
        this.context = context.getApplicationContext();
        preferences = this.context.getSharedPreferences("updates", Context.MODE_PRIVATE);
    }

    synchronized JSONObject cached() throws Exception {
        String saved = preferences.getString("available_manifest", null);
        if (saved == null) return status("none");
        try {
            JSONObject manifest = new JSONObject(saved);
            PackageInfo installed = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            if (isNewer(manifest.optString("versionName"), installed.versionName)) {
                candidate = candidateFrom(manifest);
                return availableStatus(candidate, installed.versionName);
            }
            clearCandidate();
            return status("none");
        } catch (Exception error) {
            clearCandidate();
            return status("none");
        }
    }

    private void clearCandidate() {
        candidate = null;
        preferences.edit().remove("available_manifest").apply();
    }

    /** 语义化版本比较：remote 比 local 新返回 true。 */
    private static boolean isNewer(String remote, String local) {
        int[] r = parseVersion(remote), l = parseVersion(local);
        if (r == null || l == null) return false;
        for (int i = 0; i < 3; i++) {
            if (r[i] != l[i]) return r[i] > l[i];
        }
        return false;
    }

    private static int[] parseVersion(String v) {
        if (v == null) return null;
        String s = v.trim().replaceFirst("^[vV]", "");
        String[] parts = s.split("\\.");
        if (parts.length < 3) return null;
        try {
            return new int[]{
                    Integer.parseInt(parts[0]),
                    Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2])
            };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Candidate candidateFrom(JSONObject manifest) throws Exception {
        String version = manifest.optString("versionName");
        String tag = manifest.optString("tag");
        String apk = manifest.optString("apk");
        String sha256 = manifest.optString("sha256");
        if (!VERSION.matcher(version).matches() || !tag.equals("v" + version)
                || !apk.endsWith(".apk")) {
            throw new IllegalStateException("发布信息格式有误");
        }
        Candidate result = new Candidate();
        result.version = version;
        // 直连 GitHub 下载（跟随 302 到 objects CDN）
        result.url = RELEASE_BASE + tag + "/" + apk;
        result.digest = sha256.matches("[0-9a-fA-F]{64}") ? "sha256:" + sha256 : "";
        result.size = manifest.optLong("size");
        result.notes = manifest.optString("notes");
        return result;
    }

    private static JSONObject availableStatus(Candidate value, String localVersion) throws Exception {
        return status("available").put("version", value.version).put("currentVersion", localVersion)
                .put("notes", value.notes).put("size", value.size);
    }

    synchronized JSONObject check(boolean manual) throws Exception {
        long now = System.currentTimeMillis();
        if (manual) {
            List<Long> checks = new ArrayList<>();
            for (String value : preferences.getString("manual_checks", "").split(",")) {
                try {
                    long timestamp = Long.parseLong(value);
                    if (timestamp <= now && now - timestamp < MANUAL_WINDOW_MS) checks.add(timestamp);
                } catch (NumberFormatException ignored) { }
            }
            if (checks.size() >= MANUAL_LIMIT) return status("throttled");
            checks.add(now);
            StringBuilder saved = new StringBuilder();
            for (long timestamp : checks) {
                if (saved.length() > 0) saved.append(',');
                saved.append(timestamp);
            }
            preferences.edit().putString("manual_checks", saved.toString()).apply();
        } else {
            long last = preferences.getLong("last_auto_check", 0);
            if (last > 0 && now >= last && now - last < AUTO_INTERVAL_MS) return cached();
            preferences.edit().putLong("last_auto_check", now).apply();
        }

        JSONObject manifest = fetchManifest();
        if (manual) preferences.edit().putLong("last_auto_check", now).apply();
        PackageInfo installed = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
        String localVersion = installed.versionName;
        if (manifest == null) {
            clearCandidate();
            return status("unpublished");
        }
        if (!isNewer(manifest.optString("versionName"), localVersion)) {
            clearCandidate();
            return status("current").put("version", localVersion);
        }
        candidate = candidateFrom(manifest);
        preferences.edit().putString("available_manifest", manifest.toString()).apply();
        return availableStatus(candidate, localVersion);
    }

    synchronized File download(Progress progress) throws Exception {
        if (downloading) throw new IllegalStateException("更新包正在下载");
        if (candidate == null) throw new IllegalStateException("请先检查更新");
        if (!candidate.url.startsWith(RELEASE_BASE + "download/")
                && !candidate.url.startsWith(RELEASE_BASE)) {
            throw new IllegalStateException("更新包地址无效");
        }
        downloading = true;
        try {
            File directory = new File(context.getCacheDir(), "updates");
            if (!directory.exists() && !directory.mkdirs()) throw new IllegalStateException("无法创建下载目录");
            String name = candidate.url.substring(candidate.url.lastIndexOf('/') + 1);
            File apk = new File(directory, name);
            if (apk.isFile() && (!candidate.digest.isEmpty() && matchesDigest(apk, candidate.digest))) {
                progress.onPercent(100);
                return apk;
            }
            File partial = new File(directory, name + ".part");
            // 最多两次尝试：第一次中断后保留 .part，第二次走 Range 续传；仍失败则保留 .part，
            // 下次点「重试下载」可从断点继续，不再每次从头下。
            Exception lastError = null;
            for (int attempt = 0; attempt < 2; attempt++) {
                try {
                    return downloadAttempt(candidate.url, candidate.size, candidate.digest, partial, apk, progress);
                } catch (Exception error) {
                    lastError = error;
                    // 校验失败说明已下载内容本身就是坏的，续传无意义，清掉从头下；
                    // 「下载不完整」/网络中断则保留 .part 供续传。
                    if (error instanceof IllegalStateException && error.getMessage() != null
                            && error.getMessage().contains("校验失败")) {
                        if (partial.exists()) partial.delete();
                    }
                    progress.onPercent(0);
                }
            }
            throw lastError != null ? lastError : new IllegalStateException("下载失败，请重试");
        } finally {
            downloading = false;
        }
    }

    /** 单次下载尝试：.part 存在时带 Range 续传（服务器支持则 206），完成后校验大小 + SHA-256。 */
    private File downloadAttempt(String url, long expectedSize, String digest, File partial, File apk, Progress progress) throws Exception {
        long resume = partial.isFile() ? partial.length() : 0;
        HttpURLConnection connection = open(url, resume);
        try {
            int code = connection.getResponseCode();
            boolean resumed = resume > 0 && code == HttpURLConnection.HTTP_PARTIAL;
            long downloaded = resumed ? resume : 0;
            if (resume > 0 && !resumed) partial.delete();   // 服务器不认 Range：从头全量
            long total = expectedSize > 0 ? expectedSize : connection.getContentLengthLong();
            try (InputStream input = connection.getInputStream();
                 FileOutputStream output = new FileOutputStream(partial, resumed)) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                int previous = -1;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                    downloaded += count;
                    int percent = total > 0 ? (int) Math.min(99, downloaded * 100 / total) : 0;
                    if (percent != previous) { progress.onPercent(percent); previous = percent; }
                }
            }
            if (expectedSize > 0 && downloaded != expectedSize) throw new IllegalStateException("更新包下载不完整，请重试");
            if (!digest.isEmpty() && !matchesDigest(partial, digest)) throw new IllegalStateException("更新包校验失败，请重试");
            if (apk.exists() && !apk.delete()) throw new IllegalStateException("无法替换旧下载包");
            if (!partial.renameTo(apk)) throw new IllegalStateException("无法保存更新包");
            progress.onPercent(100);
            return apk;
        } finally {
            connection.disconnect();
        }
    }

    private static HttpURLConnection open(String address, long from) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(60_000);
        connection.setRequestProperty("User-Agent", "ChengZi-Android-Updater");
        connection.setRequestProperty("Accept", "application/json");
        if (from > 0) connection.setRequestProperty("Range", "bytes=" + from + "-");
        int code = connection.getResponseCode();
        if ((code < 200 || code >= 300) && code != HttpURLConnection.HTTP_PARTIAL) {
            connection.disconnect();
            throw new IllegalStateException("GitHub 请求失败（" + code + "）");
        }
        return connection;
    }

    private static JSONObject status(String value) throws Exception { return new JSONObject().put("status", value); }

    /** 拉取 GitHub Releases API latest：tag_name / body / 第一个 APK asset。 */
    private static JSONObject fetchManifest() throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(RELEASE_API).openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(30_000);
        connection.setRequestProperty("User-Agent", "ChengZi-Android-Updater");
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        int code = connection.getResponseCode();
        if (code == 404) { connection.disconnect(); return null; }
        if (code < 200 || code >= 300) {
            connection.disconnect();
            throw new IllegalStateException("GitHub 请求失败（" + code + "）");
        }
        try (InputStream stream = connection.getInputStream()) {
            byte[] bytes = new byte[256 * 1024];
            int length = 0;
            while (true) {
                int count = stream.read(bytes, length, bytes.length - length);
                if (count < 0) break;
                length += count;
                if (length == bytes.length) throw new IllegalStateException("发布列表过大");
            }
            JSONObject release = new JSONObject(new String(bytes, 0, length, java.nio.charset.StandardCharsets.UTF_8));
            String tag = release.optString("tag_name", "");
            String version = tag.replaceFirst("^[vV]", "");
            String notes = release.optString("body", "");
            JSONArray assets = release.optJSONArray("assets");
            if (assets == null || assets.length() == 0) throw new IllegalStateException("Release 无 APK 附件");
            // 在 assets 里找 APK 文件（release 可能同时带镜像 tar，按名字区分）
            JSONObject asset = null;
            for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.optJSONObject(i);
                if (a != null && a.optString("name", "").endsWith(".apk")) { asset = a; break; }
            }
            if (asset == null) throw new IllegalStateException("Release 无 APK 附件");
            JSONObject manifest = new JSONObject();
            manifest.put("versionName", version);
            manifest.put("tag", tag);
            manifest.put("apk", asset.optString("name", ""));
            manifest.put("browser_download_url", asset.optString("browser_download_url", ""));
            manifest.put("size", asset.optLong("size", 0));
            manifest.put("notes", notes);
            // GitHub API 返回 digest（如 sha256:...），去掉前缀只留 hex
            String digest = asset.optString("digest", "");
            digest = digest.replaceFirst("^sha256:", "").trim();
            manifest.put("sha256", digest);
            return manifest;
        } finally {
            connection.disconnect();
        }
    }

    private static boolean matchesDigest(File file, String expected) throws Exception {
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new java.io.FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) sha256.update(buffer, 0, count);
        }
        StringBuilder actual = new StringBuilder("sha256:");
        for (byte value : sha256.digest()) actual.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return actual.toString().equalsIgnoreCase(expected);
    }
}
