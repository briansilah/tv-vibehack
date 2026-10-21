package com.gathin.tvhome;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.ViewConfiguration;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps a held Volume Up or Down changing the volume while {@link HomeButtonService} filters keys.
 *
 * While any accessibility service filters keys, Android's KeyboardInterceptor hands Volume Up and Down to the window
 * manager's policy before the service sees them. On a TV the policy changes the volume by one step and consumes the
 * key, so it never reaches InputDispatcher, which is what repeats a held key: a hold changes the volume by one step,
 * on every Android TV (KeyMapper's issue 2101). The service never sees volume keys, so it can't pass them on.
 *
 * So this repeats the step itself, with InputDispatcher's timing: when a volume key changes the volume, it steps again
 * once the key repeat timeout has passed, then at the key repeat rate, for as long as the input system has had a key
 * down that the service didn't see (a volume key: the service sees every other key) without a break since that
 * change. It looks every repeat interval, from the change on, so a key let go and pressed again is a new press, not a
 * hold. Only `dumpsys input` says which keys are down: there's no API for it, and the volume key's
 * up never reaches an app either. It needs the DUMP permission, which the install script grants. Without it a hold
 * stays one step.
 */
final class VolumeHold {

    private static final String TAG = "TvVolumeHold";
    /** AudioManager's, hidden from the SDK. */
    private static final String VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION";
    private static final String EXTRA_STREAM = "android.media.EXTRA_VOLUME_STREAM_TYPE";
    private static final String EXTRA_ALIAS = "android.media.EXTRA_VOLUME_STREAM_TYPE_ALIAS";
    private static final String EXTRA_VALUE = "android.media.EXTRA_VOLUME_STREAM_VALUE";
    private static final String EXTRA_PREVIOUS = "android.media.EXTRA_PREV_VOLUME_STREAM_VALUE";

    private static final Pattern DEVICE = Pattern.compile("^ {2}Device (-?\\d+): ");
    private static final Pattern KEY_DOWNS = Pattern.compile("^\\s+KeyDowns: (\\d+) keys? currently down");

    private final Context context;
    private final AudioManager audio;
    /**
     * Keys the service has seen go down and not up yet, by {@link #key}, with their press's down time. Written by the
     * service's main thread; the handler thread only removes presses it knows are over.
     */
    private final Map<Long, Long> seen = new ConcurrentHashMap<>();
    /**
     * The repeat timeout counts from the volume change, not from the press, which no app can see. On TCL's Google TV
     * the volume changes as the key goes down, so that's the same. Stock Android holds a volume key back 150 ms first,
     * in case it starts a key combination (KeyCombinationManager), and there the first repeat comes that much later
     * than without the service; an app can't read those combinations, and erring late never turns a press into two
     * steps.
     */
    private final Repeat repeat = new Repeat(ViewConfiguration.getKeyRepeatTimeout(),
            ViewConfiguration.getKeyRepeatDelay());
    private HandlerThread thread;
    private Handler handler;
    private int stream = AudioManager.STREAM_MUSIC;
    private int steps;
    private boolean warned;

    private final BroadcastReceiver volumeChanged = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent intent) {
            int type = intent.getIntExtra(EXTRA_STREAM, -1);
            // Every stream that follows another (on a TV, all of them follow music) reports the change too.
            if (type < 0 || intent.getIntExtra(EXTRA_ALIAS, type) != type) return;
            int value = intent.getIntExtra(EXTRA_VALUE, -1);
            int previous = intent.getIntExtra(EXTRA_PREVIOUS, -1);
            if (value < 0 || previous < 0 || value == previous) return;
            if (type == stream && repeat.ownStep(previous, value)) return;
            handler.removeCallbacks(check);
            // A key changes the volume by one step; anything else (an app, the Assistant) also ends a repeat.
            boolean key = Math.abs(value - previous) == 1;
            if (steps > 0) Log.i(TAG, "volume key held: repeated it " + steps + " times");
            steps = 0;
            if (!key || !dumpGranted()) {
                repeat.reset();
                return;
            }
            stream = type;
            handler.postAtTime(check, repeat.volumeChanged(SystemClock.uptimeMillis(), previous, value));
        }
    };

    private final Runnable check = this::checkHeld;

    VolumeHold(Context context) {
        this.context = context;
        audio = context.getSystemService(AudioManager.class);
    }

    /** Whether the key is still down; once the repeat timeout has passed, one more step. */
    private void checkHeld() {
        Map<Long, Long> before = new HashMap<>(seen);
        Map<Integer, Integer> down = readKeysDown();
        int unseen = 0;
        if (down != null) {
            forgetReleased(before, down);
            Set<Long> keys = new HashSet<>(before.keySet());
            keys.addAll(seen.keySet());
            unseen = unseenKeysDown(down, perDevice(keys));
        }
        long now = SystemClock.uptimeMillis();
        long next = Repeat.IDLE;
        int action = repeat.check(now, unseen);
        if (action == Repeat.STEP) {
            // Something else moved the volume since (another press): its own change starts over. At the end of the
            // range the volume stays put, and so do we.
            int volume = audio.getStreamVolume(stream);
            int after = volume == repeat.expected() ? step(repeat.direction()) : volume;
            if (after != volume) {
                steps++;
                next = repeat.stepped(now, after);
            } else {
                repeat.stop();
            }
        } else if (action == Repeat.WAIT) {
            next = repeat.next();
        }
        if (next != Repeat.IDLE) {
            handler.postAtTime(check, next);
        } else if (steps > 0) {
            Log.i(TAG, "volume key held: repeated it " + steps + " times");
            steps = 0;
        }
    }

    /**
     * Nothing else can tell whether a volume key is still down. Checked on each change rather than once: the install
     * script grants it after the update, by when the service may already be back.
     */
    private boolean dumpGranted() {
        boolean granted = context.checkSelfPermission(Manifest.permission.DUMP) == PackageManager.PERMISSION_GRANTED;
        if (!granted && !warned) Log.w(TAG, "no DUMP permission: a held volume key changes the volume by one step");
        warned = !granted;
        return granted;
    }

    void start() {
        thread = new HandlerThread(TAG);
        thread.start();
        handler = new Handler(thread.getLooper());
        IntentFilter filter = new IntentFilter(VOLUME_CHANGED);
        int flags = Build.VERSION.SDK_INT >= 33 ? Context.RECEIVER_NOT_EXPORTED : 0;
        context.registerReceiver(volumeChanged, filter, null, handler, flags);
    }

    void stop() {
        try {
            context.unregisterReceiver(volumeChanged);
        } catch (IllegalArgumentException ignored) {
            // never started
        }
        if (thread != null) {
            handler.removeCallbacksAndMessages(null);
            thread.quitSafely();
            thread = null;
        }
    }

    /** Every key the service sees, from its onKeyEvent; called on every key, so it does little. */
    void keyEvent(KeyEvent event) {
        long k = key(event.getDeviceId(), event.getScanCode(), event.getKeyCode());
        if (event.getAction() == KeyEvent.ACTION_DOWN) seen.put(k, event.getDownTime());
        else if (event.getAction() == KeyEvent.ACTION_UP) seen.remove(k);
    }

    static long key(int deviceId, int scanCode, int keyCode) {
        return ((long) deviceId << 32) | ((long) (scanCode & 0xffff) << 16) | (keyCode & 0xffff);
    }

    static Map<Integer, Integer> perDevice(Set<Long> keys) {
        Map<Integer, Integer> counts = new HashMap<>();
        for (long k : keys) counts.merge((int) (k >> 32), 1, Integer::sum);
        return counts;
    }

    /** How many keys are down that the service didn't see go down: on each device, those beyond the ones it saw. */
    static int unseenKeysDown(Map<Integer, Integer> down, Map<Integer, Integer> seen) {
        int unseen = 0;
        for (Map.Entry<Integer, Integer> e : down.entrySet()) {
            Integer s = seen.get(e.getKey());
            unseen += Math.max(0, e.getValue() - (s == null ? 0 : s));
        }
        return unseen;
    }

    /**
     * Drops keys whose up the service missed: those it had seen down before the dump, on a device with nothing down
     * in it. Only that press goes: one the service sees go down again meanwhile has another down time, so it stays.
     */
    private void forgetReleased(Map<Long, Long> before, Map<Integer, Integer> down) {
        for (Map.Entry<Long, Long> e : before.entrySet()) {
            Integer n = down.get((int) (e.getKey() >> 32));
            if (n == null || n == 0) seen.remove(e.getKey(), e.getValue());
        }
    }

    /**
     * Keys down per input device, from the Input Reader State of {@code dumpsys input}; null if it has none (no DUMP
     * permission). Devices that repeat keys themselves are left out: their volume keys already repeat.
     */
    static Map<Integer, Integer> keysDown(BufferedReader dump) throws IOException {
        Map<Integer, Integer> down = null;
        boolean inReader = false;
        Integer device = null;
        boolean repeatsItself = false;
        int count = 0;
        for (String line; (line = dump.readLine()) != null; ) {
            if (!line.startsWith(" ")) {
                if (device != null && !repeatsItself) down.put(device, count);
                device = null;
                inReader = line.startsWith("Input Reader State");
                if (inReader && down == null) down = new HashMap<>();
                continue;
            }
            if (!inReader) continue;
            Matcher m = DEVICE.matcher(line);
            boolean next = m.find();
            if (next || line.startsWith("  Configuration:")) {
                if (device != null && !repeatsItself) down.put(device, count);
                device = next ? Integer.valueOf(m.group(1)) : null;
                repeatsItself = false;
                count = 0;
            } else if (device != null) {
                if (line.trim().equals("HandlesKeyRepeat: true")) repeatsItself = true;
                Matcher k = KEY_DOWNS.matcher(line);
                if (k.find()) count += Integer.parseInt(k.group(1));
            }
        }
        if (device != null && !repeatsItself) down.put(device, count);
        return down;
    }

    private Map<Integer, Integer> readKeysDown() {
        Process p = null;
        try {
            p = new ProcessBuilder("/system/bin/dumpsys", "input").redirectErrorStream(true).start();
            p.getOutputStream().close();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(),
                    StandardCharsets.UTF_8))) {
                Map<Integer, Integer> down = keysDown(r);
                if (down == null) Log.w(TAG, "dumpsys input has no Input Reader State; is DUMP granted?");
                return down;
            }
        } catch (IOException e) {
            Log.w(TAG, "could not read dumpsys input", e);
            return null;
        } finally {
            if (p != null) p.destroy();
        }
    }

    /** One step, as the key's own: shows the volume bar. Returns the new volume. */
    private int step(int direction) {
        audio.adjustStreamVolume(stream, direction > 0 ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER,
                AudioManager.FLAG_SHOW_UI);
        return audio.getStreamVolume(stream);
    }

    /**
     * When to look at the keys again and what to do then, with InputDispatcher's timing. Times are uptime
     * milliseconds; volumes are the stream's index.
     */
    static final class Repeat {
        static final long IDLE = -1;
        static final int STOP = 0;
        static final int WAIT = 1;
        static final int STEP = 2;
        private final long timeout;
        private final long delay;
        private boolean armed;
        private boolean repeating;
        /** Keys down that the service didn't see, at the first look after the change; -1 before it. */
        private int keys = -1;
        private int direction;
        /** The volume the key's own step set, and the one our last step set. */
        private int start;
        private int expected;
        private long due;
        private long next = IDLE;

        Repeat(long timeout, long delay) {
            this.timeout = timeout;
            this.delay = delay;
        }

        /**
         * The volume went from {@code previous} to {@code value}, not by our step: a volume key went down (the
         * system's own one step), or something else changed it. Returns when to look: at once, as a short press may
         * already be over.
         */
        long volumeChanged(long now, int previous, int value) {
            armed = true;
            repeating = false;
            keys = -1;
            direction = Integer.signum(value - previous);
            start = expected = value;
            due = now + timeout;
            return next = now;
        }

        /**
         * True for a change one of our steps made: one step in our direction, to a volume we stepped to. Their
         * broadcasts may come late, even after we've stopped, or be merged, so it's the value that says, not the
         * order; what we stepped through is kept until a change that isn't ours.
         */
        boolean ownStep(int previous, int value) {
            if (value - previous != direction) return false;
            int n = (value - start) * direction;
            return n >= 1 && n <= (expected - start) * direction;
        }

        /**
         * What to do now, with {@code unseen} keys down that the service didn't see: {@link #STOP} once any of them
         * goes up or another goes down, as InputDispatcher ends a repeat on any key up and moves it to a new key;
         * {@link #STEP} once the repeat timeout has passed.
         */
        int check(long now, int unseen) {
            if (keys < 0) keys = unseen;
            if (!armed || unseen == 0 || unseen != keys) {
                stop();
                return STOP;
            }
            if (repeating || now >= due) return STEP;
            next = Math.min(now + delay, due);
            return WAIT;
        }

        /** Our step set the volume to {@code value}. Returns when to look again. */
        long stepped(long now, int value) {
            repeating = true;
            expected = value;
            next = Math.max(next, due) + delay;
            if (next <= now) next = now + delay;
            return next;
        }

        long stop() {
            armed = false;
            repeating = false;
            return next = IDLE;
        }

        /** Stops, and forgets the steps we made: a change that isn't ours came in. */
        void reset() {
            stop();
            start = expected;
        }

        long next() {
            return next;
        }

        int direction() {
            return direction;
        }

        int expected() {
            return expected;
        }
    }
}
