package com.veyra.gybeyond;

import com.hongguotv.core.ContentRepository;
import com.hongguotv.core.Signer;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Public website search covers both supported content kinds in one request. */
final class HongguoSearch {
    private final OkHttpClient http;
    HongguoSearch(OkHttpClient http) { this.http = http; }
    JSONObject search(String keyword) throws Exception { return search(keyword, false); }
    JSONObject search(String keyword, boolean sequelLookup) throws Exception {
        keyword = keyword.trim();
        if (keyword.isEmpty()) return new JSONObject().put("items", new JSONArray()).put("nextPage", JSONObject.NULL);
        if (keyword.length() > 80) throw new IllegalArgumentException("搜索词过长");
        // 红果联想接口：一次返回以关键词开头的完整短剧名（含"剧名+第N季"全集版），
        // 网页搜索漏掉的全集版（如 341 集版持械入宋第三季）靠它补全（juku 同款方案）。
        java.util.LinkedHashMap<String, JSONObject> seen = new java.util.LinkedHashMap<>();
        try {
            JSONArray suggest = suggestionItems(keyword, 50);
            for (int i = 0; i < suggest.length(); i++) {
                JSONObject it = suggest.optJSONObject(i);
                if (it != null && normalize(it.optString("title")).contains(normalize(keyword)))
                    seen.putIfAbsent(it.optString("id"), it);
            }
        } catch (Exception ignored) { }
        Request request = new Request.Builder().url("https://hongguoduanju.com/search/" + Signer.Companion.encode(keyword))
                .header("User-Agent", "Mozilla/5.0").build();
        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) throw new IOException("搜索暂时无法连接，请重试");
            JSONObject loader = ContentRepository.Companion.extractRouter(response.body().string()).getJSONObject("loaderData");
            for (java.util.Iterator<String> keys = loader.keys(); keys.hasNext();) {
                JSONObject section = loader.optJSONObject(keys.next());
                if (section != null && section.has("searchList")) {
                    JSONObject web = parse(section, keyword, sequelLookup);
                    JSONArray webItems = web.optJSONArray("items");
                    int webCount = webItems == null ? 0 : webItems.length();
                    for (int i = 0; webItems != null && i < webItems.length(); i++) {
                        JSONObject it = webItems.optJSONObject(i);
                        if (it != null) seen.putIfAbsent(it.optString("id"), it);
                    }
                    if (seen.size() > webCount) {
                        JSONArray merged = new JSONArray();
                        for (JSONObject it : seen.values()) merged.put(it);
                        return new JSONObject().put("items", merged).put("nextPage", JSONObject.NULL);
                    }
                    return web;
                }
            }
            throw new IOException("搜索页面数据结构已变化");
        }
    }
    /** 红果联想接口（hongguoduanju.com，juku 验证可用）：suggest_list 过滤短剧名类型。 */
    private JSONArray suggestionItems(String query, int count) throws Exception {
        Request request = new Request.Builder()
                .url("https://hongguoduanju.com/incent_resource/suggestion?app_id=8662&query="
                        + Signer.Companion.encode(query) + "&count=" + count)
                .header("User-Agent", "Mozilla/5.0")
                .header("Referer", "https://hongguoduanju.com/")
                .header("Accept", "application/json").build();
        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) throw new IOException("联想接口不可用");
            JSONObject json = new JSONObject(response.body().string());
            JSONArray list = json.optJSONArray("suggest_list");
            JSONArray items = new JSONArray();
            Set<String> ids = new HashSet<>();
            for (int i = 0; list != null && i < list.length(); i++) {
                JSONObject record = list.optJSONObject(i);
                if (record == null || !"short_play_name".equals(record.optString("word_type"))) continue;
                JSONObject video = record.optJSONObject("video_data");
                String id = video == null ? "" : video.optString("series_id");
                if (id.isEmpty() || !id.matches("[0-9]{1,30}") || !ids.add(id)) continue;
                String title = record.optString("name");
                if (title.isEmpty() && video != null) title = video.optString("series_title");
                JSONArray tags = new JSONArray();
                if (video != null) {
                    JSONArray cats = video.optJSONArray("category_list");
                    for (int j = 0; cats != null && j < cats.length() && tags.length() < 5; j++) {
                        String n = cats.optJSONObject(j).optString("name");
                        if (!n.isEmpty()) tags.put(n);
                    }
                }
                items.put(new JSONObject().put("id", id).put("title", title)
                        .put("cover", video == null ? "" : video.optString("series_cover"))
                        .put("description", video == null ? "" : video.optString("series_intro"))
                        .put("episodeLabel", video == null ? "" : video.optString("episode_right_text"))
                        .put("category", "").put("score", "").put("tags", tags));
            }
            return items;
        }
    }
    private static String normalize(String text) { return text.replaceAll("\\s+", "").toLowerCase(Locale.ROOT); }
    static JSONObject parse(JSONObject section, String keyword) throws Exception { return parse(section, keyword, false); }
    private static String normalized(String value, boolean sequelLookup) {
        return sequelLookup ? normalize(value).replaceAll("[\\p{P}\\p{Z}]", "") : normalize(value);
    }
    private static JSONObject parse(JSONObject section, String keyword, boolean sequelLookup) throws Exception {
        if (!section.optBoolean("isSuccess", true)) throw new IOException("搜索服务暂不可用，请重试");
        JSONArray rows = section.getJSONArray("searchList"), items = new JSONArray();
        Set<String> ids = new HashSet<>();
        String query = normalized(keyword, sequelLookup);
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i), video = row == null ? null : row.optJSONObject("video_data");
            if (video == null) continue;
            String id = video.optString("series_id"), title = video.optString("series_title", row.optString("name"));
            String genre = video.optString("genre");
            String type = "203".equals(genre) ? "short" : "205".equals(genre) ? "comic" : "";
            // The website may append unrelated recommendations; never present them as matches.
            if (type.isEmpty() || !id.matches("[0-9]{1,30}") || !normalized(title, sequelLookup).contains(query) || !ids.add(id)) continue;
            JSONArray tags = new JSONArray(), categories = video.optJSONArray("category_list");
            if (categories != null) for (int j = 0; j < categories.length(); j++) {
                JSONObject category = categories.optJSONObject(j);
                if (category != null && !category.optString("name").isEmpty()) tags.put(category.optString("name"));
            }
            items.put(new JSONObject().put("id", id).put("title", title).put("cover", video.optString("series_cover"))
                    .put("description", video.optString("series_intro")).put("episodeLabel", video.optString("episode_right_text"))
                    .put("category", type).put("score", "").put("tags", tags));
        }
        return new JSONObject().put("items", items).put("nextPage", JSONObject.NULL);
    }
}
