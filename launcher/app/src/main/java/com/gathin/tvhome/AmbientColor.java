package com.gathin.tvhome;

/**
 * Picks the ambient glow colour for an app from its banner (or icon) pixels. Plain Java, no android.*,
 * so it runs in JVM unit tests.
 *
 * <p>Rule: the glow takes the banner's dominant hue only when that hue covers enough of the banner,
 * counting each pixel by how colourful it is. Otherwise the banner reads as neutral and the glow is a
 * grey as bright as the banner. A mostly black banner with a thin coloured edge (Apple TV) gets a dark
 * grey glow; Netflix red, Prime blue and YouTube red keep their colour.
 */
final class AmbientColor {

    /** Used when there is nothing to sample (no drawable, or fully transparent). */
    static final int FALLBACK = 0xFF444444;

    /** Hue histogram resolution: 24 bins of 15 degrees. */
    private static final int HUE_BINS = 24;
    /** A hue cluster is a bin plus its neighbours on each side (45 degrees in total). */
    private static final int CLUSTER_RADIUS = 1;
    /** Pixels more transparent than this are ignored (icon corners, adaptive icon masks). */
    private static final int MIN_ALPHA = 32;
    /** Pixels with less chroma (max - min of R, G, B, 0..1) count as grey: their hue is noise. */
    private static final float MIN_CHROMA = 0.12f;
    /** Share of the banner, weighted by chroma, that the dominant hue needs to tint the glow. */
    private static final float MIN_COVERAGE = 0.035f;

    private AmbientColor() {
    }

    /**
     * The glow colour for these pixels: opaque, never 0. Pixels are non-premultiplied ARGB, as
     * returned by {@code Bitmap.getPixels}.
     */
    static int pick(int[] argb) {
        if (argb == null || argb.length == 0) return FALLBACK;
        float[] binWeight = new float[HUE_BINS];
        float[] binR = new float[HUE_BINS];
        float[] binG = new float[HUE_BINS];
        float[] binB = new float[HUE_BINS];
        float area = 0f, sumR = 0f, sumG = 0f, sumB = 0f;
        for (int p : argb) {
            int a = p >>> 24;
            if (a < MIN_ALPHA) continue;
            float alpha = a / 255f;
            int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
            area += alpha;
            sumR += alpha * r;
            sumG += alpha * g;
            sumB += alpha * b;
            int max = Math.max(r, Math.max(g, b));
            int min = Math.min(r, Math.min(g, b));
            float chroma = (max - min) / 255f;
            if (chroma < MIN_CHROMA) continue;
            int bin = (int) (hue(r, g, b, max, max - min) / (360f / HUE_BINS)) % HUE_BINS;
            float w = alpha * chroma;
            binWeight[bin] += w;
            binR[bin] += w * r;
            binG[bin] += w * g;
            binB[bin] += w * b;
        }
        if (area <= 0f) return FALLBACK;

        int bestBin = -1;
        float bestWeight = 0f;
        for (int i = 0; i < HUE_BINS; i++) {
            float w = 0f;
            for (int d = -CLUSTER_RADIUS; d <= CLUSTER_RADIUS; d++) w += binWeight[(i + d + HUE_BINS) % HUE_BINS];
            if (w > bestWeight) {
                bestWeight = w;
                bestBin = i;
            }
        }

        float[] hsv = new float[3];
        if (bestBin >= 0 && bestWeight / area >= MIN_COVERAGE) {
            float r = 0f, g = 0f, b = 0f;
            for (int d = -CLUSTER_RADIUS; d <= CLUSTER_RADIUS; d++) {
                int i = (bestBin + d + HUE_BINS) % HUE_BINS;
                r += binR[i];
                g += binG[i];
                b += binB[i];
            }
            rgbToHsv(r / bestWeight, g / bestWeight, b / bestWeight, hsv);
            hsv[1] = Math.min(1f, hsv[1] * 1.1f);
            hsv[2] = Math.max(0.5f, hsv[2]);
            return hsvToRgb(hsv);
        }

        // Neutral banner: a grey as bright as the banner, so a black banner glows dark and a white one light.
        float luma = (0.2126f * sumR + 0.7152f * sumG + 0.0722f * sumB) / area / 255f;
        hsv[0] = 0f;
        hsv[1] = 0f;
        hsv[2] = Math.max(0.2f, Math.min(1f, luma));
        return hsvToRgb(hsv);
    }

    /** Hue in degrees (0..360) of a colour with the given largest component and chroma (> 0). */
    private static float hue(float r, float g, float b, float max, float chroma) {
        float h;
        if (max == r) h = ((g - b) / chroma) % 6f;
        else if (max == g) h = (b - r) / chroma + 2f;
        else h = (r - g) / chroma + 4f;
        h *= 60f;
        return h < 0f ? h + 360f : h;
    }

    /** RGB components in 0..255 to HSV (hue 0..360, saturation and value 0..1), like Color.colorToHSV. */
    static void rgbToHsv(float r, float g, float b, float[] hsv) {
        float max = Math.max(r, Math.max(g, b));
        float c = max - Math.min(r, Math.min(g, b));
        hsv[0] = c > 0f ? hue(r, g, b, max, c) : 0f;
        hsv[1] = max > 0f ? c / max : 0f;
        hsv[2] = max / 255f;
    }

    /** HSV to an opaque ARGB colour, like Color.HSVToColor. */
    static int hsvToRgb(float[] hsv) {
        float h = ((hsv[0] % 360f) + 360f) % 360f / 60f;
        float s = hsv[1], v = hsv[2];
        float c = v * s;
        float x = c * (1f - Math.abs(h % 2f - 1f));
        float m = v - c;
        float r, g, b;
        switch ((int) h) {
            case 0: r = c; g = x; b = 0f; break;
            case 1: r = x; g = c; b = 0f; break;
            case 2: r = 0f; g = c; b = x; break;
            case 3: r = 0f; g = x; b = c; break;
            case 4: r = x; g = 0f; b = c; break;
            default: r = c; g = 0f; b = x; break;
        }
        return 0xFF000000
                | Math.round((r + m) * 255f) << 16
                | Math.round((g + m) * 255f) << 8
                | Math.round((b + m) * 255f);
    }
}
