package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * Marks the app being moved, as Google TV does: a ring around the tile, with a gap, and a chevron on each side
 * it can still move to. Sits centred over the tile's card, {@link #MARGIN_DP} larger on every side, and is
 * scaled with it.
 */
public class MoveMarks extends View {

    /** Room around the card for the ring and the chevrons. */
    static final float MARGIN_DP = 40f;
    private static final float RING_GAP_DP = 5f;
    private static final float RING_DP = 3f;
    private static final float CARD_RADIUS_DP = 16f;
    /** Each chevron sits on a dark disc so it reads over any neighbouring artwork, this far out from the ring. */
    private static final float DISC_RADIUS_DP = 13f;
    private static final float DISC_GAP_DP = 4f;
    /** Chevron: half its height, and how far its point stands out. */
    private static final float CHEVRON_HALF_DP = 5.5f;
    private static final float CHEVRON_DEPTH_DP = 4f;

    static final int LEFT = 1, RIGHT = 2, UP = 4, DOWN = 8;

    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint chevron = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint disc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint discEdge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path path = new Path();
    private final float density;
    private int directions;

    public MoveMarks(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(RING_DP * density);
        ring.setColor(0xF2FFFFFF);
        chevron.setStyle(Paint.Style.STROKE);
        chevron.setStrokeWidth(2.6f * density);
        chevron.setStrokeCap(Paint.Cap.ROUND);
        chevron.setStrokeJoin(Paint.Join.ROUND);
        chevron.setColor(0xF2FFFFFF);
        disc.setColor(0xE6141416);
        discEdge.setStyle(Paint.Style.STROKE);
        discEdge.setStrokeWidth(density);
        discEdge.setColor(0x47FFFFFF);
        setWillNotDraw(false);
        setAlpha(0f);
    }

    /** Which chevrons to show: a mask of {@link #LEFT}, {@link #RIGHT}, {@link #UP} and {@link #DOWN}. */
    void setDirections(int mask) {
        if (mask == directions) return;
        directions = mask;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float m = MARGIN_DP * density;
        float gap = (RING_GAP_DP + RING_DP / 2f) * density;
        rect.set(m - gap, m - gap, getWidth() - m + gap, getHeight() - m + gap);
        float r = CARD_RADIUS_DP * density + gap;
        c.drawRoundRect(rect, r, r, ring);

        // Each chevron on its disc, just outside the ring.
        float rd = DISC_RADIUS_DP * density;
        float out = RING_DP * density / 2f + DISC_GAP_DP * density + rd;
        float half = CHEVRON_HALF_DP * density, depth = CHEVRON_DEPTH_DP * density;
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        if ((directions & LEFT) != 0) drawDisc(c, rect.left - out, cy, rd);
        if ((directions & RIGHT) != 0) drawDisc(c, rect.right + out, cy, rd);
        if ((directions & UP) != 0) drawDisc(c, cx, rect.top - out, rd);
        if ((directions & DOWN) != 0) drawDisc(c, cx, rect.bottom + out, rd);
        path.reset();
        if ((directions & LEFT) != 0) {
            float x = rect.left - out;
            path.moveTo(x + depth / 2f, cy - half);
            path.lineTo(x - depth / 2f, cy);
            path.lineTo(x + depth / 2f, cy + half);
        }
        if ((directions & RIGHT) != 0) {
            float x = rect.right + out;
            path.moveTo(x - depth / 2f, cy - half);
            path.lineTo(x + depth / 2f, cy);
            path.lineTo(x - depth / 2f, cy + half);
        }
        if ((directions & UP) != 0) {
            float y = rect.top - out;
            path.moveTo(cx - half, y + depth / 2f);
            path.lineTo(cx, y - depth / 2f);
            path.lineTo(cx + half, y + depth / 2f);
        }
        if ((directions & DOWN) != 0) {
            float y = rect.bottom + out;
            path.moveTo(cx - half, y - depth / 2f);
            path.lineTo(cx, y + depth / 2f);
            path.lineTo(cx + half, y - depth / 2f);
        }
        c.drawPath(path, chevron);
    }

    private void drawDisc(Canvas c, float x, float y, float r) {
        c.drawCircle(x, y, r, disc);
        c.drawCircle(x, y, r, discEdge);
    }
}
