package com.gathin.tvhome;

import android.animation.ValueAnimator;

import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.FloatPropertyCompat;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

/**
 * The top shelf full screen, as on tvOS since tvOS 13: Up from a dock app showing a suggested title sends the dock,
 * and the grid under it, down off the screen, so the title's picture has the whole screen, and the title and its
 * button move down into the space the dock left. Down or Back brings the dock back. The time and profile picture in
 * the top right stay.
 *
 * <p>The motion is fitted to Apple's own capture of it (WWDC19 session 211, "Mastering the Living Room With tvOS",
 * at 5:31, frame by frame): the dock sets off from rest on a critically damped spring of 20 rad/s and comes to rest
 * 60 px (of 1080) below the screen, out of sight about 170 ms after it starts. The same spring brings it back, and a
 * press while it moves turns it round with its speed kept. With Android's Remove animations it goes straight to the
 * end. This only says how far along it is: {@link Listener} moves things, by translation, without layout.
 */
final class ShelfFullScreen {

    /** ω² for ω = 20 rad/s: tvOS's dock, fitted to within a pixel at 720p. */
    static final float STIFFNESS = 400f;
    static final float DAMPING = SpringForce.DAMPING_RATIO_NO_BOUNCY;
    /**
     * tvOS's safe area, 60 of 1080 px, as a part of the screen's height: where the dock comes to rest below the
     * screen's bottom edge, and the bottom of the title's button above it.
     */
    static final float SAFE = 60f / 1080f;

    interface Listener {
        /**
         * {@code fraction}: how far the dock has gone, 0 in place, 1 away; {@code full}: where it's going. Each frame,
         * and at once when either changes.
         */
        void onFullScreen(float fraction, boolean full);
    }

    private static final FloatPropertyCompat<ShelfFullScreen> FRACTION = new FloatPropertyCompat<>("fullScreen") {
        @Override
        public float getValue(ShelfFullScreen s) {
            return s.fraction;
        }

        @Override
        public void setValue(ShelfFullScreen s, float value) {
            s.fraction = value;
            s.listener.onFullScreen(value, s.full);
        }
    };

    private final Listener listener;
    private final SpringAnimation spring;
    private float fraction;
    private boolean full;

    ShelfFullScreen(Listener listener) {
        this.listener = listener;
        spring = new SpringAnimation(this, FRACTION, 0f);
        spring.getSpring().setStiffness(STIFFNESS).setDampingRatio(DAMPING);
        spring.setMinimumVisibleChange(DynamicAnimation.MIN_VISIBLE_CHANGE_SCALE);
    }

    boolean isFull() {
        return full;
    }

    /** Full screen ({@code on}), or the dock back. The listener hears of it at once, before anything moves. */
    void set(boolean on) {
        if (full == on) return;
        // DynamicAnimation 1.0 does not honor the system's Remove animations setting.
        if (!ValueAnimator.areAnimatorsEnabled()) {
            snap(on);
            return;
        }
        full = on;
        listener.onFullScreen(fraction, full);
        spring.animateToFinalPosition(on ? 1f : 0f);
    }

    /** Straight there, without the motion: for when Home can't be seen. */
    void snap(boolean on) {
        full = on;
        spring.cancel();
        // A cancelled spring keeps its speed for the next run.
        spring.setStartVelocity(0f);
        fraction = on ? 1f : 0f;
        listener.onFullScreen(fraction, full);
    }

    /** How far along the spring is (0 to 1), {@code seconds} after it set off from rest. */
    static float position(float seconds) {
        double x = Math.sqrt(STIFFNESS) * seconds;
        return (float) (1 - (1 + x) * Math.exp(-x));
    }

    /** How far the dock goes, in pixels: its panel's top from {@code panelTop} to {@link #SAFE} below the screen. */
    static float away(float screenHeight, float panelTop) {
        return Math.max(0f, screenHeight - panelTop) + SAFE * screenHeight;
    }

    /** How far the title and its button go down, in pixels: the button's bottom from {@code bottom} to the safe area. */
    static float drop(float screenHeight, float bottom) {
        return Math.max(0f, screenHeight * (1f - SAFE) - bottom);
    }
}
