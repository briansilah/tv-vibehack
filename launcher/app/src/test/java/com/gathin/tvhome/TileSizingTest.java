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
                        AppAdapter.tileWidthPx(Math.round(864 * density), columns, density));
                assertEquals((int) (163 * density),
                        AppAdapter.dockRowHeightPx(Math.round(864 * density), columns, density));
            }
        }
    }

    @Test
    public void denseRowsFitEvenWhenAnAppIsPickedUp() {
        for (float density : new float[]{1f, 1.33125f, 1.5f, 2f}) {
            int inner = Math.round(864 * density);
            for (int columns = 5; columns <= 10; columns++) {
                int width = AppAdapter.tileWidthPx(inner, columns, density);
                assertTrue(width > 0);
                assertTrue(width < Math.round(180 * density));
                assertTrue(width * AppAdapter.LIFT_SCALE + 12 * density <= inner / columns);
                assertEquals(Math.round(width * 101f / 180f), AppAdapter.tileHeightPx(width));
                assertEquals((int) (163 * density) + AppAdapter.tileHeightPx(width)
                                - Math.round(101 * density),
                        AppAdapter.dockRowHeightPx(inner, columns, density));
            }
        }
    }

    @Test
    public void radiiFollowTileSizeAndNeverGrowPastTheDesign() {
        assertEquals(16f, AppAdapter.cornerRadius(180, 1f), 0f);
        assertEquals(8f, AppAdapter.cornerRadius(90, 1f), 0f);
        assertEquals(32f, AppAdapter.cornerRadius(720, 2f), 0f);
    }
}
