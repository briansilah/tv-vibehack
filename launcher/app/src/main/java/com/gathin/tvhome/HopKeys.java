package com.gathin.tvhome;

import android.view.KeyEvent;

/**
 * The remote's presses while Google TV's own home is in front on its way to being covered by this launcher (on waking
 * after more than 30 s in standby, and at boot: see {@link HomeButtonService}). They would act on Google TV's home,
 * which is gone a moment later: moving its focus, or opening whatever it had focused. Instead the service takes them,
 * until Home shows or {@link #HOLD_MS} has passed, and the first move is made again in Home, as the user saw nothing
 * happen after it (see {@link LateKeys}). Plain Java, so it runs in JVM unit tests. The service's thread.
 */
final class HopKeys {

    /** Home shows well within this (about half a second on a TCL Google TV); past it keys go through again. */
    static final long HOLD_MS = 2500L;
    private static final int REMEMBERED = 16;

    private long until = Long.MIN_VALUE;
    /** The first move taken, to make again in Home (0: none). */
    private int replay;
    /** The down times of presses taken, so their repeats and releases are taken too. */
    private final long[] taken = new long[REMEMBERED];
    private int next;
    private int count;

    /** Google TV's home came up and this launcher is on its way: keys are taken from now. */
    void covering(long now) {
        until = now + HOLD_MS;
        replay = 0;
    }

    /**
     * Home is in front: keys go to it again. Returns the move to make again there (a D-pad key code), or 0: also when
     * Home took longer than {@link #HOLD_MS}, as presses since have gone where they went.
     */
    int shown(long now) {
        int r = now < until ? replay : 0;
        until = Long.MIN_VALUE;
        replay = 0;
        return r;
    }

    /** Whether the service takes this key event. Times are {@code SystemClock.uptimeMillis()}'s. */
    boolean take(int action, int keyCode, int repeat, long downTime, long now) {
        if (!LateKeys.navigation(keyCode) && keyCode != KeyEvent.KEYCODE_BACK) return false;
        if (wasTaken(downTime)) return true;
        if (now >= until) {
            replay = 0;
            return false;
        }
        if (action != KeyEvent.ACTION_DOWN) return false;
        remember(downTime);
        if (replay == 0 && repeat == 0 && !LateKeys.select(keyCode) && keyCode != KeyEvent.KEYCODE_BACK) {
            replay = keyCode;
        }
        return true;
    }

    private boolean wasTaken(long downTime) {
        for (int i = 0; i < count; i++) {
            if (taken[i] == downTime) return true;
        }
        return false;
    }

    private void remember(long downTime) {
        taken[next] = downTime;
        next = (next + 1) % REMEMBERED;
        count = Math.min(count + 1, REMEMBERED);
    }
}
