package com.veyra.gybeyond;

import android.content.Context;
import android.content.SharedPreferences;

/** 后端服务器地址配置：登录/注册时填写，持久化到本地；未配置时用内置默认（可为空，表示必须填写）。 */
public final class ServerConfig {
    private static final String PREFS = "veyra-server";
    private static final String KEY = "serverBase";

    private ServerConfig() {}

    /** 当前生效的后端地址（带尾斜杠）；未配置自定义时返回内置默认（config.xml，可能为空串）。 */
    public static String get(Context context) {
        String saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").trim();
        if (!saved.isEmpty()) return withSlash(saved);
        return withSlash(context.getString(R.string.api_base_url));
    }

    public static void set(Context context, String server) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, server.trim()).apply();
    }

    public static void clear(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply();
    }

    public static boolean hasCustom(Context context) {
        return !context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").trim().isEmpty();
    }

    /** 校验服务器地址：http(s)://host[:port][/path]，合法返回规范化地址（带尾斜杠），否则抛 IllegalArgumentException。 */
    public static String normalize(String server) {
        String s = server == null ? "" : server.trim();
        if (s.isEmpty()) throw new IllegalArgumentException("请填写服务器地址");
        if (!s.matches("(?i)^https?://[a-z0-9.-]+(:\\d{1,5})?(/.*)?$"))
            throw new IllegalArgumentException("服务器地址格式不对，示例：https://example.com:1314");
        return withSlash(s);
    }

    private static String withSlash(String s) {
        if (s.isEmpty()) return "";
        return s.endsWith("/") ? s : s + "/";
    }
}
