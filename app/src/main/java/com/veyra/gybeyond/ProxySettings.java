package com.veyra.gybeyond;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 黄果网络通道设置（仅黄果/私密模式生效；红果永远直连，不走本设置）。
 * 三态模式（无自动判定，显式选择，切换立即生效）：
 *   MODE_DIRECT  仅直连：只用系统网络栈（VPN/全局代理自然生效），失败即报错
 *   MODE_PROXY   仅代理：只用配置的代理地址，失败即报错
 *   MODE_BACKEND 走后端：黄果内容（含视频分片）全部经后端 /api/huangguo-proxy 转发
 * 每次请求实时读取，切换模式立即生效（无需重建/重启）。默认走后端转发。
 */
final class ProxySettings {
    private static final String PREFS = "chengzi-settings";
    private static final String KEY = "huangguoProxy";
    private static final String KEY_MODE = "huangguoMode";

    static final int MODE_DIRECT = 1;
    static final int MODE_PROXY = 2;
    static final int MODE_BACKEND = 3;

    private ProxySettings() { }

    /** 当前代理地址，未设置返回空字符串。 */
    static String address(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").trim();
    }

    static void set(Context context, String address) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, String.valueOf(address).trim()).apply();
    }

    /** 当前黄果通道模式（1 直连 / 2 代理 / 3 转发），默认走后端转发；旧版 0=自动 一并兜底为转发。 */
    static int mode(Context context) {
        int mode = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_MODE, MODE_BACKEND);
        if (mode < MODE_DIRECT || mode > MODE_BACKEND) mode = MODE_BACKEND;
        return mode;
    }

    static void setMode(Context context, int mode) {
        if (mode < MODE_DIRECT || mode > MODE_BACKEND) mode = MODE_BACKEND;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_MODE, mode).apply();
    }
}
