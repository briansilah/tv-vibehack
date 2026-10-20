package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ShelfOrderTest {

    private static final ShelfOrder.Failed NONE_FAILED = it -> false;

    private static ShelfFeeds.Item item(String id) {
        return new ShelfFeeds.Item(id, "Title " + id, "https://bg/" + id, "https://logo/" + id);
    }

    /** What an app publishes on the TV: no id, so a title is its backdrop. Each read makes new items. */
    private static ShelfFeeds.Item own(String name) {
        return new ShelfFeeds.Item(null, name, "content://tv/" + name, null);
    }

    private static List<ShelfFeeds.Item> items(String... ids) {
        List<ShelfFeeds.Item> l = new ArrayList<>();
        for (String id : ids) l.add(item(id));
        return l;
    }

    private static ShelfOrder.Failed failing(String... ids) {
        Set<String> bad = new HashSet<>();
        for (String id : ids) bad.add("https://bg/" + id);
        return it -> bad.contains(it.backdrop);
    }

    private static List<String> ids(List<ShelfFeeds.Item> l) {
        List<String> out = new ArrayList<>();
        for (ShelfFeeds.Item it : l) out.add(it.id.isEmpty() ? it.title : it.id);
        return out;
    }

    @Test
    public void rightThenLeftComesBackThroughTheSameTitles() {
        List<ShelfFeeds.Item> l = items("a", "b", "c", "d", "e");
        int at = 0;
        List<String> seen = new ArrayList<>();
        for (int dir : new int[]{1, 1, 1, -1, -1, -1}) {
            at = ShelfOrder.step(l, at, dir, NONE_FAILED);
            seen.add(l.get(at).id);
        }
        assertEquals(Arrays.asList("b", "c", "d", "c", "b", "a"), seen);
    }

    @Test
    public void stepsRoundTheEndsAndPassOverFailedPictures() {
        List<ShelfFeeds.Item> l = items("a", "b", "c", "d");
        assertEquals(3, ShelfOrder.step(l, 0, -1, NONE_FAILED));
        assertEquals(0, ShelfOrder.step(l, 3, 1, NONE_FAILED));
        ShelfOrder.Failed bc = failing("b", "c");
        assertEquals(3, ShelfOrder.step(l, 0, 1, bc));
        assertEquals(0, ShelfOrder.step(l, 3, -1, bc));
        // Only the title you're on: nowhere to go.
        assertEquals(-1, ShelfOrder.step(l, 0, 1, failing("b", "c", "d")));
        assertEquals(-1, ShelfOrder.step(items("a"), 0, 1, NONE_FAILED));
        assertEquals(-1, ShelfOrder.step(null, 0, 1, NONE_FAILED));
    }

    @Test
    public void firstPassesOverFailedPictures() {
        List<ShelfFeeds.Item> l = items("a", "b", "c");
        assertEquals(0, ShelfOrder.first(l, NONE_FAILED));
        assertEquals(2, ShelfOrder.first(l, failing("a", "b")));
        assertEquals(-1, ShelfOrder.first(l, failing("a", "b", "c")));
        assertEquals(-1, ShelfOrder.first(null, NONE_FAILED));
    }

    @Test
    public void sameTitleWhenReadAgain() {
        assertTrue(ShelfOrder.same(own("Severance"), own("Severance")));
        assertFalse(ShelfOrder.same(own("Severance"), own("Silo")));
        assertTrue(ShelfOrder.same(item("tt1"), new ShelfFeeds.Item("tt1", "Other", "https://bg/other", null)));
        assertFalse(ShelfOrder.same(item("tt1"), null));
        List<ShelfFeeds.Item> l = Arrays.asList(own("Severance"), own("Silo"));
        assertEquals(1, ShelfOrder.indexOf(l, own("Silo")));
        assertEquals(-1, ShelfOrder.indexOf(l, own("Foundation")));
        assertEquals(-1, ShelfOrder.indexOf(null, own("Silo")));
    }

    @Test
    public void readingAgainKeepsTheOrderYouSaw() {
        List<ShelfFeeds.Item> shown = Arrays.asList(own("Severance"), own("Silo"), own("Foundation"));
        // Read again: new items, another order, one gone, one new.
        ShelfFeeds.Item silo = own("Silo");
        List<ShelfFeeds.Item> fresh = Arrays.asList(own("Ted Lasso"), own("Foundation"), silo);
        List<ShelfFeeds.Item> merged = ShelfOrder.merge(shown, fresh);
        assertEquals(Arrays.asList("Severance", "Silo", "Foundation", "Ted Lasso"), ids(merged));
        // As read again, for their links.
        assertSame(silo, merged.get(1));
        assertSame(shown.get(0), merged.get(0));

        assertEquals(ids(shown), ids(ShelfOrder.merge(shown, null)));
        assertEquals(ids(fresh), ids(ShelfOrder.merge(null, fresh)));
        assertNull(ShelfOrder.merge(null, null));
    }

    @Test
    public void leftRetracesRightEvenWhenTheListIsReadAgainMeanwhile() {
        List<ShelfFeeds.Item> l = Arrays.asList(own("A"), own("B"), own("C"), own("D"));
        int at = ShelfOrder.step(l, 0, 1, NONE_FAILED);
        at = ShelfOrder.step(l, at, 1, NONE_FAILED);
        assertEquals("C", l.get(at).title);
        // Home read what the app published again: what you were watching moved, and there's something new.
        l = ShelfOrder.merge(l, Arrays.asList(own("C"), own("E"), own("A"), own("B"), own("D")));
        at = ShelfOrder.step(l, at, -1, NONE_FAILED);
        assertEquals("B", l.get(at).title);
        at = ShelfOrder.step(l, at, -1, NONE_FAILED);
        assertEquals("A", l.get(at).title);
    }

    @Test
    public void keepsEachDockAppsFirstTitleNearestTheFocusedAppFirst() {
        List<List<ShelfFeeds.Item>> dock = Arrays.asList(items("n1", "n2"), items("p1", "p2"), null,
                items("h1", "h2"), items("s1"));
        List<ShelfFeeds.Item> kept = ShelfOrder.keep(dock, 3, null, -1, false, NONE_FAILED);
        assertEquals(Arrays.asList("h1", "s1", "p1", "n1"), ids(kept));
        kept = ShelfOrder.keep(dock, 0, null, -1, false, NONE_FAILED);
        assertEquals(Arrays.asList("n1", "p1", "h1", "s1"), ids(kept));
        // Out of range (the dock got shorter): from its end.
        assertEquals(Arrays.asList("s1", "h1", "p1", "n1"), ids(ShelfOrder.keep(dock, 9, null, -1, false, NONE_FAILED)));
        assertTrue(ShelfOrder.keep(Collections.emptyList(), 0, null, -1, false, NONE_FAILED).isEmpty());
    }

    @Test
    public void keepsTheTitleFocusComingToTheAppShows() {
        // Its first title's picture failed: focus coming to it shows the next, so that's the one kept.
        List<List<ShelfFeeds.Item>> dock = Arrays.asList(items("a1", "a2", "a3"), items("b1", "b2"), items("c1"));
        ShelfOrder.Failed failed = failing("a1", "c1");
        List<ShelfFeeds.Item> kept = ShelfOrder.keep(dock, 0, null, -1, false, failed);
        assertEquals(Arrays.asList("a2", "b1"), ids(kept));
        for (List<ShelfFeeds.Item> l : dock) {
            int first = ShelfOrder.first(l, failed);
            if (first >= 0) assertTrue(ShelfOrder.indexOf(kept, l.get(first)) >= 0);
        }
    }

    @Test
    public void keepsTheTitlesNextToTheOneShowingFirst() {
        List<ShelfFeeds.Item> netflix = items("n1", "n2", "n3", "n4", "n5");
        List<List<ShelfFeeds.Item>> dock = Arrays.asList(items("a1"), netflix, items("c1"));
        // On the dock, the carousel at the third title: it, the carousel's next, then each app's first.
        assertEquals(Arrays.asList("n3", "n4", "n1", "c1", "a1"),
                ids(ShelfOrder.keep(dock, 1, netflix, 2, false, NONE_FAILED)));
        // On the shelf's button, Left is a press away too.
        assertEquals(Arrays.asList("n3", "n4", "n2", "n1", "c1", "a1"),
                ids(ShelfOrder.keep(dock, 1, netflix, 2, true, NONE_FAILED)));
        // At the first title, round the end for Left; no title twice.
        assertEquals(Arrays.asList("n1", "n2", "n5", "c1", "a1"),
                ids(ShelfOrder.keep(dock, 1, netflix, 0, true, NONE_FAILED)));
        // Failed pictures are passed over, as Left and Right pass over them.
        assertEquals(Arrays.asList("n3", "n5", "n1", "c1", "a1"),
                ids(ShelfOrder.keep(dock, 1, netflix, 2, true, failing("n2", "n4"))));
    }
}
