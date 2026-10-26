package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ProfileCornerTest {

    @Test
    public void capsuleFitsTimeAndPicture() {
        // tvOS at 1080p: 24 px, "8:52 PM" 88 px, 12 px, the 50 px picture and 5 px make the 179 px capsule.
        assertEquals(179, ProfileCorner.restWidth(24, 88, 12, 50, 5));
    }

    @Test
    public void openTimeSitsJustBeforeTheDivider() {
        assertEquals(800 - 24 - 100, ProfileCorner.openTimeLeft(800, 24, 100));
        assertEquals(0, ProfileCorner.openTimeLeft(100, 24, 100));
    }

    @Test
    public void buttonsFadeInAsTheyClearThePicture() {
        assertEquals(0f, ProfileCorner.tabAlpha(0f, 72f), 0f);
        assertEquals(0.5f, ProfileCorner.tabAlpha(0.45f * 72f, 72f), 1e-6f);
        assertEquals(1f, ProfileCorner.tabAlpha(72f, 72f), 0f);
        assertEquals(1f, ProfileCorner.tabAlpha(300f, 72f), 0f);
    }

    @Test
    public void capsuleFadesAsTheCornerOpens() {
        assertEquals(1f, ProfileCorner.capsuleAlpha(0f), 0f);
        assertEquals(1f, ProfileCorner.capsuleAlpha(-0.01f), 0f);
        assertEquals(0.5f, ProfileCorner.capsuleAlpha(ProfileCorner.CAPSULE_FADE / 2f), 1e-6f);
        assertEquals(0f, ProfileCorner.capsuleAlpha(ProfileCorner.CAPSULE_FADE), 0f);
        assertEquals(0f, ProfileCorner.capsuleAlpha(1.002f), 0f);
    }

    @Test
    public void reducedMotionEndpointsShowEitherCapsuleOrControls() {
        float slot = ProfileCorner.SLOT_DP;
        for (int button = 1; button <= 3; button++) {
            float distance = button * slot;
            assertEquals(0f, ProfileCorner.tabAlpha(distance * 0f, slot), 0f);
            assertEquals(1f, ProfileCorner.tabAlpha(distance * 1f, slot), 0f);
        }
        assertEquals(1f, ProfileCorner.capsuleAlpha(0f), 0f);
        assertEquals(0f, ProfileCorner.capsuleAlpha(1f), 0f);
    }

    @Test
    public void springMovesAtOnceAndSettlesInAboutThreeHundredMs() {
        assertEquals(0f, ProfileCorner.position(0f), 1e-6f);
        assertTrue(ProfileCorner.position(1 / 60f) > 0.05f);   // the first frame already moves
        assertTrue(ProfileCorner.position(0.27f) < 0.95f);
        assertTrue(ProfileCorner.position(0.33f) > 0.95f);
        float max = 0f;
        for (int ms = 0; ms <= 2000; ms += 4) max = Math.max(max, ProfileCorner.position(ms / 1000f));
        assertTrue(max < 1.003f);   // no visible overshoot
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
