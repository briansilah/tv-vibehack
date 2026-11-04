package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CoverArtTest {

    private static final int W = 64, H = 36;

    /** A banner: a plain background with a red block and a black block (a wordmark) on it. */
    private static int[] banner(int bg) {
        int[] px = new int[W * H];
        for (int i = 0; i < px.length; i++) px[i] = bg;
        fill(px, 8, 10, 24, 26, 0xFFE50914);   // red
        fill(px, 30, 12, 56, 24, 0xFF101010);  // black text
        fill(px, 14, 15, 18, 21, bg);          // a hole in the red block, in the background colour
        return px;
    }

    private static void fill(int[] px, int x0, int y0, int x1, int y1, int c) {
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) px[y * W + x] = c;
        }
    }

    @Test
    public void cutsTheLogoOutOfAPlainBanner() {
        CoverArt.Logo logo = CoverArt.cutOut(banner(0xFFF4F4F4), W, H);
        assertNotNull(logo);
        // Cropped to the blocks, with a little room.
        assertEquals(56 - 8 + 4, logo.w);
        assertEquals(26 - 10 + 4, logo.h);
        int ox = 8 - 2, oy = 10 - 2;
        // Background gone, red kept, black turned light for the dark cover.
        assertEquals(0, logo.argb[0] >>> 24);
        assertEquals(0xFFE50914, logo.argb[(12 - oy) * logo.w + (10 - ox)]);
        assertEquals(0xFFF2F2F2, logo.argb[(18 - oy) * logo.w + (40 - ox)]);
        // A shape inside the logo in the background colour stays (YouTube's white arrow).
        assertEquals(0xFF, logo.argb[(18 - oy) * logo.w + (16 - ox)] >>> 24);
    }

    @Test
    public void lettersLoseTheirInsides() {
        // An "o": a black ring on a light banner. Its inside is background, enclosed by grey, not colour: it goes.
        int bg = 0xFFF4F4F4;
        int[] px = new int[W * H];
        java.util.Arrays.fill(px, bg);
        fill(px, 20, 8, 40, 28, 0xFF0F0F0F);
        fill(px, 24, 12, 36, 24, bg);
        CoverArt.Logo logo = CoverArt.cutOut(px, W, H);
        assertNotNull(logo);
        int ox = 20 - 2, oy = 8 - 2;
        assertEquals(0, logo.argb[(18 - oy) * logo.w + (30 - ox)] >>> 24);
        assertEquals(0xFFF2F2F2, logo.argb[(9 - oy) * logo.w + (30 - ox)]);
        // A mid-grey anti-aliasing pixel is half kept, and light.
        px[10 * W + 22] = 0xFF808080;
        logo = CoverArt.cutOut(px, W, H);
        int a = logo.argb[(10 - oy) * logo.w + (22 - ox)] >>> 24;
        assertTrue(a > 80 && a < 200);
    }

    @Test
    public void keepsATransparentLogoAndOnlyCropsIt() {
        int[] px = banner(0x00000000);
        CoverArt.Logo logo = CoverArt.cutOut(px, W, H);
        assertNotNull(logo);
        assertEquals(0xFFE50914, logo.argb[(12 - 8) * logo.w + (10 - 6)]);
        // The hole is transparent in the source, so it stays transparent.
        assertEquals(0, logo.argb[(18 - 8) * logo.w + (16 - 6)] >>> 24);
    }

    @Test
    public void givesUpOnABusyBanner() {
        int[] px = new int[W * H];
        for (int i = 0; i < px.length; i++) px[i] = 0xFF000000 | (int) ((i * 2654435761L) & 0xFFFFFF);
        assertNull(CoverArt.cutOut(px, W, H));
        assertNull(CoverArt.cutOut(null, W, H));
        assertNull(CoverArt.cutOut(new int[4], 2, 2));
    }

    @Test
    public void givesUpWhenNothingIsLeftOrEverythingIs() {
        int[] plain = new int[W * H];
        java.util.Arrays.fill(plain, 0xFF336699);
        assertNull(CoverArt.cutOut(plain, W, H));
        // A frame in the edge colour around a photo-like block: it isn't a logo.
        int[] framed = new int[W * H];
        java.util.Arrays.fill(framed, 0xFFFFFFFF);
        fill(framed, 1, 1, W - 1, H - 1, 0xFF203040);
        assertNull(CoverArt.cutOut(framed, W, H));
    }

    @Test
    public void paletteFindsTheBannersColoursBiggestFirst() {
        int[] px = new int[100];
        for (int i = 0; i < 70; i++) px[i] = 0xFFE50914;   // red
        for (int i = 70; i < 90; i++) px[i] = 0xFF1A73E8;  // blue
        for (int i = 90; i < 100; i++) px[i] = 0xFFFFFFFF; // white: no colour
        int[] p = CoverArt.palette(px, 3);
        assertEquals(2, p.length);
        assertTrue(red(p[0]) > blue(p[0]));
        assertTrue(blue(p[1]) > red(p[1]));
        assertEquals(0, CoverArt.palette(new int[]{0xFF000000, 0xFFFFFFFF, 0xFF808080}, 3).length);
    }

    @Test
    public void blobColoursVaryAOneColourPalette() {
        int[] c = CoverArt.blobColours(new int[]{0xFFE50914}, 4);
        assertEquals(0xFFE50914, c[0]);
        for (int i = 1; i < 4; i++) assertTrue(c[i] != c[0]);
        assertEquals(4, CoverArt.blobColours(new int[0], 4).length);
    }

    @Test
    public void groundIsTrueBlack() {
        assertEquals(0xFF000000, CoverArt.GROUND);
    }

    @Test
    public void blobsAreFixedPerAppAndStayOnScreen() {
        float[][] a = CoverArt.blobs(42, 5), b = CoverArt.blobs(42, 5);
        for (int i = 0; i < 5; i++) {
            assertEquals(a[i][0], b[i][0], 0f);
            assertTrue(a[i][0] >= 0f && a[i][0] <= 1f);
            assertTrue(a[i][1] >= 0f && a[i][1] <= 1f);
            assertTrue(a[i][2] > 0.2f && a[i][2] < 0.6f);
        }
    }

    private static int red(int c) {
        return (c >> 16) & 0xFF;
    }

    private static int blue(int c) {
        return c & 0xFF;
    }
}
