package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.View;

/**
 * What the top shelf says about its picture, over the art and outside the dock's glass: for suggested content, a
 * small caption ("Top 10 on Netflix · Kenya") above the title's logo (or its name, without one), bottom left above
 * the dock as tvOS places it; for cover art, the app's logo, centred above the dock. Full screen and drawn by
 * hand, so changing it redraws only this view: no layout. MainActivity moves it with the page.
 */
public class ShelfInfoView extends View {

    static final int CONTENT = 0;
    static final int COVER = 1;

    private final TextPaint captionPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint titlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint logoPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final RectF dst = new RectF();
    private final float density;
    private final float titleSize;
    private int mode;
    private Bitmap logo;
    private String title;
    private String caption;
    private float left;
    /** Content: the title's bottom. Cover: the bottom of the space above the dock. */
    private float bottom;

    public ShelfInfoView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        float sp = getResources().getDisplayMetrics().scaledDensity;
        // Text.Overline and Text.LargeTitle (styles.xml), as the top shelf's name.
        captionPaint.setTypeface(font(R.font.text_semibold));
        captionPaint.setTextSize(12.5f * sp);
        captionPaint.setLetterSpacing(0.08f);
        captionPaint.setColor(0xD9FFFFFF);
        captionPaint.setShadowLayer(6 * density, 0, density, 0x66000000);
        titlePaint.setTypeface(font(R.font.display_medium));
        titleSize = 44f * sp;
        titlePaint.setTextSize(titleSize);
        titlePaint.setLetterSpacing(-0.01f);
        titlePaint.setColor(0xFFFFFFFF);
        titlePaint.setShadowLayer(10 * density, 0, 2 * density, 0x66000000);
        setWillNotDraw(false);
    }

    private Typeface font(int id) {
        try {
            return getResources().getFont(id);
        } catch (RuntimeException e) {
            return Typeface.DEFAULT;
        }
    }

    /** Shows this; a null logo shows the title as text instead. Redraws only. */
    void set(int mode, Bitmap logo, String title, String caption) {
        this.mode = mode;
        this.logo = logo;
        this.title = title;
        this.caption = caption == null ? null : caption.toUpperCase(java.util.Locale.getDefault());
        invalidate();
    }

    Bitmap logo() {
        return logo;
    }

    /** Where it goes with the page at rest: {@code left} edge and {@code bottom} (see the field). */
    void place(float left, float bottom) {
        if (this.left == left && this.bottom == bottom) return;
        this.left = left;
        this.bottom = bottom;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        if (w == 0) return;
        if (mode == COVER) {
            drawCover(canvas, w);
        } else {
            drawContent(canvas, w);
        }
    }

    private void drawContent(Canvas canvas, int w) {
        float y = bottom;
        float maxW = Math.min(w * 0.36f, w - left - 48 * density);
        if (logo != null) {
            // Title logos are mostly wide wordmarks: as wide as a third of the screen, at most about as tall as two
            // lines of the name, so square ones don't tower.
            float maxH = 92 * density;
            float s = Math.min(maxW / logo.getWidth(), maxH / logo.getHeight());
            float lw = logo.getWidth() * s, lh = logo.getHeight() * s;
            dst.set(left, y - lh, left + lw, y);
            canvas.drawBitmap(logo, null, dst, logoPaint);
            y -= lh;
        } else if (title != null && !title.isEmpty()) {
            // A long title (a video's) steps down a size before it's cut short.
            float room = Math.min(w * 0.7f, w - left - 48 * density);
            titlePaint.setTextSize(titleSize);
            if (titlePaint.measureText(title) > room) titlePaint.setTextSize(titleSize * 0.72f);
            CharSequence t = TextUtils.ellipsize(title, titlePaint, room, TextUtils.TruncateAt.END);
            Paint.FontMetrics fm = titlePaint.getFontMetrics();
            canvas.drawText(t, 0, t.length(), left, y - fm.descent, titlePaint);
            y -= fm.descent - fm.ascent;
        }
        if (caption != null && !caption.isEmpty()) {
            CharSequence c = TextUtils.ellipsize(caption, captionPaint, maxW, TextUtils.TruncateAt.END);
            canvas.drawText(c, 0, c.length(), left, y - 12 * density, captionPaint);
        }
    }

    private void drawCover(Canvas canvas, int w) {
        if (logo == null) return;
        // Centred in the space above the dock, a little above its middle, as tvOS places YouTube's.
        float maxW = w * 0.3f, maxH = Math.max(1f, bottom * 0.3f);
        float s = Math.min(Math.min(maxW / logo.getWidth(), maxH / logo.getHeight()), 2.2f * density / 2f);
        float lw = logo.getWidth() * s, lh = logo.getHeight() * s;
        float cy = bottom * 0.46f;
        dst.set((w - lw) / 2f, cy - lh / 2f, (w + lw) / 2f, cy + lh / 2f);
        canvas.drawBitmap(logo, null, dst, logoPaint);
    }
}
