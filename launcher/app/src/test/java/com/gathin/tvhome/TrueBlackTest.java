package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Random;

public class TrueBlackTest {

    private static final int W = 320, H = 130;

    private static int rgb(int r, int g, int b) {
        return 0xFF000000 | r << 16 | g << 8 | b;
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    /**
     * A picture like YouTube's Apple TV one: most of it a charcoal ground (#242424), a little noise in it as a JPEG
     * has ({@code noise} either way), and bright shapes with soft edges over it.
     */
    private static int[] charcoalPicture(int ground, int noise) {
        int[] px = new int[W * H];
        Random rnd = new Random(7);
        int[] shapes = {rgb(255, 0, 0), rgb(250, 190, 40), rgb(20, 80, 150), rgb(30, 160, 90)};
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int n = noise > 0 ? rnd.nextInt(2 * noise + 1) - noise : 0;
                int p = rgb(clamp(ground + n), clamp(ground + n), clamp(ground + n));
                // Four discs off the corners.
                for (int k = 0; k < 4; k++) {
                    float cx = k % 2 == 0 ? 0f : W, cy = k < 2 ? 0f : H, r = 60f;
                    float d = (float) Math.hypot(x - cx, y - cy) - r;
                    float a = Math.max(0f, Math.min(1f, 0.5f - d));
                    if (a > 0f) p = ShelfArt.mix(p, shapes[k], a);
                }
                px[y * W + x] = p;
            }
        }
        return px;
    }

    private static int groundTop(int[] px) {
        int[] hist = new int[256];
        TrueBlack.count(px, px.length, hist);
        return TrueBlack.groundTop(hist);
    }

    @Test
    public void findsACharcoalGround() {
        int knee = groundTop(charcoalPicture(36, 2));
        // The ground and its noise, not the shapes.
        assertTrue("knee " + knee, knee >= 38 && knee <= 44);
        int dimmer = groundTop(charcoalPicture(20, 2));
        assertTrue("knee " + dimmer, dimmer >= 22 && dimmer <= 28);
        // A black ground lifted a little (a video's noise over it) is lifted black too.
        assertTrue(groundTop(charcoalPicture(0, 2)) >= 1);
    }

    @Test
    public void turnsTheGroundTrueBlackAndLeavesColoursAlone() {
        int[] px = charcoalPicture(36, 2), was = px.clone();
        int knee = groundTop(px), end = TrueBlack.toeEnd(knee);
        TrueBlack.toe(px, px.length, knee, end);
        int black = 0;
        for (int i = 0; i < px.length; i++) {
            int v = TrueBlack.brightness(px[i]);
            if (v == 0) black++;
            if (TrueBlack.brightness(was[i]) <= knee) assertEquals(0xFF000000, px[i]);
            if (TrueBlack.brightness(was[i]) >= end) assertEquals(was[i], px[i]);
            assertEquals(0xFF, px[i] >>> 24);
        }
        assertTrue("black " + black, black > px.length / 2);
    }

    @Test
    public void theToeKeepsHueAndOrder() {
        // A dark blue, in the toe: darker, its channels in the same proportion.
        int p = rgb(10, 20, 50);
        int[] px = {p};
        TrueBlack.toe(px, 1, 36, 60);
        int r = (px[0] >> 16) & 0xFF, g = (px[0] >> 8) & 0xFF, b = px[0] & 0xFF;
        assertTrue(b > 0 && b < 50);
        assertEquals(b / 5f, r, 1f);
        assertEquals(b * 2f / 5f, g, 1f);
        // Greys from black to white come out in the same order, black first, unchanged from the end up.
        int[] greys = new int[256];
        for (int v = 0; v < 256; v++) greys[v] = rgb(v, v, v);
        TrueBlack.toe(greys, 256, 36, 60);
        for (int v = 1; v < 256; v++) {
            assertTrue("at " + v, TrueBlack.brightness(greys[v]) >= TrueBlack.brightness(greys[v - 1]));
        }
        for (int v = 0; v <= 36; v++) assertEquals(0xFF000000, greys[v]);
        for (int v = 60; v < 256; v++) assertEquals(rgb(v, v, v), greys[v]);
    }

    @Test
    public void leavesPicturesWithoutADarkGroundAlone() {
        // Already true black around its shapes.
        assertEquals(-1, groundTop(charcoalPicture(0, 0)));
        // A navy sky, not near-black: a picture's colour, not its black.
        assertEquals(-1, groundTop(charcoalPicture(70, 2)));
        // A bright picture, dark only in a few specks.
        int[] bright = new int[W * H];
        Random rnd = new Random(3);
        for (int i = 0; i < bright.length; i++) {
            bright[i] = rnd.nextInt(100) < 5 ? rgb(30, 30, 30) : rgb(90 + rnd.nextInt(160), 120, 200);
        }
        assertEquals(-1, groundTop(bright));
        assertEquals(-1, TrueBlack.groundTop(new int[256]));
    }

    @Test
    public void theFaintHazeGoes() {
        int[] px = {rgb(1, 2, 3), rgb(0, 0, 0), rgb(3, 3, 3), rgb(2, 5, 10), rgb(20, 40, 90)};
        TrueBlack.toe(px, px.length, TrueBlack.FAINT_KNEE, TrueBlack.FAINT_END);
        assertEquals(0xFF000000, px[0]);
        assertEquals(0xFF000000, px[1]);
        assertEquals(0xFF000000, px[2]);
        assertTrue(TrueBlack.brightness(px[3]) < 10);
        assertEquals(rgb(20, 40, 90), px[4]);
    }
}
