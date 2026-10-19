package com.gathin.tvhome;

import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RecordingCanvas;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import java.util.ArrayList;

/**
 * Apple TV's Liquid Glass (tvOS 26), for the dock panel and the top right capsule. What's behind the glass, the top
 * shelf ({@link Backdrop}), shows through it slightly blurred, more colourful and bent at the edges, under a faint
 * white tint and a rim that is brightest along the top. Measured on Apple TV frames; the pull request that added
 * this has the table and the sources. How see-through it is follows one setting, from Clear to Tinted ({@link Look}),
 * as Apple's Liquid Glass slider.
 *
 * <p>Android has no backdrop blur inside a window, so the glass draws its own copy of the backdrop: a
 * {@link RenderNode} that records the backdrop's views (their existing display lists, so recording costs a few draw
 * calls), at half resolution, around the glass's shape only, with a {@link RenderEffect} on it. The GPU renders that
 * small layer again only on frames where the backdrop or the glass moved or changed, and the backdrop checks for that
 * once per frame before drawing. Three tiers, by what the TV can do:
 * <ul>
 * <li>{@link #LENS}, Android 13+: blur, more saturation, and an AGSL shader that bends the backdrop along the edges.</li>
 * <li>{@link #BLUR}, Android 12: blur and saturation, straight edges.</li>
 * <li>{@link #FLAT}, older Android, low-RAM TVs, or {@code R.bool.liquid_glass_blur} off: tint and rim only, as the
 * dock had before (more opaque towards Tinted). Over the ambient glow, which is already soft, it looks nearly the
 * same.</li>
 * </ul>
 */
final class LiquidGlass {

    static final int FLAT = 0, BLUR = 1, LENS = 2;

    /** More colourful than what's behind it, as the dock is over the top shelf's art: 1.5 times the saturation. */
    static final float SATURATION = 1.5f;
    /** What tinted glass darkens towards: tvOS's dark grey (the tiles' own, drawable/tile_bg.xml). */
    static final int BODY = 0x1C1C1E;

    /**
     * How see-through the glass is, from 0 to 100, as Apple's Liquid Glass slider (tvOS and iOS 26): its marks are
     * Clear, at {@link #CLEAR}, and Tinted, at {@link #TINTED}. Clear is what Apple TV shows by default and what was
     * measured; towards Tinted the glass blurs more and darkens until little shows through it. Below Clear it gets a
     * little clearer still.
     */
    static final class Look {
        static final int CLEAR = 5;
        static final int TINTED = 95;

        final int value;
        /**
         * Blur, Gaussian sigma: 3.5 px at 1080p at Clear (Apple TV frames: the top shelf behind the dock keeps its
         * shapes but loses its fine detail), 12 px at Tinted.
         */
        final float sigmaDp;
        /** Opacity of the dark grey over the blurred backdrop: none at Clear, 80 % at Tinted. */
        final float body;
        /** The style's white tint, times this: less below Clear. */
        final float fill;

        Look(int value) {
            this.value = Math.max(0, Math.min(100, value));
            float toTinted = clamp((this.value - CLEAR) / (float) (TINTED - CLEAR));
            float belowClear = clamp((CLEAR - this.value) / (float) CLEAR);
            sigmaDp = 1.75f + 4.25f * toTinted - 0.75f * belowClear;
            body = 0.8f * toTinted;
            fill = 1f - 0.5f * belowClear;
        }

        private static float clamp(float v) {
            return Math.max(0f, Math.min(1f, v));
        }
    }

    /** How a piece of glass looks at Clear; values measured at 1080p (1 dp is 2 px). */
    static final class Style {
        /** White tint over the backdrop. */
        final int fill;
        /** Rim: a stroke along the edge, white at these alphas at the top, where the top corners end, and the bottom. */
        final int rimTop, rimShoulder, rimBottom;
        final float rimDp;
        /** Lens: the band along the edge that bends what's behind it, and how far out it reaches at the very edge. */
        final float bezelDp, refractDp;

        Style(int fill, int rimTop, int rimShoulder, int rimBottom, float rimDp, float bezelDp, float refractDp) {
            this.fill = fill;
            this.rimTop = rimTop << 24 | 0xFFFFFF;
            this.rimShoulder = rimShoulder << 24 | 0xFFFFFF;
            this.rimBottom = rimBottom << 24 | 0xFFFFFF;
            this.rimDp = rimDp;
            this.bezelDp = bezelDp;
            this.refractDp = refractDp;
        }
    }

    /**
     * The dock: 9 % white (Apple TV lifts black to 35 and grey 55 to 76 of 255), a 3 px rim at 19 % white along the
     * top fading to 9 % down the sides and bottom, and a 12 px band along the edge that shows what's up to 20 px
     * beyond it.
     */
    static final Style DOCK = new Style(0x17, 0x30, 0x17, 0x17, 1.5f, 6f, 10f);
    /** The top right capsule: smaller, so thinner (a narrower lens); its tint and rim are the ones it had before. */
    static final Style CAPSULE = new Style(0x14, 0x33, 0x00, 0x0A, 0.75f, 4f, 5f);

    /** Which tier this TV gets. */
    static int tier(Context context) {
        if (Build.VERSION.SDK_INT < 31 || !context.getResources().getBoolean(R.bool.liquid_glass_blur)) return FLAT;
        ActivityManager am = context.getSystemService(ActivityManager.class);
        if (am != null && am.isLowRamDevice()) return FLAT;
        return Build.VERSION.SDK_INT >= 33 ? LENS : BLUR;
    }

    /**
     * What shows through the glass: a view (layout/activity_main.xml's {@code @id/backdrop}) and everything in it.
     * It must not contain any glass, and SurfaceViews in it don't show (their content isn't in the view hierarchy).
     * Before each frame it checks whether any of its views moved, faded or redrew, so the glass follows it without
     * being told. It also holds the {@link Look} that all glass over it shares.
     */
    static final class Backdrop implements ViewTreeObserver.OnPreDrawListener {
        final View view;
        final int tier;
        private Look look;
        private final ArrayList<LiquidGlass> glasses = new ArrayList<>();
        private long state;
        private boolean dirty;

        Backdrop(View view, int tier, Look look) {
            this.view = view;
            this.tier = tier;
            this.look = look;
            if (tier != FLAT) view.getViewTreeObserver().addOnPreDrawListener(this);
        }

        Look look() {
            return look;
        }

        /** Every glass over this backdrop takes the new look on its next frame. */
        void setLook(Look look) {
            if (look.value == this.look.value) return;
            this.look = look;
            for (int i = 0; i < glasses.size(); i++) glasses.get(i).lookChanged();
        }

        @Override
        public boolean onPreDraw() {
            dirty = false;
            long s = state(view, 17L);
            boolean changed = dirty || s != state;
            state = s;
            for (int i = 0; i < glasses.size(); i++) glasses.get(i).refresh(changed);
            return true;
        }

        /** Everything about the backdrop's views that changes what they draw. Invalidated views set {@link #dirty}. */
        private long state(View v, long h) {
            if (v.isDirty()) dirty = true;
            h = h * 31 + v.getVisibility();
            if (v.getVisibility() != View.VISIBLE) return h;
            h = h * 31 + Float.floatToIntBits(v.getAlpha());
            h = h * 31 + Float.floatToIntBits(v.getTranslationX());
            h = h * 31 + Float.floatToIntBits(v.getTranslationY());
            h = h * 31 + Float.floatToIntBits(v.getScaleX());
            h = h * 31 + Float.floatToIntBits(v.getScaleY());
            h = h * 31 + v.getLeft();
            h = h * 31 + v.getTop();
            h = h * 31 + v.getRight();
            h = h * 31 + v.getBottom();
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) h = state(g.getChildAt(i), h);
            }
            return h;
        }

        /** Drops the glass's copies of the backdrop; they're recorded again if drawn. */
        void release() {
            for (int i = 0; i < glasses.size(); i++) glasses.get(i).releaseLayer();
        }
    }

    @Nullable
    private final Backdrop backdrop;
    private final Style style;
    private final float density;
    private final Paint body = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** The shape, at its own origin: the canvas is moved to its top left to draw it. */
    private final Path shape = new Path();
    private final RectF local = new RectF();
    /** The copy of the backdrop (a {@link Layer}), when this TV blurs. */
    @Nullable
    private final Object layer;
    /** The look the paints and the layer were set up for. */
    private Look look;

    // Where the shape was last drawn, in its host view.
    private View host;
    private float left, top, width = -1, height = -1, radius = -1;
    /** Drawn and not taken off screen since. */
    private boolean shown;
    /** The backdrop changed while the glass wasn't on screen, or the layer needs recording for another reason. */
    private boolean stale = true;
    /** The layer's effect needs building again: new size or look. */
    private boolean reshaped = true;
    /** Where the backdrop's origin was, relative to the shape's top left, when the layer was last recorded. */
    private float recordedDx, recordedDy;
    private final float[] hostOrigin = new float[2];
    private final float[] backdropOrigin = new float[2];

    LiquidGlass(@Nullable Backdrop backdrop, Style style, float density) {
        this.backdrop = backdrop;
        this.style = style;
        this.density = density;
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(Math.max(1f, style.rimDp * density));
        int tier = backdrop != null ? backdrop.tier : FLAT;
        layer = tier != FLAT && Build.VERSION.SDK_INT >= 31 ? new Layer(style, density, tier == LENS) : null;
        if (backdrop != null) backdrop.glasses.add(this);
        setLook(backdrop != null ? backdrop.look : new Look(Look.CLEAR));
    }

    private void setLook(Look look) {
        this.look = look;
        body.setColor(Math.round(look.body * 255f) << 24 | BODY);
        fill.setColor(Math.round(style.fill * look.fill) << 24 | 0xFFFFFF);
        reshaped = true;
        stale = true;
    }

    private void lookChanged() {
        setLook(backdrop.look);
        if (host != null) host.invalidate();
    }

    /**
     * Draws the glass on {@code canvas}, which belongs to {@code host}: a rounded rectangle at {@code l, t, r, b}
     * in the host's coordinates.
     */
    void draw(Canvas canvas, View host, float l, float t, float r, float b, float radius) {
        this.host = host;
        shown = true;
        float w = r - l, h = b - t;
        if (w != width || h != height || radius != this.radius) {
            width = w;
            height = h;
            this.radius = radius;
            reshaped = true;
            local.set(0, 0, w, h);
            shape.reset();
            shape.addRoundRect(local, radius, radius, Path.Direction.CW);
            float shoulder = h > 0 ? Math.min(0.5f, radius / h) : 0.5f;
            rim.setShader(new LinearGradient(0, 0, 0, h, new int[]{style.rimTop, style.rimShoulder, style.rimBottom},
                    new float[]{0f, shoulder, 1f}, Shader.TileMode.CLAMP));
        }
        left = l;
        top = t;
        canvas.save();
        canvas.translate(l, t);
        if (layer != null && canvas.isHardwareAccelerated() && Build.VERSION.SDK_INT >= 31) {
            if (stale || reshaped || moved()) record();
            canvas.save();
            canvas.clipPath(shape);
            ((Layer) layer).draw(canvas);
            canvas.restore();
        }
        if (body.getAlpha() > 0) canvas.drawRoundRect(local, radius, radius, body);
        canvas.drawRoundRect(local, radius, radius, fill);
        float inset = rim.getStrokeWidth() / 2f;
        local.inset(inset, inset);
        canvas.drawRoundRect(local, radius - inset, radius - inset, rim);
        local.inset(-inset, -inset);
        canvas.restore();
    }

    /** The host no longer draws this glass (e.g. the dock is scrolled away): stop following the backdrop. */
    void hide() {
        shown = false;
    }

    /** Before a frame: records the backdrop again if it changed or the glass moved over it. */
    private void refresh(boolean backdropChanged) {
        if (layer == null) return;
        if (backdropChanged) stale = true;
        if (host == null || !shown || width < 0 || !onScreen(host) || Build.VERSION.SDK_INT < 31) return;
        if (stale || reshaped || moved()) record();
    }

    /** Whether the glass is at another place over the backdrop than when last recorded. */
    private boolean moved() {
        offset();
        return backdropOrigin[0] != recordedDx || backdropOrigin[1] != recordedDy;
    }

    /** The backdrop's origin relative to the shape's top left, into {@link #backdropOrigin}. */
    private void offset() {
        origin(host, hostOrigin);
        origin(backdrop.view, backdropOrigin);
        backdropOrigin[0] -= hostOrigin[0] + left;
        backdropOrigin[1] -= hostOrigin[1] + top;
    }

    @RequiresApi(31)
    private void record() {
        offset();
        recordedDx = backdropOrigin[0];
        recordedDy = backdropOrigin[1];
        ((Layer) layer).record(backdrop.view, recordedDx, recordedDy, width, height, radius,
                reshaped ? look.sigmaDp * density : -1f);
        stale = false;
        reshaped = false;
    }

    private void releaseLayer() {
        stale = true;
        if (layer != null && Build.VERSION.SDK_INT >= 31) ((Layer) layer).release();
    }

    /**
     * Where {@code v}'s top left is in its window's root view: positions and translations of the views in between,
     * not their scale (the whole screen scales together when an app opens; see HomeMotion).
     */
    static void origin(View v, float[] out) {
        float x = 0f, y = 0f;
        View view = v;
        while (true) {
            x += view.getLeft() + view.getTranslationX();
            y += view.getTop() + view.getTranslationY();
            ViewParent p = view.getParent();
            if (!(p instanceof View)) break;
            view = (View) p;
            x -= view.getScrollX();
            y -= view.getScrollY();
        }
        out[0] = x;
        out[1] = y;
    }

    /** Visible and not fully faded out, nor any of its parents. */
    private static boolean onScreen(View v) {
        for (View view = v; view != null; view = view.getParent() instanceof View ? (View) view.getParent() : null) {
            if (view.getVisibility() != View.VISIBLE || view.getAlpha() <= 0f) return false;
        }
        return v.isAttachedToWindow();
    }

    /**
     * The copy of the backdrop behind one piece of glass. Android 12+. On a TV's GPU each pass over a texture costs
     * more than the pixels in it (about 0.4 ms each on a Mali-G57, measured), so the layer takes two: the copy itself,
     * then one effect. On Android 13+ that is {@link Optics}, blur, saturation and lens in one shader; on Android 12
     * Skia's blur, with the saturation applied as the layer is drawn.
     */
    @RequiresApi(31)
    private static final class Layer {
        private static final Paint VIBRANCY = new Paint();

        static {
            ColorMatrix m = new ColorMatrix();
            m.setSaturation(SATURATION);
            VIBRANCY.setColorFilter(new ColorMatrixColorFilter(m));
        }

        private final RenderNode node = new RenderNode("LiquidGlass");
        private final float density;
        private final float bezel;
        private final float refract;
        @Nullable
        private final Optics optics;
        /** Around the shape, the copy also takes what the blur and the lens reach for, in px. */
        private float margin;
        /** The copy's resolution, a fraction of the screen's: lower for a stronger blur. */
        private float scale = MAX_SCALE;

        /** The copy is never sharper than half resolution, nor coarser than a quarter. */
        private static final float MAX_SCALE = 0.5f, MIN_SCALE = 0.25f;
        /** Blur sigma in the copy's pixels that {@link Optics} spreads its taps for. */
        private static final float LAYER_SIGMA = 1.75f;

        Layer(Style style, float density, boolean lens) {
            this.density = density;
            optics = lens && Build.VERSION.SDK_INT >= 33 ? Optics.create() : null;
            bezel = style.bezelDp * density;
            refract = optics != null ? style.refractDp * density : 0f;
            if (optics == null) node.setUseCompositingLayer(true, VIBRANCY);
        }

        /**
         * Records the backdrop around a {@code w} by {@code h} shape whose top left is at {@code -dx, -dy} from the
         * backdrop's origin. With {@code sigma} (px) at 0 or more, first sets up the blur and lens again.
         */
        void record(View backdrop, float dx, float dy, float w, float h, float radius, float sigma) {
            Perf.begin("tvhome:glass.record");
            try {
                if (sigma >= 0f) reshape(w, h, radius, sigma);
                int nw = node.getWidth(), nh = node.getHeight();
                RecordingCanvas c = node.beginRecording(nw, nh);
                try {
                    c.drawColor(Color.BLACK);   // the window's background, under the backdrop
                    c.scale(scale, scale);
                    c.translate(margin + dx, margin + dy);
                    backdrop.draw(c);
                } finally {
                    node.endRecording();
                }
            } finally {
                Perf.end();
            }
        }

        private void reshape(float w, float h, float radius, float sigma) {
            // A stronger blur needs no more detail than this: Optics' taps stay LAYER_SIGMA apart.
            scale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, LAYER_SIGMA / Math.max(0.01f, sigma)));
            margin = (float) Math.ceil(refract + 3f * sigma + density);
            int nw = (int) Math.ceil((w + 2f * margin) * scale);
            int nh = (int) Math.ceil((h + 2f * margin) * scale);
            node.setPosition(0, 0, nw, nh);
            float s = scale;
            if (optics != null && Build.VERSION.SDK_INT >= 33) {
                node.setRenderEffect(optics.effect(margin * s, margin * s, w * s, h * s, radius * s,
                        Math.min(bezel, radius) * s, refract * s, sigma * s));
            } else {
                // RenderEffect takes a radius, which Skia turns into sigma = 0.57735 * radius + 0.5.
                float blur = Math.max(0.01f, (sigma * s - 0.5f) / 0.57735f);
                node.setRenderEffect(RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP));
            }
        }

        /** Draws the copy on a canvas at the shape's top left, already clipped to the shape. */
        void draw(Canvas canvas) {
            canvas.save();
            canvas.translate(-margin, -margin);
            canvas.scale(1f / scale, 1f / scale);
            canvas.drawRenderNode(node);
            canvas.restore();
        }

        void release() {
            node.discardDisplayList();
        }
    }

    /**
     * Glass's optics in one AGSL shader, so the GPU makes one pass for them:
     * <ul>
     * <li>The lens: inside a band along the edge (the bezel), each pixel shows the backdrop from further out, most at
     * the very edge, as through the rounded edge of a slab of glass, so what is just outside the glass shows, squeezed,
     * just inside its edge. The bezel's profile is a quarter circle, as in Kyant0's AndroidLiquidGlass and kube.io's
     * recreations.</li>
     * <li>The blur, at the lens's point: a 5 by 5 binomial kernel (a Gaussian's sigma is its tap spacing) in 9 linearly
     * filtered taps, each standing for two of the kernel's.</li>
     * <li>Saturation, luminance kept, as {@link ColorMatrix#setSaturation}.</li>
     * </ul>
     * Android 13+.
     */
    @RequiresApi(33)
    private static final class Optics {
        private static final String AGSL = ""
                + "uniform shader content;\n"
                + "uniform float2 origin;\n"
                + "uniform float2 size;\n"
                + "uniform float radius;\n"
                + "uniform float bezel;\n"
                + "uniform float reach;\n"
                + "uniform float spread;\n"
                + "uniform float saturation;\n"
                + "half4 blurred(float2 c) {\n"
                + "  float o = 1.2 * spread;\n"
                + "  half4 s = content.eval(c) * 36.0;\n"
                + "  s += (content.eval(c + float2(o, 0.0)) + content.eval(c - float2(o, 0.0))\n"
                + "      + content.eval(c + float2(0.0, o)) + content.eval(c - float2(0.0, o))) * 30.0;\n"
                + "  s += (content.eval(c + float2(o, o)) + content.eval(c - float2(o, o))\n"
                + "      + content.eval(c + float2(o, -o)) + content.eval(c + float2(-o, o))) * 25.0;\n"
                + "  return s / 256.0;\n"
                + "}\n"
                + "half4 main(float2 coord) {\n"
                + "  float2 hs = size * 0.5;\n"
                + "  float2 p = coord - origin - hs;\n"
                + "  float2 q = abs(p) - hs + radius;\n"
                + "  float d = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;\n"
                + "  float2 at = coord;\n"
                + "  if (d < 0.0 && d > -bezel) {\n"
                + "    float2 n = (q.x > 0.0 && q.y > 0.0) ? normalize(q) : (q.x > q.y ? float2(1.0, 0.0) : float2(0.0, 1.0));\n"
                + "    float x = 1.0 + d / bezel;\n"
                + "    at += n * sign(p) * ((1.0 - sqrt(max(0.0, 1.0 - x * x))) * reach);\n"
                + "  }\n"
                + "  half4 c = blurred(at);\n"
                + "  half l = dot(c.rgb, half3(0.213, 0.715, 0.072));\n"
                + "  c.rgb = clamp(mix(half3(l), c.rgb, half(saturation)), 0.0, c.a);\n"
                + "  return c;\n"
                + "}\n";

        private final RuntimeShader shader;

        private Optics(RuntimeShader shader) {
            this.shader = shader;
        }

        /** Null if this TV's shader compiler won't take it: the glass then blurs with Skia, with straight edges. */
        @Nullable
        static Optics create() {
            try {
                return new Optics(new RuntimeShader(AGSL));
            } catch (RuntimeException e) {
                return null;
            }
        }

        /**
         * The effect for a shape at {@code x, y}, {@code w} by {@code h}, and a blur of {@code sigma}, all in the
         * layer's pixels.
         */
        RenderEffect effect(float x, float y, float w, float h, float radius, float bezel, float refract, float sigma) {
            shader.setFloatUniform("origin", x, y);
            shader.setFloatUniform("size", w, h);
            shader.setFloatUniform("radius", radius);
            shader.setFloatUniform("bezel", Math.max(1f, bezel));
            shader.setFloatUniform("reach", refract);
            shader.setFloatUniform("spread", sigma);
            shader.setFloatUniform("saturation", SATURATION);
            return RenderEffect.createRuntimeShaderEffect(shader, "content");
        }
    }
}
