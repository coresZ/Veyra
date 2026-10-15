package com.veyra.gybeyond;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.os.Build;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.webkit.WebView;
import android.widget.FrameLayout;
import androidx.core.graphics.PathParser;

/**
 * 原生液态玻璃底栏（布局块版）—— 复刻 Android Liquid Glass（kyant backdrop，Apache-2.0）。
 * 结构上保证可见：底栏作为 LinearLayout 布局中的固定高度块（WebView 在其上方），
 * 天然存在于布局流中，不再依赖"全屏覆盖层"。
 * 渲染：父 View 自绘全部 UI（容器色/高光/tab/金色胶囊，无 shader，必然显示）；
 *       RefractionLayer 子 View 绘制 WebView 底部对应区域的截屏 + AGSL 圆角矩形折射
 *       shader（内置 9-tap 采样模糊），失败 try-catch 降级，不影响 UI。
 * 交互：按压 pressProgress 驱动折射/高光/投影/放大，弹性吸附 + 水平拖动胶囊 + 容器反向偏移。
 */
public class GlassBottomBar extends FrameLayout {
    private static final String TAG = "GlassBottomBar";

    // ---- AGSL shader（来自 Android Liquid Glass / kyant，Apache-2.0，内置 9-tap 采样模糊）----
    private static final String SDF = """
float radiusAt(float2 coord, float4 radii) {
    if (coord.x >= 0.0) { if (coord.y <= 0.0) return radii.y; else return radii.z; }
    else { if (coord.y <= 0.0) return radii.x; else return radii.w; }
}
float sdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    float outside = length(max(cornerCoord, 0.0)) - radius;
    float inside = min(max(cornerCoord.x, cornerCoord.y), 0.0);
    return outside + inside;
}
float2 gradSdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    if (cornerCoord.x >= 0.0 || cornerCoord.y >= 0.0) return sign(coord) * normalize(max(cornerCoord, 0.0));
    else { float gradX = step(cornerCoord.y, cornerCoord.x); return sign(coord) * float2(gradX, 1.0 - gradX); }
}
float circleMap(float x) { return 1.0 - sqrt(1.0 - x * x); }""";

    /** 容器：内置模糊 + 圆角矩形折射 */
    private static final String CONTAINER_SHADER = """
uniform shader content;
uniform float2 size;
uniform float2 offset;
uniform float4 cornerRadii;
uniform float refractionHeight;
uniform float refractionAmount;
uniform float depthEffect;
uniform float blurRadius;
""" + SDF + """
half4 blurSample(float2 coord) {
    float2 r = float2(blurRadius, blurRadius);
    half4 c = half4(0.0);
    c += content.eval(coord + float2(-r.x, -r.y));
    c += content.eval(coord + float2(0.0, -r.y));
    c += content.eval(coord + float2(r.x, -r.y));
    c += content.eval(coord + float2(-r.x, 0.0));
    c += content.eval(coord);
    c += content.eval(coord + float2(r.x, 0.0));
    c += content.eval(coord + float2(-r.x, r.y));
    c += content.eval(coord + float2(0.0, r.y));
    c += content.eval(coord + float2(r.x, r.y));
    return c / 9.0;
}
half4 main(float2 coord) {
    float2 halfSize = size * 0.5;
    float2 centeredCoord = (coord + offset) - halfSize;
    float radius = radiusAt(coord, cornerRadii);
    float sd = sdRoundedRect(centeredCoord, halfSize, radius);
    if (-sd >= refractionHeight) return blurSample(coord);
    sd = min(sd, 0.0);
    float d = circleMap(1.0 - -sd / refractionHeight) * refractionAmount;
    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
    float2 grad = normalize(gradSdRoundedRect(centeredCoord, halfSize, gradRadius) + depthEffect * normalize(centeredCoord));
    float2 refractedCoord = coord + d * grad;
    return blurSample(refractedCoord);
}""";

    private static class TabItem {
        final String id;
        final String label;
        final Path icon;
        final boolean stroke;
        TabItem(String id, String label, String pathData, boolean stroke) {
            this.id = id;
            this.label = label;
            this.icon = PathParser.createPathFromPathData(pathData);
            this.stroke = stroke;
        }
    }

    private final TabItem[] tabs = {
            new TabItem("home", "首页",
                    "m3 10 9-7 9 7v10a1 1 0 0 1-1 1h-5v-7H9v7H4a1 1 0 0 1-1-1z", false),
            new TabItem("adult", "剧场",
                    "M3,5h18a2,2 0 0 1 2,2v13a2,2 0 0 1 -2,2h-18a2,2 0 0 1 -2,-2v-13a2,2 0 0 1 2,-2zM3,10h18M8,5l3,5M11,10l3,-5M14,10l3,5", true),
            new TabItem("ai", "AI",
                    "m12 2 1.8 6.2L20 10l-6.2 1.8L12 18l-1.8-6.2L4 10l6.2-1.8zM19 17l.6 1.4L21 19l-1.4.6L19 21l-.6-1.4L17 19l1.4-.6z", false),
            new TabItem("library", "我的",
                    "M12 8a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7zM4.5 21c.5-4.3 3-6.5 7.5-6.5s7 2.2 7.5 6.5", false)
    };

    private static final float CONTAINER_HEIGHT_DP = 68f;
    private static final float CONTAINER_RADIUS_DP = 24f;
    private static final float CONTAINER_MARGIN_X_DP = 14f;
    private static final float CONTAINER_MARGIN_Y_DP = 8f;
    private static final float CAPSULE_HEIGHT_DP = 54f;
    private static final float CAPSULE_RADIUS_DP = 22f;
    private static final float CONTAINER_PADDING_DP = 4f;
    private static final float ICON_SIZE_DP = 22f;
    private static final float CONTAINER_REFRACTION_DP = 24f;
    private static final float CAPSULE_REFRACTION_DP = 14f;
    private static final float PANEL_OFFSET_DP = 4f;
    private static final float CAP_PAD_DP = 14f;
    private static final float CONTAINER_BLUR_PX = 5f;

    private final float density;
    private WebView webView;
    private boolean darkTheme;
    private boolean detailMode;

    // ---- 几何（px，glassBar 局部坐标）----
    private RectF containerRect = new RectF();
    private RectF capsuleRect = new RectF();
    private float capsuleCenterX;
    private float tabWidth;
    private float pad;
    private Rect snapshotSrcRect = new Rect();        // webView 局部坐标（底部对应区域）

    // ---- 渲染状态 ----
    private Bitmap snapshot;
    private float pressProgress;
    private float capsuleScale = 1f;
    private float panelOffset;
    private int selectedIndex = 0;
    private boolean dragging;
    private float downX, downStart;
    private ValueAnimator pressAnimator;
    private boolean snapshotPending;
    private long lastSnapshotMs;

    private RefractionLayer refractionLayer;

    // ---- Paint ----
    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint capsuleFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint highlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint innerShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint containerFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint containerEdgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Path shadowPath = new Path();
    private float[] cornerRadii = new float[4];

    public GlassBottomBar(Context context) {
        this(context, null);
    }

    public GlassBottomBar(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = context.getResources().getDisplayMetrics().density;
        setWillNotDraw(false);
        setVisibility(View.VISIBLE);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        borderPaint.setStyle(Paint.Style.STROKE);

        refractionLayer = new RefractionLayer(context);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        addView(refractionLayer, lp);
    }

    public void attachWebView(WebView view) {
        this.webView = view;
    }

    public void setDarkTheme(boolean dark) {
        if (darkTheme == dark) return;
        darkTheme = dark;
        invalidateAll();
    }

    public void setDetailMode(boolean enabled) {
        if (detailMode == enabled) return;
        detailMode = enabled;
        setVisibility(enabled ? View.INVISIBLE : View.VISIBLE);
    }

    public void setSelectedTab(String tabId, boolean animate) {
        for (int i = 0; i < tabs.length; i++) {
            if (tabs[i].id.equals(tabId)) {
                setSelectedIndex(i, animate);
                return;
            }
        }
    }

    public void requestSnapshot() {
        if (webView == null || getWidth() == 0 || snapshotSrcRect.width() <= 0) return;
        long now = System.currentTimeMillis();
        if (now - lastSnapshotMs < 90) {
            snapshotPending = true;
            return;
        }
        lastSnapshotMs = now;
        snapshotPending = false;
        takeSnapshot();
    }

    /** WebView 软件绘制截屏（区域=webView 底部与底栏对应的区域，外扩 pad）。 */
    private void takeSnapshot() {
        int w = snapshotSrcRect.width();
        int h = snapshotSrcRect.height();
        if (w <= 0 || h <= 0) return;
        try {
            Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            canvas.translate(-snapshotSrcRect.left, -snapshotSrcRect.top);
            webView.draw(canvas);
            snapshot = bitmap;
            refractionLayer.invalidate();
            if (snapshotPending) {
                snapshotPending = false;
                lastSnapshotMs = 0;
                requestSnapshot();
            }
        } catch (Throwable t) {
            Log.w(TAG, "capture failed: " + t);
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float mx = dp(CONTAINER_MARGIN_X_DP);
        float my = dp(CONTAINER_MARGIN_Y_DP);
        float ch = dp(CONTAINER_HEIGHT_DP);
        containerRect.set(mx, h - my - ch, w - mx, h - my);
        float padding = dp(CONTAINER_PADDING_DP);
        tabWidth = (containerRect.width() - padding * 2f) / tabs.length;
        float capsuleH = dp(CAPSULE_HEIGHT_DP);
        capsuleRect.set(containerRect.left + padding,
                containerRect.centerY() - capsuleH / 2f,
                containerRect.left + padding + tabWidth,
                containerRect.centerY() + capsuleH / 2f);
        capsuleCenterX = capsuleCenterFor(selectedIndex);
        float radius = dp(CONTAINER_RADIUS_DP);
        cornerRadii[0] = radius; cornerRadii[1] = radius; cornerRadii[2] = radius; cornerRadii[3] = radius;
        pad = dp(CONTAINER_REFRACTION_DP) + dp(8);
        // 截屏区域 = webView 底部与底栏视觉区域对应的部分（webView 局部坐标）
        int wvW = webView != null ? webView.getWidth() : 0;
        int wvH = webView != null ? webView.getHeight() : 0;
        if (wvW > 0 && wvH > 0) {
            float barH = h - my; // 底栏视觉区域高度（容器底到顶）
            snapshotSrcRect.set(0, (int) (wvH - barH - pad), wvW, wvH);
        }
        invalidateAll();
        requestSnapshot();
    }

    private float capsuleCenterFor(int index) {
        return capsuleRect.left + tabWidth * index + tabWidth / 2f;
    }

    public void setSelectedIndex(int index, boolean animate) {
        index = Math.max(0, Math.min(tabs.length - 1, index));
        selectedIndex = index;
        float target = capsuleCenterFor(index);
        animatePress();
        if (!animate) {
            capsuleCenterX = target;
            panelOffset = 0f;
            capsuleScale = 1f;
            pressProgress = 0f;
            invalidateAll();
            return;
        }
        float start = capsuleCenterX;
        ValueAnimator move = ValueAnimator.ofFloat(start, target);
        move.setDuration(380);
        move.setInterpolator(new OvershootInterpolator(1.8f));
        move.addUpdateListener(a -> {
            capsuleCenterX = (float) a.getAnimatedValue();
            updatePanelOffset();
            invalidateAll();
        });
        move.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                panelOffset = 0f;
                invalidateAll();
            }
        });
        move.start();
    }

    private void invalidateAll() {
        refractionLayer.invalidate();
        invalidate();
    }

    private void animatePress() {
        if (pressAnimator != null) pressAnimator.cancel();
        ValueAnimator press = ValueAnimator.ofFloat(0f, 1f);
        press.setDuration(130);
        press.addUpdateListener(a -> {
            pressProgress = (float) a.getAnimatedValue();
            capsuleScale = 1f + 0.14f * pressProgress;
            invalidateAll();
        });
        pressAnimator = press;
        press.start();
        ValueAnimator release = ValueAnimator.ofFloat(1f, 0f);
        release.setDuration(420);
        release.setInterpolator(new DecelerateInterpolator(1.6f));
        release.addUpdateListener(a -> {
            float v = (float) a.getAnimatedValue();
            pressProgress = Math.max(0f, v);
            capsuleScale = 1f + 0.14f * pressProgress;
            invalidateAll();
        });
        release.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                pressAnimator = null;
            }
        });
        press.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                release.start();
            }
        });
    }

    private void updatePanelOffset() {
        float fraction = ((capsuleCenterX - containerRect.left) / containerRect.width()) * 2f - 1f;
        fraction = Math.max(-1f, Math.min(1f, fraction));
        panelOffset = dp(PANEL_OFFSET_DP) * (fraction < 0 ? -1f : 1f) * (1f - Math.abs(fraction));
    }

    // ---- 触摸/拖动 ----
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (detailMode || getVisibility() != View.VISIBLE) return false;
        int action = event.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                dragging = true;
                downX = event.getX();
                downStart = capsuleCenterX;
                pressProgress = 1f;
                capsuleScale = 1.14f;
                if (pressAnimator != null) pressAnimator.cancel();
                invalidateAll();
                return true;
            case MotionEvent.ACTION_MOVE: {
                float raw = downStart + (event.getX() - downX);
                float leftBound = capsuleRect.left + tabWidth / 2f;
                float rightBound = capsuleRect.right - tabWidth / 2f;
                capsuleCenterX = Math.max(leftBound, Math.min(rightBound, raw));
                updatePanelOffset();
                invalidateAll();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                dragging = false;
                int index = Math.round((capsuleCenterX - (capsuleRect.left + tabWidth / 2f)) / tabWidth);
                index = Math.max(0, Math.min(tabs.length - 1, index));
                setSelectedIndex(index, true);
                if (webView != null) {
                    webView.evaluateJavascript("window.hgNav && window.hgNav('" + tabs[index].id + "')", null);
                }
                return true;
            }
        }
        return super.onTouchEvent(event);
    }

    // ================= 折射层（下层：snapshot + AGSL 容器折射，失败降级） =================
    private class RefractionLayer extends View {
        private RuntimeShader shader;

        RefractionLayer(Context c) {
            super(c);
            setWillNotDraw(false);
            if (Build.VERSION.SDK_INT >= 33) {
                try { shader = new RuntimeShader(CONTAINER_SHADER); } catch (Throwable t) { shader = null; Log.w(TAG, "shader init fail", t); }
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (snapshot == null) return;
            canvas.save();
            canvas.translate(panelOffset, 0f);
            if (Build.VERSION.SDK_INT >= 33 && shader != null) {
                try {
                    shader.setFloatUniform("size", containerRect.width(), containerRect.height());
                    shader.setFloatUniform("offset", 0f, 0f);
                    shader.setFloatUniform("cornerRadii", cornerRadii);
                    shader.setFloatUniform("refractionHeight", dp(CONTAINER_REFRACTION_DP) * (0.5f + 0.5f * pressProgress));
                    shader.setFloatUniform("refractionAmount", dp(CONTAINER_REFRACTION_DP) * (0.4f + 0.6f * pressProgress));
                    shader.setFloatUniform("depthEffect", 1f);
                    shader.setFloatUniform("blurRadius", CONTAINER_BLUR_PX * density);
                    setRenderEffect(android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "content"));
                } catch (Throwable t) {
                    Log.w(TAG, "shader apply fail", t);
                    setRenderEffect(null);
                }
            } else {
                setRenderEffect(null);
            }
            canvas.drawBitmap(snapshot, null,
                    new RectF(containerRect.left - pad, containerRect.top - pad,
                            containerRect.right + pad, containerRect.bottom + pad),
                    new Paint(Paint.FILTER_BITMAP_FLAG));
            canvas.restore();
        }
    }

    // ================= 父 View 自绘 UI（容器色 + tab + 金色胶囊，无 shader，必然可见） =================
    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (containerRect.width() <= 0) return;
        float radius = dp(CONTAINER_RADIUS_DP);
        canvas.save();
        canvas.translate(panelOffset, 0f);

        // 容器半透明填充（磨砂基色）
        containerFillPaint.setShader(new LinearGradient(
                containerRect.left, containerRect.top, containerRect.left, containerRect.bottom,
                darkTheme ? Color.argb(160, 44, 52, 57) : Color.argb(130, 255, 255, 255),
                darkTheme ? Color.argb(120, 26, 31, 34) : Color.argb(80, 255, 252, 244),
                Shader.TileMode.CLAMP));
        canvas.drawRoundRect(containerRect, radius, radius, containerFillPaint);

        // 顶部亮边
        containerEdgePaint.setShader(new LinearGradient(0, containerRect.top, 0, containerRect.top + dp(14),
                darkTheme ? Color.argb(55, 255, 255, 255) : Color.argb(95, 255, 255, 255),
                Color.TRANSPARENT, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(new RectF(containerRect.left, containerRect.top, containerRect.right, containerRect.top + dp(14)),
                radius, radius, containerEdgePaint);
        borderPaint.setStrokeWidth(dp(1));
        borderPaint.setColor(darkTheme ? Color.argb(55, 255, 255, 255) : Color.argb(110, 255, 255, 255));
        canvas.drawRoundRect(containerRect, radius, radius, borderPaint);

        // 非选中 tab
        for (int i = 0; i < tabs.length; i++) {
            if (i == selectedIndex) continue;
            drawTabIcon(canvas, tabs[i], capsuleRect.left + tabWidth * i + tabWidth / 2f,
                    containerRect.centerY(), false);
        }

        // 选中胶囊（金色）
        drawCapsule(canvas, capsuleCenterX, containerRect.centerY());
        canvas.restore();
    }

    private void drawTabIcon(Canvas canvas, TabItem tab, float cx, float cy, boolean white) {
        iconPaint.setColor(white ? Color.WHITE : (darkTheme ? Color.argb(195, 196, 190, 177) : Color.argb(205, 105, 102, 94)));
        iconPaint.setStyle(tab.stroke ? Paint.Style.STROKE : Paint.Style.FILL);
        iconPaint.setStrokeWidth(dp(1.9f));
        canvas.save();
        canvas.translate(cx, cy - dp(8));
        float s = dp(ICON_SIZE_DP) / 24f;
        canvas.scale(s, s);
        canvas.drawPath(tab.icon, iconPaint);
        canvas.restore();
        textPaint.setColor(white ? Color.WHITE : (darkTheme ? Color.argb(225, 196, 190, 177) : Color.argb(225, 105, 102, 94)));
        textPaint.setTextSize(dp(10f));
        textPaint.setFakeBoldText(false);
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        canvas.drawText(tab.label, cx, cy + dp(23) - (fm.ascent + fm.descent) / 2f, textPaint);
    }

    private void drawCapsule(Canvas canvas, float centerX, float centerY) {
        float w = tabWidth * capsuleScale;
        float h = dp(CAPSULE_HEIGHT_DP) * capsuleScale;
        float left = centerX - w / 2f;
        float top = centerY - h / 2f;
        float radius = dp(CAPSULE_RADIUS_DP);

        // 投影
        if (pressProgress > 0.01f) {
            shadowPath.reset();
            shadowPath.addRoundRect(new RectF(left + dp(2), top + dp(5), left + w + dp(2), top + h + dp(5)),
                    radius, radius, Path.Direction.CW);
            shadowPaint.setColor(Color.argb((int) (90 * pressProgress), 0, 0, 0));
            shadowPaint.setShadowLayer(dp(10) * pressProgress, 0f, 0f, Color.argb((int) (90 * pressProgress), 0, 0, 0));
            canvas.drawPath(shadowPath, shadowPaint);
            shadowPaint.clearShadowLayer();
        }

        // 金色胶囊
        capsuleFillPaint.setShader(new LinearGradient(0, top, 0, top + h,
                darkTheme ? Color.rgb(255, 212, 91) : Color.rgb(240, 189, 76),
                darkTheme ? Color.rgb(230, 182, 60) : Color.rgb(214, 156, 4),
                Shader.TileMode.CLAMP));
        capsuleFillPaint.setAlpha((int) (255 * (0.6f + 0.4f * pressProgress)));
        canvas.drawRoundRect(new RectF(left, top, left + w, top + h), radius, radius, capsuleFillPaint);

        dimPaint.setColor(darkTheme ? Color.WHITE : Color.BLACK);
        dimPaint.setAlpha((int) (25 * (1f - pressProgress) + 8 * pressProgress));
        canvas.drawRoundRect(new RectF(left, top, left + w, top + h), radius, radius, dimPaint);

        highlightPaint.setShader(new LinearGradient(0, top, 0, top + h * 0.55f,
                Color.argb((int) (115 * pressProgress + 30), 255, 255, 255),
                Color.TRANSPARENT, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(new RectF(left, top, left + w, top + h), radius, radius, highlightPaint);

        innerShadowPaint.setShader(new LinearGradient(0, top + h * 0.7f, 0, top + h,
                Color.TRANSPARENT, Color.argb((int) (75 * pressProgress + 25), 0, 0, 0),
                Shader.TileMode.CLAMP));
        canvas.drawRoundRect(new RectF(left, top, left + w, top + h), radius, radius, innerShadowPaint);

        // 选中 tab 图标/文字（白色）
        TabItem tab = tabs[selectedIndex];
        iconPaint.setColor(Color.WHITE);
        iconPaint.setStyle(tab.stroke ? Paint.Style.STROKE : Paint.Style.FILL);
        iconPaint.setStrokeWidth(dp(1.9f));
        canvas.save();
        canvas.translate(centerX, centerY - dp(8));
        float s = dp(ICON_SIZE_DP) / 24f;
        canvas.scale(s, s);
        canvas.drawPath(tab.icon, iconPaint);
        canvas.restore();
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(dp(10f));
        textPaint.setFakeBoldText(true);
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        canvas.drawText(tab.label, centerX, centerY + dp(23) - (fm.ascent + fm.descent) / 2f, textPaint);
        textPaint.setFakeBoldText(false);
    }

    private float dp(float v) {
        return v * density;
    }
}
