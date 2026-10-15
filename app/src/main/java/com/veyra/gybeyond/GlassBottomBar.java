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
import android.os.Handler;
import android.os.Looper;
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
 * 原生液态玻璃底栏 —— 复刻 Android Liquid Glass（backdrop）：
 * WebView 背景软件绘制截屏 → AGSL RuntimeShader 圆角矩形折射（内置采样模糊+色散），
 * 三层分离渲染（容器玻璃层 / 胶囊折射层 / 胶囊 UI 层），按压/拖动 pressProgress
 * 驱动折射强度/高光/阴影/放大，弹性吸附动画+水平拖动。
 * 仅使用与官方签名一致的 RenderEffect.createRuntimeShaderEffect(shader, "content")（API 33+）。
 */
public class GlassBottomBar extends FrameLayout {
    private static final String TAG = "GlassBottomBar";

    // ---- AGSL shader（来自 Android Liquid Glass / kyant backdrop，Apache-2.0，内置 9-tap 采样模糊）----
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

    /** 胶囊：圆角矩形折射 + 色散（无模糊） */
    private static final String CAPSULE_SHADER = """
uniform shader content;
uniform float2 size;
uniform float2 offset;
uniform float4 cornerRadii;
uniform float refractionHeight;
uniform float refractionAmount;
uniform float depthEffect;
uniform float chromaticAberration;
""" + SDF + """
half4 main(float2 coord) {
    float2 halfSize = size * 0.5;
    float2 centeredCoord = (coord + offset) - halfSize;
    float radius = radiusAt(coord, cornerRadii);
    float sd = sdRoundedRect(centeredCoord, halfSize, radius);
    if (-sd >= refractionHeight) return content.eval(coord);
    sd = min(sd, 0.0);
    float d = circleMap(1.0 - -sd / refractionHeight) * refractionAmount;
    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
    float2 grad = normalize(gradSdRoundedRect(centeredCoord, halfSize, gradRadius) + depthEffect * normalize(centeredCoord));
    float2 refractedCoord = coord + d * grad;
    float dispersionIntensity = chromaticAberration * ((centeredCoord.x * centeredCoord.y) / (halfSize.x * halfSize.y));
    float2 dispersedCoord = d * grad * dispersionIntensity;
    half4 color = half4(0.0);
    half4 red = content.eval(refractedCoord + dispersedCoord);
    color.r += red.r / 3.5; color.a += red.a / 7.0;
    half4 orange = content.eval(refractedCoord + dispersedCoord * (2.0 / 3.0));
    color.r += orange.r / 3.5; color.g += orange.g / 7.0; color.a += orange.a / 7.0;
    half4 yellow = content.eval(refractedCoord + dispersedCoord * (1.0 / 3.0));
    color.r += yellow.r / 3.5; color.g += yellow.g / 3.5; color.a += yellow.a / 7.0;
    half4 green = content.eval(refractedCoord);
    color.g += green.g / 3.5; color.a += green.a / 7.0;
    half4 cyan = content.eval(refractedCoord - dispersedCoord * (1.0 / 3.0));
    color.g += cyan.g / 3.5; color.b += cyan.b / 3.0; color.a += cyan.a / 7.0;
    half4 blue = content.eval(refractedCoord - dispersedCoord * (2.0 / 3.0));
    color.b += blue.b / 3.0; color.a += blue.a / 7.0;
    half4 purple = content.eval(refractedCoord - dispersedCoord);
    color.r += purple.r / 7.0; color.b += purple.b / 3.0; color.a += purple.a / 7.0;
    return color;
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
    private static final float CONTAINER_MARGIN_BOTTOM_DP = 12f;
    private static final float CAPSULE_HEIGHT_DP = 54f;
    private static final float CAPSULE_RADIUS_DP = 22f;
    private static final float CONTAINER_PADDING_DP = 4f;
    private static final float ICON_SIZE_DP = 22f;
    private static final float CONTAINER_REFRACTION_DP = 24f;
    private static final float CAPSULE_REFRACTION_DP = 14f;
    private static final float PANEL_OFFSET_DP = 4f;
    private static final float CAP_PAD_DP = 16f;          // 胶囊截屏外扩
    private static final float CONTAINER_BLUR_PX = 5f;    // 容器内置模糊半径(px)

    private final float density;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private WebView webView;
    private boolean darkTheme;
    private boolean detailMode;

    // ---- 几何（px）----
    private RectF containerRect = new RectF();
    private RectF capsuleRect = new RectF();
    private float capsuleCenterX;
    private float tabWidth;
    private float pad;
    private Rect snapshotSrcRect = new Rect();        // webView 局部坐标
    private RectF snapshotDstRect = new RectF();      // 父 View 坐标

    // ---- 渲染状态 ----
    private Bitmap snapshot;
    private float pressProgress;
    private float capsuleScale = 1f;
    private float panelOffset;
    private int selectedIndex = 0;
    private boolean animating;
    private boolean snapshotPending;
    private long lastSnapshotMs;
    private ValueAnimator pressAnimator;

    // ---- 四层子 View ----
    private ContainerGlassLayer containerLayer;
    private TabBarLayer tabBarLayer;
    private CapsuleGlassLayer capsuleBackdropLayer;
    private CapsuleUiLayer capsuleUiLayer;

    // ---- Paint（父层，供 UI 层复用绘制参数）----
    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint capsuleFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint highlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint innerShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Path shadowPath = new Path();

    private float[] cornerRadii = new float[4];
    private float[] capsuleRadii = new float[4];

    public GlassBottomBar(Context context) {
        this(context, null);
    }

    public GlassBottomBar(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = context.getResources().getDisplayMetrics().density;
        setWillNotDraw(true); // 父层只做布局/动画/触摸，绘制全在子层
        setVisibility(View.VISIBLE);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        borderPaint.setStyle(Paint.Style.STROKE);

        containerLayer = new ContainerGlassLayer(context);
        tabBarLayer = new TabBarLayer(context);
        capsuleBackdropLayer = new CapsuleGlassLayer(context);
        capsuleUiLayer = new CapsuleUiLayer(context);
        addViewLayer(containerLayer, 0);
        addViewLayer(tabBarLayer, 1);
        addViewLayer(capsuleBackdropLayer, 2);
        addViewLayer(capsuleUiLayer, 3);
    }

    private void addViewLayer(View v, int order) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        v.setLayoutParams(lp);
        addView(v);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    public void attachWebView(WebView view) {
        this.webView = view;
    }

    public void setDarkTheme(boolean dark) {
        if (darkTheme == dark) return;
        darkTheme = dark;
        capsuleUiLayer.invalidate();
        containerLayer.invalidate();
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

    /** WebView 软件绘制截屏（区域=容器外扩 pad）。硬件加速下 WebView.draw 走软件渲染路径。 */
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
            invalidate();
            containerLayer.invalidate();
            capsuleBackdropLayer.invalidate();
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
        float mb = dp(CONTAINER_MARGIN_BOTTOM_DP);
        float ch = dp(CONTAINER_HEIGHT_DP);
        containerRect.set(mx, h - mb - ch, w - mx, h - mb);
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
        float cRadius = dp(CAPSULE_RADIUS_DP);
        capsuleRadii[0] = cRadius; capsuleRadii[1] = cRadius; capsuleRadii[2] = cRadius; capsuleRadii[3] = cRadius;
        pad = dp(CONTAINER_REFRACTION_DP) + dp(8);
        int[] loc = new int[2];
        webView.getLocationOnScreen(loc);
        int[] myLoc = new int[2];
        getLocationOnScreen(myLoc);
        float dx = myLoc[0] - loc[0];
        float dy = myLoc[1] - loc[1];
        snapshotSrcRect.set((int) (containerRect.left + dx - pad), (int) (containerRect.top + dy - pad),
                (int) (containerRect.right + dx + pad), (int) (containerRect.bottom + dy + pad));
        snapshotDstRect.set(containerRect.left - pad, containerRect.top - pad,
                containerRect.right + pad, containerRect.bottom + pad);
        // 胶囊层尺寸：胶囊 + 外扩 CAP_PAD
        float capW = tabWidth + dp(CAP_PAD_DP) * 2f;
        float capH = capsuleH + dp(CAP_PAD_DP) * 2f;
        FrameLayout.LayoutParams capLp = new FrameLayout.LayoutParams(
                (int) capW, (int) capH);
        capsuleBackdropLayer.setLayoutParams(capLp);
        capsuleUiLayer.setLayoutParams(capLp);
        syncCapsuleLayers(false);
        requestSnapshot();
    }

    private float capsuleCenterFor(int index) {
        return capsuleRect.left + tabWidth * index + tabWidth / 2f;
    }

    /** 胶囊两层跟随 capsuleCenterX（左/上=胶囊左上角-pad） */
    private void syncCapsuleLayers(boolean animated) {
        float left = capsuleCenterX - (tabWidth + dp(CAP_PAD_DP) * 2f) / 2f;
        float top = capsuleRect.top - dp(CAP_PAD_DP);
        capsuleBackdropLayer.setTranslationX(left - capsuleBackdropLayer.getLeft());
        capsuleBackdropLayer.setTranslationY(top - capsuleBackdropLayer.getTop());
        capsuleUiLayer.setTranslationX(left - capsuleUiLayer.getLeft());
        capsuleUiLayer.setTranslationY(top - capsuleUiLayer.getTop());
        capsuleBackdropLayer.setCapsuleScale(capsuleScale);
        capsuleUiLayer.setCapsuleScale(capsuleScale);
        if (animated) {
            capsuleBackdropLayer.invalidate();
            capsuleUiLayer.invalidate();
        }
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
            syncCapsuleLayers(false);
            containerLayer.invalidate();
            return;
        }
        float start = capsuleCenterX;
        animating = true;
        ValueAnimator move = ValueAnimator.ofFloat(start, target);
        move.setDuration(380);
        move.setInterpolator(new OvershootInterpolator(1.8f));
        move.addUpdateListener(a -> {
            capsuleCenterX = (float) a.getAnimatedValue();
            updatePanelOffset();
            syncCapsuleLayers(true);
        });
        move.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                animating = false;
                panelOffset = 0f;
                containerLayer.invalidate();
            }
        });
        move.start();
    }

    private void animatePress() {
        if (pressAnimator != null) pressAnimator.cancel();
        ValueAnimator press = ValueAnimator.ofFloat(0f, 1f);
        press.setDuration(130);
        press.addUpdateListener(a -> {
            pressProgress = (float) a.getAnimatedValue();
            capsuleScale = 1f + 0.14f * pressProgress;
            syncCapsuleLayers(true);
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
            syncCapsuleLayers(true);
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
        containerLayer.setPanelOffset(panelOffset);
        tabBarLayer.setPanelOffset(panelOffset);
    }

    // ---- 拖动 ----
    private boolean dragging;
    private float downX, downStart;

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (detailMode || getVisibility() != View.VISIBLE) return false;
        int action = event.getActionMasked();
        if (action != MotionEvent.ACTION_MOVE && !dragging
                && !containerRect.contains(event.getX(), event.getY())) {
            return false; // 容器外触摸穿透给 WebView
        }
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                dragging = true;
                downX = event.getX();
                downStart = capsuleCenterX;
                pressProgress = 1f;
                capsuleScale = 1.14f;
                if (pressAnimator != null) pressAnimator.cancel();
                syncCapsuleLayers(true);
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_MOVE: {
                float raw = downStart + (event.getX() - downX);
                float leftBound = capsuleRect.left + tabWidth / 2f;
                float rightBound = capsuleRect.right - tabWidth / 2f;
                capsuleCenterX = Math.max(leftBound, Math.min(rightBound, raw));
                updatePanelOffset();
                syncCapsuleLayers(true);
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

    // ================= 非选中 tab 层（无 shader，画图标/文字） =================
    private class TabBarLayer extends View {
        private float offset;

        TabBarLayer(Context c) {
            super(c);
            setWillNotDraw(false);
        }

        void setPanelOffset(float v) { offset = v; invalidate(); }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.save();
            canvas.translate(offset, 0f);
            for (int i = 0; i < tabs.length; i++) {
                if (i == selectedIndex) continue;
                float cx = capsuleRect.left + tabWidth * i + tabWidth / 2f;
                float cy = containerRect.centerY();
                TabItem tab = tabs[i];
                iconPaint.setColor(darkTheme ? Color.argb(190, 196, 190, 177) : Color.argb(200, 105, 102, 94));
                iconPaint.setStyle(tab.stroke ? Paint.Style.STROKE : Paint.Style.FILL);
                iconPaint.setStrokeWidth(dp(1.9f));
                canvas.save();
                canvas.translate(cx, cy - dp(8));
                float s = dp(ICON_SIZE_DP) / 24f;
                canvas.scale(s, s);
                canvas.drawPath(tab.icon, iconPaint);
                canvas.restore();
                textPaint.setColor(darkTheme ? Color.argb(220, 196, 190, 177) : Color.argb(220, 105, 102, 94));
                textPaint.setTextSize(dp(10f));
                textPaint.setFakeBoldText(false);
                Paint.FontMetrics fm = textPaint.getFontMetrics();
                float textY = cy + dp(23) - (fm.ascent + fm.descent) / 2f;
                canvas.drawText(tab.label, cx, textY, textPaint);
            }
            canvas.restore();
        }
    }

    // ================= 容器玻璃层（全屏，画 snapshot + 折射） =================
    private class ContainerGlassLayer extends View {
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float offset;
        private RuntimeShader shader;

        ContainerGlassLayer(Context c) {
            super(c);
            setWillNotDraw(false);
            if (Build.VERSION.SDK_INT >= 33) {
                try { shader = new RuntimeShader(CONTAINER_SHADER); } catch (Throwable t) { shader = null; }
            }
        }

        void setPanelOffset(float v) { offset = v; invalidate(); }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.save();
            canvas.translate(offset, 0f);
            float radius = dp(CONTAINER_RADIUS_DP);
            if (snapshot != null) {
                canvas.save();
                canvas.clipPath(containerPathFor(containerRect, radius));
                if (Build.VERSION.SDK_INT >= 33 && shader != null) {
                    shader.setFloatUniform("size", containerRect.width(), containerRect.height());
                    shader.setFloatUniform("offset", 0f, 0f);
                    shader.setFloatUniform("cornerRadii", cornerRadii);
                    shader.setFloatUniform("refractionHeight", dp(CONTAINER_REFRACTION_DP));
                    shader.setFloatUniform("refractionAmount", dp(CONTAINER_REFRACTION_DP));
                    shader.setFloatUniform("depthEffect", 1f);
                    shader.setFloatUniform("blurRadius", CONTAINER_BLUR_PX * density);
                    setRenderEffect(android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "content"));
                }
                RectF dst = new RectF(containerRect.left - pad, containerRect.top - pad,
                        containerRect.right + pad, containerRect.bottom + pad);
                canvas.drawBitmap(snapshot, null, dst, new Paint(Paint.FILTER_BITMAP_FLAG));
                canvas.restore();
            }
            fillPaint.setShader(new LinearGradient(
                    containerRect.left, containerRect.top, containerRect.left, containerRect.bottom,
                    darkTheme ? Color.argb(200, 44, 52, 57) : Color.argb(170, 255, 255, 255),
                    darkTheme ? Color.argb(150, 26, 31, 34) : Color.argb(110, 255, 252, 244),
                    Shader.TileMode.CLAMP));
            canvas.drawRoundRect(containerRect, radius, radius, fillPaint);
            edgePaint.setShader(new LinearGradient(0, containerRect.top, 0, containerRect.top + dp(14),
                    darkTheme ? Color.argb(55, 255, 255, 255) : Color.argb(100, 255, 255, 255),
                    Color.TRANSPARENT, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(new RectF(containerRect.left, containerRect.top, containerRect.right, containerRect.top + dp(14)),
                    radius, radius, edgePaint);
            borderPaint.setStrokeWidth(dp(1));
            borderPaint.setColor(darkTheme ? Color.argb(55, 255, 255, 255) : Color.argb(120, 255, 255, 255));
            canvas.drawRoundRect(containerRect, radius, radius, borderPaint);
            canvas.restore();
        }
    }

    private Path containerPathFor(RectF r, float radius) {
        Path p = new Path();
        p.addRoundRect(r, radius, radius, Path.Direction.CW);
        return p;
    }

    // ================= 胶囊折射层（画胶囊区域背景折射） =================
    private class CapsuleGlassLayer extends View {
        private float scale = 1f;
        private RuntimeShader shader;

        CapsuleGlassLayer(Context c) {
            super(c);
            setWillNotDraw(false);
            if (Build.VERSION.SDK_INT >= 33) {
                try { shader = new RuntimeShader(CAPSULE_SHADER); } catch (Throwable t) { shader = null; }
            }
        }

        void setCapsuleScale(float v) { scale = v; }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (snapshot == null || shader == null) return;
            float w = tabWidth * scale;
            float h = dp(CAPSULE_HEIGHT_DP) * scale;
            // 胶囊中心在容器中的位置（snapshot 坐标 = 容器坐标 + pad）
            float cx = capsuleCenterX - containerRect.left + pad;
            float cy = containerRect.centerY() - containerRect.top + pad;
            float capPad = dp(CAP_PAD_DP);
            // snapshot 子区域（胶囊外扩 capPad）
            float left = cx - w / 2f - capPad;
            float top = cy - h / 2f - capPad;
            Rect src = new Rect((int) left, (int) top, (int) (left + w + capPad * 2f), (int) (top + h + capPad * 2f));
            src.left = Math.max(0, src.left); src.top = Math.max(0, src.top);
            src.right = Math.min(snapshot.getWidth(), src.right);
            src.bottom = Math.min(snapshot.getHeight(), src.bottom);
            if (src.width() <= 0 || src.height() <= 0) return;
            shader.setFloatUniform("size", w, h);
            shader.setFloatUniform("offset", 0f, 0f);
            shader.setFloatUniform("cornerRadii", capsuleRadii);
            shader.setFloatUniform("refractionHeight", dp(10f) * pressProgress);
            shader.setFloatUniform("refractionAmount", dp(CAPSULE_REFRACTION_DP) * pressProgress);
            shader.setFloatUniform("depthEffect", 1f);
            shader.setFloatUniform("chromaticAberration", 1f);
            setRenderEffect(android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "content"));
            RectF dst = new RectF(0, 0, getWidth(), getHeight());
            canvas.drawBitmap(snapshot, src, dst, new Paint(Paint.FILTER_BITMAP_FLAG));
        }
    }

    // ================= 胶囊 UI 层（金色胶囊 + 高光/阴影 + 图标/文字，无 shader） =================
    private class CapsuleUiLayer extends View {
        private float scale = 1f;

        CapsuleUiLayer(Context c) {
            super(c);
            setWillNotDraw(false);
        }

        void setCapsuleScale(float v) { scale = v; }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = tabWidth * scale;
            float h = dp(CAPSULE_HEIGHT_DP) * scale;
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float left = cx - w / 2f;
            float top = cy - h / 2f;
            float radius = dp(CAPSULE_RADIUS_DP);
            Path cap = new Path();
            cap.addRoundRect(new RectF(left, top, left + w, top + h), radius, radius, Path.Direction.CW);

            if (pressProgress > 0.01f) {
                shadowPath.reset();
                shadowPath.addRoundRect(new RectF(left + dp(2), top + dp(5), left + w + dp(2), top + h + dp(5)),
                        radius, radius, Path.Direction.CW);
                shadowPaint.setColor(Color.argb((int) (90 * pressProgress), 0, 0, 0));
                shadowPaint.setShadowLayer(dp(10) * pressProgress, 0f, 0f, Color.argb((int) (90 * pressProgress), 0, 0, 0));
                canvas.drawPath(shadowPath, shadowPaint);
                shadowPaint.clearShadowLayer();
            }

            capsuleFillPaint.setShader(new LinearGradient(0, top, 0, top + h,
                    darkTheme ? Color.rgb(255, 212, 91) : Color.rgb(240, 189, 76),
                    darkTheme ? Color.rgb(230, 182, 60) : Color.rgb(214, 156, 4),
                    Shader.TileMode.CLAMP));
            capsuleFillPaint.setAlpha((int) (255 * (0.55f + 0.45f * pressProgress)));
            canvas.drawPath(cap, capsuleFillPaint);

            dimPaint.setColor(darkTheme ? Color.WHITE : Color.BLACK);
            dimPaint.setAlpha((int) (25 * (1f - pressProgress) + 8 * pressProgress));
            canvas.drawRoundRect(new RectF(left, top, left + w, top + h), radius, radius, dimPaint);

            highlightPaint.setShader(new LinearGradient(0, top, 0, top + h * 0.55f,
                    Color.argb((int) (110 * pressProgress + 25), 255, 255, 255),
                    Color.TRANSPARENT, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(new RectF(left, top, left + w, top + h), radius, radius, highlightPaint);

            innerShadowPaint.setShader(new LinearGradient(0, top + h * 0.7f, 0, top + h,
                    Color.TRANSPARENT, Color.argb((int) (70 * pressProgress + 20), 0, 0, 0),
                    Shader.TileMode.CLAMP));
            canvas.drawRoundRect(new RectF(left, top, left + w, top + h), radius, radius, innerShadowPaint);

            // 图标 + 文字
            TabItem tab = tabs[selectedIndex];
            iconPaint.setColor(Color.WHITE);
            iconPaint.setStyle(tab.stroke ? Paint.Style.STROKE : Paint.Style.FILL);
            iconPaint.setStrokeWidth(dp(1.9f));
            canvas.save();
            canvas.translate(cx, cy - dp(8));
            float s = dp(ICON_SIZE_DP) / 24f;
            canvas.scale(s, s);
            canvas.drawPath(tab.icon, iconPaint);
            canvas.restore();
            textPaint.setColor(Color.WHITE);
            textPaint.setTextSize(dp(10f));
            textPaint.setFakeBoldText(true);
            Paint.FontMetrics fm = textPaint.getFontMetrics();
            float textY = cy + dp(23) - (fm.ascent + fm.descent) / 2f;
            canvas.drawText(tab.label, cx, textY, textPaint);
            textPaint.setFakeBoldText(false);
        }
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        capsuleBackdropLayer.layout(0, 0, capsuleBackdropLayer.getMeasuredWidth(), capsuleBackdropLayer.getMeasuredHeight());
        capsuleUiLayer.layout(0, 0, capsuleUiLayer.getMeasuredWidth(), capsuleUiLayer.getMeasuredHeight());
        syncCapsuleLayers(false);
    }

    private float dp(float v) {
        return v * density;
    }
}
