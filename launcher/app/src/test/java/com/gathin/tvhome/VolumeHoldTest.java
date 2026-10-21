package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class VolumeHoldTest {

    /** Trimmed from Android 14's dumpsys input: a TV remote's two devices and the virtual keyboard. */
    private static final String DUMP = String.join("\n",
            "INPUT MANAGER (dumpsys input)",
            "",
            "Event Hub State:",
            "  Devices:",
            "    6: BT_RC933B_B1 Keyboard (aka device 0 - built-in keyboard)",
            "    7: BT_RC933B_B1 Consumer Control",
            "",
            "Input Reader State (Nums of device: 3):",
            "  Device 6: BT_RC933B_B1 Keyboard",
            "    EventHub Devices: [ 6 ] ",
            "    Sources: KEYBOARD | DPAD",
            "    Keyboard Input Mapper:",
            "      Parameters:",
            "        OrientationAware: false",
            "        HandlesKeyRepeat: false",
            "      KeyDowns: %d keys currently down",
            "      MetaState: 0x0",
            "  Device 7: BT_RC933B_B1 Consumer Control",
            "    EventHub Devices: [ 7 ] ",
            "    Sources: KEYBOARD",
            "    Keyboard Input Mapper:",
            "      Parameters:",
            "        OrientationAware: false",
            "        HandlesKeyRepeat: false",
            "      KeyDowns: %d keys currently down",
            "      MetaState: 0x0",
            "  Device -1: Virtual",
            "    Keyboard Input Mapper:",
            "      Parameters:",
            "        HandlesKeyRepeat: false",
            "      KeyDowns: 0 keys currently down",
            "  Configuration:",
            "    ExcludedDeviceNames: []",
            "",
            "Input Dispatcher State:",
            "  DispatchEnabled: true",
            "  KeyDowns: 5 keys currently down",
            "");

    private static Map<Integer, Integer> parse(String dump) throws IOException {
        return VolumeHold.keysDown(new BufferedReader(new StringReader(dump)));
    }

    private static Map<Integer, Integer> map(int... deviceAndCount) {
        Map<Integer, Integer> m = new HashMap<>();
        for (int i = 0; i < deviceAndCount.length; i += 2) m.put(deviceAndCount[i], deviceAndCount[i + 1]);
        return m;
    }

    @Test
    public void readsKeysDownPerDeviceFromTheInputReaderOnly() throws IOException {
        assertEquals(map(6, 0, 7, 1, -1, 0), parse(String.format(DUMP, 0, 1)));
        assertEquals(map(6, 2, 7, 0, -1, 0), parse(String.format(DUMP, 2, 0)));
    }

    @Test
    public void leavesOutDevicesThatRepeatKeysThemselves() throws IOException {
        String dump = String.format(DUMP, 0, 1).replaceFirst(
                "(Device 7: BT_RC933B_B1 Consumer Control\\n(?:.*\\n)*?) {8}HandlesKeyRepeat: false",
                "$1        HandlesKeyRepeat: true");
        assertEquals(map(6, 0, -1, 0), parse(dump));
    }

    @Test
    public void withoutTheDumpPermissionThereIsNoAnswer() throws IOException {
        assertNull(parse("Permission Denial: can't dump InputManager from from pid=4242, uid=10123 due to missing "
                + "android.permission.DUMP permission\n"));
    }

    @Test
    public void keysTheServiceNeverSawAreVolumeKeys() {
        assertEquals(1, VolumeHold.unseenKeysDown(map(6, 0, 7, 1), map()));
        // The D-pad held on the remote's keyboard, seen by the service: nothing else is down.
        assertEquals(0, VolumeHold.unseenKeysDown(map(6, 1, 7, 0), map(6, 1)));
        // A key seen on one device doesn't account for a key down on another.
        assertEquals(1, VolumeHold.unseenKeysDown(map(6, 1, 7, 1), map(6, 1)));
        // Both volume keys held.
        assertEquals(2, VolumeHold.unseenKeysDown(map(6, 0, 7, 2), map()));
        // A key the service saw go down but whose up it missed doesn't count below zero.
        assertEquals(0, VolumeHold.unseenKeysDown(map(6, 0, 7, 0), map(6, 1)));
    }

    @Test
    public void countsSeenKeysPerDevice() {
        Set<Long> keys = new HashSet<>();
        keys.add(VolumeHold.key(6, 103, 19));
        keys.add(VolumeHold.key(6, 108, 20));
        keys.add(VolumeHold.key(-1, 0, 3));
        assertEquals(map(6, 2, -1, 1), VolumeHold.perDevice(keys));
    }

    private static final int STOP = VolumeHold.Repeat.STOP;
    private static final int WAIT = VolumeHold.Repeat.WAIT;
    private static final int STEP = VolumeHold.Repeat.STEP;

    @Test
    public void aHeldKeyRepeatsWithInputDispatchersTiming() {
        VolumeHold.Repeat r = new VolumeHold.Repeat(400, 50);
        assertEquals(1000, r.volumeChanged(1000, 5, 6));    // looks at once
        assertEquals(1, r.direction());
        assertEquals(6, r.expected());
        for (long t = 1000; t < 1400; t += 50) {
            assertEquals(WAIT, r.check(t, 1));
            assertEquals(t + 50, r.next());
        }
        assertEquals(STEP, r.check(1400, 1));
        assertEquals(1450, r.stepped(1400, 7));
        assertEquals(7, r.expected());
        assertEquals(STEP, r.check(1458, 1));
        assertEquals(1500, r.stepped(1458, 8));             // a late look keeps the pace
        assertEquals(STEP, r.check(1620, 1));
        assertEquals(1670, r.stepped(1620, 9));             // a very late one doesn't catch up in a burst
        assertEquals(STOP, r.check(1670, 0));
        assertEquals(VolumeHold.Repeat.IDLE, r.next());
    }

    @Test
    public void aTapNeverRepeats() {
        VolumeHold.Repeat r = new VolumeHold.Repeat(400, 50);
        r.volumeChanged(1000, 6, 5);
        assertEquals(-1, r.direction());
        assertEquals(WAIT, r.check(1000, 1));
        assertEquals(STOP, r.check(1050, 0));
        // Down again later, but this press had no change of its own: not a hold.
        assertEquals(STOP, r.check(1400, 1));
    }

    @Test
    public void theLastLookBeforeTheTimeoutIsAtTheTimeout() {
        VolumeHold.Repeat r = new VolumeHold.Repeat(400, 50);
        r.volumeChanged(1000, 5, 6);
        assertEquals(WAIT, r.check(1380, 1));
        assertEquals(1400, r.next());
    }

    @Test
    public void anyKeyUpOrDownEndsTheRepeat() {
        VolumeHold.Repeat r = new VolumeHold.Repeat(400, 50);
        // Volume Down pressed while Volume Up is held: its own change arms the repeat with two keys down.
        r.volumeChanged(1000, 7, 6);
        assertEquals(WAIT, r.check(1000, 2));
        assertEquals(STEP, r.check(1400, 2));
        r.stepped(1400, 5);
        // Volume Down let go, Volume Up still held: stop, as InputDispatcher does on any key up.
        assertEquals(STOP, r.check(1450, 1));
        // Another key down (Power, the Assistant's button) moves the repeat away too.
        r.volumeChanged(2000, 5, 6);
        assertEquals(WAIT, r.check(2000, 1));
        assertEquals(STOP, r.check(2050, 2));
    }

    @Test
    public void ownStepsAreKnownByTheirValues() {
        VolumeHold.Repeat r = new VolumeHold.Repeat(400, 50);
        r.volumeChanged(1000, 5, 6);
        assertFalse(r.ownStep(6, 7));                      // waiting: any change is a new press
        r.check(1400, 1);
        r.stepped(1400, 7);
        r.check(1450, 1);
        r.stepped(1450, 8);
        assertTrue(r.ownStep(6, 7));                       // late, or after the next step
        assertTrue(r.ownStep(7, 8));
        assertFalse(r.ownStep(8, 9));                      // past ours: another press
        assertFalse(r.ownStep(8, 7));                      // the other way
        assertFalse(r.ownStep(6, 8));                      // not one step
        // Stopped (the key went up) before our last step's broadcast came: still ours, so it doesn't start over.
        r.stop();
        assertTrue(r.ownStep(7, 8));
        assertFalse(r.ownStep(8, 9));
        // A change that isn't ours forgets them.
        r.reset();
        assertFalse(r.ownStep(7, 8));
        r.volumeChanged(2000, 8, 7);
        assertFalse(r.ownStep(8, 7));                      // waiting: nothing of ours yet
    }

    @Test
    public void anotherPressStartsOver() {
        VolumeHold.Repeat r = new VolumeHold.Repeat(400, 50);
        r.volumeChanged(1000, 5, 6);
        r.check(1400, 1);
        r.stepped(1400, 7);
        assertEquals(1460, r.volumeChanged(1460, 7, 6));
        assertEquals(-1, r.direction());
        assertEquals(6, r.expected());
        assertEquals(WAIT, r.check(1460, 1));
        assertEquals(STEP, r.check(1860, 1));
    }
}
