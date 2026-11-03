package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;

import android.view.KeyEvent;
import org.junit.Test;

public class InputPressTest {
    private final HomeButtonService.InputPress press = new HomeButtonService.InputPress();

    @Test
    public void disabledOrUnavailablePassesTheWholePressThrough() {
        assertEquals(0, press.key(KeyEvent.ACTION_DOWN, 0, false, false));
        assertEquals(0, press.key(KeyEvent.ACTION_DOWN, 1, false, true));
        assertEquals(0, press.key(KeyEvent.ACTION_UP, 0, false, true));
    }

    @Test
    public void opensOnceOnReleaseEvenAfterRepeats() {
        assertEquals(1, press.key(KeyEvent.ACTION_DOWN, 0, false, true));
        assertEquals(1, press.key(KeyEvent.ACTION_DOWN, 1, false, true));
        assertEquals(1, press.key(KeyEvent.ACTION_DOWN, 2, false, true));
        assertEquals(2, press.key(KeyEvent.ACTION_UP, 0, false, true));
        assertEquals(0, press.key(KeyEvent.ACTION_UP, 0, false, true));
    }

    @Test
    public void canceledReleaseIsConsumedWithoutOpening() {
        assertEquals(1, press.key(KeyEvent.ACTION_DOWN, 0, false, true));
        assertEquals(1, press.key(KeyEvent.ACTION_UP, 0, true, true));
        assertEquals(0, press.key(KeyEvent.ACTION_UP, 0, false, true));
    }

    @Test
    public void orphanRepeatOrReleaseDoesNotOpen() {
        assertEquals(0, press.key(KeyEvent.ACTION_DOWN, 1, false, true));
        assertEquals(0, press.key(KeyEvent.ACTION_UP, 0, false, true));
    }

    @Test
    public void canceledDownDoesNotClaimThePress() {
        assertEquals(0, press.key(KeyEvent.ACTION_DOWN, 0, true, true));
        assertEquals(0, press.key(KeyEvent.ACTION_UP, 0, false, true));
    }

    @Test
    public void aNewPressRechecksAvailability() {
        assertEquals(1, press.key(KeyEvent.ACTION_DOWN, 0, false, true));
        assertEquals(2, press.key(KeyEvent.ACTION_UP, 0, false, true));
        assertEquals(0, press.key(KeyEvent.ACTION_DOWN, 0, false, false));
        assertEquals(0, press.key(KeyEvent.ACTION_UP, 0, false, false));
    }
}
