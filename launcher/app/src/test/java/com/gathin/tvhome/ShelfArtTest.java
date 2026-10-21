package com.gathin.tvhome;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ShelfArtTest {

    /** Small renders: the design space is scaled, so a 384 x 216 picture is the same as the 1920 x 1080 one. */
    private static final int W = 384, H = 216;
    private static final Map<String, int[]> RENDERS = new HashMap<>();

    private static int[] render(String key) {
        return RENDERS.computeIfAbsent(key, k -> ShelfArt.paint(ShelfArtworks.byKey(k), k, W, H));
    }

    private static float luma(int p) {
        return (0.2126f * ((p >> 16) & 0xFF) + 0.7152f * ((p >> 8) & 0xFF) + 0.0722f * (p & 0xFF)) / 255f;
    }

    /** Pixels of the calm middle, where the logo goes (a little inside it). */
    private static List<Integer> calm(int[] px) {
        List<Integer> out = new ArrayList<>();
        float s = W / ShelfArt.DW;
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                float dx = ((x + 0.5f) / s - ShelfArt.CALM_X) / (ShelfArt.CALM_RX * 0.95f);
                float dy = ((y + 0.5f) / s - ShelfArt.CALM_Y) / (ShelfArt.CALM_RY * 0.95f);
                if (dx * dx + dy * dy <= 1f) out.add(px[y * W + x]);
            }
        }
        return out;
    }

    @Test
    public void everyAppWithArtworkGetsItsPicture() {
        assertTrue(ShelfArtworks.keys().size() >= 30);
        for (Map.Entry<String, String> e : ShelfArtworks.packages().entrySet()) {
            assertTrue(e.getKey(), ShelfArt.has(e.getKey()));
            assertNotNull(e.getKey(), ShelfArtworks.byKey(e.getValue()));
        }
        assertTrue(ShelfArt.has("com.netflix.ninja"));
        assertTrue(ShelfArt.has("com.formulaone.production"));
        // TCL's apps share one.
        assertEquals(ShelfArtworks.key("com.tcl.tv"), ShelfArtworks.key("com.tcl.dashboard"));
        assertFalse(ShelfArt.has("com.example.unknown"));
        assertFalse(ShelfArt.has(null));
        assertNull(ShelfArt.render("com.example.unknown", W, H));
        assertNull(ShelfArt.render("com.netflix.ninja", 8, 4));
    }

    @Test
    public void paintsTheSamePictureEveryTime() {
        int[] a = ShelfArt.render("com.netflix.ninja", W, H);
        int[] b = ShelfArt.render("com.netflix.ninja", W, H);
        assertNotNull(a);
        assertArrayEquals(a, b);
        for (int p : a) assertEquals(0xFF, p >>> 24);
    }

    @Test
    public void keepsTheMiddleCalmAndDarkForTheLogo() {
        for (String key : ShelfArtworks.keys()) {
            List<Integer> calm = calm(render(key));
            float sum = 0f;
            int bright = 0;
            for (int p : calm) {
                float l = luma(p);
                sum += l;
                if (l > 0.3f) bright++;
            }
            // A white logo and name read on it: dark, with hardly anything painted there.
            assertTrue(key + " middle too light", sum / calm.size() < 0.16f);
            assertTrue(key + " shapes under the logo: " + bright, bright < calm.size() * 0.01f);
        }
    }

    @Test
    public void fillsTheEdgesAndStaysMidToDark() {
        int band = (int) (W * 0.18f);
        for (String key : ShelfArtworks.keys()) {
            int[] px = render(key);
            int ground = px[(int) (ShelfArt.CALM_Y / ShelfArt.DH * H) * W + W / 2];
            int painted = 0, left = 0, right = 0;
            float sum = 0f;
            for (int i = 0; i < px.length; i++) {
                int p = px[i], x = i % W;
                boolean paint = CoverArt.distance(p, ground) > 0.12f;
                if (paint) painted++;
                if (paint && x < band) left++;
                if (paint && x >= W - band) right++;
                sum += luma(p);
            }
            float share = painted / (float) px.length;
            assertTrue(key + " too bare: " + share, share > 0.25f);
            assertTrue(key + " too full: " + share, share < 0.75f);
            // Shapes cluster at both sides, running off the edges, as in the reference.
            float sides = band * H;
            assertTrue(key + " left edge bare: " + left / sides, left / sides > 0.45f);
            assertTrue(key + " right edge bare: " + right / sides, right / sides > 0.45f);
            // The dock's glass and white titles sit on it.
            assertTrue(key + " too light", sum / px.length < 0.42f);
        }
    }

    @Test
    public void noTwoLookAlike() {
        List<String> keys = new ArrayList<>(ShelfArtworks.keys());
        Map<String, float[]> small = new HashMap<>();
        for (String key : keys) small.put(key, thumbnail(render(key), 16, 9));
        for (int i = 0; i < keys.size(); i++) {
            for (int j = i + 1; j < keys.size(); j++) {
                float[] a = small.get(keys.get(i)), b = small.get(keys.get(j));
                float d = 0f;
                for (int k = 0; k < a.length; k++) d += Math.abs(a[k] - b[k]);
                d /= a.length;
                assertTrue(keys.get(i) + " and " + keys.get(j) + " look alike: " + d, d > 0.08f);
            }
        }
    }

    /** RGB averages over a {@code tw} x {@code th} grid, 0..1. */
    private static float[] thumbnail(int[] px, int tw, int th) {
        float[] out = new float[tw * th * 3];
        int bw = W / tw, bh = H / th;
        for (int ty = 0; ty < th; ty++) {
            for (int tx = 0; tx < tw; tx++) {
                float r = 0f, g = 0f, b = 0f;
                for (int y = ty * bh; y < (ty + 1) * bh; y++) {
                    for (int x = tx * bw; x < (tx + 1) * bw; x++) {
                        int p = px[y * W + x];
                        r += (p >> 16) & 0xFF;
                        g += (p >> 8) & 0xFF;
                        b += p & 0xFF;
                    }
                }
                float n = bw * bh * 255f;
                int o = 3 * (ty * tw + tx);
                out[o] = r / n;
                out[o + 1] = g / n;
                out[o + 2] = b / n;
            }
        }
        return out;
    }

    @Test
    public void hslAndTone() {
        assertEquals(0xFFFF0000, ShelfArt.hsl(0f, 1f, 0.5f));
        assertEquals(0xFF00FF00, ShelfArt.hsl(120f, 1f, 0.5f));
        assertEquals(0xFF0000FF, ShelfArt.hsl(-120f, 1f, 0.5f));
        assertEquals(0xFF808080, ShelfArt.hsl(200f, 0f, 0.5f));
        int red = ShelfArt.hsl(0f, 1f, 0.5f);
        assertTrue(luma(ShelfArt.tone(red, 0.1f)) > luma(red));
        assertTrue(luma(ShelfArt.tone(red, -0.1f)) < luma(red));
        assertEquals(0xFFFFFFFF, ShelfArt.tone(red, 1f));
    }

    @Test
    public void shapesKnowInsideFromOutside() {
        ShelfArt.Shape c = ShelfArt.circle(100f, 100f, 50f);
        assertTrue(c.d(100f, 100f) < 0f);
        assertEquals(0f, c.d(150f, 100f), 1e-3f);
        assertTrue(c.d(200f, 100f) > 0f);
        ShelfArt.Shape sq = ShelfArt.rect(0f, 0f, 100f, 100f, 0f, 0f);
        assertEquals(-50f, sq.d(0f, 0f), 1e-3f);
        assertEquals(10f, sq.d(60f, 0f), 1e-3f);
        ShelfArt.Shape ring = c.minus(ShelfArt.circle(100f, 100f, 30f));
        assertTrue(ring.d(100f, 100f) > 0f);
        assertTrue(ring.d(140f, 100f) < 0f);
        ShelfArt.Shape arc = ShelfArt.arc(0f, 0f, 100f, 10f, 0f, 90f);
        assertTrue(arc.d(0f, 100f) < 0f);
        assertTrue(arc.d(0f, -100f) > 0f);
    }

    /**
     * The whole collection on one sheet, in the order of ShelfArtworks.keys(), for a look:
     * SHELF_ART_SHEET=/tmp/sheet.png ./gradlew testDebugUnitTest --tests com.gathin.tvhome.ShelfArtTest. Skipped
     * otherwise.
     */
    @Test
    public void contactSheet() throws Exception {
        String out = System.getenv("SHELF_ART_SHEET");
        if (out == null || out.isEmpty()) return;
        int tw = 480, th = 270, gap = 12, cols = 5;
        List<String> keys = new ArrayList<>(ShelfArtworks.keys());
        int rows = (keys.size() + cols - 1) / cols;
        int sw = cols * (tw + gap) + gap, sh = rows * (th + gap) + gap;
        int[] sheet = new int[sw * sh];
        java.util.Arrays.fill(sheet, 0xFF18181A);
        for (int i = 0; i < keys.size(); i++) {
            int[] px = ShelfArt.paint(ShelfArtworks.byKey(keys.get(i)), keys.get(i), tw, th);
            int x0 = gap + (i % cols) * (tw + gap), y0 = gap + (i / cols) * (th + gap);
            for (int y = 0; y < th; y++) System.arraycopy(px, y * tw, sheet, (y0 + y) * sw + x0, tw);
        }
        try (java.io.OutputStream os = new java.io.FileOutputStream(new File(out))) {
            os.write(png(sheet, sw, sh));
        }
    }

    /** A plain RGB PNG (the JVM tests have no java.awt). */
    private static byte[] png(int[] px, int w, int h) throws java.io.IOException {
        java.io.ByteArrayOutputStream raw = new java.io.ByteArrayOutputStream();
        for (int y = 0; y < h; y++) {
            raw.write(0);
            for (int x = 0; x < w; x++) {
                int p = px[y * w + x];
                raw.write((p >> 16) & 0xFF);
                raw.write((p >> 8) & 0xFF);
                raw.write(p & 0xFF);
            }
        }
        java.io.ByteArrayOutputStream z = new java.io.ByteArrayOutputStream();
        try (java.util.zip.DeflaterOutputStream d = new java.util.zip.DeflaterOutputStream(z)) {
            raw.writeTo(d);
        }
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        out.write(new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
        java.nio.ByteBuffer ihdr = java.nio.ByteBuffer.allocate(13).putInt(w).putInt(h);
        ihdr.put((byte) 8).put((byte) 2).put((byte) 0).put((byte) 0).put((byte) 0);
        chunk(out, "IHDR", ihdr.array());
        chunk(out, "IDAT", z.toByteArray());
        chunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    private static void chunk(java.io.ByteArrayOutputStream out, String type, byte[] data) throws java.io.IOException {
        byte[] t = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        out.write(java.nio.ByteBuffer.allocate(4).putInt(data.length).array());
        out.write(t);
        out.write(data);
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(t);
        crc.update(data);
        out.write(java.nio.ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
    }
}
