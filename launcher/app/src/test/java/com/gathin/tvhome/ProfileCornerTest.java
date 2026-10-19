package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ProfileCornerTest {

    @Test
    public void capsuleFitsTimeAndPicture() {
        // tvOS at 1080p: 24 px, "8:52 PM" 88 px, 12 px, the 50 px picture and 5 px make the 180 px capsule.
        assertEquals(179, ProfileCorner.restWidth(24, 88, 12, 50, 5));
    }

    @Test
    public void openTimeSitsWhereControlCentersPanelStarts() {
        // 444 px from the right end, well clear of the buttons.
        assertEquals(1000 - 444, ProfileCorner.openTimeLeft(1000, 444, 800, 32, 100));
    }

    @Test
    public void openTimeKeepsClearOfWideButtons() {
        // Buttons from 500: the time ends 32 px before them instead.
        assertEquals(500 - 32 - 100, ProfileCorner.openTimeLeft(1000, 444, 500, 32, 100));
        assertEquals(0, ProfileCorner.openTimeLeft(300, 444, 100, 32, 100));
    }

    @Test
    public void leapStartsWhereTvosFirstMovingFrameIs() {
        float[] rest = ProfileCorner.leap(0f);
        assertEquals(0f, rest[0], 1e-6f);
        assertEquals(0f, rest[1], 1e-6f);
        // Measured: 47 % and 52 % of the way in the first frames that move when opening, 30 % when closing.
        assertEquals(0.43f, ProfileCorner.leap(ProfileCorner.OPEN_LEAP_S)[0], 0.03f);
        assertEquals(0.30f, ProfileCorner.leap(ProfileCorner.CLOSE_LEAP_S)[0], 0.03f);
        assertTrue(ProfileCorner.leap(ProfileCorner.OPEN_LEAP_S)[1] > 0f);
    }

    @Test
    public void leapSettlesWithoutVisibleOvershoot() {
        float max = 0f;
        for (int ms = 0; ms <= 2000; ms += 4) max = Math.max(max, ProfileCorner.leap(ms / 1000f)[0]);
        assertTrue(max < 1.002f);
        // 95 % about 310 ms after tvOS's first moving frame.
        assertTrue(ProfileCorner.leap(ProfileCorner.OPEN_LEAP_S + 0.28f)[0] < 0.95f);
        assertTrue(ProfileCorner.leap(ProfileCorner.OPEN_LEAP_S + 0.34f)[0] > 0.95f);
    }

    @Test
    public void pictureSizes() {
        float full = ProfileCorner.AVATAR_FOCUSED_DP;
        assertEquals(ProfileCorner.AVATAR_REST_DP / full, ProfileCorner.avatarScale(0f, 0f), 1e-6f);
        assertEquals(ProfileCorner.AVATAR_REST_DP / full, ProfileCorner.avatarScale(0f, 1f), 1e-6f);
        assertEquals(ProfileCorner.AVATAR_OPEN_DP / full, ProfileCorner.avatarScale(1f, 0f), 1e-6f);
        assertEquals(1f, ProfileCorner.avatarScale(1f, 1f), 1e-6f);
    }

    @Test
    public void initials() {
        assertEquals("", ProfileCorner.initials(null));
        assertEquals("", ProfileCorner.initials("   "));
        assertEquals("B", ProfileCorner.initials(" brian "));
        assertEquals("BS", ProfileCorner.initials("Brian Silah"));
        assertEquals("AS", ProfileCorner.initials("Anna Maria  Schmidt"));
        assertEquals("ÉZ", ProfileCorner.initials("élodie zoë"));
    }
}
