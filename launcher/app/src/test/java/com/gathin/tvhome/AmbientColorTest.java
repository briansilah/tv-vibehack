package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

/** Synthetic 64x36 banners (the size AppAdapter samples at) through {@link AmbientColor#pick}. */
public class AmbientColorTest {

    private static final int W = 64, H = 36;

    private static final int BLACK = 0xFF000000;
    private static final int WHITE = 0xFFFFFFFF;

    private static int[] banner(int background) {
        int[] px = new int[W * H];
        Arrays.fill(px, background);
        return px;
    }

    private static void fill(int[] px, int x0, int y0, int x1, int y1, int color) {
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) px[y * W + x] = color;
        }
    }

    /** One-pixel outline around a rectangle, cycling through the given colours. */
    private static void outline(int[] px, int x0, int y0, int x1, int y1, int... colors) {
        int i = 0;
        for (int x = x0 - 1; x <= x1; x++) {
            px[(y0 - 1) * W + x] = colors[i++ % colors.length];
            px[y1 * W + x] = colors[i++ % colors.length];
        }
        for (int y = y0; y < y1; y++) {
            px[y * W + x0 - 1] = colors[i++ % colors.length];
            px[y * W + x1] = colors[i++ % colors.length];
        }
    }

    private static float[] hsv(int color) {
        assertEquals("opaque", 0xFF, color >>> 24);
        float[] hsv = new float[3];
        AmbientColor.rgbToHsv((color >> 16) & 0xFF, (color >> 8) & 0xFF, color & 0xFF, hsv);
        return hsv;
    }

    private static float hueDistance(float a, float b) {
        float d = Math.abs(a - b) % 360f;
        return d > 180f ? 360f - d : d;
    }

    private static void assertNeutral(int color, float minValue, float maxValue) {
        float[] hsv = hsv(color);
        assertTrue(String.format("expected grey, got #%06X", color & 0xFFFFFF), hsv[1] <= 0.05f);
        assertTrue(String.format("value %.2f of #%06X not in [%.2f, %.2f]", hsv[2], color & 0xFFFFFF, minValue, maxValue),
                hsv[2] >= minValue && hsv[2] <= maxValue);
    }

    private static void assertHue(int color, float hue) {
        float[] hsv = hsv(color);
        assertTrue(String.format("expected hue %.0f, got #%06X (hue %.0f)", hue, color & 0xFFFFFF, hsv[0]),
                hueDistance(hsv[0], hue) <= 10f);
        assertTrue(String.format("expected a vivid colour, got #%06X", color & 0xFFFFFF), hsv[1] >= 0.8f);
        assertTrue(String.format("expected a bright colour, got #%06X", color & 0xFFFFFF), hsv[2] >= 0.5f);
    }

    // ---- mostly black ------------------------------------------------------------------------------

    @Test
    public void mostlyBlackWithIridescentLogoEdge_isDarkGrey() {
        // Apple TV: near-black banner, white logo whose edge shimmers blue / purple / pink / green.
        int[] px = banner(0xFF141414);
        fill(px, 20, 12, 44, 24, WHITE);
        outline(px, 20, 12, 44, 24, 0xFF6E8BFF, 0xFF9A6BFF, 0xFFE07BD0, 0xFF7FE0A0);
        assertNeutral(AmbientColor.pick(px), 0.2f, 0.4f);
    }

    @Test
    public void mostlyBlackWithSmallSaturatedAccent_isDarkGrey() {
        int[] px = banner(BLACK);
        fill(px, 28, 16, 36, 21, 0xFF5B3CFF);   // ~1.7% of the banner, fully saturated blue-purple
        assertNeutral(AmbientColor.pick(px), 0.2f, 0.3f);
    }

    @Test
    public void pureBlack_isDarkGrey() {
        assertNeutral(AmbientColor.pick(banner(BLACK)), 0.2f, 0.2f);
    }

    @Test
    public void mostlyBlackWithLargeColouredLogo_keepsTheLogoColour() {
        int[] px = banner(BLACK);
        fill(px, 8, 8, 56, 28, 0xFFE50914);   // ~42% red
        assertHue(AmbientColor.pick(px), 357f);
    }

    @Test
    public void darkTintedBlack_isNotTreatedAsColour() {
        // JPEG-ish near-black with a faint blue cast: high HSV saturation, but almost no chroma.
        assertNeutral(AmbientColor.pick(banner(0xFF0A0A1C)), 0.2f, 0.2f);
    }

    @Test
    public void darkButColouredBanner_keepsItsHueLifted() {
        int[] px = banner(0xFF1A1A70);   // deep indigo, like ZEE5
        fill(px, 24, 10, 40, 26, WHITE);
        assertHue(AmbientColor.pick(px), 240f);
    }

    // ---- mostly white ------------------------------------------------------------------------------

    @Test
    public void mostlyWhite_isLightGrey() {
        int[] px = banner(0xFFF4F4F4);
        fill(px, 12, 14, 52, 22, 0xFF5F6368);   // grey wordmark
        assertNeutral(AmbientColor.pick(px), 0.75f, 1f);
    }

    @Test
    public void whiteWithMulticolourSpeck_isLightGrey() {
        // Play Store like: a small four-colour logo on white is not one brand colour.
        int[] px = banner(0xFFF4F4F4);
        fill(px, 10, 14, 13, 22, 0xFF00A173);
        fill(px, 13, 14, 16, 22, 0xFF00C3FF);
        fill(px, 16, 14, 19, 22, 0xFFFFD400);
        fill(px, 19, 14, 22, 22, 0xFFFF3A44);
        fill(px, 26, 14, 54, 22, 0xFF5F6368);
        assertNeutral(AmbientColor.pick(px), 0.75f, 1f);
    }

    @Test
    public void whiteWithRedWordmark_isRed() {
        // Netflix: white banner, red wordmark over ~15% of it.
        int[] px = banner(WHITE);
        fill(px, 8, 13, 56, 23, 0xFFE50914);
        fill(px, 8, 13, 56, 14, 0xFFF08A8F);   // anti-aliased top edge
        assertHue(AmbientColor.pick(px), 357f);
    }

    @Test
    public void lightGreyWithRedPlayButton_isRed() {
        // YouTube: ~6% red button next to a dark wordmark of similar size.
        int[] px = banner(0xFFF2F2F2);
        fill(px, 10, 13, 22, 24, 0xFFFF0000);
        fill(px, 15, 16, 18, 21, WHITE);
        fill(px, 24, 13, 54, 23, 0xFF282828);
        assertHue(AmbientColor.pick(px), 0f);
    }

    // ---- colourful ---------------------------------------------------------------------------------

    @Test
    public void dominantBrandColour_isThatColour() {
        // Prime Video: blue banner, white wordmark.
        int[] px = banner(0xFF0F79FF);
        fill(px, 14, 12, 50, 22, WHITE);
        int color = AmbientColor.pick(px);
        assertHue(color, 213f);
        assertTrue(hsv(color)[2] >= 0.95f);
    }

    @Test
    public void twoColourBanner_takesTheLargerArea() {
        int orange = 0xFFFF6A00, blue = 0xFF0057FF;
        int[] px = banner(orange);
        fill(px, 38, 0, W, H, blue);   // 40% blue
        assertHue(AmbientColor.pick(px), 25f);

        px = banner(orange);
        fill(px, 26, 0, W, H, blue);   // 60% blue
        assertHue(AmbientColor.pick(px), 220f);
    }

    @Test
    public void gradientBanner_picksAColourFromTheGradient() {
        // Hotstar like: cyan -> blue -> purple -> pink across the banner.
        int[] px = new int[W * H];
        for (int x = 0; x < W; x++) {
            float[] hsv = {190f + 140f * x / (W - 1), 0.9f, 1f};
            fill(px, x, 0, x + 1, H, AmbientColor.hsvToRgb(hsv));
        }
        float hue = hsv(AmbientColor.pick(px))[0];
        assertTrue("hue " + hue, hue >= 190f && hue <= 330f);
    }

    // ---- transparency / icon fallback --------------------------------------------------------------

    @Test
    public void transparentPixelsAreIgnored() {
        // Icon fallback: an opaque green square on a transparent canvas is green, not darkened by the corners.
        int[] px = banner(0x00000000);
        fill(px, 20, 6, 44, 30, 0xFF1DB954);
        int color = AmbientColor.pick(px);
        assertHue(color, 141f);
        assertTrue(hsv(color)[2] >= 0.6f);

        // Same with a white icon: light grey, not the dark grey the transparent area would average to.
        px = banner(0x00000000);
        fill(px, 20, 6, 44, 30, WHITE);
        assertNeutral(AmbientColor.pick(px), 0.95f, 1f);
    }

    @Test
    public void nearlyTransparentPixelsAreIgnored() {
        int[] px = banner(0x10FF0000);   // faint red haze, alpha 16
        fill(px, 20, 6, 44, 30, WHITE);
        assertNeutral(AmbientColor.pick(px), 0.95f, 1f);
    }

    @Test
    public void nothingToSample_isFallback() {
        assertEquals(AmbientColor.FALLBACK, AmbientColor.pick(banner(0x00000000)));
        assertEquals(AmbientColor.FALLBACK, AmbientColor.pick(new int[0]));
        assertEquals(AmbientColor.FALLBACK, AmbientColor.pick(null));
    }

    // ---- colour maths ------------------------------------------------------------------------------

    @Test
    public void hsvConversionsMatchAndroid() {
        assertEquals(0xFFFF0000, AmbientColor.hsvToRgb(new float[]{0f, 1f, 1f}));
        assertEquals(0xFF00FF00, AmbientColor.hsvToRgb(new float[]{120f, 1f, 1f}));
        assertEquals(0xFF000080, AmbientColor.hsvToRgb(new float[]{240f, 1f, 0.5f}));
        assertEquals(0xFF808080, AmbientColor.hsvToRgb(new float[]{0f, 0f, 0.5f}));
        float[] hsv = new float[3];
        AmbientColor.rgbToHsv(0xE5, 0x09, 0x14, hsv);
        assertEquals(357f, hsv[0], 0.5f);
        assertEquals(0.96f, hsv[1], 0.01f);
        assertEquals(0.90f, hsv[2], 0.01f);
    }
}
