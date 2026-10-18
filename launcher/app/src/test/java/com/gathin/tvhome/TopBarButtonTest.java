package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Geometry of {@link TopBarButton} as it opens from an icon circle into a labelled pill. */
public class TopBarButtonTest {

    private static final int H = 84;          // 42dp at xhdpi
    private static final int OPEN = 250;      // a full "Settings" pill
    private static final int ICON = 44;
    private static final int PAD = 32;

    @Test
    public void closedIsACircle() {
        assertEquals(H, TopBarButton.width(H, OPEN, 0f));
    }

    @Test
    public void openIsTheFullPill() {
        assertEquals(OPEN, TopBarButton.width(H, OPEN, 1f));
        assertEquals(H + (OPEN - H) / 2, TopBarButton.width(H, OPEN, 0.5f));
    }

    @Test
    public void overshootWidensButNeverClosesPastTheCircle() {
        assertTrue(TopBarButton.width(H, OPEN, 1.05f) > OPEN);
        assertEquals(H, TopBarButton.width(H, OPEN, -0.05f));
    }

    @Test
    public void neverNarrowerThanTheCircle() {
        // An empty label must not make the open button thinner than it is tall.
        assertEquals(H, TopBarButton.width(H, 60, 1f));
    }

    @Test
    public void iconMovesFromCentreToPadding() {
        assertEquals((H - ICON) / 2, TopBarButton.iconLeft(H, ICON, PAD, 0f));
        assertEquals(PAD, TopBarButton.iconLeft(H, ICON, PAD, 1f));
        assertEquals(PAD, TopBarButton.iconLeft(H, ICON, PAD, 1.05f));   // overshoot widens the pill only
    }

    @Test
    public void topBarStyleFromConfig() {
        assertEquals(R.style.ThemeOverlay_TopBar_Pills, TopBarButton.themeFor("pills"));
        assertEquals(R.style.ThemeOverlay_TopBar_Apple, TopBarButton.themeFor(" apple "));
        assertEquals(R.style.ThemeOverlay_TopBar_Google, TopBarButton.themeFor("google"));
        assertEquals(R.style.ThemeOverlay_TopBar_Google, TopBarButton.themeFor("unknown"));
        assertEquals(R.style.ThemeOverlay_TopBar_Google, TopBarButton.themeFor(null));
    }

    @Test
    public void labelFadesInOverTheLastPartOfTheOpening() {
        assertEquals(0f, TopBarButton.labelAlpha(0f), 0f);
        assertEquals(0f, TopBarButton.labelAlpha(TopBarButton.LABEL_FADE_FROM), 0f);
        assertEquals(0.5f, TopBarButton.labelAlpha((1f + TopBarButton.LABEL_FADE_FROM) / 2f), 1e-6f);
        assertEquals(1f, TopBarButton.labelAlpha(1f), 0f);
        assertEquals(1f, TopBarButton.labelAlpha(1.05f), 0f);
        assertEquals(0f, TopBarButton.labelAlpha(-0.05f), 0f);
    }
}
