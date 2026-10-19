package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.PathInterpolator;

/**
 * The top shelf's picture, full screen behind the dock as on tvOS: suggested content's backdrop or an app's cover
 * art, cross-faded when it changes, under a scrim that darkens the bottom (where the dock and the title sit) and,
 * for content, the left (where the title's logo is). Draws its two pictures itself, scaled to fill: changing
 * picture only redraws this view, it never lays out the screen. It sits in the backdrop the dock's glass shows.
 */
public class TopShelfView extends View {

    private final Layer[] layers = {new Layer(), new Layer()};
    /** The layer drawn on top, fading in; -1 before the first picture. */
    private int front = -1;
    private final Paint scrimBottom = new Paint();
    private final Paint scrimLeft = new Paint();
    private final Matrix matrix = new Matrix();
    private float scrim;
    private float scrimFrom;
    private float scrimTo;
    private ValueAnimator fade;
    private Runnable fadeEnd;
    private final DecelerateInterpolator decelerate = new DecelerateInterpolator(1.5f);
    /** The carousel's push: eases in and out, like tvOS's paging. */
    private final PathInterpolator push = new PathInterpolator(0.45f, 0f, 0.15f, 1f);

    private static final class Layer {
        Bitmap bitmap;
        Object tag;
        final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        float alpha;
        float from;
        /** Horizontal offset, in widths of the view: 1 is just off the right edge. */
        float offset;
    }

    public TopShelfView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setWillNotDraw(false);
    }

    /** The picture showing, or fading in (null for none). */
    Object currentTag() {
        return front >= 0 && layers[front].bitmap != null ? layers[front].tag : null;
    }

    /** Whether a picture fills the screen, fully faded in: what's under this view can't be seen. */
    boolean covered() {
        return front >= 0 && layers[front].bitmap != null && layers[front].alpha >= 1f;
    }

    /**
     * Cross-fades to {@code bitmap} (null: fades the picture out, so whatever is behind shows) over {@code ms},
     * the scrim to {@code scrimAlpha} (0..1). {@code end} runs once it has finished, or straight away if it already
     * shows that; a later call drops an earlier call's {@code end} without running it.
     */
    void show(Bitmap bitmap, Object tag, float scrimAlpha, long ms, Runnable end) {
        if (fade != null) {
            fadeEnd = null;
            fade.cancel();
        }
        settleOffsets();
        Layer top = front >= 0 ? layers[front] : null;
        if (bitmap != null && top != null && top.bitmap == bitmap) {
            if (top.alpha >= 1f && scrim == scrimAlpha) {
                if (end != null) end.run();
                return;
            }
        } else if (bitmap != null) {
            // A fade cut short: the picture fading in becomes the one underneath, as it is now, but whole, so the
            // new one doesn't fade in over nothing.
            if (top != null && top.bitmap != null) {
                top.alpha = 1f;
                Layer under = layers[1 - front];
                under.bitmap = null;
                under.tag = null;
                under.alpha = 0f;
            }
            front = front < 0 ? 0 : 1 - front;
            Layer next = layers[front];
            next.bitmap = bitmap;
            next.tag = tag;
            next.alpha = 0f;
        }
        for (Layer l : layers) l.from = l.alpha;
        scrimFrom = scrim;
        scrimTo = scrimAlpha;
        fadeEnd = end;
        final boolean out = bitmap == null;
        fade = ValueAnimator.ofFloat(0f, 1f);
        fade.setDuration(Math.max(0, ms));
        fade.setInterpolator(decelerate);
        fade.addUpdateListener(a -> {
            float t = a.getAnimatedFraction();
            for (int i = 0; i < 2; i++) {
                Layer l = layers[i];
                float target = out ? 0f : i == front ? 1f : l.from;
                l.alpha = l.from + (target - l.from) * t;
            }
            scrim = scrimFrom + (scrimTo - scrimFrom) * t;
            if (t >= 1f) settle(out);
            invalidate();
        });
        fade.start();
    }

    /**
     * The carousel's next title: it pushes in from the right while the one showing goes out to the left, as tvOS
     * pages through its Top Shelf. Falls back to a cross-fade if nothing is showing.
     */
    void slide(Bitmap bitmap, Object tag, long ms, Runnable end) {
        if (front < 0 || layers[front].bitmap == null || bitmap == null) {
            show(bitmap, tag, scrim, ms, end);
            return;
        }
        if (fade != null) {
            fadeEnd = null;
            fade.cancel();
        }
        settleOffsets();
        Layer old = layers[front];
        old.alpha = 1f;
        front = 1 - front;
        final Layer next = layers[front];
        next.bitmap = bitmap;
        next.tag = tag;
        next.alpha = 1f;
        next.offset = 1f;
        final Layer gone = old;
        fadeEnd = end;
        fade = ValueAnimator.ofFloat(0f, 1f);
        fade.setDuration(ms);
        fade.setInterpolator(push);
        fade.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            next.offset = 1f - t;
            gone.offset = -t;
            if (a.getAnimatedFraction() >= 1f) {
                next.offset = 0f;
                gone.offset = 0f;
                settle(false);
            }
            invalidate();
        });
        fade.start();
    }

    /** A push cut short: the picture coming in jumps into place, the other goes. */
    private void settleOffsets() {
        if (layers[0].offset == 0f && layers[1].offset == 0f) return;
        for (Layer l : layers) l.offset = 0f;
        if (front >= 0) {
            Layer under = layers[1 - front];
            under.bitmap = null;
            under.tag = null;
            under.alpha = 0f;
        }
    }

    /** At the end of a fade: drops the pictures no longer seen, so their memory can go. */
    private void settle(boolean out) {
        if (out) {
            for (Layer l : layers) {
                l.bitmap = null;
                l.tag = null;
                l.alpha = 0f;
            }
            front = -1;
        } else if (front >= 0) {
            Layer under = layers[1 - front];
            under.bitmap = null;
            under.tag = null;
            under.alpha = 0f;
        }
        Runnable end = fadeEnd;
        fadeEnd = null;
        if (end != null) post(end);
    }

    /** The pictures this view holds, so a memory trim keeps them. */
    void collect(java.util.Collection<Bitmap> into) {
        for (Layer l : layers) {
            if (l.bitmap != null) into.add(l.bitmap);
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        // Darker towards the bottom, where the dock and the title are; on tvOS the art fades into the dock too.
        scrimBottom.setShader(new LinearGradient(0, h * 0.35f, 0, h,
                new int[]{0x00000000, 0x8C000000, 0xD9000000}, new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        // Behind the title, on the left: enough that a title stays readable over a thumbnail's own lettering.
        scrimLeft.setShader(new LinearGradient(0, 0, w * 0.7f, 0,
                new int[]{0xC2000000, 0x80000000, 0x00000000}, new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        boolean any = false;
        for (int pass = 0; pass < 2; pass++) {
            // The one underneath first.
            int i = front < 0 ? pass : pass == 0 ? 1 - front : front;
            Layer l = layers[i];
            if (l.bitmap == null || l.alpha <= 0f) continue;
            any = true;
            float s = Math.max(w / (float) l.bitmap.getWidth(), h / (float) l.bitmap.getHeight());
            if (l.offset <= -1f || l.offset >= 1f) continue;
            matrix.setScale(s, s);
            matrix.postTranslate((w - l.bitmap.getWidth() * s) / 2f + l.offset * w,
                    (h - l.bitmap.getHeight() * s) / 2f);
            l.paint.setAlpha(Math.round(255 * l.alpha));
            canvas.drawBitmap(l.bitmap, matrix, l.paint);
        }
        if (!any || scrim <= 0f) return;
        float a = scrim * Math.max(layers[0].alpha, layers[1].alpha);
        scrimBottom.setAlpha(Math.round(255 * a));
        canvas.drawRect(0, 0, w, h, scrimBottom);
        if (front >= 0 && layers[front].tag instanceof Content) {
            scrimLeft.setAlpha(Math.round(255 * a));
            canvas.drawRect(0, 0, w, h, scrimLeft);
        }
    }

    /** Tag for a suggested title's backdrop, which also gets the left scrim for its title logo. */
    static final class Content {
        final String url;

        Content(String url) {
            this.url = url;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Content && ((Content) o).url.equals(url);
        }

        @Override
        public int hashCode() {
            return url.hashCode();
        }
    }
}
