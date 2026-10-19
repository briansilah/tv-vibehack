package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.view.KeyEvent;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class AppOrderTest {

    private static final String NETFLIX = "com.netflix.ninja/.MainActivity";
    private static final String PRIME = "com.amazon.amazonvideo.livingroom/.Main";
    private static final String YOUTUBE = "com.google.android.youtube.tv/.Shell";
    private static final String HOTSTAR = "in.startv.hotstar/.Splash";
    private static final String APPLE = "com.apple.atve.androidtv.appletv/.Main";
    private static final String KODI = "org.xbmc.kodi/.Splash";
    private static final String PLEX = "com.plexapp.android/.Splash";
    private static final String SPOTIFY = "com.spotify.tv.android/.SpotifyTVActivity";

    private static final List<String> DOCK = Arrays.asList(
            "com.netflix.ninja", "com.amazon.amazonvideo.livingroom", "com.google.android.youtube.tv",
            "in.startv.hotstar");

    private static List<String> list(String... keys) {
        return new ArrayList<>(Arrays.asList(keys));
    }

    // ---- default order (config.xml) ---------------------------------------------------------------

    @Test
    public void defaultPutsDockAppsFirstInConfiguredOrderThenTheRestByName() {
        List<String> byName = list(APPLE, HOTSTAR, KODI, NETFLIX, PLEX, PRIME, SPOTIFY, YOUTUBE);
        assertEquals(list(NETFLIX, PRIME, YOUTUBE, HOTSTAR, APPLE, KODI, PLEX, SPOTIFY),
                AppOrder.arrange(byName, DOCK, null));
    }

    @Test
    public void defaultFillsTheSlotOfAMissingDockAppWithTheNextAppByName() {
        List<String> byName = list(APPLE, KODI, NETFLIX, PLEX, SPOTIFY, YOUTUBE);
        assertEquals(list(NETFLIX, YOUTUBE, APPLE, KODI, PLEX, SPOTIFY), AppOrder.arrange(byName, DOCK, null));
    }

    @Test
    public void defaultKeepsSeveralActivitiesOfADockPackageTogetherByName() {
        String netflixKids = "com.netflix.ninja/.KidsActivity";
        List<String> byName = list(APPLE, netflixKids, NETFLIX, YOUTUBE);
        assertEquals(list(netflixKids, NETFLIX, YOUTUBE, APPLE), AppOrder.arrange(byName, DOCK, null));
    }

    // ---- saved order ------------------------------------------------------------------------------

    @Test
    public void savedOrderWinsOverTheConfiguredDock() {
        List<String> byName = list(APPLE, HOTSTAR, KODI, NETFLIX, PRIME, YOUTUBE);
        List<String> saved = list(KODI, NETFLIX, APPLE, YOUTUBE, PRIME, HOTSTAR);
        assertEquals(saved, AppOrder.arrange(byName, DOCK, saved));
    }

    @Test
    public void uninstalledAppsDropOutAndTheRestCloseUp() {
        List<String> byName = list(APPLE, KODI, NETFLIX, YOUTUBE);
        List<String> saved = list(KODI, PRIME, NETFLIX, APPLE, YOUTUBE);
        // Prime left the dock: the others close up and YouTube, first in the grid, moves up into the dock.
        assertEquals(list(KODI, NETFLIX, APPLE, YOUTUBE), AppOrder.arrange(byName, DOCK, saved));
    }

    @Test
    public void newlyInstalledAppsGoAtTheEndByNameEvenIfTheyAreConfiguredDockApps() {
        List<String> byName = list(APPLE, HOTSTAR, KODI, NETFLIX, SPOTIFY, YOUTUBE);
        List<String> saved = list(YOUTUBE, KODI, APPLE, NETFLIX);
        assertEquals(list(YOUTUBE, KODI, APPLE, NETFLIX, HOTSTAR, SPOTIFY), AppOrder.arrange(byName, DOCK, saved));
    }

    @Test
    public void anAppWhoseActivityWasRenamedKeepsItsPlace() {
        String kodiRenamed = "org.xbmc.kodi/.Main";
        List<String> byName = list(APPLE, kodiRenamed, NETFLIX);
        List<String> saved = list(NETFLIX, KODI, APPLE);
        assertEquals(list(NETFLIX, kodiRenamed, APPLE), AppOrder.arrange(byName, DOCK, saved));
    }

    @Test
    public void aSecondActivityOfASavedPackageIsNewNotARename() {
        String kodiSettings = "org.xbmc.kodi/.Settings";
        List<String> byName = list(APPLE, KODI, kodiSettings, NETFLIX);
        List<String> saved = list(NETFLIX, KODI, APPLE);
        assertEquals(list(NETFLIX, KODI, APPLE, kodiSettings), AppOrder.arrange(byName, DOCK, saved));
    }

    @Test
    public void duplicateSavedKeysAreIgnored() {
        List<String> byName = list(APPLE, KODI, NETFLIX);
        List<String> saved = list(KODI, APPLE, KODI, NETFLIX, APPLE);
        assertEquals(list(KODI, APPLE, NETFLIX), AppOrder.arrange(byName, DOCK, saved));
    }

    @Test
    public void anEmptySavedOrderShowsEverythingByName() {
        List<String> byName = list(APPLE, KODI, NETFLIX);
        assertEquals(byName, AppOrder.arrange(byName, DOCK, list()));
    }

    // ---- persistence ------------------------------------------------------------------------------

    @Test
    public void encodeAndDecodeRoundTrip() {
        List<String> keys = list(NETFLIX, KODI, APPLE);
        assertEquals(keys, AppOrder.decode(AppOrder.encode(keys)));
    }

    @Test
    public void decodeOfNothingSavedIsNull() {
        assertNull(AppOrder.decode(null));
    }

    @Test
    public void decodeSkipsBlankLines() {
        assertEquals(list(NETFLIX, KODI), AppOrder.decode("\n" + NETFLIX + "\n\n" + KODI + "\n"));
    }

    // ---- moving -----------------------------------------------------------------------------------

    @Test
    public void movingIntoTheDockPushesItsLastAppIntoTheGrid() {
        List<String> order = list(NETFLIX, PRIME, YOUTUBE, HOTSTAR, APPLE, KODI);
        AppOrder.move(order, 5, 1);
        assertEquals(list(NETFLIX, KODI, PRIME, YOUTUBE, HOTSTAR, APPLE), order);
    }

    @Test
    public void movingOutOfTheDockPullsTheGridsFirstAppUp() {
        List<String> order = list(NETFLIX, PRIME, YOUTUBE, HOTSTAR, APPLE, KODI);
        AppOrder.move(order, 0, 4);
        assertEquals(list(PRIME, YOUTUBE, HOTSTAR, APPLE, NETFLIX, KODI), order);
    }

    @Test
    public void dockToggleMovesBetweenTheDocksLastSlotAndTheGridsFirst() {
        assertEquals(3, AppOrder.dockToggleTarget(9, 10, 4));
        assertEquals(3, AppOrder.dockToggleTarget(4, 10, 4));
        assertEquals(4, AppOrder.dockToggleTarget(0, 10, 4));
        assertEquals(4, AppOrder.dockToggleTarget(3, 10, 4));
        assertEquals(2, AppOrder.dockToggleTarget(2, 4, 4));   // no grid to move to
    }

    // ---- D-pad steps (4 columns, 10 apps: rows 0-3 dock, 4-7, 8-9) ----------------------------------

    private static int step(int position, int keyCode) {
        return AppOrder.step(position, 10, 4, keyCode);
    }

    @Test
    public void leftAndRightStopAtTheRowEnds() {
        assertEquals(4, step(4, KeyEvent.KEYCODE_DPAD_LEFT));
        assertEquals(4, step(5, KeyEvent.KEYCODE_DPAD_LEFT));
        assertEquals(3, step(3, KeyEvent.KEYCODE_DPAD_RIGHT));
        assertEquals(7, step(6, KeyEvent.KEYCODE_DPAD_RIGHT));
        assertEquals(9, step(9, KeyEvent.KEYCODE_DPAD_RIGHT));   // last app, row not full
    }

    @Test
    public void upLeavesTheGridFromTheDockAndOtherwiseMovesOneRow() {
        assertEquals(-1, step(2, KeyEvent.KEYCODE_DPAD_UP));
        assertEquals(2, step(6, KeyEvent.KEYCODE_DPAD_UP));
        assertEquals(5, step(9, KeyEvent.KEYCODE_DPAD_UP));
    }

    @Test
    public void downMovesOneRowOrToTheLastAppWhenTheRowBelowIsShorter() {
        assertEquals(6, step(2, KeyEvent.KEYCODE_DPAD_DOWN));
        assertEquals(9, step(6, KeyEvent.KEYCODE_DPAD_DOWN));
        assertEquals(9, step(7, KeyEvent.KEYCODE_DPAD_DOWN));
        assertEquals(8, step(8, KeyEvent.KEYCODE_DPAD_DOWN));   // already on the last row
    }
}
