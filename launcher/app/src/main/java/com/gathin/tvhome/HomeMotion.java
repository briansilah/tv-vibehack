package com.gathin.tvhome;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.view.View;
import android.view.animation.PathInterpolator;

/**
 * Home's own part of opening an app and coming back (see {@link Transitions}), animated on the whole screen's
 * root view: scale and alpha only, drawn from a hardware layer, so each frame just redraws one texture.
 */
final class HomeMotion {

    /** Opening an app: Home grows to this as it fades, like tvOS's (1.0 to 1.15 in 180 ms, still growing). */
    static final float PUSH_SCALE = 1.2f;
    static final long PUSH_MS = 200L;
    /**
     * Opacity during the push: tvOS's fades fast at first, half gone in about 35 ms and nearly all in 120 ms:
     * (1 - t)^3.5 over {@link #PUSH_MS}.
     */
    static final double PUSH_FADE_POWER = 3.5;
    /**
     * Coming back to Home (Home or Back from an app), Home falls back into place from this size while the system
     * fades its window in, as tvOS's does: from about 1.3x, as a critically damped spring of {@link #LAND_OMEGA}.
     */
    static final float LAND_FROM = 1.3f;
    /**
     * Fitted to the Apple TV recording (Home's size frame by frame): half-way after 200 ms, 90 % after 470 ms,
     * settled after about 800 ms. tvOS's Home is fully shown after about 200 ms, and keeps settling after that.
     */
    static final double LAND_OMEGA = 8.25;
    static final long LAND_MS = 900L;
    /** Coming back from a push while still on screen: fully shown again in this long. */
    private static final long LAND_FADE_MS = 200L;
    private static final TimeInterpolator FADE_IN = new PathInterpolator(0f, 0f, 0.2f, 1f);   // linear_out_slow_in

    private final View root;
    private ValueAnimator anim;
    /** Pushed away for an app, or on its way back. */
    private boolean away;

    HomeMotion(View root) {
        this.root = root;
    }

    boolean isAway() {
        return away;
    }

    /** Opening an app: Home grows toward you and fades out. */
    void push() {
        away = true;
        final float s0 = root.getScaleX();
        final float a0 = root.getAlpha();
        run(PUSH_MS, t -> {
            float s = s0 + (PUSH_SCALE - s0) * t;
            root.setScaleX(s);
            root.setScaleY(s);
            root.setAlpha(a0 * (float) Math.pow(1f - t, PUSH_FADE_POWER));
        });
    }

    /**
     * Home comes back from a push, still on screen: the app didn't cover it after all, or you came back before it
     * did. It falls back from where it is and fades back in itself.
     */
    void fallBack() {
        land(root.getScaleX(), root.getAlpha());
    }

    /**
     * Home shows again after an app, inside a window the system fades in (see MainActivity#comeBackFromApp): it
     * falls back into place from {@link #LAND_FROM}.
     */
    void land() {
        land(LAND_FROM, 1f);
    }

    private void land(final float s0, final float a0) {
        away = false;
        final double end = settle(LAND_MS / 1000.0);
        run(LAND_MS, t -> {
            float s = s0 + (1f - s0) * (float) (settle(t * LAND_MS / 1000.0) / end);
            root.setScaleX(s);
            root.setScaleY(s);
            root.setAlpha(a0 + (1f - a0) * FADE_IN.getInterpolation(Math.min(1f, t * LAND_MS / (float) LAND_FADE_MS)));
        });
    }

    /** A critically damped spring released from rest: how much of the way it has gone after {@code seconds}. */
    static double settle(double seconds) {
        double x = LAND_OMEGA * seconds;
        return 1.0 - (1.0 + x) * Math.exp(-x);
    }

    /** Straight back to rest, e.g. while Home can't be seen. */
    void reset() {
        if (anim != null) anim.cancel();
        anim = null;
        away = false;
        root.setScaleX(1f);
        root.setScaleY(1f);
        root.setAlpha(1f);
        root.setLayerType(View.LAYER_TYPE_NONE, null);
    }

    private interface Frame {
        void at(float t);
    }

    private void run(long ms, Frame frame) {
        if (anim != null) anim.cancel();
        root.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        final ValueAnimator a = ValueAnimator.ofFloat(0f, 1f).setDuration(ms);
        a.setInterpolator(null);   // linear time; each property shapes its own curve
        a.addUpdateListener(v -> frame.at(v.getAnimatedFraction()));
        a.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (anim != a) return;
                anim = null;
                root.setLayerType(View.LAYER_TYPE_NONE, null);
            }
        });
        anim = a;
        frame.at(0f);
        a.start();
    }
}
