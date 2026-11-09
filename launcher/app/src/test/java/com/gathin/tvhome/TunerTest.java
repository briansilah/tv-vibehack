package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class TunerTest {
    @Test
    public void firstTunerIsSelected() {
        assertEquals("tv/.Input/HW0", MainActivity.preferredTuner(null, "tv/.Input/HW0"));
    }

    @Test
    public void hardwareBeatsAlphabeticallyEarlierSoftwareInEitherOrder() {
        assertEquals("tv/.Input/HW12", MainActivity.preferredTuner("airplay/.Input", "tv/.Input/HW12"));
        assertEquals("tv/.Input/HW12", MainActivity.preferredTuner("tv/.Input/HW12", "airplay/.Input"));
    }

    @Test
    public void sameKindUsesStableAlphabeticalOrder() {
        assertEquals("tv/.Input/HW1", MainActivity.preferredTuner("tv/.Input/HW2", "tv/.Input/HW1"));
        assertEquals("tv/.Input/HW1", MainActivity.preferredTuner("tv/.Input/HW1", "tv/.Input/HW2"));
        assertEquals("a/.Input", MainActivity.preferredTuner("z/.Input", "a/.Input"));
        assertEquals("a/.Input", MainActivity.preferredTuner("a/.Input", "z/.Input"));
    }

    @Test
    public void hardwareMarkerMustEndInDigitsAtTheEndOfAnId() {
        assertEquals("z/.Input/HW0", MainActivity.preferredTuner("a/.HWService", "z/.Input/HW0"));
        assertEquals("z/.Input/HW0", MainActivity.preferredTuner("a/.Input/HW", "z/.Input/HW0"));
        assertEquals("z/.Input/HW0", MainActivity.preferredTuner("a/.Input/HW1/extra", "z/.Input/HW0"));
    }
}
