package com.gathin.tvhome;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The top shelf's titles for the app focused: where Left, Right and the carousel go, in an order that holds while
 * you look at them, and which pictures to keep decoded so that what shows next is ready. Plain Java, so it runs in
 * JVM unit tests.
 */
final class ShelfOrder {

    /** Whether a title's picture couldn't be had lately (too small, or no download): it's passed over. */
    interface Failed {
        boolean failed(ShelfFeeds.Item it);
    }

    private ShelfOrder() {
    }

    /** What makes a title the same one when its app's list is read again (which makes new items). */
    static String key(ShelfFeeds.Item it) {
        return it.id.isEmpty() ? it.backdrop : it.id;
    }

    static boolean same(ShelfFeeds.Item a, ShelfFeeds.Item b) {
        return a != null && b != null && key(a).equals(key(b));
    }

    static int indexOf(List<ShelfFeeds.Item> items, ShelfFeeds.Item it) {
        if (items == null || it == null) return -1;
        for (int i = 0; i < items.size(); i++) {
            if (same(items.get(i), it)) return i;
        }
        return -1;
    }

    /** The title focus coming to the app shows: the first whose picture hasn't failed, or -1. */
    static int first(List<ShelfFeeds.Item> items, Failed failed) {
        if (items == null) return -1;
        for (int i = 0; i < items.size(); i++) {
            if (!failed.failed(items.get(i))) return i;
        }
        return -1;
    }

    /**
     * The next title from {@code from} going {@code dir} (1: right, -1: left), round the end of the list, passing
     * over those whose pictures failed; -1 if there's none but {@code from}.
     */
    static int step(List<ShelfFeeds.Item> items, int from, int dir, Failed failed) {
        if (items == null) return -1;
        int n = items.size();
        for (int k = 1; k < n; k++) {
            int i = Math.floorMod(from + dir * k, n);
            if (!failed.failed(items.get(i))) return i;
        }
        return -1;
    }

    /**
     * The app's titles read again ({@code fresh}) while you're looking at them ({@code shown}): those you have stay
     * where they are (as read again, for their links), and new ones go at the end, so Left and Right keep going
     * through the same titles in the same order.
     */
    static List<ShelfFeeds.Item> merge(List<ShelfFeeds.Item> shown, List<ShelfFeeds.Item> fresh) {
        if (shown == null) return fresh == null ? null : new ArrayList<>(fresh);
        Map<String, ShelfFeeds.Item> byKey = new HashMap<>();
        if (fresh != null) {
            for (ShelfFeeds.Item it : fresh) byKey.put(key(it), it);
        }
        List<ShelfFeeds.Item> out = new ArrayList<>(shown.size());
        Set<String> have = new HashSet<>();
        for (ShelfFeeds.Item it : shown) {
            ShelfFeeds.Item now = byKey.get(key(it));
            out.add(now != null ? now : it);
            have.add(key(it));
        }
        if (fresh != null) {
            for (ShelfFeeds.Item it : fresh) {
                if (have.add(key(it))) out.add(it);
            }
        }
        return out;
    }

    /**
     * The titles whose pictures to keep decoded, most urgent first. On the app focused ({@code titles}, at
     * {@code at}): the title showing or on its way, the next (the carousel's and Right's) and, while {@code browsing}
     * with Left and Right, the previous. Then each dock app's first title ({@code dock}: each one's titles, or
     * null), which focus coming to it shows, nearest the focused app ({@code focused}) first. One picture a dock
     * app and a few around what's showing: no more, as on a TV with 1 GB each full-screen picture counts.
     */
    static List<ShelfFeeds.Item> keep(List<List<ShelfFeeds.Item>> dock, int focused, List<ShelfFeeds.Item> titles,
                                      int at, boolean browsing, Failed failed) {
        Map<String, ShelfFeeds.Item> out = new LinkedHashMap<>();
        if (titles != null && at >= 0 && at < titles.size()) {
            add(out, titles.get(at), failed);
            int next = step(titles, at, 1, failed);
            if (next >= 0) add(out, titles.get(next), failed);
            int prev = browsing ? step(titles, at, -1, failed) : -1;
            if (prev >= 0) add(out, titles.get(prev), failed);
        }
        int n = dock.size();
        int from = Math.max(0, Math.min(focused, n - 1));
        for (int d = 0; d < n; d++) {
            for (int i : d == 0 ? new int[]{from} : new int[]{from + d, from - d}) {
                if (i < 0 || i >= n) continue;
                List<ShelfFeeds.Item> l = dock.get(i);
                int f = first(l, failed);
                if (f >= 0) add(out, l.get(f), failed);
            }
        }
        return new ArrayList<>(out.values());
    }

    private static void add(Map<String, ShelfFeeds.Item> out, ShelfFeeds.Item it, Failed failed) {
        if (it.backdrop != null && !failed.failed(it) && !out.containsKey(it.backdrop)) out.put(it.backdrop, it);
    }
}
