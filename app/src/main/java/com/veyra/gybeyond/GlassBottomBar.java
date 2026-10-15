package com.veyra.gybeyond;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelCopy;
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
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.webkit.WebView;
import androidx.core.graphics.PathParser;

/**
 * 原生液态玻璃底栏 —— 复刻 Android Liquid Glass（backdrop）效果：
 * 背景内容经 PixelCopy 截屏后，由 AGSL RuntimeShader 做圆角矩形折射（含色散），
 * 选中胶囊按压/拖动时折射强度与高光阴影随 pressProgress 变化。
 * API 33+ 全效果；31-32 仅模糊；<31 静态渐变降级。
 */
public class GlassBottomBar extends View {
    private static final String TAG = "GlassBottomBar";

    // ---- AGSL shader（来自 Android Liquid Glass / kyant backdrop，Apache-2.0）----
    private static final String ROUNDED_RECT_SDF = """
float radiusAt(float2 coord, float4 radii) {
    if (coord.x >= 0.0) {
        if (coord.y <= 0.0) return radii.y;
        else return radii.z;
    } else {
        if (coord.y <= 0.0) return radii.x;
        else return radii.w;
    }
}
float sdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    float outside = length(max(cornerCoord, 0.0)) - radius;
    float inside = min(max(cornerCoord.x, cornerCoord.y), 0.0);
    return outside + inside;
}
float2 gradSdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    if (cornerCoord.x >= 0.0 || cornerCoord.y >= 0.0) {
        return sign(coord) * normalize(max(cornerCoord, 0.0));
    } else {
        float gradX = step(cornerCoord.y, cornerCoord.x);
        return sign(coord) * float2(gradX, 1.0 - gradX);
    }
}""";

    private static final String REFRACTION_SHADER = """
uniform shader content;
uniform float2 size;
uniform float2 offset;
uniform float4 cornerRadii;
uniform float refractionHeight;
uniform float refractionAmount;
uniform float depthEffect;
""" + ROUNDED_RECT_SDF + """
float circleMap(float x) {
    return 1.0 - sqrt(1.0 - x * x);
}
half4 main(float2 coord) {
    float2 halfSize = size * 0.5;
    float2 centeredCoord = (coord + offset) - halfSize;
    float radius = radiusAt(coord, cornerRadii);
    float sd = sdRoundedRect(centeredCoord, halfSize, radius);
    if (-sd >= refractionHeight) {
        return content.eval(coord);
    }
    sd = min(sd, 0.0);
    float d = circleMap(1.0 - -sd / refractionHeight) * refractionAmount;
    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
    float2 grad = normalize(gradSdRoundedRect(centeredCoord, halfSize, gradRadius) + depthEffect * normalize(centeredCoord));
    float2 refractedCoord = coord + d * grad;
    return content.eval(refractedCoord);
}""";

    private static final String REFRACTION_DISPERSION_SHADER = """
uniform shader content;
uniform float2 size;
uniform float2 offset;
uniform float4 cornerRadii;
uniform float refractionHeight;
uniform float refractionAmount;
uniform float depthEffect;
uniform float chromaticAberration;
""" + ROUNDED_RECT_SDF + """
float circleMap(float x) {
    return 1.0 - sqrt(1.0 - x * x);
}
half4 main(float2 coord) {
    float2 halfSize = size * 0.5;
    float2 centeredCoord = (coord + offset) - halfSize;
    float radius = radiusAt(coord, cornerRadii);
    float sd = sdRoundedRect(centeredCoord, halfSize, radius);
    if (-sd >= refractionHeight) {
        return content.eval(coord);
    }
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
    private static final float BLUR_RADIUS_DP = 8f;
    private static final float CONTAINER_REFRACTION_DP = 24f;
    private static final float CAPSULE_REFRACTION_DP = 14f;
    private static final float PANEL_OFFSET_DP = 4f;

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
    private float[] cornerRadii = new float[4];
    private float pad;
    private Rect snapshotSrcRect = new Rect();
    private RectF snapshotDstRect = new RectF();

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

    // ---- Paint ----
    private final Paint containerFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint capsuleFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint containerBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint capsuleBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint highlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint innerShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Path containerPath = new Path();
    private final Path shadowPath = new Path();
    private final RectF capsuleLocal = new RectF();

    // ---- RuntimeShader（API 33+）----
    private RuntimeShader containerShader;
    private RuntimeShader capsuleShader;
    private float[] capsuleRadii = new float[4];

    public GlassBottomBar(Context context) {
        this(context, null);
    }

    public GlassBottomBar(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = context.getResources().getDisplayMetrics().density;
        setWillNotDraw(false);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        borderPaint.setStyle(Paint.Style.STROKE);
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                containerShader = new RuntimeShader(REFRACTION_SHADER);
                capsuleShader = new RuntimeShader(REFRACTION_DISPERSION_SHADER);
            } catch (Throwable t) {
                Log.w(TAG, "RuntimeShader unavailable", t);
                containerShader = null;
                capsuleShader = null;
            }
        }
    }

    public void attachWebView(WebView view) {
        this.webView = view;
    }

    public void setDarkTheme(boolean dark) {
        if (darkTheme == dark) return;
        darkTheme = dark;
        invalidate();
    }

    public void setDetailMode(boolean enabled) {
        if (detailMode == enabled) return;
        detailMode = enabled;
        invalidate();
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

    private void takeSnapshot() {
        int w = snapshotSrcRect.width();
        int h = snapshotSrcRect.height();
        if (w <= 0 || h <= 0) return;
        Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        try {
            PixelCopy.request(webView, snapshotSrcRect, bitmap, copyResult -> {
                if (copyResult == PixelCopy.SUCCESS) {
                    snapshot = bitmap;
                    invalidate();
                } else {
                    bitmap.recycle();
                }
                if (snapshotPending) {
                    snapshotPending = false;
                    lastSnapshotMs = 0;
                    requestSnapshot();
                }
            });
        } catch (Throwable t) {
            bitmap.recycle();
            Log.w(TAG, "PixelCopy failed: " + t);
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
        containerPath.reset();
        containerPath.addRoundRect(containerRect, radius, radius, Path.Direction.CW);
        pad = dp(BLUR_RADIUS_DP) + dp(CONTAINER_REFRACTION_DP) + dp(8);
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
            invalidate();
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
            invalidate();
        });
        move.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                animating = false;
                panelOffset = 0f;
                invalidate();
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
            invalidate();
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
            invalidate();
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

    // ---- 拖动 ----
    private boolean dragging;
    private float downX, downStart;

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (detailMode) return false;
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
                invalidate();
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_MOVE: {
                float raw = downStart + (event.getX() - downX);
                float leftBound = capsuleRect.left + tabWidth / 2f;
                float rightBound = capsuleRect.right - tabWidth / 2f;
                capsuleCenterX = Math.max(leftBound, Math.min(rightBound, raw));
                updatePanelOffset();
                invalidate();
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

    // ---- 绘制 ----
    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (detailMode) return;
        applyBgEffects();
        canvas.save();
        canvas.translate(panelOffset, 0f);
        drawContainer(canvas);
        drawTabs(canvas);
        canvas.restore();
    }

    /** 每帧把 blur→折射 RenderEffect 链应用到背景绘制 Paint（snapshot 变化时自动生效） */
    private void applyBgEffects() {
        if (snapshot == null) {
            containerBgPaint.setRenderEffect(null);
            capsuleBgPaint.setRenderEffect(null);
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 && containerShader != null && capsuleShader != null) {
            android.graphics.RenderEffect containerBit = android.graphics.RenderEffect.createBitmapEffect(snapshot);
            android.graphics.RenderEffect blurFx = android.graphics.RenderEffect.createBlurEffect(
                    dp(BLUR_RADIUS_DP), dp(BLUR_RADIUS_DP), Shader.TileMode.CLAMP, containerBit);
            containerShader.setFloatUniform("size", containerRect.width(), containerRect.height());
            containerShader.setFloatUniform("offset", 0f, 0f);
            containerShader.setFloatUniform("cornerRadii", cornerRadii);
            containerShader.setFloatUniform("refractionHeight", dp(CONTAINER_REFRACTION_DP));
            containerShader.setFloatUniform("refractionAmount", dp(CONTAINER_REFRACTION_DP));
            containerShader.setFloatUniform("depthEffect", 1f);
            containerBgPaint.setRenderEffect(android.graphics.RenderEffect.createRuntimeShaderEffect(
                    containerShader, "content", blurFx));

            android.graphics.RenderEffect capsuleBit = android.graphics.RenderEffect.createBitmapEffect(snapshot);
            float cw = capsuleRect.width() * capsuleScale;
            float ch = capsuleRect.height() * capsuleScale;
            capsuleShader.setFloatUniform("size", cw, ch);
            // 胶囊绘制时原点已在胶囊中心：centeredCoord = coord + offset - halfSize，需 offset = halfSize
            capsuleShader.setFloatUniform("offset", cw / 2f, ch / 2f);
            capsuleShader.setFloatUniform("cornerRadii", capsuleRadii);
            capsuleShader.setFloatUniform("refractionHeight", dp(10f) * pressProgress);
            capsuleShader.setFloatUniform("refractionAmount", dp(CAPSULE_REFRACTION_DP) * pressProgress);
            capsuleShader.setFloatUniform("depthEffect", 1f);
            capsuleShader.setFloatUniform("chromaticAberration", 1f);
            capsuleBgPaint.setRenderEffect(android.graphics.RenderEffect.createRuntimeShaderEffect(
                    capsuleShader, "content", capsuleBit));
        } else if (Build.VERSION.SDK_INT >= 31) {
            // API 31-32：仅模糊降级
            android.graphics.RenderEffect blurFx = android.graphics.RenderEffect.createBlurEffect(
                    dp(BLUR_RADIUS_DP), dp(BLUR_RADIUS_DP), Shader.TileMode.CLAMP,
                    android.graphics.RenderEffect.createBitmapEffect(snapshot));
            containerBgPaint.setRenderEffect(blurFx);
            capsuleBgPaint.setRenderEffect(null);
        }
    }

    private void drawContainer(Canvas canvas) {
        canvas.save();
        canvas.clipPath(containerPath);
        if (snapshot != null) {
            canvas.translate(containerRect.left, containerRect.top);
            canvas.drawBitmap(snapshot, null, new RectF(
                    -pad, -pad, snapshotDstRect.width() - pad, snapshotDstRect.height() - pad), containerBgPaint);
            canvas.translate(-containerRect.left, -containerRect.top);
        }
        containerFillPaint.setShader(new LinearGradient(
                containerRect.left, containerRect.top, containerRect.left, containerRect.bottom,
                darkTheme ? Color.argb(215, 44, 52, 57) : Color.argb(190, 255, 255, 255),
                darkTheme ? Color.argb(160, 26, 31, 34) : Color.argb(120, 255, 252, 244),
                Shader.TileMode.CLAMP));
        canvas.drawRoundRect(containerRect, dp(CONTAINER_RADIUS_DP), dp(CONTAINER_RADIUS_DP), containerFillPaint);
        Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        edge.setShader(new LinearGradient(0, containerRect.top, 0, containerRect.top + dp(14),
                darkTheme ? Color.argb(60, 255, 255, 255) : Color.argb(110, 255, 255, 255),
                Color.TRANSPARENT, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(new RectF(containerRect.left, containerRect.top, containerRect.right, containerRect.top + dp(14)),
                dp(CONTAINER_RADIUS_DP), dp(CONTAINER_RADIUS_DP), edge);
        canvas.restore();
        borderPaint.setStrokeWidth(dp(1));
        borderPaint.setColor(darkTheme ? Color.argb(60, 255, 255, 255) : Color.argb(130, 255, 255, 255));
        canvas.drawRoundRect(containerRect, dp(CONTAINER_RADIUS_DP), dp(CONTAINER_RADIUS_DP), borderPaint);
    }

    private void drawTabs(Canvas canvas) {
        for (int i = 0; i < tabs.length; i++) {
            if (i == selectedIndex && !dragging) continue;
            drawTabIcon(canvas, capsuleRect.left + tabWidth * i + tabWidth / 2f, containerRect.centerY(), false, i);
        }
        float cx = dragging ? capsuleCenterX : capsuleCenterFor(selectedIndex);
        drawCapsule(canvas, cx, containerRect.centerY(), selectedIndex);
    }

    private void drawCapsule(Canvas canvas, float cx, float cy, int index) {
        float w = capsuleRect.width() * capsuleScale;
        float h = capsuleRect.height() * capsuleScale;
        float left = cx - w / 2f;
        float top = cy - h / 2f;
        float radius = dp(CAPSULE_RADIUS_DP);
        Path cap = new Path();
        cap.addRoundRect(new RectF(left, top, left + w, top + h), radius, radius, Path.Direction.CW);

        if (pressProgress > 0.01f) {
            shadowPaint.setColor(Color.BLACK);
            shadowPaint.setAlpha((int) (90 * pressProgress));
            shadowPaint.setMaskFilter(new BlurMaskFilter(dp(10) * pressProgress, BlurMaskFilter.NORMAL));
            shadowPath.reset();
            shadowPath.addRoundRect(new RectF(left + dp(2), top + dp(5), left + w + dp(2), top + h + dp(5)),
                    radius, radius, Path.Direction.CW);
            canvas.drawPath(shadowPath, shadowPaint);
            shadowPaint.setMaskFilter(null);
        }

        capsuleFillPaint.setShader(new LinearGradient(0, top, 0, top + h,
                darkTheme ? Color.rgb(255, 212, 91) : Color.rgb(240, 189, 76),
                darkTheme ? Color.rgb(230, 182, 60) : Color.rgb(214, 156, 4),
                Shader.TileMode.CLAMP));
        capsuleFillPaint.setAlpha((int) (255 * (0.55f + 0.45f * pressProgress)));
        canvas.drawPath(cap, capsuleFillPaint);

        if (snapshot != null) {
            canvas.save();
            canvas.clipPath(cap);
            canvas.translate(cx, cy);
            float localW = w, localH = h;
            capsuleLocal.set(-localW / 2f, -localH / 2f, localW / 2f, localH / 2f);
            canvas.drawBitmap(snapshot, null, new RectF(
                    -pad - (cx - containerRect.left), -pad - (cy - containerRect.top),
                    snapshotDstRect.width() - pad - (cx - containerRect.left),
                    snapshotDstRect.height() - pad - (cy - containerRect.top)), capsuleBgPaint);
            dimPaint.setColor(darkTheme ? Color.WHITE : Color.BLACK);
            dimPaint.setAlpha((int) (25 * (1f - pressProgress) + 8 * pressProgress));
            canvas.drawRoundRect(capsuleLocal, radius, radius, dimPaint);
            highlightPaint.setShader(new LinearGradient(0, -localH / 2f, 0, -localH / 2f + localH * 0.55f,
                    Color.argb((int) (110 * pressProgress + 25), 255, 255, 255),
                    Color.TRANSPARENT, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(capsuleLocal, radius, radius, highlightPaint);
            innerShadowPaint.setShader(new LinearGradient(0, localH * 0.3f, 0, localH / 2f,
                    Color.TRANSPARENT, Color.argb((int) (70 * pressProgress + 20), 0, 0, 0),
                    Shader.TileMode.CLAMP));
            canvas.drawRoundRect(capsuleLocal, radius, radius, innerShadowPaint);
            canvas.restore();
        }

        drawTabIcon(canvas, cx, cy, true, index);
    }

    private void drawTabIcon(Canvas canvas, float cx, float cy, boolean selected, int index) {
        TabItem tab = tabs[index];
        int color = selected ? Color.WHITE
                : darkTheme ? Color.argb(190, 196, 190, 177) : Color.argb(200, 105, 102, 94);
        iconPaint.setColor(color);
        iconPaint.setStyle(tab.stroke ? Paint.Style.STROKE : Paint.Style.FILL);
        iconPaint.setStrokeWidth(dp(1.9f));
        canvas.save();
        canvas.translate(cx, cy - dp(8));
        float s = dp(ICON_SIZE_DP) / 24f;
        canvas.scale(s, s);
        canvas.drawPath(tab.icon, iconPaint);
        canvas.restore();
        textPaint.setColor(selected ? Color.WHITE
                : darkTheme ? Color.argb(220, 196, 190, 177) : Color.argb(220, 105, 102, 94));
        textPaint.setTextSize(dp(10f));
        textPaint.setFakeBoldText(selected);
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float textY = cy + dp(23) - (fm.ascent + fm.descent) / 2f;
        canvas.drawText(tab.label, cx, textY, textPaint);
        textPaint.setFakeBoldText(false);
    }

    private float dp(float v) {
        return v * density;
    }
}
