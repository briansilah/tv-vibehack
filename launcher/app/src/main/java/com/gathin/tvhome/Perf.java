package com.gathin.tvhome;

import android.os.Build;
import android.os.SystemClock;
import android.os.Trace;
import android.util.Log;

import java.util.Locale;

/**
 * Local performance instrumentation, read by tools/perf.py (see docs/performance.md). Nothing leaves the TV.
 *
 * <p>Trace sections ("tvhome:...") show up in Perfetto traces and cost nothing when no trace is running.
 * Timing lines go to logcat under {@link #TAG} only after {@code setprop log.tag.TvHomePerf DEBUG}.
 */
final class Perf {

    static final String TAG = "TvHomePerf";

    private Perf() {
    }

    static void begin(String section) {
        Trace.beginSection(section.length() > 127 ? section.substring(0, 127) : section);
    }

    static void end() {
        Trace.endSection();
    }

    /** A section that can span frames (e.g. from a focus change until an animation ends). Android 10+. */
    static void beginAsync(String section, int cookie) {
        if (Build.VERSION.SDK_INT >= 29) Trace.beginAsyncSection(section, cookie);
    }

    static void endAsync(String section, int cookie) {
        if (Build.VERSION.SDK_INT >= 29) Trace.endAsyncSection(section, cookie);
    }

    static long now() {
        return SystemClock.elapsedRealtimeNanos();
    }

    static boolean enabled() {
        return Log.isLoggable(TAG, Log.DEBUG);
    }

    /** Logs {@code "<event> ms=<time since startNanos> <extra>"}; extra is space-separated key=value pairs. */
    static void log(String event, long startNanos, String extra) {
        if (!enabled()) return;
        double ms = (now() - startNanos) / 1e6;
        Log.d(TAG, String.format(Locale.US, "%s ms=%.1f%s", event, ms, extra == null ? "" : " " + extra));
    }

    static double ms(long fromNanos, long toNanos) {
        return (toNanos - fromNanos) / 1e6;
    }

    /**
     * Times an on-screen change that finishes in parts (e.g. the top shelf's name and its glow), from what
     * started it to its last part: a "tvhome:&lt;event&gt;" async trace section and, with logging on, a line
     * {@code "<event> ms=<until the last part> <part>_ms=<each part> <extra>"}. Starting again restarts it;
     * a change that is cut short logs nothing, and parts reported for an earlier start are ignored.
     * Main thread only.
     */
    static final class Span {
        private final String event;
        private final String section;
        private final String[] parts;
        private final long[] partNanos;
        private long start;
        private int cookie;
        private String extra;

        Span(String event, String... parts) {
            this.event = event;
            this.section = "tvhome:" + event;
            this.parts = parts;
            this.partNanos = new long[parts.length];
        }

        /** Returns this start's token for {@link #part}. */
        int start(String extra) {
            cancel();
            start = now();
            java.util.Arrays.fill(partNanos, 0L);
            this.extra = extra;
            beginAsync(section, ++cookie);
            return cookie;
        }

        /** Part {@code i} of the change started with {@code token} is on screen. */
        void part(int i, int token) {
            if (start == 0 || token != cookie || partNanos[i] != 0) return;
            partNanos[i] = now();
            for (long p : partNanos) {
                if (p == 0) return;
            }
            endAsync(section, cookie);
            if (enabled()) {
                StringBuilder sb = new StringBuilder();
                for (int k = 0; k < parts.length; k++) {
                    sb.append(parts[k]).append(String.format(Locale.US, "_ms=%.1f ", ms(start, partNanos[k])));
                }
                if (extra != null) sb.append(extra);
                log(event, start, sb.toString().trim());
            }
            start = 0;
        }

        void cancel() {
            if (start == 0) return;
            endAsync(section, cookie);
            start = 0;
        }
    }
}
