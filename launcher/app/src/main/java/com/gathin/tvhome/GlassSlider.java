package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.SeekBar;

/**
 * Apple's Liquid Glass slider (Settings on tvOS and iOS 26), for the remote: Left and Right move it in steps of 5
 * between 0 and 100, OK or Back closes it. On the left an outlined pair of panes (clear), on the right a filled pair
 * (tinted); the track fills blue up to a white thumb, with marks under it at Clear and Tinted
 * ({@link LiquidGlass.Look}). Accessibility uses a native SeekBar range and the same five-point steps; activating
 * the control closes it, just like OK. Its drawing is independent of those semantics.
 */
public class GlassSlider extends View {

    interface Listener {
        void onGlassChanged(int value);

        void onGlassDone();
    }

    static final int STEP = 5;
    private static final int TRACK = 0xFF3A3A3C;
    private static final int FILL = 0xFF3478F6;
    private static final int ICON = 0xB3FFFFFF;

    private final float density;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private int value = LiquidGlass.Look.CLEAR;
    private Listener listener;

    public GlassSlider(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        setFocusable(true);
        setClickable(true);
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    void setValue(int value) {
        int next = clamp(value);
        if (next == this.value) return;
        this.value = next;
        invalidate();
        sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SELECTED);
    }

    int getValue() {
        return value;
    }

    /** The next value one press away, Left ({@code dir} -1) or Right (+1). */
    static int step(int value, int dir) {
        return clamp(value + dir * STEP);
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(100, v));
    }

    private boolean changeValue(int next) {
        next = clamp(next);
        if (next == value) return false;
        setValue(next);
        if (listener != null) listener.onGlassChanged(next);
        return true;
    }

    @Override
    public CharSequence getAccessibilityClassName() {
        return SeekBar.class.getName();
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setRangeInfo(AccessibilityNodeInfo.RangeInfo.obtain(
                AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT, 0, 100, value));
        info.setScrollable(isEnabled());
        if (isEnabled()) {
            if (value > 0) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
            if (value < 100) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS);
        }
    }

    @Override
    public void onInitializeAccessibilityEvent(AccessibilityEvent event) {
        super.onInitializeAccessibilityEvent(event);
        event.setItemCount(101);
        event.setCurrentItemIndex(value);
    }

    @Override
    public boolean performAccessibilityAction(int action, Bundle arguments) {
        if (!isEnabled()) return false;
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) return changeValue(step(value, 1));
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) return changeValue(step(value, -1));
        if (action == AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId()) {
            if (arguments == null || !arguments.containsKey(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE)) {
                return false;
            }
            float requested = arguments.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE);
            if (!Float.isFinite(requested)) return false;
            return changeValue(Math.round(requested));
        }
        return super.performAccessibilityAction(action, arguments);
    }

    @Override
    public boolean performClick() {
        if (!isEnabled()) return false;
        super.performClick();
        if (listener != null) listener.onGlassDone();
        return true;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                changeValue(step(value, keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1));
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return true;   // stay on the slider until it's closed
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BACK:
                return true;   // closes on release, so the release doesn't reach the tile focus goes back to
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                performClick();
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (listener != null) listener.onGlassDone();
                return true;
            default:
                return super.onKeyUp(keyCode, event);
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        float h = getHeight(), cy = h / 2f;
        float icon = 22 * density, gap = 14 * density;
        float trackLeft = icon + gap, trackRight = getWidth() - icon - gap;
        float trackH = 6 * density, thumbR = 11 * density;

        drawPanes(c, 0f, cy, icon, false);
        drawPanes(c, getWidth() - icon, cy, icon, true);

        paint.setStyle(Paint.Style.FILL);
        float x = trackLeft + (trackRight - trackLeft) * value / 100f;
        rect.set(trackLeft, cy - trackH / 2f, trackRight, cy + trackH / 2f);
        paint.setColor(TRACK);
        c.drawRoundRect(rect, trackH / 2f, trackH / 2f, paint);
        rect.right = x;
        paint.setColor(FILL);
        c.drawRoundRect(rect, trackH / 2f, trackH / 2f, paint);

        paint.setColor(0x66FFFFFF);
        float dotY = cy + thumbR + 5 * density, dotR = 1.5f * density;
        for (int mark : new int[]{LiquidGlass.Look.CLEAR, LiquidGlass.Look.TINTED}) {
            c.drawCircle(trackLeft + (trackRight - trackLeft) * mark / 100f, dotY, dotR, paint);
        }

        paint.setColor(isFocused() ? 0xFFFFFFFF : 0xFFD8D8DC);
        paint.setShadowLayer(3 * density, 0f, density, 0x66000000);
        c.drawCircle(x, cy, thumbR, paint);
        paint.clearShadowLayer();
    }

    /** Two overlapping rounded panes, as Apple's icons: outlined (clear) or filled (tinted). */
    private void drawPanes(Canvas c, float left, float cy, float size, boolean filled) {
        float w = size * 0.72f, hh = size * 0.56f, r = 4 * density, d = size - w;
        paint.setColor(ICON);
        paint.setStrokeWidth(1.5f * density);
        // The back pane, up and left.
        rect.set(left, cy - hh / 2f - d / 2f, left + w, cy + hh / 2f - d / 2f);
        paint.setStyle(Paint.Style.STROKE);
        c.drawRoundRect(rect, r, r, paint);
        // The front pane, down and right.
        rect.offset(d, d);
        if (filled) {
            paint.setStyle(Paint.Style.FILL);
            c.drawRoundRect(rect, r, r, paint);
        } else {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFF2A2A2E);
            c.drawRoundRect(rect, r, r, paint);
            paint.setColor(ICON);
            paint.setStyle(Paint.Style.STROKE);
            c.drawRoundRect(rect, r, r, paint);
        }
    }

    @Override
    protected void onFocusChanged(boolean gainFocus, int direction, android.graphics.Rect previouslyFocusedRect) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect);
        invalidate();
    }
}
