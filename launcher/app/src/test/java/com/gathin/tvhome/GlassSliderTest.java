package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class GlassSliderTest {
    @Test
    public void remoteAndAccessibilityUseFivePointSteps() {
        for (int value = 5; value <= 95; value++) {
            assertEquals(value + 5, GlassSlider.step(value, 1));
            assertEquals(value - 5, GlassSlider.step(value, -1));
        }
    }

    @Test
    public void stepsStopAtTheRangeEndpoints() {
        assertEquals(0, GlassSlider.step(0, -1));
        assertEquals(0, GlassSlider.step(3, -1));
        assertEquals(100, GlassSlider.step(98, 1));
        assertEquals(100, GlassSlider.step(100, 1));
    }
}
