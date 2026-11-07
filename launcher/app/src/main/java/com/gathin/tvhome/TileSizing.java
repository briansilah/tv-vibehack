package com.gathin.tvhome;

/** Dense-grid geometry, independent of Android views so the fit can be checked on the JVM. */
final class TileSizing {
    private static final float DESIGN_W_DP = 180f, DESIGN_H_DP = 101f;

    private TileSizing() {}

    /** Keep the four-column design; denser grids reserve room for Arrange apps' larger lift. */
    static int tileWidthPx(int innerWidthPx, int columns, float density) {
        int design = Math.round(DESIGN_W_DP * density);
        if (innerWidthPx <= 0 || columns <= 4) return design;
        int cell = innerWidthPx / columns;
        int fit = (int) ((cell - 12 * density) / AppAdapter.LIFT_SCALE);
        return Math.max(1, Math.min(design, fit));
    }

    static int tileHeightPx(int tileWidthPx) {
        return Math.round(tileWidthPx * DESIGN_H_DP / DESIGN_W_DP);
    }

    static int dockRowHeightPx(int innerWidthPx, int columns, float density) {
        int designRow = (int) ((145 + 6 + 12) * density);
        if (columns <= 4) return designRow;
        return designRow + tileHeightPx(tileWidthPx(innerWidthPx, columns, density))
                - Math.round(DESIGN_H_DP * density);
    }

    static float cornerRadius(int tileWidthPx, float density) {
        return AppAdapter.TILE_RADIUS_DP * density * Math.min(1f, tileWidthPx / (DESIGN_W_DP * density));
    }
}
