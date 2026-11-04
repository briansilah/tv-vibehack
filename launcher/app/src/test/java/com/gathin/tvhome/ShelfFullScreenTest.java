package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ShelfFullScreenTest {

    private static final float FRAME = 1001f / 30000f;

    @Test
    public void followsTvosDockFrameByFrame() {
        // WWDC19 session 211 at 5:31, 1280 x 720 at 29.97 fps: tvOS's dock panel spans rows 492 to 672 at rest, and
        // its icons are 33, 96, 155 and 196 px lower on the four frames after it sets off (3 ms after the frame
        // before them).
        float away = ShelfFullScreen.away(720f, 492f);
        float[] measured = {33f, 96f, 155f, 196f};
        for (int i = 0; i < measured.length; i++) {
            float y = away * ShelfFullScreen.position((i + 1) * FRAME - 0.0033f);
            assertEquals("frame " + (i + 1), measured[i], y, 3f);
        }
        // On the fifth it's below the screen: its icons, 28 px down the panel, are gone.
        assertTrue(492f + 28f + away * ShelfFullScreen.position(5 * FRAME - 0.0033f) > 720f);
    }

    @Test
    public void dockIsOutOfSightAfterAboutAFifthOfASecond() {
        // The launcher's dock at 1080p (2x): its panel's top 209 dp above the screen's bottom edge.
        float away = ShelfFullScreen.away(1080f, 1080f - 418f);
        assertEquals(418f + 60f, away, 0.5f);
        assertTrue(away * ShelfFullScreen.position(0.15f) < 418f);
        assertTrue(away * ShelfFullScreen.position(0.20f) > 418f);
    }

    @Test
    public void springStartsFromRestAndNeverOvershoots() {
        assertEquals(0f, ShelfFullScreen.position(0f), 0f);
        float last = 0f;
        for (int ms = 1; ms <= 2000; ms++) {
            float p = ShelfFullScreen.position(ms / 1000f);
            assertTrue(p >= last && p <= 1f);
            last = p;
        }
        assertTrue(ShelfFullScreen.position(0.4f) > 0.99f);
    }

    @Test
    public void buttonGoesDownToTheSafeArea() {
        // The button's bottom from where the title sat above the dock to 60 px above the screen's bottom edge.
        assertEquals(1020f - 600f, ShelfFullScreen.drop(1080f, 600f), 0.01f);
        assertEquals(0f, ShelfFullScreen.drop(1080f, 1050f), 0f);
    }
}
