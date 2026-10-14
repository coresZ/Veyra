package com.veyra.gybeyond;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 黄果网络代理设置：格式 "host:port"（如 127.0.0.1:7890）。
 * 仅黄果内容使用：代理可用时直连，代理不通时回退后端转发；红果不受影响。
 */
final class ProxySettings {
    private static final String PREFS = "chengzi-settings";
    private static final String KEY = "huangguoProxy";
    private static final String KEY_BACKEND = "backendFallback";

    private ProxySettings() { }

    /** 当前代理地址，未设置返回空字符串。 */
    static String address(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").trim();
    }

    static void set(Context context, String address) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, String.valueOf(address).trim()).apply();
    }

    /** 私密模式是否强制走后端转发（手动开关，覆盖自动判定）。 */
    static boolean backendFallback(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_BACKEND, false);
    }

    static void setBackendFallback(Context context, boolean value) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_BACKEND, value).apply();
    }
}
