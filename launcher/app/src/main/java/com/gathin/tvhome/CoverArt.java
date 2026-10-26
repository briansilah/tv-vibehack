package com.gathin.tvhome;

/**
 * The top shelf's cover art for an app without suggestions, after tvOS's static Top Shelf images (YouTube's logo
 * on bright shapes): the app's logo, cut out of its banner, over soft blobs in the banner's own colours. This
 * class does the pixel work; plain Java, so it runs in JVM unit tests. Pixels are non-premultiplied ARGB, as
 * {@code Bitmap.getPixels} returns them.
 */
final class CoverArt {

    /** Background pixels: no further than this from the banner's edge colour (0..1, largest channel difference)... */
    private static final float BG_NEAR = 0.10f;
    /** ...fading out up to this far, for the logo's anti-aliased edges. Further is logo. */
    private static final float BG_FAR = 0.28f;
    /** Share of the banner's edge that must be the background colour for it to count as plain. */
    private static final float PLAIN_EDGE = 0.7f;
    /** Share of the banner left after the cut beyond which it wasn't a logo on a plain background. */
    private static final float MAX_LOGO_SHARE = 0.6f;
    /** Dark greys and black (a wordmark drawn for a light banner) turn this light on the dark cover. */
    private static final int LIGHT = 0xF2;
    private static final float NEUTRAL_CHROMA = 0.08f;
    private static final float DARK_LUMA = 0.55f;
    private static final int HUE_BINS = 24;
    private static final float MIN_CHROMA = 0.15f;

    private CoverArt() {
    }

    /** A logo cut out of a banner: {@code w} x {@code h} ARGB pixels, transparent around it. */
    static final class Logo {
        final int[] argb;
        final int w;
        final int h;

        Logo(int[] argb, int w, int h) {
            this.argb = argb;
            this.w = w;
            this.h = h;
        }
    }

    /**
     * The banner's logo without its background, ready for a dark cover, cropped to it; or null when the banner
     * isn't a logo on a plain background (a photo, a busy pattern).
     * <ul>
     *   <li>Coloured pixels are kept by how far their colour is from the background's, the anti-aliased edge
     *   un-blended from it.</li>
     *   <li>Grey, black and white pixels are keyed by brightness: on a light banner the darker they are the more
     *   they're kept, and they turn light (a black wordmark becomes white on the dark cover); on a dark banner the
     *   lighter, the more. So a letter's inside (the hole in an "o") goes, as the background does.</li>
     *   <li>Background-coloured shapes enclosed by colour stay as they are: YouTube's white play arrow.</li>
     * </ul>
     * A banner already transparent around its logo (an adaptive banner's foreground) is only made light and cropped.
     */
    static Logo cutOut(int[] px, int w, int h) {
        if (px == null || w < 3 || h < 3 || px.length < w * h) return null;
        int ring = 2 * w + 2 * (h - 2);
        int[] edge = new int[ring];
        int opaque = 0;
        for (int i = 0; i < ring; i++) {
            int p = px[edgeIndex(i, w, h)];
            if ((p >>> 24) >= 32) edge[opaque++] = p;
        }
        int[] out = new int[w * h];
        boolean cut = opaque > ring / 2;
        if (cut) {
            int bg = median(edge, opaque);
            int near = 0;
            for (int i = 0; i < opaque; i++) {
                if (distance(edge[i], bg) <= BG_NEAR) near++;
            }
            if (near < PLAIN_EDGE * opaque) return null;
            boolean[] kept = enclosedByColour(px, w, h, bg);
            int br = (bg >> 16) & 0xFF, bgG = (bg >> 8) & 0xFF, bb = bg & 0xFF;
            float bgLuma = luma(br, bgG, bb);
            boolean lightBg = bgLuma >= 0.5f;
            for (int i = 0; i < w * h; i++) {
                int p = px[i];
                float a0 = (p >>> 24) / 255f;
                if (a0 < 1f / 255f) continue;
                int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
                if (kept[i]) {
                    out[i] = p;
                    continue;
                }
                if (neutral(r, g, b)) {
                    float l = luma(r, g, b);
                    float k = lightBg ? (bgLuma - l) / Math.max(0.2f, bgLuma - 0.08f)
                            : (l - bgLuma) / Math.max(0.2f, 0.92f - bgLuma);
                    k = Math.max(0f, Math.min(1f, k));
                    if (k * a0 < 1f / 255f) continue;
                    int c = lightBg ? LIGHT : Math.max(r, Math.max(g, b));
                    out[i] = Math.round(k * a0 * 255f) << 24 | c << 16 | c << 8 | c;
                    continue;
                }
                float k = smoothstep(BG_NEAR, BG_FAR, distance(p, bg));
                if (k * a0 < 1f / 255f) continue;
                if (k < 1f) {
                    // An edge pixel is part logo, part background: take the background back out of its colour.
                    r = unblend(r, br, k);
                    g = unblend(g, bgG, k);
                    b = unblend(b, bb, k);
                }
                out[i] = Math.round(k * a0 * 255f) << 24 | r << 16 | g << 8 | b;
            }
        } else {
            for (int i = 0; i < w * h; i++) {
                int p = px[i];
                float alpha = (p >>> 24) / 255f;
                if (alpha < 1f / 255f) continue;
                out[i] = forDarkCover(alpha, (p >> 16) & 0xFF, (p >> 8) & 0xFF, p & 0xFF);
            }
        }
        int minX = w, minY = h, maxX = -1, maxY = -1, solid = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if ((out[y * w + x] >>> 24) < 64) continue;
                solid++;
                if (x < minX) minX = x;
                if (x > maxX) maxX = x;
                if (y < minY) minY = y;
                if (y > maxY) maxY = y;
            }
        }
        if (maxX < 0 || (cut && solid > MAX_LOGO_SHARE * w * h)) return null;
        int pad = 2;
        minX = Math.max(0, minX - pad);
        minY = Math.max(0, minY - pad);
        maxX = Math.min(w - 1, maxX + pad);
        maxY = Math.min(h - 1, maxY + pad);
        int cw = maxX - minX + 1, ch = maxY - minY + 1;
        int[] crop = new int[cw * ch];
        for (int y = 0; y < ch; y++) System.arraycopy(out, (minY + y) * w + minX, crop, y * cw, cw);
        return new Logo(crop, cw, ch);
    }

    /**
     * Background-coloured pixels to keep: regions in the background's colour that the edge can't reach and that
     * colour surrounds (a white arrow on a red button). Regions that greys surround (the inside of a letter) aren't.
     */
    private static boolean[] enclosedByColour(int[] px, int w, int h, int bg) {
        int n = w * h;
        boolean[] near = new boolean[n];
        for (int i = 0; i < n; i++) near[i] = distance(px[i], bg) < BG_FAR;
        boolean[] seen = new boolean[n];
        int[] queue = new int[n];
        int head = 0, tail = 0;
        int ring = 2 * w + 2 * (h - 2);
        for (int i = 0; i < ring; i++) {
            int idx = edgeIndex(i, w, h);
            if (near[idx] && !seen[idx]) {
                seen[idx] = true;
                queue[tail++] = idx;
            }
        }
        // The background proper: what joins the edge.
        while (head < tail) {
            int idx = queue[head++];
            int x = idx % w, y = idx / w;
            if (x > 0) tail = visit(near, seen, queue, tail, idx - 1);
            if (x < w - 1) tail = visit(near, seen, queue, tail, idx + 1);
            if (y > 0) tail = visit(near, seen, queue, tail, idx - w);
            if (y < h - 1) tail = visit(near, seen, queue, tail, idx + w);
        }
        boolean[] keep = new boolean[n];
        for (int start = 0; start < n; start++) {
            if (!near[start] || seen[start]) continue;
            // An enclosed region: gather it, and count what borders it.
            head = 0;
            tail = 0;
            seen[start] = true;
            queue[tail++] = start;
            int colour = 0, border = 0;
            while (head < tail) {
                int idx = queue[head++];
                int x = idx % w, y = idx / w;
                int[] around = {x > 0 ? idx - 1 : -1, x < w - 1 ? idx + 1 : -1, y > 0 ? idx - w : -1,
                        y < h - 1 ? idx + w : -1};
                for (int j : around) {
                    if (j < 0) continue;
                    if (near[j]) {
                        if (!seen[j]) {
                            seen[j] = true;
                            queue[tail++] = j;
                        }
                    } else {
                        border++;
                        int p = px[j];
                        if (!neutral((p >> 16) & 0xFF, (p >> 8) & 0xFF, p & 0xFF)) colour++;
                    }
                }
            }
            if (colour * 2 > border) {
                for (int k = 0; k < tail; k++) keep[queue[k]] = true;
            }
        }
        return keep;
    }

    private static int visit(boolean[] near, boolean[] seen, int[] queue, int tail, int idx) {
        if (seen[idx] || !near[idx]) return tail;
        seen[idx] = true;
        queue[tail] = idx;
        return tail + 1;
    }

    private static boolean neutral(int r, int g, int b) {
        int max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        return (max - min) / 255f < NEUTRAL_CHROMA;
    }

    private static float luma(int r, int g, int b) {
        return (0.2126f * r + 0.7152f * g + 0.0722f * b) / 255f;
    }

    /** The i-th pixel around the edge, clockwise from the top left. */
    private static int edgeIndex(int i, int w, int h) {
        if (i < w) return i;
        i -= w;
        if (i < h - 2) return (i + 1) * w + (w - 1);
        i -= h - 2;
        if (i < w) return (h - 1) * w + (w - 1 - i);
        i -= w;
        return (h - 2 - i) * w;
    }

    /** Dark neutral pixels turn light, keeping their alpha; colours stay as they are. */
    private static int forDarkCover(float alpha, int r, int g, int b) {
        int max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        float luma = (0.2126f * r + 0.7152f * g + 0.0722f * b) / 255f;
        if ((max - min) / 255f < NEUTRAL_CHROMA && luma < DARK_LUMA) {
            r = g = b = LIGHT;
        }
        return Math.round(alpha * 255f) << 24 | r << 16 | g << 8 | b;
    }

    private static int unblend(int c, int bg, float k) {
        return Math.max(0, Math.min(255, Math.round((c - (1f - k) * bg) / k)));
    }

    /** Largest channel difference, 0..1. */
    static float distance(int p, int q) {
        int dr = Math.abs(((p >> 16) & 0xFF) - ((q >> 16) & 0xFF));
        int dg = Math.abs(((p >> 8) & 0xFF) - ((q >> 8) & 0xFF));
        int db = Math.abs((p & 0xFF) - (q & 0xFF));
        return Math.max(dr, Math.max(dg, db)) / 255f;
    }

    private static float smoothstep(float a, float b, float x) {
        float t = Math.max(0f, Math.min(1f, (x - a) / (b - a)));
        return t * t * (3f - 2f * t);
    }

    /** Per-channel median of the first n pixels, opaque. */
    private static int median(int[] px, int n) {
        int[] c = new int[n];
        int out = 0xFF000000;
        for (int shift = 16; shift >= 0; shift -= 8) {
            for (int i = 0; i < n; i++) c[i] = (px[i] >> shift) & 0xFF;
            java.util.Arrays.sort(c, 0, n);
            out |= c[n / 2] << shift;
        }
        return out;
    }

    /**
     * Up to {@code max} vivid colours of the pixels, most covered first: hue clusters weighted by how colourful
     * each pixel is, brightened for a dark cover. Empty for a neutral banner (black, white and greys).
     */
    static int[] palette(int[] px, int max) {
        float[] weight = new float[HUE_BINS], sr = new float[HUE_BINS], sg = new float[HUE_BINS],
                sb = new float[HUE_BINS];
        float area = 0f;
        for (int p : px) {
            int a = p >>> 24;
            if (a < 32) continue;
            float alpha = a / 255f;
            area += alpha;
            int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
            int hi = Math.max(r, Math.max(g, b)), lo = Math.min(r, Math.min(g, b));
            float chroma = (hi - lo) / 255f;
            if (chroma < MIN_CHROMA) continue;
            float[] hsv = new float[3];
            AmbientColor.rgbToHsv(r, g, b, hsv);
            int bin = (int) (hsv[0] / (360f / HUE_BINS)) % HUE_BINS;
            float wgt = alpha * chroma;
            weight[bin] += wgt;
            sr[bin] += wgt * r;
            sg[bin] += wgt * g;
            sb[bin] += wgt * b;
        }
        int[] found = new int[Math.max(0, max)];
        int n = 0;
        boolean[] used = new boolean[HUE_BINS];
        while (n < found.length && area > 0f) {
            int best = -1;
            float bestW = 0f;
            for (int i = 0; i < HUE_BINS; i++) {
                if (used[i]) continue;
                float wgt = 0f;
                for (int d = -1; d <= 1; d++) {
                    int j = (i + d + HUE_BINS) % HUE_BINS;
                    if (!used[j]) wgt += weight[j];
                }
                if (wgt > bestW) {
                    bestW = wgt;
                    best = i;
                }
            }
            // Ignore specks: a colour needs a little of the banner to itself.
            if (best < 0 || bestW / area < 0.004f) break;
            float r = 0f, g = 0f, b = 0f;
            for (int d = -1; d <= 1; d++) {
                int j = (best + d + HUE_BINS) % HUE_BINS;
                if (used[j]) continue;
                r += sr[j];
                g += sg[j];
                b += sb[j];
            }
            for (int d = -2; d <= 2; d++) used[(best + d + HUE_BINS) % HUE_BINS] = true;
            float[] hsv = new float[3];
            AmbientColor.rgbToHsv(r / bestW, g / bestW, b / bestW, hsv);
            hsv[1] = Math.max(0.55f, Math.min(1f, hsv[1] * 1.15f));
            hsv[2] = Math.max(0.7f, Math.min(0.95f, hsv[2]));
            found[n++] = AmbientColor.hsvToRgb(hsv);
        }
        return java.util.Arrays.copyOf(found, n);
    }

    /**
     * The cover's {@code n} blob colours: the palette's, then shades either side of them on the colour wheel, so a
     * one-colour banner (YouTube red) gets related ones (orange, pink, a deep red) rather than unrelated ones.
     * Greys for a neutral banner.
     */
    static int[] blobColours(int[] palette, int n) {
        int[] out = new int[n];
        if (palette == null || palette.length == 0) {
            int[] greys = {0xFF6B6B78, 0xFF44444F, 0xFF8A8A96, 0xFF33333C};
            for (int i = 0; i < n; i++) out[i] = greys[i % greys.length];
            return out;
        }
        // Hue shift, saturation and value factors for the second, third... round through the palette.
        float[][] variants = {{28f, 1f, 0.95f}, {-30f, 0.9f, 0.85f}, {0f, 0.95f, 0.5f}, {52f, 0.85f, 0.75f}};
        float[] hsv = new float[3];
        for (int i = 0; i < n; i++) {
            int c = palette[i % palette.length];
            int round = i / palette.length;
            if (round == 0) {
                out[i] = c;
                continue;
            }
            float[] v = variants[(round - 1) % variants.length];
            AmbientColor.rgbToHsv((c >> 16) & 0xFF, (c >> 8) & 0xFF, c & 0xFF, hsv);
            hsv[0] += v[0];
            hsv[1] = Math.min(1f, hsv[1] * v[1]);
            hsv[2] *= v[2];
            out[i] = AmbientColor.hsvToRgb(hsv);
        }
        return out;
    }

    /**
     * The cover's ground, which the logo and blobs stand out on: true black, so a TV that dims its backlight by zone
     * (mini-LED) or lights each pixel (OLED) is dark there (TrueBlack).
     */
    static final int GROUND = 0xFF000000;

    /**
     * Where the {@code n} blobs go, as {x, y, radius} fractions of the cover's width and height (radius of its
     * width): fixed for an app (seeded by its package), around the edges and along the bottom, where the dock's glass
     * shows them, leaving the middle, where the logo is, darker.
     */
    static float[][] blobs(long seed, int n) {
        long s = seed * 0x5DEECE66DL + 0xBL;
        float[][] out = new float[n][3];
        for (int i = 0; i < n; i++) {
            s = s * 0x5DEECE66DL + 0xBL;
            float u = ((s >>> 17) & 0xFFFF) / 65535f;
            s = s * 0x5DEECE66DL + 0xBL;
            float v = ((s >>> 17) & 0xFFFF) / 65535f;
            s = s * 0x5DEECE66DL + 0xBL;
            float r = ((s >>> 17) & 0xFFFF) / 65535f;
            // Alternate sides; every other blob sits low, behind the dock.
            boolean left = (i + (seed & 1)) % 2 == 0;
            out[i][0] = left ? 0.02f + 0.26f * u : 0.72f + 0.26f * u;
            out[i][1] = i % 2 == 0 ? 0.05f + 0.45f * v : 0.7f + 0.3f * v;
            out[i][2] = 0.28f + 0.22f * r;
        }
        return out;
    }
}
