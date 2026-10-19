package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;
import android.widget.FrameLayout;

/**
 * A stand-in for top shelf art behind the glass, to see and measure Liquid Glass over busy, colourful pictures until
 * the top shelf shows real cover art: bold colour fields, bright discs, fine stripes and large type, a different
 * picture for each dock slot, cross-fading as focus moves along the dock. Like cover art, each is a bitmap, drawn
 * once (half the screen's size, scaled up), so the screen costs what it would with real pictures. Only there when
 * asked for:
 * {@code adb shell am start -n com.gathin.tvhome/.MainActivity --ez glass_test_art true} (false takes it away).
 */
final class GlassTestArt extends FrameLayout {

    static final String EXTRA = "glass_test_art";
    private static final long FADE_MS = 250L;
    /** Background from, background to, discs. */
    private static final int[][] PALETTES = {
            {0xFFE50914, 0xFF2B0A3D, 0xFFFFB000},
            {0xFF00A8E1, 0xFF0B1F3A, 0xFF7CFFCB},
            {0xFFF6F7F8, 0xFFC9D3DC, 0xFFFFFFFF},   // near white, as snowy or bright-room art
            {0xFF34C759, 0xFF0E2F1F, 0xFFFFE066},
            {0xFFAF52DE, 0xFF0A0A2A, 0xFF5AC8FA},
    };

    private final Picture front;
    private final Picture back;
    private final Bitmap[] pictures = new Bitmap[PALETTES.length];
    private int slot = -1;

    GlassTestArt(Context context) {
        super(context);
        back = new Picture(context);
        front = new Picture(context);
        addView(back, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        addView(front, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        setAlpha(0f);
    }

    /** The picture for dock slot {@code slot}, cross-fading from the one shown. */
    void show(int slot) {
        animate().alpha(1f).setDuration(FADE_MS).start();
        if (slot == this.slot) return;
        this.slot = slot;
        back.setSlot(front.slot);
        back.setAlpha(1f);
        front.animate().cancel();
        front.setSlot(slot);
        front.setAlpha(0f);
        front.animate().alpha(1f).setDuration(FADE_MS).start();
    }

    void hide() {
        animate().alpha(0f).setDuration(FADE_MS).start();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w <= 0 || h <= 0) return;
        for (int i = 0; i < pictures.length; i++) {
            Bitmap b = Bitmap.createBitmap(w / 2, h / 2, Bitmap.Config.ARGB_8888);
            paint(new Canvas(b), b.getWidth(), b.getHeight(), i);
            b.prepareToDraw();
            pictures[i] = b;
        }
        front.invalidate();
        back.invalidate();
    }

    /** A picture for dock slot {@code slot}, {@code w} by {@code h}. */
    private static void paint(Canvas c, int w, int h, int slot) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setColor(0xF2FFFFFF);
        text.setTypeface(Typeface.DEFAULT_BOLD);
        int[] p = PALETTES[slot % PALETTES.length];
        Shader disc = new RadialGradient(0, 0, h * 0.22f, new int[]{p[2], p[2] & 0x00FFFFFF},
                new float[]{0.6f, 1f}, Shader.TileMode.CLAMP);
        paint.setShader(new LinearGradient(0, 0, w, h, p[0], p[1], Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, paint);
        // Discs where the dock and the top right are, so the glass has something bright to bend.
        paint.setShader(disc);
        float[][] at = {{0.18f, 0.80f}, {0.55f, 0.92f}, {0.86f, 0.10f}, {0.78f, 0.62f}};
        for (int i = 0; i < at.length; i++) {
            float x = w * at[(i + slot) % at.length][0], y = h * at[i][1];
            c.save();
            c.translate(x, y);
            c.drawCircle(0, 0, h * 0.22f, paint);
            c.restore();
        }
        paint.setShader(null);
        // Fine diagonal stripes across the bottom third: they show how much the glass blurs.
        paint.setColor(0x40FFFFFF);
        paint.setStrokeWidth(Math.max(1f, h / 180f));
        float step = h / 45f;
        for (float x = -h; x < w; x += step) c.drawLine(x, h, x + h * 0.4f, h * 0.6f, paint);
        text.setTextSize(h * 0.16f);
        c.drawText("TOP SHELF " + (slot + 1), w * 0.06f, h * 0.38f, text);
    }

    private final class Picture extends View {
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        int slot = -1;

        Picture(Context context) {
            super(context);
        }

        void setSlot(int slot) {
            this.slot = slot;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            Bitmap b = slot >= 0 ? pictures[slot % pictures.length] : null;
            if (b == null) return;
            c.save();
            c.scale(getWidth() / (float) b.getWidth(), getHeight() / (float) b.getHeight());
            c.drawBitmap(b, 0f, 0f, paint);
            c.restore();
        }
    }
}
