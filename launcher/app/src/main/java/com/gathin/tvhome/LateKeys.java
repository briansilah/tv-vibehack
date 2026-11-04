package com.gathin.tvhome;

import android.view.KeyEvent;

/**
 * Presses Home couldn't take when they were made, which reach it together later: right after the TV wakes or boots,
 * Android holds keys until Home's window has focus (up to 5 s), then delivers them all at once, and focus would jump
 * across several apps the user never saw it pass. Home takes the first held press, as the user saw nothing happen
 * after it, and drops every press made before it took that one; a late Select is never taken, so nothing opens that
 * wasn't on screen when it was pressed. Presses made after Home took the first always go through. Plain Java, so it
 * runs in JVM unit tests. Main thread.
 */
final class LateKeys {

    /**
     * A press this old when it reaches Home was held up. A remote's key takes about 3 ms on a TV, a frame 7 ms; one
     * held while Home's window comes up, hundreds of milliseconds to seconds.
     */
    static final long LATE_MS = 250L;
    private static final int REMEMBERED = 16;

    /** When Home last took a late move: presses made before then were held behind it, and go. */
    private long burstAt = Long.MIN_VALUE;
    /** The down times of presses dropped, so their repeats and releases go too. */
    private final long[] dropped = new long[REMEMBERED];
    private int next;
    private int count;

    /**
     * Whether to drop this key event. {@code now} and the event's times are {@code SystemClock.uptimeMillis()}'s, as
     * KeyEvent's. Only presses count: a held key's repeats and its release go with the press.
     */
    boolean drop(int action, int keyCode, int repeat, long eventTime, long downTime, long now) {
        if (!navigation(keyCode)) return false;
        if (wasDropped(downTime)) return true;
        if (action != KeyEvent.ACTION_DOWN || repeat > 0) return false;
        boolean late = now - eventTime > LATE_MS;
        if (eventTime >= burstAt && !(late && select(keyCode))) {
            if (late) burstAt = now;
            return false;
        }
        remember(downTime);
        return true;
    }

    static boolean navigation(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return true;
            default:
                return select(keyCode);
        }
    }

    static boolean select(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER;
    }

    private boolean wasDropped(long downTime) {
        for (int i = 0; i < count; i++) {
            if (dropped[i] == downTime) return true;
        }
        return false;
    }

    private void remember(long downTime) {
        dropped[next] = downTime;
        next = (next + 1) % REMEMBERED;
        count = Math.min(count + 1, REMEMBERED);
    }
}
