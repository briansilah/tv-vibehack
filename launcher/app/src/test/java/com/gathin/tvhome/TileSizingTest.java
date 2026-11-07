package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TileSizingTest {
    @Test
    public void defaultColumnsKeepTheDesignAndExactDockPlacement() {
        for (float density : new float[]{1f, 1.33125f, 1.5f, 2f}) {
            for (int columns = 1; columns <= 4; columns++) {
                assertEquals(Math.round(180 * density),
                        TileSizing.tileWidthPx(Math.round(864 * density), columns, density));
                assertEquals((int) (163 * density),
                        TileSizing.dockRowHeightPx(Math.round(864 * density), columns, density));
            }
        }
    }

    @Test
    public void denseRowsFitEvenWhenAnAppIsPickedUp() {
        for (float density : new float[]{1f, 1.33125f, 1.5f, 2f}) {
            int inner = Math.round(864 * density);
            for (int columns = 5; columns <= 10; columns++) {
                int width = TileSizing.tileWidthPx(inner, columns, density);
                assertTrue(width > 0);
                assertTrue(width < Math.round(180 * density));
                assertTrue(width * AppAdapter.LIFT_SCALE + 12 * density <= inner / columns);
                assertEquals(Math.round(width * 101f / 180f), TileSizing.tileHeightPx(width));
                assertEquals((int) (163 * density) + TileSizing.tileHeightPx(width)
                                - Math.round(101 * density),
                        TileSizing.dockRowHeightPx(inner, columns, density));
            }
        }
    }

    @Test
    public void radiiFollowTileSizeAndNeverGrowPastTheDesign() {
        assertEquals(16f, TileSizing.cornerRadius(180, 1f), 0f);
        assertEquals(8f, TileSizing.cornerRadius(90, 1f), 0f);
        assertEquals(32f, TileSizing.cornerRadius(720, 2f), 0f);
    }
}
