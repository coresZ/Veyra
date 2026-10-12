package com.veyra.gybeyond;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;

final class ContentMode {
    static final String PUBLIC = "hongguo", PRIVATE = "huangguo";
    private static ContentMode instance;
    private final SharedPreferences preferences;
    private volatile String active = PUBLIC;
    // 私密模式解锁仅保留在内存：进程被杀重启后回到红果，需重新验证动态码
    private volatile boolean unlockedMemory = false;

    private ContentMode(Context context) { preferences = context.getSharedPreferences("content-access", Context.MODE_PRIVATE); }
    static synchronized ContentMode get(Context context) {
        if (instance == null) instance = new ContentMode(context.getApplicationContext());
        return instance;
    }
    String active() { return active; }
    void startPublic() { active = PUBLIC; }
    boolean unlocked() { return unlockedMemory; }
    boolean adultConfirmed() { return preferences.getBoolean("adultConfirmed", false); }
    void confirmAdult() { preferences.edit().putBoolean("adultConfirmed", true).apply(); }
    /** 动态码验证通过后调用（验证逻辑在 MainActivity，这里只记录解锁状态）。 */
    void markPrivateUnlocked() { unlockedMemory = true; }
    /** 已验证动态码并确认年龄后进入私密模式。 */
    synchronized JSONObject enterPrivateVerified() throws Exception {
        if (!unlockedMemory) throw new IllegalStateException("请先验证动态码");
        if (adultConfirmed()) active = PRIVATE;
        return status();
    }
    synchronized JSONObject finishPrivateEntry() throws Exception {
        if (!unlockedMemory) throw new IllegalStateException("请先验证动态码");
        confirmAdult(); active = PRIVATE;
        return status();
    }
    void require(String source) {
        if (!PUBLIC.equals(source) && !PRIVATE.equals(source)) throw new IllegalArgumentException("无效内容来源");
        if (!source.equals(active) || PRIVATE.equals(source) && (!unlockedMemory || !adultConfirmed()))
            throw new IllegalStateException("内容已切换，请重新打开");
    }
    JSONObject status() throws Exception {
        return new JSONObject().put("source", active).put("unlocked", unlockedMemory)
                .put("needsAgeConfirmation", unlockedMemory && !adultConfirmed());
    }
}
