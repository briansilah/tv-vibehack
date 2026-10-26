package com.gathin.tvhome;

/**
 * The top shelf's artwork: an original picture for each app (see {@link ShelfArtworks}), painted on the TV from its
 * brand's colours and motifs, in the manner of tvOS's static Top Shelf images: bold flat shapes cut off by the edges of
 * the screen, dry-brush streaks and scribbles in them, around a calm middle where {@link ShelfInfoView} draws the
 * app's logo, all on true black. No pictures ship with the launcher: each is painted when first needed.
 *
 * <p>Plain Java, so it runs in JVM unit tests, and deterministic: an app always gets the same picture. Recipes draw in
 * a 1920 x 1080 design space (the screen at 1080p), scaled to the size asked for. Shapes are signed distance fields:
 * exact anti-aliased edges at any size, and cheap, as only the pixels along an edge need its distance (an 8-pixel grid
 * says which cells are wholly inside or outside).
 */
final class ShelfArt {

    /** The design space. */
    static final float DW = 1920f, DH = 1080f;
    /**
     * The calm middle, which recipes leave to the ground: an ellipse half the screen wide around the logo, which
     * ShelfInfoView centres above the dock (its middle at 0.46 of the dock's top, about 312 at 1080p), at most 576 x
     * 203 with the app's name.
     */
    static final float CALM_X = 960f, CALM_Y = 312f, CALM_RX = 480f, CALM_RY = 200f;

    /** A picture: paints itself on an easel. */
    interface Recipe {
        void paint(Easel e);
    }

    private ShelfArt() {
    }

    /** Whether the app has artwork. Main-thread safe: no I/O. */
    static boolean has(String pkg) {
        return pkg != null && ShelfArtworks.recipe(pkg) != null;
    }

    /** The app's artwork, {@code w} x {@code h} opaque ARGB pixels, or null if it has none. Slow: a worker thread. */
    static int[] render(String pkg, int w, int h) {
        Recipe r = pkg == null ? null : ShelfArtworks.recipe(pkg);
        if (r == null || w < 16 || h < 9) return null;
        return paint(r, ShelfArtworks.key(pkg), w, h);
    }

    static int[] paint(Recipe r, String key, int w, int h) {
        Easel e = new Easel(w, h, seed(key));
        r.paint(e);
        e.finish();
        return e.px;
    }

    /** A stable seed for a name (String.hashCode is fixed by the language, but spreads short names poorly). */
    static long seed(String key) {
        long s = 0xCBF29CE484222325L;
        for (int i = 0; i < key.length(); i++) {
            s ^= key.charAt(i);
            s *= 0x100000001B3L;
        }
        return s;
    }

    // ---- shapes --------------------------------------------------------------------------------------------

    /** A signed distance field in design units, negative inside, with bounds. */
    abstract static class Shape {
        float x0, y0, x1, y1;

        abstract float d(float x, float y);

        Shape bounds(float x0, float y0, float x1, float y1) {
            this.x0 = x0;
            this.y0 = y0;
            this.x1 = x1;
            this.y1 = y1;
            return this;
        }

        /** This without {@code o}. */
        Shape minus(final Shape o) {
            final Shape a = this;
            return new Shape() {
                @Override
                float d(float x, float y) {
                    return Math.max(a.d(x, y), -o.d(x, y));
                }
            }.bounds(x0, y0, x1, y1);
        }

        Shape and(final Shape o) {
            final Shape a = this;
            return new Shape() {
                @Override
                float d(float x, float y) {
                    return Math.max(a.d(x, y), o.d(x, y));
                }
            }.bounds(Math.max(x0, o.x0), Math.max(y0, o.y0), Math.min(x1, o.x1), Math.min(y1, o.y1));
        }

        /** Grown by {@code r} all round: rounds a polygon's corners. */
        Shape grow(final float r) {
            final Shape a = this;
            return new Shape() {
                @Override
                float d(float x, float y) {
                    return a.d(x, y) - r;
                }
            }.bounds(x0 - r, y0 - r, x1 + r, y1 + r);
        }

        /** Its outline, {@code t} wide. */
        Shape outline(final float t) {
            final Shape a = this;
            return new Shape() {
                @Override
                float d(float x, float y) {
                    return Math.abs(a.d(x, y)) - t / 2f;
                }
            }.bounds(x0 - t, y0 - t, x1 + t, y1 + t);
        }
    }

    static Shape circle(final float cx, final float cy, final float r) {
        return new Shape() {
            @Override
            float d(float x, float y) {
                float dx = x - cx, dy = y - cy;
                return (float) Math.sqrt(dx * dx + dy * dy) - r;
            }
        }.bounds(cx - r, cy - r, cx + r, cy + r);
    }

    /** An ellipse turned by {@code deg} (an approximate field, close enough for edges). */
    static Shape ellipse(final float cx, final float cy, final float rx, final float ry, float deg) {
        final float c = (float) Math.cos(Math.toRadians(deg)), s = (float) Math.sin(Math.toRadians(deg));
        float ex = (float) Math.sqrt(rx * rx * c * c + ry * ry * s * s);
        float ey = (float) Math.sqrt(rx * rx * s * s + ry * ry * c * c);
        return new Shape() {
            @Override
            float d(float x, float y) {
                float dx = x - cx, dy = y - cy;
                float u = dx * c + dy * s, v = -dx * s + dy * c;
                float k0 = (float) Math.sqrt(u * u / (rx * rx) + v * v / (ry * ry));
                float k1 = (float) Math.sqrt(u * u / (rx * rx * rx * rx) + v * v / (ry * ry * ry * ry));
                return k1 < 1e-6f ? -Math.min(rx, ry) : k0 * (k0 - 1f) / k1;
            }
        }.bounds(cx - ex, cy - ey, cx + ex, cy + ey);
    }

    /** A polygon through the points x, y, x, y, ...; any simple polygon. */
    static Shape poly(final float... xy) {
        final int n = xy.length / 2;
        final float[] ex = new float[n], ey = new float[n], inv = new float[n];
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            ex[i] = xy[2 * j] - xy[2 * i];
            ey[i] = xy[2 * j + 1] - xy[2 * i + 1];
            float l = ex[i] * ex[i] + ey[i] * ey[i];
            inv[i] = l > 0f ? 1f / l : 0f;
            minX = Math.min(minX, xy[2 * i]);
            maxX = Math.max(maxX, xy[2 * i]);
            minY = Math.min(minY, xy[2 * i + 1]);
            maxY = Math.max(maxY, xy[2 * i + 1]);
        }
        return new Shape() {
            @Override
            float d(float x, float y) {
                float best = Float.MAX_VALUE;
                boolean inside = false;
                for (int i = 0, j = n - 1; i < n; j = i++) {
                    float vx = xy[2 * i], vy = xy[2 * i + 1];
                    float wx = x - vx, wy = y - vy;
                    float t = (wx * ex[i] + wy * ey[i]) * inv[i];
                    t = t < 0f ? 0f : (t > 1f ? 1f : t);
                    float bx = wx - ex[i] * t, by = wy - ey[i] * t;
                    float dd = bx * bx + by * by;
                    if (dd < best) best = dd;
                    boolean c1 = y >= vy, c2 = y < xy[2 * j + 1], c3 = ex[i] * wy > ey[i] * wx;
                    if ((c1 && c2 && c3) || (!c1 && !c2 && !c3)) inside = !inside;
                }
                float dist = (float) Math.sqrt(best);
                return inside ? -dist : dist;
            }
        }.bounds(minX, minY, maxX, maxY);
    }

    /** A rectangle {@code w} x {@code h} centred on cx, cy, turned by {@code deg}, its corners rounded by {@code r}. */
    static Shape rect(float cx, float cy, float w, float h, float deg, float r) {
        float c = (float) Math.cos(Math.toRadians(deg)), s = (float) Math.sin(Math.toRadians(deg));
        float hw = Math.max(0.5f, w / 2f - r), hh = Math.max(0.5f, h / 2f - r);
        float[] xy = new float[8];
        float[][] k = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}};
        for (int i = 0; i < 4; i++) {
            float u = k[i][0] * hw, v = k[i][1] * hh;
            xy[2 * i] = cx + u * c - v * s;
            xy[2 * i + 1] = cy + u * s + v * c;
        }
        Shape p = poly(xy);
        return r > 0f ? p.grow(r) : p;
    }

    /** A regular polygon or star: {@code n} points at radius {@code ro}, and between them {@code ri} (0: none). */
    static Shape star(float cx, float cy, float ro, float ri, int n, float deg) {
        int k = ri > 0f ? 2 * n : n;
        float[] xy = new float[2 * k];
        for (int i = 0; i < k; i++) {
            float r = ri > 0f && i % 2 == 1 ? ri : ro;
            double a = Math.toRadians(deg - 90.0) + 2 * Math.PI * i / k;
            xy[2 * i] = cx + r * (float) Math.cos(a);
            xy[2 * i + 1] = cy + r * (float) Math.sin(a);
        }
        return poly(xy);
    }

    /** A ring at radius {@code r}, {@code t} thick. */
    static Shape ring(final float cx, final float cy, final float r, final float t) {
        return new Shape() {
            @Override
            float d(float x, float y) {
                float dx = x - cx, dy = y - cy;
                return Math.abs((float) Math.sqrt(dx * dx + dy * dy) - r) - t / 2f;
            }
        }.bounds(cx - r - t, cy - r - t, cx + r + t, cy + r + t);
    }

    /**
     * An arc of a ring at radius {@code r}, {@code t} thick, from angle {@code from} clockwise to {@code to} (degrees,
     * 0 pointing right), with round ends.
     */
    static Shape arc(final float cx, final float cy, final float r, final float t, float from, float to) {
        while (to < from) to += 360f;
        final double a0 = Math.toRadians(from), span = Math.toRadians(to - from);
        final float ax = cx + r * (float) Math.cos(a0), ay = cy + r * (float) Math.sin(a0);
        final float bx = cx + r * (float) Math.cos(a0 + span), by = cy + r * (float) Math.sin(a0 + span);
        return new Shape() {
            @Override
            float d(float x, float y) {
                float dx = x - cx, dy = y - cy;
                double a = Math.atan2(dy, dx) - a0;
                a = a - 2 * Math.PI * Math.floor(a / (2 * Math.PI));
                if (a <= span) return Math.abs((float) Math.sqrt(dx * dx + dy * dy) - r) - t / 2f;
                float d1 = (float) Math.hypot(x - ax, y - ay), d2 = (float) Math.hypot(x - bx, y - by);
                return Math.min(d1, d2) - t / 2f;
            }
        }.bounds(cx - r - t, cy - r - t, cx + r + t, cy + r + t);
    }

    /** A straight band from one point to another, {@code t} wide, with round ends. */
    static Shape band(final float ax, final float ay, final float bx, final float by, final float t) {
        final float ex = bx - ax, ey = by - ay, inv = 1f / Math.max(1e-6f, ex * ex + ey * ey);
        return new Shape() {
            @Override
            float d(float x, float y) {
                float wx = x - ax, wy = y - ay;
                float k = Math.max(0f, Math.min(1f, (wx * ex + wy * ey) * inv));
                float dx = wx - ex * k, dy = wy - ey * k;
                return (float) Math.sqrt(dx * dx + dy * dy) - t / 2f;
            }
        }.bounds(Math.min(ax, bx) - t, Math.min(ay, by) - t, Math.max(ax, bx) + t, Math.max(ay, by) + t);
    }

    /**
     * A ribbon along a cubic curve (four points x, y), {@code w0} wide at its start and {@code w1} at its end, cut
     * square: Netflix's ribbon, a swoosh.
     */
    static Shape ribbon(float[] c, float w0, float w1) {
        int n = 40;
        float[] left = new float[2 * (n + 1)], right = new float[2 * (n + 1)];
        for (int i = 0; i <= n; i++) {
            float t = i / (float) n, u = 1f - t;
            float x = u * u * u * c[0] + 3 * u * u * t * c[2] + 3 * u * t * t * c[4] + t * t * t * c[6];
            float y = u * u * u * c[1] + 3 * u * u * t * c[3] + 3 * u * t * t * c[5] + t * t * t * c[7];
            float dx = 3 * u * u * (c[2] - c[0]) + 6 * u * t * (c[4] - c[2]) + 3 * t * t * (c[6] - c[4]);
            float dy = 3 * u * u * (c[3] - c[1]) + 6 * u * t * (c[5] - c[3]) + 3 * t * t * (c[7] - c[5]);
            float l = (float) Math.sqrt(dx * dx + dy * dy);
            if (l < 1e-6f) l = 1f;
            float hw = (w0 + (w1 - w0) * t) / 2f, nx = -dy / l * hw, ny = dx / l * hw;
            left[2 * i] = x + nx;
            left[2 * i + 1] = y + ny;
            right[2 * (n - i)] = x - nx;
            right[2 * (n - i) + 1] = y - ny;
        }
        float[] xy = new float[left.length + right.length];
        System.arraycopy(left, 0, xy, 0, left.length);
        System.arraycopy(right, 0, xy, left.length, right.length);
        return poly(xy);
    }

    /** The plane on one side of a line through x, y at {@code deg}: inside to its left (y up being "left" at 0). */
    static Shape half(final float x, final float y, float deg) {
        final float nx = (float) -Math.sin(Math.toRadians(deg)), ny = (float) Math.cos(Math.toRadians(deg));
        return new Shape() {
            @Override
            float d(float px, float py) {
                return (px - x) * nx + (py - y) * ny;
            }
        }.bounds(-DW, -DH, 2 * DW, 2 * DH);
    }

    // ---- inks ----------------------------------------------------------------------------------------------

    /**
     * How a shape is painted: one flat colour (or a two-colour gradient), mostly left plain, with sparse long
     * dry-brush strokes of one close tone across part of it, all one way; and edges cut by hand, wavering a little.
     */
    static final class Ink {
        final int colour;
        int colour2;
        float gx0, gy0, gx1, gy1;
        boolean gradient;
        /** The strokes' tone (0: none) and their direction, in degrees. */
        int tone;
        float angle;
        /** How much of the shape the strokes cover, about: 0..1. */
        float amount = 0.22f;
        /** How far the shape darkens away from the light (top left), 0..1: a little roundness. */
        float shade = 0.07f;
        /** How far the edges waver, in design units. */
        float wobble = 5f;
        float alpha = 1f;

        Ink(int colour) {
            this.colour = colour;
        }

        /** Strokes of {@code tone} along {@code deg}. */
        Ink strokes(int tone, float deg) {
            this.tone = tone;
            this.angle = deg;
            return this;
        }

        Ink wobble(float w) {
            this.wobble = w;
            return this;
        }

        Ink alpha(float a) {
            this.alpha = a;
            return this;
        }

        /** From {@code colour} at x0, y0 to {@code to} at x1, y1. */
        Ink gradient(int to, float x0, float y0, float x1, float y1) {
            this.colour2 = to;
            this.gx0 = x0;
            this.gy0 = y0;
            this.gx1 = x1;
            this.gy1 = y1;
            this.gradient = true;
            return this;
        }
    }

    static Ink ink(int colour) {
        return new Ink(colour);
    }

    // ---- the easel -----------------------------------------------------------------------------------------

    /** Where a picture is painted: its pixels, the scale from design units, and its noise. */
    static final class Easel {
        private static final int N = 256;
        /** Pixels per grid cell: cells wholly inside or outside a shape skip its distance. */
        private static final int G = 8;

        final int w, h;
        final int[] px;
        /** Pixels per design unit, and the design space's offset in pixels (when the output isn't 16:9). */
        final float s;
        private final float ox, oy;
        private final float[] table = new float[N * N];
        private long rnd;
        private final long seed;

        Easel(int w, int h, long seed) {
            this.w = w;
            this.h = h;
            this.px = new int[w * h];
            this.s = Math.max(w / DW, h / DH);
            this.ox = (w - DW * s) / 2f;
            this.oy = (h - DH * s) / 2f;
            this.seed = seed;
            this.rnd = seed ^ 0x9E3779B97F4A7C15L;
            for (int i = 0; i < table.length; i++) table[i] = rand();
        }

        /** A random number in [0, 1), the same sequence for the same app. */
        float rand() {
            rnd ^= rnd << 13;
            rnd ^= rnd >>> 7;
            rnd ^= rnd << 17;
            return ((rnd >>> 40) & 0xFFFFFF) / (float) (1 << 24);
        }

        float rand(float a, float b) {
            return a + (b - a) * rand();
        }

        // -- noise

        /** Value noise in [0, 1], smooth, repeating every 256 units. */
        float noise(float x, float y) {
            int xi = (int) Math.floor(x), yi = (int) Math.floor(y);
            float fx = x - xi, fy = y - yi;
            fx = fx * fx * (3f - 2f * fx);
            fy = fy * fy * (3f - 2f * fy);
            int x0 = xi & (N - 1), x1 = (xi + 1) & (N - 1), y0 = (yi & (N - 1)) * N, y1 = ((yi + 1) & (N - 1)) * N;
            float a = table[y0 + x0], b = table[y0 + x1], c = table[y1 + x0], d = table[y1 + x1];
            float top = a + (b - a) * fx, bottom = c + (d - c) * fx;
            return top + (bottom - top) * fy;
        }

        /** Three octaves of noise, in [0, 1]. */
        float fbm(float x, float y) {
            float v = noise(x, y) * 0.57f;
            v += noise(x * 2.03f + 17.1f, y * 2.03f + 5.3f) * 0.29f;
            v += noise(x * 4.11f + 3.7f, y * 4.11f + 41.9f) * 0.14f;
            return v;
        }

        /**
         * How far an edge is moved here, within {@code amount}: a slow wave, as a shape cut out by hand, and a much
         * smaller quick one, as its paper's grain.
         */
        float wobble(float x, float y, float amount) {
            return amount * (1.6f * (noise(x * 0.0045f + 51f, y * 0.0045f + 7f) - 0.5f)
                    + 0.5f * (noise(x * 0.05f + 3f, y * 0.05f + 19f) - 0.5f));
        }

        // -- painting

        /** The ground: one colour, faintly mottled like paint on paper. */
        void ground(int colour) {
            int r = (colour >> 16) & 0xFF, g = (colour >> 8) & 0xFF, b = colour & 0xFF;
            for (int y = 0; y < h; y++) {
                float dy = (y - oy) / s;
                for (int x = 0; x < w; x++) {
                    float dx = (x - ox) / s;
                    float m = 1f + 0.06f * (fbm(dx * 0.0025f + 90f, dy * 0.0025f + 13f) - 0.5f);
                    px[y * w + x] = 0xFF000000 | clamp(Math.round(r * m)) << 16 | clamp(Math.round(g * m)) << 8
                            | clamp(Math.round(b * m));
                }
            }
        }

        /** A soft pool of light (or shade, with a dark colour), {@code r} wide, at most {@code a} strong. */
        void glow(float cx, float cy, float r, int colour, float a) {
            int x0 = Math.max(0, (int) Math.floor(ox + (cx - r) * s)), x1 = Math.min(w, (int) Math.ceil(ox + (cx + r) * s));
            int y0 = Math.max(0, (int) Math.floor(oy + (cy - r) * s)), y1 = Math.min(h, (int) Math.ceil(oy + (cy + r) * s));
            for (int y = y0; y < y1; y++) {
                float dy = (y + 0.5f - oy) / s - cy;
                for (int x = x0; x < x1; x++) {
                    float dx = (x + 0.5f - ox) / s - cx;
                    float t = 1f - (float) Math.sqrt(dx * dx + dy * dy) / r;
                    if (t <= 0f) continue;
                    int i = y * w + x;
                    px[i] = blend(px[i], colour, a * t * t * (3f - 2f * t));
                }
            }
        }

        /** Paints a shape. */
        void fill(Shape sh, Ink ink) {
            float pad = ink.wobble + 2f / s;
            int px0 = Math.max(0, (int) Math.floor(ox + (sh.x0 - pad) * s));
            int py0 = Math.max(0, (int) Math.floor(oy + (sh.y0 - pad) * s));
            int px1 = Math.min(w, (int) Math.ceil(ox + (sh.x1 + pad) * s));
            int py1 = Math.min(h, (int) Math.ceil(oy + (sh.y1 + pad) * s));
            if (px0 >= px1 || py0 >= py1) return;
            int gw = (px1 - px0 + G - 1) / G, gh = (py1 - py0 + G - 1) / G;
            float[] grid = new float[(gw + 1) * (gh + 1)];
            for (int j = 0; j <= gh; j++) {
                for (int i = 0; i <= gw; i++) {
                    grid[j * (gw + 1) + i] = sh.d((px0 + i * G - ox) / s, (py0 + j * G - oy) / s);
                }
            }
            // A cell whose corners are all further from the edge than its diagonal (plus the wobble) is wholly
            // inside or outside: distance fields change no faster than the distance moved.
            float margin = G * 1.5f / s + ink.wobble + 1f / s;
            Painter p = new Painter(this, ink);
            boolean brushed = ink.tone != 0 && ink.amount > 0f;
            Mask mask = brushed ? new Mask(px0, py0, px1 - px0, py1 - py0) : null;
            for (int j = 0; j < gh; j++) {
                for (int i = 0; i < gw; i++) {
                    float a = grid[j * (gw + 1) + i], b = grid[j * (gw + 1) + i + 1];
                    float c = grid[(j + 1) * (gw + 1) + i], d = grid[(j + 1) * (gw + 1) + i + 1];
                    int cx0 = px0 + i * G, cy0 = py0 + j * G;
                    int cx1 = Math.min(px1, cx0 + G), cy1 = Math.min(py1, cy0 + G);
                    if (a > margin && b > margin && c > margin && d > margin) continue;
                    boolean whole = a < -margin && b < -margin && c < -margin && d < -margin;
                    for (int y = cy0; y < cy1; y++) {
                        float dy = (y + 0.5f - oy) / s;
                        for (int x = cx0; x < cx1; x++) {
                            float dx = (x + 0.5f - ox) / s;
                            float cov = 1f;
                            if (!whole) {
                                float dist = sh.d(dx, dy);
                                if (ink.wobble > 0f) dist += wobble(dx, dy, ink.wobble);
                                cov = 0.5f - dist * s;
                                if (cov <= 0f) continue;
                                if (cov > 1f) cov = 1f;
                            }
                            p.paint(y * w + x, dx, dy, cov);
                            if (mask != null) mask.set(x, y, cov);
                        }
                    }
                }
            }
            if (brushed) sweeps(sh, ink, mask);
        }

        /** Where a shape was painted, and how much: brush strokes over it stay inside it. */
        private static final class Mask {
            final int x0, y0, w, h;
            final byte[] a;

            Mask(int x0, int y0, int w, int h) {
                this.x0 = x0;
                this.y0 = y0;
                this.w = w;
                this.h = h;
                this.a = new byte[w * h];
            }

            void set(int x, int y, float cov) {
                a[(y - y0) * w + (x - x0)] = (byte) Math.round(cov * 255f);
            }

            float at(int x, int y) {
                x -= x0;
                y -= y0;
                if (x < 0 || y < 0 || x >= w || y >= h) return 0f;
                return (a[y * w + x] & 0xFF) / 255f;
            }
        }

        /**
         * Dry-brush strokes over a painted shape, as the reference's: a few long sweeps of a close tone, all one way
         * and curving a little, each made of bristles that run out at different lengths, so it fades in streaks.
         * Most of the shape stays plain paint.
         */
        private void sweeps(Shape sh, Ink ink, Mask mask) {
            float bw = sh.x1 - sh.x0, bh = sh.y1 - sh.y0, size = Math.min(bw, bh);
            if (size < 40f) return;
            float ca = (float) Math.cos(Math.toRadians(ink.angle)), sa = (float) Math.sin(Math.toRadians(ink.angle));
            float cx = (sh.x0 + sh.x1) / 2f, cy = (sh.y0 + sh.y1) / 2f;
            float reach = (float) Math.hypot(bw, bh) / 2f;
            // A sweep or two on a small shape, a few more on a big one; most of it plain all the same.
            int n = 1 + Math.round(ink.amount * 7f * Math.min(1.3f, (bw * bh) / (600f * 600f) + 0.4f));
            for (int k = 0; k < n; k++) {
                // Where it starts across the shape, how long and wide it is, how it bends.
                float across = rand(-0.75f, 0.75f) * reach, along0 = rand(-1f, 0.2f) * reach;
                float len = reach * rand(0.7f, 1.3f), wid = Math.min(220f, size * rand(0.14f, 0.28f));
                float bend = rand(-0.18f, 0.18f) * len;
                int bristles = 6 + Math.round(wid / 18f);
                for (int b = 0; b < bristles; b++) {
                    float off = (b / (float) (bristles - 1) - 0.5f) * wid;
                    // Each bristle runs out somewhere along the stroke; the middle ones furthest.
                    float edge = Math.abs(b / (float) (bristles - 1) - 0.5f) * 2f;
                    float run = len * rand(0.45f, 1f) * (1f - 0.4f * edge);
                    float start = rand(0f, 0.18f) * len;
                    int segs = 10;
                    float[] pts = new float[2 * (segs + 1)], taper = new float[segs + 1];
                    for (int i = 0; i <= segs; i++) {
                        float t = i / (float) segs, a = along0 + start + run * t;
                        float c = across + off + bend * (float) Math.sin(Math.PI * (a - along0) / len);
                        pts[2 * i] = cx + a * ca - c * sa;
                        pts[2 * i + 1] = cy + a * sa + c * ca;
                        taper[i] = (float) Math.sin(Math.PI * Math.min(1f, t * 1.15f + 0.04f)) * 0.9f + 0.1f;
                    }
                    float bwid = wid / bristles * rand(0.7f, 1.5f);
                    Ink line = new Ink(ink.tone).alpha(rand(0.7f, 1f));
                    stroke(pts, bwid, taper, line, null, mask);
                }
            }
        }

        /**
         * A line through the points x, y, ..., {@code width} wide (each point's width scaled by {@code taper}, if
         * given), painted only inside {@code clip} if given.
         */
        void stroke(float[] pts, float width, float[] taper, Ink ink, Shape clip) {
            stroke(pts, width, taper, ink, clip, null);
        }

        private void stroke(float[] pts, float width, float[] taper, Ink ink, Shape clip, Mask mask) {
            int n = pts.length / 2;
            if (n < 2) return;
            float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
            for (int i = 0; i < n; i++) {
                minX = Math.min(minX, pts[2 * i]);
                maxX = Math.max(maxX, pts[2 * i]);
                minY = Math.min(minY, pts[2 * i + 1]);
                maxY = Math.max(maxY, pts[2 * i + 1]);
            }
            float pad = width + 2f / s;
            int bx0 = Math.max(0, (int) Math.floor(ox + (minX - pad) * s));
            int by0 = Math.max(0, (int) Math.floor(oy + (minY - pad) * s));
            int bx1 = Math.min(w, (int) Math.ceil(ox + (maxX + pad) * s));
            int by1 = Math.min(h, (int) Math.ceil(oy + (maxY + pad) * s));
            if (bx0 >= bx1 || by0 >= by1) return;
            int bw = bx1 - bx0;
            float[] cov = new float[bw * (by1 - by0)];
            for (int k = 0; k < n - 1; k++) {
                float ax = pts[2 * k], ay = pts[2 * k + 1], ex = pts[2 * k + 2] - ax, ey = pts[2 * k + 3] - ay;
                float wa = width * (taper == null ? 1f : taper[k]) / 2f;
                float wb = width * (taper == null ? 1f : taper[k + 1]) / 2f;
                float hw = Math.max(wa, wb) + 1f / s;
                int sx0 = Math.max(bx0, (int) Math.floor(ox + (Math.min(ax, ax + ex) - hw) * s));
                int sx1 = Math.min(bx1, (int) Math.ceil(ox + (Math.max(ax, ax + ex) + hw) * s));
                int sy0 = Math.max(by0, (int) Math.floor(oy + (Math.min(ay, ay + ey) - hw) * s));
                int sy1 = Math.min(by1, (int) Math.ceil(oy + (Math.max(ay, ay + ey) + hw) * s));
                float inv = 1f / Math.max(1e-6f, ex * ex + ey * ey);
                for (int y = sy0; y < sy1; y++) {
                    float dy = (y + 0.5f - oy) / s - ay;
                    for (int x = sx0; x < sx1; x++) {
                        float dx = (x + 0.5f - ox) / s - ax;
                        float t = Math.max(0f, Math.min(1f, (dx * ex + dy * ey) * inv));
                        float qx = dx - ex * t, qy = dy - ey * t;
                        float dist = (float) Math.sqrt(qx * qx + qy * qy) - (wa + (wb - wa) * t);
                        float c = 0.5f - dist * s;
                        if (c <= 0f) continue;
                        int i = (y - by0) * bw + (x - bx0);
                        if (c > cov[i]) cov[i] = Math.min(1f, c);
                    }
                }
            }
            Painter p = new Painter(this, ink);
            for (int y = by0; y < by1; y++) {
                float dy = (y + 0.5f - oy) / s;
                for (int x = bx0; x < bx1; x++) {
                    float c = cov[(y - by0) * bw + (x - bx0)];
                    if (c <= 0f) continue;
                    float dx = (x + 0.5f - ox) / s;
                    if (mask != null) {
                        float k = mask.at(x, y);
                        if (k <= 0f) continue;
                        c *= k;
                    }
                    if (clip != null) {
                        float k = 0.5f - clip.d(dx, dy) * s;
                        if (k <= 0f) continue;
                        if (k < 1f) c *= k;
                    }
                    p.paint(y * w + x, dx, dy, c);
                }
            }
        }

        /**
         * A bold hand-drawn scribble, as the reference's: a pen {@code width} wide drawing a coil of loose ovals,
         * {@code loops} of them, across about {@code r} either side of cx, cy, with a few drips running off it along
         * {@code dripDeg}; painted only inside {@code clip} if given.
         */
        void scribble(float cx, float cy, float r, float width, float loops, float dripDeg, Ink ink, Shape clip) {
            int steps = Math.max(60, Math.round(loops * 64f));
            float[] pts = new float[2 * steps];
            float[] taper = new float[steps];
            // The coil runs across the drips; its ovals lie across its way.
            double along = Math.toRadians(dripDeg - 75f + rand(-15f, 15f));
            float dx = (float) Math.cos(along), dy = (float) Math.sin(along), qx = -dy, qy = dx;
            float t0 = rand(0f, 200f), phase = rand(0f, 6.28f), way = rand() < 0.5f ? -1f : 1f;
            // A hand doesn't keep time: the pen moves on unevenly and turns unevenly, so loops bunch up and spread
            // out, swell and shrink, and lean one way then the other.
            float[] adv = new float[steps], turn = new float[steps];
            float sumA = 0f, sumT = 0f;
            for (int i = 0; i < steps; i++) {
                adv[i] = sumA;
                turn[i] = sumT;
                float na = noise(t0 + i * 0.05f, 1.1f), nt = noise(t0 + i * 0.06f, 5.5f);
                sumA += 0.08f + 2.6f * na * na * na;
                sumT += 0.35f + 1.3f * nt * nt;
            }
            for (int i = 0; i < steps; i++) {
                float t = i / (float) (steps - 1), ta = adv[i] / sumA;
                float th = way * (float) (2 * Math.PI * loops) * turn[i] / sumT + phase;
                float g = (ta - 0.5f) * 1.7f * r;
                float bend = (noise(t0 + t * 2.2f, 3.3f) - 0.5f) * 0.7f * r;
                float rad = r * (0.20f + 0.42f * noise(t0 + t * 4f, 7.9f));
                float lean = (noise(t0 + t * 3f, 12.5f) - 0.5f) * 1.2f, cl = (float) Math.cos(lean), sl = (float) Math.sin(lean);
                float ou = rad * 0.6f * (float) Math.cos(th), ov = rad * (float) Math.sin(th);
                float u = g + ou * cl - ov * sl, v = bend + ou * sl + ov * cl;
                pts[2 * i] = cx + u * dx + v * qx;
                pts[2 * i + 1] = cy + u * dy + v * qy;
                float end = Math.min(1f, Math.min(i, steps - 1 - i) / 8f);
                taper[i] = (0.75f + 0.45f * noise(t0 + i * 0.05f, 4.4f)) * (0.35f + 0.65f * end);
            }
            stroke(pts, width, taper, ink, clip);
            // Drips: paint running off the line, all one way, thinning out.
            float dc = (float) Math.cos(Math.toRadians(dripDeg)), ds = (float) Math.sin(Math.toRadians(dripDeg));
            int drips = 3 + (int) (rand() * 3f);
            for (int k = 0; k < drips; k++) {
                int at = (int) (rand(0.1f, 0.9f) * steps);
                float len = r * rand(0.10f, 0.28f), sx = pts[2 * at], sy = pts[2 * at + 1], w1 = rand(-0.08f, 0.08f);
                float[] d = {sx, sy, sx + (dc - ds * w1) * len * 0.5f, sy + (ds + dc * w1) * len * 0.5f,
                        sx + dc * len, sy + ds * len};
                stroke(d, width, new float[]{0.85f, 0.65f, 0.35f}, ink, clip);
            }
        }

        /**
         * Last: a fine grain, which keeps gradients from banding and gives the paint a little tooth; never on the
         * black, which stays true black, and the faint haze over it goes (TrueBlack).
         */
        void finish() {
            long hseed = seed * 0x2545F4914F6CDD1DL;
            for (int i = 0; i < px.length; i++) {
                int p = px[i];
                if ((p & 0xFFFFFF) == 0) continue;
                long z = (i + hseed) * 0x9E3779B97F4A7C15L;
                z = (z ^ (z >>> 31)) * 0xBF58476D1CE4E5B9L;
                int n = (int) ((z >>> 59) & 0x7) - 4;   // -4..3
                int r = clamp(((p >> 16) & 0xFF) + n), g = clamp(((p >> 8) & 0xFF) + n), b = clamp((p & 0xFF) + n);
                px[i] = 0xFF000000 | r << 16 | g << 8 | b;
            }
            TrueBlack.toe(px, px.length, TrueBlack.FAINT_KNEE, TrueBlack.FAINT_END);
        }
    }

    /** Puts an ink's colour on pixels: its gradient, and a little shade away from the light. */
    private static final class Painter {
        private final Easel e;
        private final Ink ink;
        private final int dark;

        Painter(Easel e, Ink ink) {
            this.e = e;
            this.ink = ink;
            this.dark = tone(ink.colour, -0.5f);
        }

        void paint(int i, float x, float y, float cov) {
            int c = ink.colour;
            if (ink.gradient) {
                float gx = ink.gx1 - ink.gx0, gy = ink.gy1 - ink.gy0, len = gx * gx + gy * gy;
                if (len > 0f) {
                    float t = ((x - ink.gx0) * gx + (y - ink.gy0) * gy) / len;
                    c = mix(ink.colour, ink.colour2, Math.max(0f, Math.min(1f, t)));
                }
            } else if (ink.shade > 0f) {
                // Light from the top left: a slow, even falloff across the screen.
                float t = Math.max(0f, Math.min(1f, (x / DW + y / DH) * 0.5f));
                c = mix(c, dark, ink.shade * t);
            }
            e.px[i] = blend(e.px[i], c, cov * ink.alpha);
        }
    }

    // ---- colour --------------------------------------------------------------------------------------------

    static int blend(int dst, int src, float a) {
        if (a >= 1f) return 0xFF000000 | (src & 0xFFFFFF);
        if (a <= 0f) return dst;
        return mix(dst, src, a);
    }

    /** From {@code a} at 0 to {@code b} at 1, opaque. */
    static int mix(int a, int b, float t) {
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int r = Math.round(ar + (((b >> 16) & 0xFF) - ar) * t);
        int g = Math.round(ag + (((b >> 8) & 0xFF) - ag) * t);
        int bl = Math.round(ab + ((b & 0xFF) - ab) * t);
        return 0xFF000000 | r << 16 | g << 8 | bl;
    }

    /** A colour from hue (degrees), saturation and lightness (0..1), as CSS's hsl(). */
    static int hsl(float h, float sat, float l) {
        h = ((h % 360f) + 360f) % 360f / 360f;
        float q = l < 0.5f ? l * (1f + sat) : l + sat - l * sat, p = 2f * l - q;
        return 0xFF000000 | channel(p, q, h + 1f / 3f) << 16 | channel(p, q, h) << 8 | channel(p, q, h - 1f / 3f);
    }

    private static int channel(float p, float q, float t) {
        if (t < 0f) t += 1f;
        if (t > 1f) t -= 1f;
        float v = t < 1f / 6f ? p + (q - p) * 6f * t : t < 0.5f ? q : t < 2f / 3f ? p + (q - p) * (2f / 3f - t) * 6f : p;
        return clamp(Math.round(v * 255f));
    }

    /** {@code c} with its lightness moved by {@code dl} (-1..1), its hue and saturation kept: a close tone of it. */
    static int tone(int c, float dl) {
        float r = ((c >> 16) & 0xFF) / 255f, g = ((c >> 8) & 0xFF) / 255f, b = (c & 0xFF) / 255f;
        float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b)), l = (max + min) / 2f;
        float h = 0f, sat = 0f, d = max - min;
        if (d > 1e-6f) {
            sat = l > 0.5f ? d / (2f - max - min) : d / (max + min);
            if (max == r) h = (g - b) / d + (g < b ? 6f : 0f);
            else if (max == g) h = (b - r) / d + 2f;
            else h = (r - g) / d + 4f;
            h *= 60f;
        }
        return hsl(h, sat, Math.max(0f, Math.min(1f, l + dl)));
    }


    private static int clamp(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }
}
