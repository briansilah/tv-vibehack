package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/**
 * A soft shade in the top right corner of the top shelf, so the time and the picture stay legible over bright art:
 * darkest in the corner itself and easing out over a wide ellipse, like a photograph's vignetting, with no edge
 * anywhere and no blur, which over sharp art would look like a fault in the screen. Over dark art it barely shows.
 * The last view in the backdrop, so the capsule's glass sees the shaded art; it fades with the top right as the page
 * scrolls (MainActivity.followPage). One gradient, drawn once.
 */
public class CornerShade extends View {

    /** The shade's centre, from the screen's top right corner: just off the capsule's right end. */
    static final float CENTER_FROM_RIGHT_DP = 40f;
    static final float CENTER_FROM_TOP_DP = 0f;
    /** Its reach: past Control Center's row when it opens, well short of the top shelf's centred logo. */
    static final float RADIUS_X_DP = 560f;
    static final float RADIUS_Y_DP = 260f;
    /** At its darkest: 25 % black. Most of the work is the capsule's own glass, which dims bright art inside it. */
    static final int ALPHA = 0x40;
    /** An eased fall-off (a smoothstep), so no ring shows where the shade ends. */
    private static final float[] STOPS = {0f, 0.2f, 0.45f, 0.7f, 0.9f, 1f};
    private static final float[] LEVELS = {1f, 0.9f, 0.62f, 0.3f, 0.08f, 0f};

    private final float density;
    private final Paint paint = new Paint();
    private final Matrix matrix = new Matrix();
    private int shadedWidth = -1;

    public CornerShade(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        setWillNotDraw(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        int[] colors = new int[STOPS.length];
        for (int i = 0; i < colors.length; i++) colors[i] = Math.round(ALPHA * LEVELS[i]) << 24;
        paint.setShader(new RadialGradient(0f, 0f, 1f, colors, STOPS, Shader.TileMode.CLAMP));
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        float cx = w - CENTER_FROM_RIGHT_DP * density, cy = CENTER_FROM_TOP_DP * density;
        float rx = RADIUS_X_DP * density, ry = RADIUS_Y_DP * density;
        if (w != shadedWidth) {
            shadedWidth = w;
            matrix.setScale(rx, ry);
            matrix.postTranslate(cx, cy);
            paint.getShader().setLocalMatrix(matrix);
        }
        canvas.drawRect(Math.max(0f, cx - rx), 0f, w, Math.min(getHeight(), cy + ry), paint);
    }
}
