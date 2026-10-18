package com.gathin.tvhome;

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
}
