package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.KeyEvent;

import org.junit.Test;

public class WakeKeysTest {

    private static final int DOWN = KeyEvent.ACTION_DOWN, UP = KeyEvent.ACTION_UP;
    private static final int LEFT = KeyEvent.KEYCODE_DPAD_LEFT, RIGHT = KeyEvent.KEYCODE_DPAD_RIGHT;
    private static final int OK = KeyEvent.KEYCODE_DPAD_CENTER;

    @Test
    public void freshPressesAllGoThrough() {
        LateKeys k = new LateKeys();
        for (long t = 1000; t < 5000; t += 300) {
            assertFalse(k.drop(DOWN, LEFT, 0, t, t, t + 5));
            assertFalse(k.drop(UP, LEFT, 0, t + 80, t, t + 85));
        }
        // Held up a little (a slow frame): still the user's, and so is the next.
        assertFalse(k.drop(DOWN, RIGHT, 0, 6000, 6000, 6000 + LateKeys.STALL_MS));
        assertFalse(k.drop(DOWN, RIGHT, 0, 6040, 6040, 6052));
    }

    @Test
    public void ofABurstOfLatePressesOnlyTheFirstMoveIsTaken() {
        LateKeys k = new LateKeys();
        // Pressed Left at 1.0 s, again at 1.8 s and 2.5 s while Home wasn't there; all arrive at 3.0 s.
        long at = 3000;
        assertFalse(k.drop(DOWN, LEFT, 0, 1000, 1000, at));
        assertFalse(k.drop(UP, LEFT, 0, 1100, 1000, at));
        assertTrue(k.drop(DOWN, LEFT, 0, 1800, 1800, at + 1));
        assertTrue(k.drop(UP, LEFT, 0, 1900, 1800, at + 1));
        assertTrue(k.drop(DOWN, LEFT, 0, 2500, 2500, at + 2));
        assertTrue(k.drop(UP, LEFT, 0, 2600, 2500, at + 2));
        // The next press, once Home is there, is the user's.
        assertFalse(k.drop(DOWN, RIGHT, 0, 3400, 3400, 3405));
        // A press made once the first late one could be seen moving isn't part of the burst, even if it waits too.
        LateKeys k2 = new LateKeys();
        assertFalse(k2.drop(DOWN, LEFT, 0, 1000, 1000, 3000));
        assertTrue(k2.drop(DOWN, LEFT, 0, 3000 + LateKeys.SEEN_MS - 1, 3149, 3200));
        assertFalse(k2.drop(DOWN, LEFT, 0, 3000 + LateKeys.SEEN_MS, 3150, 3600));
    }

    @Test
    public void aPressHeldBehindTheFirstGoesHoweverLittleItWaited() {
        // Seen on an emulator waking like Google TV: Left at 0.70 s and 0.89 s, both delivered at 1.08 s as Home's
        // window got focus, 379 and 187 ms late. Focus jumped two apps at once.
        LateKeys k = new LateKeys();
        assertFalse(k.drop(DOWN, LEFT, 0, 699, 699, 1078));
        assertTrue(k.drop(DOWN, LEFT, 0, 892, 892, 1079));
        assertTrue(k.drop(UP, LEFT, 0, 972, 892, 1079));
        assertFalse(k.drop(DOWN, LEFT, 0, 1385, 1385, 1474));
    }

    @Test
    public void aMoveMadeAgainInHomeAndAPressHeldBehindItAreOneMove() {
        // On the TV: Left during the hand-over, made again in Home (102 ms late, waiting for its window's focus), and
        // a second Left made before that one was taken, 93 ms late. Focus moved two apps in 52 ms.
        LateKeys k = new LateKeys();
        assertFalse(k.drop(DOWN, LEFT, 0, 776, 776, 878));
        assertTrue(k.drop(DOWN, LEFT, 0, 838, 838, 931));
        assertFalse(k.drop(DOWN, LEFT, 0, 1369, 1369, 1397));
    }

    @Test
    public void aPressMadeJustAfterTheHeldMoveIsPartOfIt() {
        // On the TV: the move made again in Home came 81 ms late; a second Left, pressed again because nothing had
        // happened, was made 81 ms after Home took it and moved focus another app 113 ms later.
        LateKeys k = new LateKeys();
        assertFalse(k.drop(DOWN, LEFT, 0, 683, 683, 764));
        assertTrue(k.drop(DOWN, LEFT, 0, 845, 845, 877));
        assertFalse(k.drop(DOWN, LEFT, 0, 1373, 1373, 1390));
    }

    @Test
    public void aLateSelectNeverOpensAnything() {
        LateKeys k = new LateKeys();
        assertTrue(k.drop(DOWN, OK, 0, 1000, 1000, 3000));
        // Its release goes too, so no click; and a held Select's repeats.
        assertTrue(k.drop(UP, OK, 0, 1200, 1000, 3001));
        assertTrue(k.drop(DOWN, OK, 1, 1500, 1000, 3001));
        // A fresh one opens as usual.
        assertFalse(k.drop(DOWN, OK, 0, 4000, 4000, 4003));
        assertFalse(k.drop(UP, OK, 0, 4090, 4000, 4093));
    }

    @Test
    public void aLaterStallIsANewBurst() {
        LateKeys k = new LateKeys();
        assertFalse(k.drop(DOWN, LEFT, 0, 1000, 1000, 3000));
        assertTrue(k.drop(DOWN, LEFT, 0, 2000, 2000, 3001));
        // Pressed after Home took the first one, held up again: its first move goes through.
        assertFalse(k.drop(DOWN, RIGHT, 0, 5000, 5000, 6000));
        assertTrue(k.drop(DOWN, RIGHT, 0, 5500, 5500, 6001));
    }

    @Test
    public void aHeldKeyKeepsRepeatingThroughASlowMoment() {
        LateKeys k = new LateKeys();
        // Right held from 1.0 s; one repeat waits 310 ms behind a busy main thread, the next was made meanwhile.
        assertFalse(k.drop(DOWN, RIGHT, 0, 1000, 1000, 1003));
        assertFalse(k.drop(DOWN, RIGHT, 1, 1450, 1000, 1760));
        assertFalse(k.drop(DOWN, RIGHT, 2, 1500, 1000, 1762));
        assertFalse(k.drop(DOWN, RIGHT, 3, 1550, 1000, 1800));
        assertFalse(k.drop(UP, RIGHT, 0, 1600, 1000, 1810));
        // A press that was dropped takes its repeats with it.
        assertFalse(k.drop(DOWN, LEFT, 0, 2000, 2000, 2400));
        assertTrue(k.drop(DOWN, LEFT, 0, 2100, 2100, 2401));
        assertTrue(k.drop(DOWN, LEFT, 1, 2600, 2100, 2650));
    }

    @Test
    public void otherKeysAreLeftAlone() {
        LateKeys k = new LateKeys();
        assertFalse(k.drop(DOWN, KeyEvent.KEYCODE_BACK, 0, 1000, 1000, 9000));
        assertFalse(k.drop(DOWN, KeyEvent.KEYCODE_VOLUME_UP, 0, 1000, 1000, 9000));
        assertFalse(k.drop(DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0, 1000, 1000, 9000));
    }

    @Test
    public void whileGoogleTvsHomeIsCoveredItsPressesAreTakenAndTheFirstMoveMadeAgain() {
        HopKeys h = new HopKeys();
        // Before: everything goes through.
        assertFalse(h.take(DOWN, LEFT, 0, 900, 900));
        assertFalse(h.take(UP, LEFT, 0, 900, 950));
        h.covering(1000);
        assertTrue(h.take(DOWN, LEFT, 0, 1100, 1100));
        assertTrue(h.take(UP, LEFT, 0, 1100, 1180));
        assertTrue(h.take(DOWN, RIGHT, 0, 1300, 1300));
        assertTrue(h.take(DOWN, OK, 0, 1400, 1400));
        assertTrue(h.take(DOWN, KeyEvent.KEYCODE_BACK, 0, 1450, 1450));
        // Volume and Home aren't the D-pad's.
        assertFalse(h.take(DOWN, KeyEvent.KEYCODE_VOLUME_UP, 0, 1460, 1460));
        assertFalse(h.take(DOWN, KeyEvent.KEYCODE_HOME, 0, 1470, 1470));
        assertEquals(LEFT, h.shown(1490));
        // Home shows: keys reach it again; a release of a key taken before is still taken.
        assertTrue(h.take(UP, RIGHT, 0, 1300, 1500));
        assertFalse(h.take(DOWN, LEFT, 0, 1600, 1600));
        assertEquals(0, h.shown(1610));
    }

    @Test
    public void googleTvsHomeIsLetGoIfHomeDoesntShow() {
        HopKeys h = new HopKeys();
        h.covering(1000);
        assertTrue(h.take(DOWN, LEFT, 0, 1200, 1200));
        assertFalse(h.take(DOWN, LEFT, 0, 1000 + HopKeys.HOLD_MS, 1000 + HopKeys.HOLD_MS));
        // Home showing much later (an ordinary Home press, minutes on) makes no old move.
        assertEquals(0, h.shown(200_000));
        // Nor if it took longer than the hold, though no key came after it.
        HopKeys late = new HopKeys();
        late.covering(1000);
        assertTrue(late.take(DOWN, RIGHT, 0, 1100, 1100));
        assertEquals(0, late.shown(1000 + HopKeys.HOLD_MS + 1));
        // Only a press is made again, never Select or Back.
        HopKeys h2 = new HopKeys();
        h2.covering(1000);
        assertTrue(h2.take(DOWN, OK, 0, 1100, 1100));
        assertTrue(h2.take(DOWN, KeyEvent.KEYCODE_BACK, 0, 1200, 1200));
        assertEquals(0, h2.shown(1300));
    }
}
