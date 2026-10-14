package com.veyra.gybeyond;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 红果 App 详情接口（api5-normal-sinfonlineb.fqnovel.com /novel/player/video_detail/v1/）。
 * 复刻 juku 签名体系（X-SS-STUB / X-Khronos / X-Gorgon / X-SS-Req-Ticket），每次请求随机生成
 * 设备身份（device_id/iid），规避固定设备画像风控；低频调用（搜索结果补齐系列/季数据用）。
 * 目标：拿到红果官方系列键 related_album_id 与官方季序号 season_index，供前端按官方系列分组。
 */
final class HongguoAppApi {
    private static final String BASE = "https://api5-normal-sinfonlineb.fqnovel.com";
    private static final String UA = "com.phoenix.read/73532 (Linux; U; Android 16; zh_CN; 25053RT47C; "
            + "Build/BP2A.250605.031.A3; Cronet/TTNetVersion:04657795 2026-01-23 QuicVersion:c67e9834 2025-09-08)";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final byte[] KEY = { (byte) 0x44, (byte) 0xb9, (byte) 0xb9, (byte) 0xd9, (byte) 0xa4, (byte) 0xae,
            (byte) 0xf9, (byte) 0xfc, (byte) 0xa4, (byte) 0x93, (byte) 0xaa, (byte) 0x75, (byte) 0x7c, (byte) 0xa3,
            (byte) 0xc2, (byte) 0xc4, (byte) 0xa4, (byte) 0x96, (byte) 0x93, (byte) 0x8f };

    private final OkHttpClient http;
    private final LinkedHashMap<String, JSONObject> cache = new LinkedHashMap<>(32, 0.75f, true);

    HongguoAppApi(OkHttpClient http) { this.http = http; }

    /** 取一部剧的官方系列信息；失败返回 null（调用方降级为不分组）。内存缓存避免重复请求。 */
    JSONObject fetch(String seriesId) {
        if (seriesId == null || !seriesId.matches("[0-9]{1,30}")) return null;
        synchronized (cache) {
            JSONObject hit = cache.get(seriesId);
            if (hit != null) return hit;
        }
        try {
            JSONObject result = doFetch(seriesId);
            if (result == null) return null;
            synchronized (cache) {
                cache.put(seriesId, result);
                while (cache.size() > 256) {
                    String oldest = cache.keySet().iterator().next();
                    cache.remove(oldest);
                }
            }
            return result;
        } catch (Exception ignored) { return null; }
    }

    private JSONObject doFetch(String seriesId) throws Exception {
        final long nowSec = System.currentTimeMillis() / 1000L;
        final String deviceId = newDeviceId(), installId = newDeviceId();
        Map<String, String> params = new TreeMap<>();
        params.put("aid", "8662"); params.put("app_name", "novelread");
        params.put("version_code", "73532"); params.put("version_name", "7.3.5.32");
        params.put("manifest_version_code", "73532"); params.put("update_version_code", "73532");
        params.put("channel", "update_64"); params.put("device_platform", "android");
        params.put("os", "android"); params.put("ssmix", "a"); params.put("device_type", "25053RT47C");
        params.put("device_brand", "Redmi"); params.put("language", "zh"); params.put("os_api", "36");
        params.put("os_version", "16"); params.put("resolution", "1280*2772"); params.put("dpi", "520");
        params.put("ac", "wifi"); params.put("device_id", deviceId); params.put("iid", installId);
        params.put("_rticket", String.valueOf(System.currentTimeMillis()));
        String rawQuery = goEncode(params);
        byte[] body = ("{\"series_id\":\"" + seriesId + "\"}").getBytes(StandardCharsets.UTF_8);
        Request.Builder rb = new Request.Builder()
                .url(BASE + "/novel/player/video_detail/v1/?" + rawQuery)
                .post(RequestBody.create(JSON, body))
                .header("User-Agent", UA)
                .header("Accept", "application/json")
                .header("Sdk-Version", "2")
                .header("X-XS-From-Web", "0");
        sign(rb, rawQuery, body, nowSec);
        try (Response response = http.newCall(rb.build()).execute()) {
            if (!response.isSuccessful() || response.body() == null) return null;
            JSONObject json = new JSONObject(response.body().string());
            JSONObject data = json.optJSONObject("data");
            JSONObject video = data == null ? null : data.optJSONObject("video_data");
            if (video == null) return null;
            return new JSONObject()
                    .put("album_id", String.valueOf(video.optLong("related_album_id", 0L)))
                    .put("season_index", video.optInt("season_index", 0))
                    .put("episode_cnt", video.optInt("episode_cnt", 0))
                    .put("series_title", video.optString("series_title", ""));
        }
    }

    /** juku signHongguoRequest：X-SS-STUB=body MD5 大写；X-Gorgon=20 字节载荷混淆+固定前缀。 */
    private static void sign(Request.Builder rb, String rawQuery, byte[] body, long nowSec) {
        byte[] queryHash = md5(rawQuery.getBytes(StandardCharsets.UTF_8));
        byte[] payload = new byte[20];
        System.arraycopy(queryHash, 0, payload, 0, 4);
        if (body != null) {
            byte[] bodyHash = md5(body);
            System.arraycopy(bodyHash, 0, payload, 4, 4);
            rb.header("X-SS-STUB", hex(bodyHash));
        }
        payload[12] = 0; payload[13] = 6; payload[14] = 11; payload[15] = 28;
        payload[16] = (byte) (nowSec >>> 24); payload[17] = (byte) (nowSec >>> 16);
        payload[18] = (byte) (nowSec >>> 8); payload[19] = (byte) nowSec;
        for (int i = 0; i < 20; i++) payload[i] ^= KEY[i];
        for (int i = 0; i < 20; i++)
            payload[i] = (byte) (rev8(rotl8(payload[i] & 0xff, 4) ^ (payload[(i + 1) % 20] & 0xff)) ^ 0xff ^ 20);
        byte[] signature = new byte[26];
        signature[0] = (byte) 0x84; signature[1] = 0x04; signature[2] = 0x40; signature[3] = 0x1c;
        signature[4] = 0; signature[5] = 0;
        System.arraycopy(payload, 0, signature, 6, 20);
        rb.header("X-Khronos", String.valueOf(nowSec));
        // juku：X-Gorgon=Go hex.EncodeToString（小写）；X-SS-STUB=fmt.Sprintf("%X")（大写）
        rb.header("X-Gorgon", hex(signature, false));
        rb.header("X-SS-Req-Ticket", String.valueOf(System.currentTimeMillis()));
    }

    private static int rotl8(int x, int n) { return ((x << n) | (x >>> (8 - n))) & 0xff; }
    private static int rev8(int x) { return Integer.reverse(x & 0xff) >>> 24; }

    private static byte[] md5(byte[] input) {
        try {
            MessageDigest d = MessageDigest.getInstance("MD5");
            return d.digest(input);
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static String hex(byte[] bytes) { return hex(bytes, true); }

    private static String hex(byte[] bytes, boolean upper) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            String h = String.format(Locale.ROOT, upper ? "%02X" : "%02x", b & 0xff);
            sb.append(h);
        }
        return sb.toString();
    }

    /** Go url.Values.Encode()：TreeMap 按键排序 + QueryEscape（空格→+、~ 保留、其余 %XX 大写）。 */
    private static String goEncode(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(goEscape(e.getKey())).append('=').append(goEscape(e.getValue()));
        }
        return sb.toString();
    }

    private static String goEscape(String s) {
        StringBuilder out = new StringBuilder();
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        for (byte b : bytes) {
            int c = b & 0xff;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~') out.append((char) c);
            else if (c == ' ') out.append('+');
            else out.append('%').append(String.format(Locale.ROOT, "%02X", c));
        }
        return out.toString();
    }

    private static String newDeviceId() {
        long base = 1_000_000_000_000_000_000L + Math.floorMod(ThreadLocalRandom.current().nextLong(), 8_000_000_000_000_000_000L);
        return String.valueOf(base);
    }
}
