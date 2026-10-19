package com.gathin.tvhome;

import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.AnimationSet;
import android.view.animation.AnimationUtils;

import java.util.HashMap;
import java.util.Map;

/**
 * Opening an app and coming back to Home, after tvOS 26 (measured from Apple TV 4K screen recordings). tvOS moves
 * as if the camera flies through Home into the app and back out:
 * - opening: Home grows toward you (to about 1.15x in 180 ms) and fades out in about 150 ms, while the app comes
 *   from behind, growing from about 0.8x as it fades in, centred on the screen rather than out of the tile;
 * - coming back: the app shrinks away and fades, and Home falls back into place from about 1.3x while it fades in
 *   (in about 200 ms; the size settles over about 800 ms), with focus still on the app you opened.
 *
 * Android only lets an ordinary app animate its own windows; the system animates other apps' windows. For a
 * launch, {@link ActivityOptions#makeScaleUpAnimation} is the one option it still honours between tasks (Android 11
 * dropped custom animations there, see DefaultTransitionHandler), so the app's window grows from a rect we choose
 * while fading in, and our window stays as it is, for {@link HomeMotion} to push Home away inside it. Remote
 * animations, which let Google's launcher animate both windows, need a permission only system apps get.
 */
final class Transitions {

    /** The app's window grows from this size, centred, as it fades in (the system's 336 ms scale-up). */
    static final float APP_FROM = 0.86f;
    /**
     * Home's window when HomeButtonService brings it back: full size, so the system only fades it in (a scale-up
     * from the whole screen) while Home falls back into place inside it ({@link HomeMotion#land}). The system's
     * scale-up lasts a fixed 336 ms, too short for tvOS's 800 ms fall.
     */
    static final float HOME_FROM = 1f;
    /** In HomeButtonService's intent to show Home: how the system shows Home's window, {@link #SHOW_FADE} or {@link #SHOW_INSTANT}. */
    static final String EXTRA_SHOW = "com.gathin.tvhome.SHOW";
    /** How Home came back. Back from an app: no intent; the system closes the app's task (or sends it back). */
    static final int SHOW_BACK = 0;
    /** A Home press: the system fades Home's window in (see {@link #homeOptions}) and Home falls back inside it. */
    static final int SHOW_FADE = 1;
    /** Covering the stock home: shown at once, and Home doesn't move either. */
    static final int SHOW_INSTANT = 2;
    /** Any other intent, e.g. the system's own Home where this launcher is the home app, or `am start`. */
    static final int SHOW_INTENT = 3;

    private static final Map<ComponentName, Boolean> seeThrough = new HashMap<>();
    /** See {@link #systemFadeMs}: [Back, an intent]; null until {@link #prepare} has read them. */
    private static volatile long[] fades;

    private Transitions() {
    }

    /** {@code scale} times a w x h screen, centred on it, as {left, top, width, height}: where a window scales from. */
    static int[] centred(int w, int h, float scale) {
        int sw = Math.round(w * scale), sh = Math.round(h * scale);
        return new int[]{(w - sw) / 2, (h - sh) / 2, sw, sh};
    }

    private static ActivityOptions scaleUp(View source, int w, int h, float scale) {
        int[] r = centred(w, h, scale);
        return ActivityOptions.makeScaleUpAnimation(source, r[0], r[1], r[2], r[3]);
    }

    /**
     * Opening an app: its window comes from behind Home, growing from {@link #APP_FROM} as it fades in. The rect is
     * placed from the window's own root, which never scales: Home's root may still be landing.
     */
    static Bundle launchOptions(View root) {
        return scaleUp(root.getRootView(), root.getWidth(), root.getHeight(), APP_FROM).toBundle();
    }

    /** Showing Home from HomeButtonService: Home's window fades in over the app, which stays put underneath. */
    static Bundle homeOptions(Context ctx) {
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        // Not attached to a window, so its place on screen is 0,0: the rect is in screen coordinates.
        return scaleUp(new View(ctx), dm.widthPixels, dm.heightPixels, HOME_FROM).toBundle();
    }

    /**
     * Whether the app's activity lets what's behind show through (e.g. a settings side panel), from its theme, as the
     * system decides it. Home then stays as it is instead of moving away. A trampoline with no display is not.
     * Cached per activity; the first time costs about a millisecond (the app's resources are loaded already).
     */
    static boolean seeThrough(Context ctx, ComponentName c) {
        if (c == null) return false;
        Boolean known = seeThrough.get(c);
        if (known != null) return known;
        boolean see = false;
        try {
            PackageManager pm = ctx.getPackageManager();
            ActivityInfo ai = pm.getActivityInfo(c, 0);
            int theme = ai.getThemeResource();
            if (theme != 0) {
                Resources.Theme t = pm.getResourcesForApplication(ai.applicationInfo).newTheme();
                t.applyStyle(theme, true);
                // Sorted by id, as obtainStyledAttributes needs.
                TypedArray a = t.obtainStyledAttributes(new int[]{
                        android.R.attr.windowIsFloating, android.R.attr.windowIsTranslucent,
                        android.R.attr.windowNoDisplay});
                try {
                    see = (a.getBoolean(0, false) || a.getBoolean(1, false)) && !a.getBoolean(2, false);
                } finally {
                    a.recycle();
                }
            }
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            see = false;
        }
        seeThrough.put(c, see);
        return see;
    }

    /**
     * How long the system fades Home's window in when Home comes back on its own (see SHOW_*), or 0 when it does
     * more than fade it. Back from an app uses the task close and to-back animations, an intent the task open and
     * to-front ones. Google TV only fades on Back (atv_fade_out_bottom: 400 ms, slow at first); stock Android slides
     * windows sideways. Home can fall back into place inside a fading window, not inside a sliding one.
     */
    static long systemFadeMs(int showMode) {
        long[] f = fades;
        if (f == null) return 0;
        return showMode == SHOW_BACK ? f[0] : showMode == SHOW_INTENT ? f[1] : 0;
    }

    /** Reads {@link #systemFadeMs} in the background (about 20 ms on a TV: it loads the system's animations). */
    static void prepare(Context app) {
        if (fades != null) return;
        Thread t = new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            readFades(app);
        }, "tvhome-transitions");
        t.setDaemon(true);
        t.start();
    }

    private static void readFades(Context ctx) {
        long t0 = Perf.now();
        long[] f = {0, 0};
        StringBuilder seen = new StringBuilder();
        try {
            Context system = new ContextThemeWrapper(ctx, android.R.style.Theme_DeviceDefault);
            TypedArray w = system.obtainStyledAttributes(new int[]{android.R.attr.windowAnimationStyle});
            int style = w.getResourceId(0, 0);
            w.recycle();
            if (style != 0) {
                int close = anim(system, style, android.R.attr.taskCloseEnterAnimation);
                int toBack = anim(system, style, android.R.attr.taskToBackEnterAnimation);
                int open = anim(system, style, android.R.attr.taskOpenEnterAnimation);
                int toFront = anim(system, style, android.R.attr.taskToFrontEnterAnimation);
                f[0] = Math.min(fadeMs(ctx, close), fadeMs(ctx, toBack));
                f[1] = Math.min(fadeMs(ctx, open), fadeMs(ctx, toFront));
                if (Perf.enabled()) {
                    seen.append(" close=").append(describe(ctx, close)).append(" to_back=").append(describe(ctx, toBack))
                            .append(" open=").append(describe(ctx, open)).append(" to_front=").append(describe(ctx, toFront));
                }
            }
        } catch (RuntimeException e) {
            f = new long[]{0, 0};
        }
        fades = f;
        Perf.log("system_transitions", t0, "back_fade_ms=" + f[0] + " intent_fade_ms=" + f[1] + seen);
    }

    private static int anim(Context ctx, int style, int attr) {
        TypedArray a = ctx.obtainStyledAttributes(style, new int[]{attr});
        try {
            return a.getResourceId(0, 0);
        } finally {
            a.recycle();
        }
    }

    /** For the perf log: an animation resource's name and what it animates, e.g. task_close_enter[Alpha]. */
    private static String describe(Context ctx, int animRes) {
        if (animRes == 0) return "none";
        try {
            Animation a = AnimationUtils.loadAnimation(ctx, animRes);
            return ctx.getResources().getResourceEntryName(animRes) + kinds(a) + a.computeDurationHint() + "ms";
        } catch (RuntimeException e) {
            return animRes + "?";
        }
    }

    private static String kinds(Animation a) {
        if (!(a instanceof AnimationSet)) return "[" + a.getClass().getSimpleName().replace("Animation", "") + "]";
        StringBuilder sb = new StringBuilder("[");
        for (Animation child : ((AnimationSet) a).getAnimations()) sb.append(kinds(child));
        return sb.append(']').toString();
    }

    /** The animation's length if it only fades, else 0. */
    private static long fadeMs(Context ctx, int animRes) {
        if (animRes == 0) return 0;
        Animation a = AnimationUtils.loadAnimation(ctx, animRes);
        return onlyFades(a) ? Math.max(1, a.computeDurationHint()) : 0;
    }

    static boolean onlyFades(Animation a) {
        if (a instanceof AlphaAnimation) return true;
        if (!(a instanceof AnimationSet)) return false;
        for (Animation child : ((AnimationSet) a).getAnimations()) {
            if (!onlyFades(child)) return false;
        }
        return !((AnimationSet) a).getAnimations().isEmpty();
    }
}
