package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewOutlineProvider;

import java.util.ArrayList;
import java.util.List;

/**
 * Home in miniature, for Home Settings: what the top shelf shows behind the dock for an app, with one of its choices.
 * Drawn as Home lays it out on its 960 x 540 dp screen (the art full screen, its words above the dock as
 * ShelfInfoView places them, the glow as MainActivity draws it, the dock with the app focused), scaled down to fit.
 */
public class ShelfPreview extends View {

    /** Home's screen, and where its dock and words sit on it, in dp. */
    private static final float W = 960f, H = 540f;
    private static final float DOCK_L = 54f, DOCK_R = 906f, DOCK_T = 330f, DOCK_B = 514f, DOCK_RADIUS = 38f;
    private static final float TILE_GAP = 22f, TILE_RADIUS = AppAdapter.TILE_RADIUS_DP;
    private static final float NAME_LEFT = 68f;
    private static final float CORNER_DP = 18f;

    private static final int GLOW = 0, CONTENT = 1, COVER = 2, IMAGE = 3;

    private final float density;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint glass = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint namePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint captionPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF dst = new RectF();
    private final Rect src = new Rect();

    private int mode = GLOW;
    private Bitmap backdrop;
    private Bitmap logo;
    private String name;
    private String title;
    private String caption;
    private int glow = AmbientColor.FALLBACK;
    private final List<Drawable> dock = new ArrayList<>();
    private int focused = -1;

    public ShelfPreview(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        float sp = getResources().getDisplayMetrics().scaledDensity;
        // Home's own sizes (Text.LargeTitle and Text.Overline, as ShelfInfoView), at Home's scale.
        namePaint.setTypeface(font(R.font.display_medium));
        namePaint.setTextSize(44f * sp);
        namePaint.setLetterSpacing(-0.01f);
        namePaint.setColor(0xFFFFFFFF);
        captionPaint.setTypeface(font(R.font.text_semibold));
        captionPaint.setTextSize(12.5f * sp);
        captionPaint.setLetterSpacing(0.08f);
        captionPaint.setColor(0xD9FFFFFF);
        glass.setColor(0x24FFFFFF);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(density);
        rim.setColor(0x38FFFFFF);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline outline) {
                outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), CORNER_DP * density);
            }
        });
        setClipToOutline(true);
    }

    private Typeface font(int id) {
        try {
            return getResources().getFont(id);
        } catch (RuntimeException e) {
            return Typeface.DEFAULT;
        }
    }

    /** The dock's banners, and which one is the app being shown (-1: none of them). */
    void setDock(List<Drawable> banners, int focusedSlot) {
        dock.clear();
        dock.addAll(banners);
        focused = focusedSlot;
        invalidate();
    }

    /** No art: the glow in {@code colour}, with the app's name. */
    void showGlow(String appName, int colour) {
        set(GLOW, null, null, appName, null, null);
        glow = colour;
    }

    /** A suggested title: its backdrop, its logo (or its name) and what it is. */
    void showContent(Bitmap backdrop, Bitmap logo, String title, String caption) {
        set(CONTENT, backdrop, logo, null, title, caption);
    }

    /** Cover art (with the app's logo above the dock), or a static image ({@code image}, its logo in the picture). */
    void showCover(Bitmap backdrop, Bitmap logo, boolean image) {
        set(image ? IMAGE : COVER, backdrop, image ? null : logo, null, null, null);
    }

    private void set(int mode, Bitmap backdrop, Bitmap logo, String name, String title, String caption) {
        this.mode = mode;
        this.backdrop = backdrop;
        this.logo = logo;
        this.name = name;
        this.title = title;
        this.caption = caption == null ? null : caption.toUpperCase(java.util.Locale.getDefault());
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (getWidth() == 0) return;
        float s = getWidth() / (W * density);
        canvas.save();
        canvas.scale(s, s);
        float w = W * density, h = H * density;
        canvas.drawColor(0xFF000000);
        if (backdrop != null && !backdrop.isRecycled()) {
            drawFill(canvas, backdrop, w, h);
        } else {
            int c = glow & 0x00FFFFFF;
            paint.setShader(new RadialGradient(w * 0.32f, h * 0.42f, w * 0.62f,
                    new int[]{0x5C000000 | c, 0x26000000 | c, c}, null, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, paint);
            paint.setShader(null);
        }
        float dockTop = DOCK_T * density;
        float left = NAME_LEFT * density;
        float above = dockTop - 26 * density;
        if (mode == GLOW) {
            drawText(canvas, name, left, above, w);
        } else if (mode == CONTENT) {
            float y = above;
            if (logo != null && !logo.isRecycled()) {
                float maxW = w * 0.36f, maxH = 92 * density;
                float k = Math.min(1f, Math.min(maxW / logo.getWidth(), maxH / logo.getHeight()));
                dst.set(left, y - logo.getHeight() * k, left + logo.getWidth() * k, y);
                canvas.drawBitmap(logo, null, dst, paint);
                y = dst.top;
            } else if (title != null && !title.isEmpty()) {
                y = drawText(canvas, title, left, y, w);
            }
            if (caption != null && !caption.isEmpty()) {
                CharSequence c = TextUtils.ellipsize(caption, captionPaint, w * 0.36f, TextUtils.TruncateAt.END);
                canvas.drawText(c, 0, c.length(), left, y - 12 * density, captionPaint);
            }
        } else if (mode == COVER && logo != null && !logo.isRecycled()) {
            float maxW = w * 0.3f, maxH = dockTop * 0.3f;
            float k = Math.min(1f, Math.min(maxW / logo.getWidth(), maxH / logo.getHeight()));
            float lw = logo.getWidth() * k, lh = logo.getHeight() * k, cy = dockTop * 0.46f;
            dst.set((w - lw) / 2f, cy - lh / 2f, (w + lw) / 2f, cy + lh / 2f);
            canvas.drawBitmap(logo, null, dst, paint);
        }
        drawDock(canvas);
        canvas.restore();
    }

    /** A name or title as Home writes it, its bottom at {@code bottom}; returns its top. */
    private float drawText(Canvas canvas, String text, float left, float bottom, float w) {
        if (text == null || text.isEmpty()) return bottom;
        CharSequence t = TextUtils.ellipsize(text, namePaint, w * 0.7f, TextUtils.TruncateAt.END);
        Paint.FontMetrics fm = namePaint.getFontMetrics();
        canvas.drawText(t, 0, t.length(), left, bottom - fm.descent, namePaint);
        return bottom - (fm.descent - fm.ascent);
    }

    /** Fills the screen with {@code b}, cropping what doesn't fit, as the top shelf does. */
    private void drawFill(Canvas canvas, Bitmap b, float w, float h) {
        float bw = b.getWidth(), bh = b.getHeight();
        float k = Math.max(w / bw, h / bh);
        int cw = Math.round(w / k), ch = Math.round(h / k);
        int x = Math.round((bw - cw) / 2f);
        // A static image keeps its top: tvOS shows it from the top of the screen down.
        int y = mode == IMAGE ? 0 : Math.round((bh - ch) / 2f);
        src.set(x, y, x + cw, y + ch);
        dst.set(0, 0, w, h);
        canvas.drawBitmap(b, src, dst, paint);
    }

    /** The dock's glass and its apps, the one shown lifted and the others stepped back. */
    private void drawDock(Canvas canvas) {
        float d = density;
        dst.set(DOCK_L * d, DOCK_T * d, DOCK_R * d, DOCK_B * d);
        canvas.drawRoundRect(dst, DOCK_RADIUS * d, DOCK_RADIUS * d, glass);
        canvas.drawRoundRect(dst, DOCK_RADIUS * d, DOCK_RADIUS * d, rim);
        int n = Math.max(4, dock.size());
        float inner = (DOCK_R - DOCK_L) - 2 * TILE_GAP;
        float tw = (inner - (n - 1) * TILE_GAP) / n, th = tw * 9f / 16f;
        float top = DOCK_T + ((DOCK_B - DOCK_T) - th) / 2f;
        for (int i = 0; i < dock.size(); i++) {
            Drawable b = dock.get(i);
            if (b == null) continue;
            float l = DOCK_L + TILE_GAP + i * (tw + TILE_GAP);
            boolean lifted = i == focused;
            float grow = lifted ? tw * (AppAdapter.FOCUS_SCALE - 1f) / 2f : 0f;
            float growV = lifted ? th * (AppAdapter.FOCUS_SCALE - 1f) / 2f : 0f;
            canvas.save();
            dst.set((l - grow) * d, (top - growV) * d, (l + tw + grow) * d, (top + th + growV) * d);
            android.graphics.Path clip = new android.graphics.Path();
            clip.addRoundRect(dst, TILE_RADIUS * d, TILE_RADIUS * d, android.graphics.Path.Direction.CW);
            canvas.clipPath(clip);
            Rect saved = new Rect(b.getBounds());
            b.setBounds(Math.round(dst.left), Math.round(dst.top), Math.round(dst.right), Math.round(dst.bottom));
            b.setAlpha(lifted || focused < 0 ? 255 : 140);
            b.draw(canvas);
            b.setAlpha(255);
            b.setBounds(saved);
            canvas.restore();
        }
    }
}
