package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;

public class ShelfChoicesTest {

    private static final String NETFLIX = "com.netflix.ninja";
    private static final String YOUTUBE = "com.google.android.youtube.tv";

    /** The owner's saved choices: Netflix on its glow, and every app on its static image. */
    private static MemoryPrefs saved() {
        MemoryPrefs p = new MemoryPrefs();
        p.edit().putString(ShelfChoice.key(NETFLIX), "glow").putString(ShelfChoice.PREF_ALL, "image").apply();
        return p;
    }

    private static ShelfChoices choices(MemoryPrefs p) {
        return new ShelfChoices(p, Collections.singleton(YOUTUBE));
    }

    @Test
    public void savedChoicesApplyAllAppsOverEachAppsOwn() {
        ShelfChoices c = choices(saved());
        assertEquals(ShelfChoice.IMAGE, c.get(NETFLIX));
        assertEquals(ShelfChoice.GLOW, c.own(NETFLIX));
        assertEquals(ShelfChoice.IMAGE, c.all());
        assertEquals(2, c.inEffect());
    }

    @Test
    public void theShippedConfigurationIgnoresThemWithoutErasingThem() {
        MemoryPrefs p = saved();
        ShelfChoices c = choices(p);
        c.useShipped(true);
        assertEquals(ShelfChoice.SUGGESTIONS, c.get(NETFLIX));
        assertEquals(ShelfChoice.IMAGE, c.get(YOUTUBE));
        assertNull(c.all());
        assertEquals(0, c.inEffect());
        c.useShipped(false);
        assertEquals(ShelfChoice.IMAGE, c.get(NETFLIX));
        assertEquals("glow", p.getString(ShelfChoice.key(NETFLIX), null));
    }

    @Test
    public void eachNewOneStartsFromTheConfigurationItIsGiven() {
        // As MainActivity rebuilt in the same process: a new TopShelf, so new ShelfChoices, told the process's
        // default_config before it shows anything.
        MemoryPrefs p = saved();
        ShelfChoices before = choices(p);
        before.useShipped(true);
        ShelfChoices rebuilt = choices(p);
        assertEquals(2, rebuilt.inEffect());   // what it would use if nobody told it
        rebuilt.useShipped(true);
        assertEquals(0, rebuilt.inEffect());
        assertEquals(ShelfChoice.SUGGESTIONS, rebuilt.get(NETFLIX));
    }

    @Test
    public void changesAreNotedByAppAndForAllApps() {
        MemoryPrefs p = new MemoryPrefs();
        ShelfChoices c = choices(p);
        c.watch();
        c.set(NETFLIX, ShelfChoice.GLOW);
        assertEquals(Collections.singleton(NETFLIX), c.takeChanged());
        assertTrue(c.takeChanged().isEmpty());
        c.setAll(ShelfChoice.IMAGE);
        assertEquals(Collections.singleton(ShelfChoices.ALL), c.takeChanged());
        // Into the shipped configuration and out of it: every saved choice may change what shows.
        c.useShipped(true);
        assertEquals(new HashSet<>(Arrays.asList(ShelfChoices.ALL, NETFLIX)), c.takeChanged());
    }

    @Test
    public void choosingTheDefaultForgetsTheChoice() {
        MemoryPrefs p = new MemoryPrefs();
        ShelfChoices c = choices(p);
        c.set(YOUTUBE, ShelfChoice.GLOW);
        c.set(YOUTUBE, ShelfChoice.IMAGE);   // config.xml's for YouTube
        assertTrue(p.getAll().isEmpty());
        c.setAll(ShelfChoice.GLOW);
        c.setAll(null);
        assertTrue(p.getAll().isEmpty());
    }
}
