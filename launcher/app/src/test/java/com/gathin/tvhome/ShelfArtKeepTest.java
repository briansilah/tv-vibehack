package com.gathin.tvhome;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class ShelfArtKeepTest {

    @Test
    public void anArtworkFinishedAfterFocusMovedOnIsntKept() {
        ShelfArtKeep k = new ShelfArtKeep();
        // Focus on F1 TV: it and its neighbours may show.
        k.keep(Arrays.asList("plex", "f1", "music"));
        assertTrue(k.store("f1", true));
        // Focus moved on quickly while Plex's was being painted.
        k.keep(Arrays.asList("music", "settings"));
        assertFalse(k.store("plex", true));
        assertTrue(k.store("music", true));
        // Anything but an artwork is kept as before.
        assertTrue(k.store("plex", false));
    }

    @Test
    public void nothingIsKeptOnceHomeIsHidden() {
        ShelfArtKeep k = new ShelfArtKeep();
        k.keep(Collections.singletonList("f1"));
        k.none();
        assertFalse(k.kept("f1"));
        assertFalse(k.store("f1", true));
        assertTrue(k.store("f1", false));
    }

    @Test
    public void aStaticImageIsMadeWithTheDockOnceKnown() {
        ShelfArtKeep k = new ShelfArtKeep();
        // Not made yet: an app whose choice is a static image is tried once; another's artwork waits.
        assertTrue(k.preload("youtube", true, true));
        assertFalse(k.preload("f1", true, false));
        // Any cover but an artwork is made with the dock.
        assertTrue(k.preload("netflix", false, false));
        // YouTube's image is on the TV: made with the dock from then on, as before (back from an app, at once).
        k.made("youtube", true);
        assertTrue(k.preload("youtube", true, true));
        // None on the TV: it was its artwork, which waits like any other.
        k.made("youtube", false);
        assertFalse(k.preload("youtube", true, true));
        // Its choice changed: found out again.
        k.forget("youtube");
        assertTrue(k.preload("youtube", true, true));
        k.made("youtube", true);
        k.forgetAll();
        assertFalse(k.preload("youtube", true, false));
    }
}
