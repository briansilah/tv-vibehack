package com.gathin.tvhome;

/**
 * True black for the top shelf's pictures. On a TV that dims its backlight zone by zone (mini-LED) or lights each
 * pixel itself (OLED), only 0, 0, 0 is off: a charcoal ground, such as tvOS's #242424 behind YouTube's Top Shelf image,
 * keeps half the screen glowing grey. So the dark ground of a picture goes to true black, and the darkest tones just
 * above it ease into it along a soft toe, each pixel scaled whole so its hue stays; brighter colours are left as they
 * are. Plain Java, so it runs in JVM unit tests. Pixels are opaque ARGB.
 */
final class TrueBlack {

    /**
     * The faint haze a painted picture leaves on its black (a glow's last breath, the darkest edge of a shape): at or
     * under {@link #FAINT_KNEE} it goes, fading in up to {@link #FAINT_END}.
     */
    static final int FAINT_KNEE = 3, FAINT_END = 12;
    /** A ground is near-black: its brightness (largest channel) no more than this, about a fifth. */
    static final int MAX_GROUND = 48;
    /** ... and covers at least this share of the picture. */
    static final float MIN_SHARE = 0.15f;
    /** How far a ground's own pixels spread either side of its commonest brightness (paper grain, a video's noise). */
    private static final int SPREAD = 8;

    private TrueBlack() {
    }

    /** A pixel's brightness: its largest channel, 0..255. */
    static int brightness(int p) {
        return Math.max((p >> 16) & 0xFF, Math.max((p >> 8) & 0xFF, p & 0xFF));
    }

    /** Adds the first {@code n} pixels' brightness to {@code hist} (256 bins). */
    static void count(int[] px, int n, int[] hist) {
        for (int i = 0; i < n; i++) hist[brightness(px[i])]++;
    }

    /**
     * The top of a picture's near-black ground (the brightness at or under which its pixels are ground), from its
     * brightness histogram; or -1 if it has none: its dark parts are already black, or there's no dark ground.
     */
    static int groundTop(int[] hist) {
        long total = 0;
        for (int c : hist) total += c;
        if (total == 0) return -1;
        // Its commonest dark brightness, black aside, smoothed over a few levels.
        int peak = -1;
        long best = 0;
        for (int v = 1; v <= MAX_GROUND; v++) {
            long s = 0;
            for (int k = Math.max(1, v - 2); k <= Math.min(255, v + 2); k++) s += hist[k];
            if (s > best) {
                best = s;
                peak = v;
            }
        }
        if (peak < 0) return -1;
        int lo = Math.max(1, peak - SPREAD), hi = peak + SPREAD;
        long ground = 0;
        for (int v = lo; v <= hi; v++) ground += hist[v];
        if (ground < MIN_SHARE * total) return -1;
        // Its top: under which nearly all of it lies, its grain and noise, not the picture's darkest colours.
        long want = ground * 99 / 100, sum = 0;
        for (int v = lo; v <= hi; v++) {
            sum += hist[v];
            if (sum >= want) return v;
        }
        return hi;
    }

    /** Where the toe above a ground at {@code knee} meets the picture as it was. */
    static int toeEnd(int knee) {
        return knee + Math.max(FAINT_END - FAINT_KNEE, knee / 2);
    }

    /**
     * The first {@code n} pixels with everything at or under {@code knee} black, and up to {@code end} scaled down
     * smoothly into it (each pixel whole, so its hue stays); brighter ones as they were.
     */
    static void toe(int[] px, int n, int knee, int end) {
        float span = Math.max(1, end - knee);
        for (int i = 0; i < n; i++) {
            int p = px[i], v = brightness(p);
            if (v >= end) continue;
            if (v <= knee) {
                px[i] = p & 0xFF000000;
                continue;
            }
            float t = (v - knee) / span, k = t * t * (3f - 2f * t);
            int r = Math.round(((p >> 16) & 0xFF) * k), g = Math.round(((p >> 8) & 0xFF) * k),
                    b = Math.round((p & 0xFF) * k);
            px[i] = (p & 0xFF000000) | r << 16 | g << 8 | b;
        }
    }
}
