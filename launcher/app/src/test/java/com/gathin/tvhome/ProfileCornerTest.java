package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;

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
    public void tabsFadeInOverTheFirstPartOfTheWay() {
        assertEquals(0f, ProfileCorner.tabAlpha(0f), 0f);
        assertEquals(0f, ProfileCorner.tabAlpha(-0.01f), 0f);
        assertEquals(0.5f, ProfileCorner.tabAlpha(ProfileCorner.TAB_FADE / 2f), 1e-6f);
        assertEquals(1f, ProfileCorner.tabAlpha(ProfileCorner.TAB_FADE), 0f);
        assertEquals(1f, ProfileCorner.tabAlpha(1.002f), 0f);
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
