package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LiquidGlassTest {

    private static final float EPS = 1e-4f;

    @Test
    public void clearIsTheMeasuredLook() {
        LiquidGlass.Look clear = new LiquidGlass.Look(LiquidGlass.Look.CLEAR);
        assertEquals(1.75f, clear.sigmaDp, EPS);   // 3.5 px at 1080p
        assertEquals(0f, clear.body, EPS);
        assertEquals(1f, clear.fill, EPS);
    }

    @Test
    public void tintedIsFrostedAndDark() {
        LiquidGlass.Look tinted = new LiquidGlass.Look(LiquidGlass.Look.TINTED);
        assertEquals(6f, tinted.sigmaDp, EPS);
        assertEquals(0.8f, tinted.body, EPS);
        assertEquals(1f, tinted.fill, EPS);
    }

    @Test
    public void belowClearIsClearerStill() {
        LiquidGlass.Look zero = new LiquidGlass.Look(0);
        assertEquals(1f, zero.sigmaDp, EPS);
        assertEquals(0f, zero.body, EPS);
        assertEquals(0.5f, zero.fill, EPS);
    }

    @Test
    public void valuesAreClamped() {
        assertEquals(0, new LiquidGlass.Look(-20).value);
        assertEquals(100, new LiquidGlass.Look(140).value);
        assertEquals(0.8f, new LiquidGlass.Look(100).body, EPS);
    }

    @Test
    public void moreTintedNeverGetsClearer() {
        LiquidGlass.Look previous = new LiquidGlass.Look(0);
        for (int v = 1; v <= 100; v++) {
            LiquidGlass.Look look = new LiquidGlass.Look(v);
            assertTrue("blur at " + v, look.sigmaDp >= previous.sigmaDp);
            assertTrue("tint at " + v, look.body >= previous.body);
            assertTrue("white at " + v, look.fill >= previous.fill);
            previous = look;
        }
    }

    @Test
    public void sliderStepsByFiveAndStopsAtTheEnds() {
        assertEquals(10, GlassSlider.step(LiquidGlass.Look.CLEAR, 1));
        assertEquals(0, GlassSlider.step(LiquidGlass.Look.CLEAR, -1));
        assertEquals(0, GlassSlider.step(0, -1));
        assertEquals(100, GlassSlider.step(LiquidGlass.Look.TINTED, 1));
        assertEquals(100, GlassSlider.step(100, 1));
        // From Clear, Right reaches Tinted exactly.
        int v = LiquidGlass.Look.CLEAR;
        while (v < LiquidGlass.Look.TINTED) v = GlassSlider.step(v, 1);
        assertEquals(LiquidGlass.Look.TINTED, v);
    }
}
