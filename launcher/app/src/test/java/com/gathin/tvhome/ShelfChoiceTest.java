package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ShelfChoiceTest {

    @Test
    public void withoutASavedChoiceEveryAppShowsItsPicture() {
        // A picture that stays put, not suggestions, which change and need the network.
        assertEquals(ShelfChoice.IMAGE, ShelfChoice.byDefault());
        assertEquals(ShelfChoice.IMAGE, ShelfChoice.resolve(null));
    }

    @Test
    public void aSavedChoiceWinsOverTheDefault() {
        assertEquals(ShelfChoice.SUGGESTIONS, ShelfChoice.resolve("suggestions"));
        assertEquals(ShelfChoice.IMAGE, ShelfChoice.resolve("image"));
        assertEquals(ShelfChoice.GLOW, ShelfChoice.resolve("glow"));
    }

    @Test
    public void anUnreadableChoiceFallsBackToTheDefault() {
        assertEquals(ShelfChoice.IMAGE, ShelfChoice.resolve("hologram"));
        assertEquals(ShelfChoice.IMAGE, ShelfChoice.resolve(""));
        assertEquals(ShelfChoice.IMAGE, ShelfChoice.resolve("colour:red"));
        assertEquals(ShelfChoice.IMAGE, ShelfChoice.resolve("colour:FF00001"));
    }

    @Test
    public void allAppsChoiceIsOverEachAppsOwnWithoutErasingIt() {
        // Per app (none saved for all): each app's own, else the default.
        assertEquals(ShelfChoice.GLOW, ShelfChoice.resolve(null, "glow"));
        assertEquals(ShelfChoice.IMAGE, ShelfChoice.resolve(null, null));
        // All apps' over the app's own and over the default.
        assertEquals(ShelfChoice.IMAGE, ShelfChoice.resolve("image", "glow"));
        assertEquals(ShelfChoice.SUGGESTIONS, ShelfChoice.resolve("suggestions", null));
        assertEquals(ShelfChoice.ARTWORK, ShelfChoice.resolve("artwork", "colour:0A84FF"));
        // An unreadable one for all apps: each app's own again.
        assertEquals(ShelfChoice.GLOW, ShelfChoice.resolve("hologram", "glow"));
        // Its key isn't an app's.
        assertNull(ShelfChoice.pkgOf(ShelfChoice.PREF_ALL));
    }

    @Test
    public void aPictureOfItsOwnFallsBackToItsColourNotItsCoverArt() {
        assertFalse(ShelfChoice.IMAGE.fallsBackToCoverArt());
        assertTrue(ShelfChoice.SUGGESTIONS.fallsBackToCoverArt());
        assertTrue(ShelfChoice.ARTWORK.fallsBackToCoverArt());
        assertTrue(ShelfChoice.COVER_ART.fallsBackToCoverArt());
    }

    @Test
    public void everyChoiceSurvivesSavingAndReading() {
        for (ShelfChoice.Kind k : ShelfChoice.Kind.values()) {
            ShelfChoice c = ShelfChoice.of(k);
            assertEquals(c, ShelfChoice.decode(c.encode()));
        }
        for (int rgb : ShelfChoice.PALETTE) {
            ShelfChoice c = ShelfChoice.colour(rgb);
            assertEquals(c, ShelfChoice.decode(c.encode()));
        }
    }

    @Test
    public void choicesAreSavedAsShortWords() {
        assertEquals("suggestions", ShelfChoice.SUGGESTIONS.encode());
        assertEquals("image", ShelfChoice.IMAGE.encode());
        assertEquals("artwork", ShelfChoice.ARTWORK.encode());
        assertEquals("cover_art", ShelfChoice.COVER_ART.encode());
        assertEquals("glow", ShelfChoice.GLOW.encode());
        assertEquals("colour:0A84FF", ShelfChoice.colour(0x0A84FF).encode());
    }

    @Test
    public void aColourIsOpaqueAndReadInEitherCase() {
        assertEquals(0xFF0A84FF, ShelfChoice.colour(0x0A84FF).colour);
        assertEquals(0xFF0A84FF, ShelfChoice.colour(0x330A84FF).colour);
        assertEquals(ShelfChoice.colour(0xBF5AF2), ShelfChoice.decode("colour:bf5af2"));
        assertFalse(ShelfChoice.colour(0xBF5AF2).equals(ShelfChoice.colour(0xBF5AF3)));
    }

    @Test
    public void onlySuggestionsShowTitles() {
        for (ShelfChoice.Kind k : ShelfChoice.Kind.values()) {
            assertEquals(k == ShelfChoice.Kind.SUGGESTIONS, ShelfChoice.of(k).suggests());
        }
    }

    @Test
    public void theGlowAloneHasNoPicture() {
        assertTrue(ShelfChoice.SUGGESTIONS.picture());
        assertTrue(ShelfChoice.IMAGE.picture());
        assertTrue(ShelfChoice.ARTWORK.picture());
        assertTrue(ShelfChoice.COVER_ART.picture());
        assertFalse(ShelfChoice.GLOW.picture());
        assertFalse(ShelfChoice.colour(0xFF453A).picture());
    }

    @Test
    public void aStaticPictureOpensTheAppFromItsButtonButSuggestionsCoverArtDoesnt() {
        assertTrue(ShelfChoice.IMAGE.staticPicture());
        assertTrue(ShelfChoice.ARTWORK.staticPicture());
        assertTrue(ShelfChoice.COVER_ART.staticPicture());
        assertFalse(ShelfChoice.SUGGESTIONS.staticPicture());
        assertFalse(ShelfChoice.GLOW.staticPicture());
    }

    @Test
    public void coverArtOnlySkipsTheArtwork() {
        assertTrue(ShelfChoice.SUGGESTIONS.triesArtwork());
        assertTrue(ShelfChoice.IMAGE.triesArtwork());
        assertTrue(ShelfChoice.ARTWORK.triesArtwork());
        assertFalse(ShelfChoice.COVER_ART.triesArtwork());
    }

    @Test
    public void preferenceKeysNameTheirApp() {
        assertEquals("shelf_choice.com.netflix.ninja", ShelfChoice.key("com.netflix.ninja"));
        assertEquals("com.netflix.ninja", ShelfChoice.pkgOf("shelf_choice.com.netflix.ninja"));
        assertNull(ShelfChoice.pkgOf("liquid_glass"));
        assertNull(ShelfChoice.pkgOf("shelf_choice."));
        assertNull(ShelfChoice.pkgOf(null));
    }
}
