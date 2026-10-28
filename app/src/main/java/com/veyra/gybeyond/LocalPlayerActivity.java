package com.veyra.gybeyond;

import android.app.Activity;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;

/**
 * 本地成品播放器：直接播 Download/Veyra/&lt;剧名&gt; 下已下载/已合并的 mp4，不走后端取址。
 *
 * 与在线 PlayerActivity 完全隔离（新类，不改在线播放链路），只服务「我的下载」点封面本机直看：
 *  - 合并好的整集（剧名.mp4）：单文件播放；
 *  - 未合并的分集（第NN集.mp4）：一集一个文件，播完自动接下一集，底部按钮可前后切集。
 * 内容模式隔离由 DownloadManager.localFiles 兜底（切换过模式会直接抛错，拿不到对方文件）。
 */
public final class LocalPlayerActivity extends Activity {

    private ExoPlayer player;
    private PlayerView view;
    private TextView caption;
    private LinearLayout navRow;
    private JSONArray episodes = new JSONArray();
    private int index = 0;
    private boolean merged = false;
    private String title = "";

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideSystemBars();

        title = getIntent().getStringExtra("title");
        String path = getIntent().getStringExtra("path");
        merged = getIntent().getBooleanExtra("merged", false);
        String raw = getIntent().getStringExtra("episodes");
        try { if (raw != null && !raw.isEmpty()) episodes = new JSONArray(raw); } catch (Exception ignored) { }

        if (path == null || path.isEmpty() || !new File(path).canRead()) {
            Toast.makeText(this, "本地文件不存在或不可读，可能已被删除", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.setFitsSystemWindows(true);

        view = new PlayerView(this);
        view.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        view.setUseController(true);
        root.addView(view, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        caption = new TextView(this);
        caption.setTextColor(Color.WHITE);
        caption.setTextSize(14f);
        caption.setPadding(40, 32, 40, 0);
        caption.setText(label(path));
        root.addView(caption, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT));

        if (!merged && episodes.length() > 1) {
            navRow = new LinearLayout(this);
            navRow.setOrientation(LinearLayout.HORIZONTAL);
            navRow.setGravity(Gravity.CENTER);
            navRow.setPadding(0, 0, 0, 48);
            Button prev = new Button(this);
            prev.setText("上一集");
            prev.setOnClickListener(v -> advance(-1));
            Button next = new Button(this);
            next.setText("下一集");
            next.setOnClickListener(v -> advance(1));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(24, 0, 24, 0);
            navRow.addView(prev, lp);
            navRow.addView(next, lp);
            FrameLayout.LayoutParams navLp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
            navLp.gravity = Gravity.BOTTOM;
            root.addView(navRow, navLp);
        }

        player = new ExoPlayer.Builder(this).build();
        view.setPlayer(player);
        play(path);
        setContentView(root);
    }

    private String label(String path) {
        String file = new File(path).getName();
        String base = title == null || title.isEmpty() ? file : title;
        if (merged) return base + " · 整集合并版";
        return base + " · " + file.replace(".mp4", "") + "（共 " + episodes.length() + " 集）";
    }

    private void play(String path) {
        if (player == null) return;
        MediaItem item = new MediaItem.Builder()
                .setUri(Uri.fromFile(new File(path)))
                .setMimeType(MimeTypes.VIDEO_MP4)
                .build();
        player.setMediaItem(item);
        player.prepare();
        player.setPlayWhenReady(true);
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_ENDED) advance(1);
            }
        });
    }

    /** 分集播放：播完自动连播，底部按钮手动切集。合并版没有下一集。 */
    private void advance(int delta) {
        if (merged || episodes.length() == 0) return;
        int next = index + delta;
        if (next < 0) next = 0;
        if (next >= episodes.length()) {
            Toast.makeText(this, "已播完最后一集", Toast.LENGTH_SHORT).show();
            return;
        }
        JSONObject ep = episodes.optJSONObject(next);
        String path = ep == null ? "" : ep.optString("path", "");
        if (path.isEmpty() || !new File(path).canRead()) {
            Toast.makeText(this, "第 " + (next + 1) + " 集文件不可读", Toast.LENGTH_LONG).show();
            return;
        }
        index = next;
        play(path);
        if (caption != null) caption.setText(label(path));
    }

    @Override protected void onResume() { super.onResume(); if (player != null) player.setPlayWhenReady(true); hideSystemBars(); }
    @Override protected void onPause() { super.onPause(); if (player != null) player.setPlayWhenReady(false); }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (view != null) view.setPlayer(null);
        if (player != null) player.release();
        player = null;
    }

    @SuppressWarnings("deprecation")
    private void hideSystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }
}
