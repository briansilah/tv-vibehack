package com.gathin.tvhome;

import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The order of the apps on the home screen, free of Android state so it can be unit tested. An app is
 * identified by its key: its launcher component, flattened ("package/.Activity").
 *
 * <p>As on tvOS, the dock is simply the first row of one list. Moving an app into it pushes the dock's
 * last app down into the grid; moving one out pulls the grid's first app up.
 */
final class AppOrder {

    private AppOrder() {
    }

    static String packageOf(String key) {
        int slash = key.indexOf('/');
        return slash < 0 ? key : key.substring(0, slash);
    }

    /**
     * The home screen order.
     *
     * @param alphabetical the installed apps' keys, sorted by name
     * @param dockApps     packages for the dock, in order (res/values/config.xml, dock_apps)
     * @param saved        the user's arrangement, or null while they have never rearranged
     */
    static List<String> arrange(List<String> alphabetical, List<String> dockApps, List<String> saved) {
        return saved == null ? byDefault(alphabetical, dockApps) : bySaved(alphabetical, saved);
    }

    /**
     * As {@link #arrange(List, List, List)}, with {@code last} (Home Settings) after the other apps rather than among
     * them by name, as a settings app goes after the apps: by default, and when it's new to a saved arrangement. Once
     * the user has placed it, it stays where they put it.
     */
    static List<String> arrange(List<String> alphabetical, List<String> dockApps, List<String> saved, String last) {
        List<String> names = alphabetical;
        if (last != null && alphabetical.contains(last)) {
            names = new ArrayList<>(alphabetical);
            names.remove(last);
            names.add(last);
        }
        return arrange(names, dockApps, saved);
    }

    /**
     * The dock apps first, in their configured order, then everything else by name. Dock apps that aren't
     * installed are skipped, so the next apps by name fill their slots.
     */
    static List<String> byDefault(List<String> alphabetical, List<String> dockApps) {
        List<String> out = new ArrayList<>(alphabetical);
        // Stable: apps outside the dock (and several activities of one dock package) keep their name order.
        Collections.sort(out, Comparator.comparingInt(key -> {
            int i = dockApps.indexOf(packageOf(key));
            return i < 0 ? Integer.MAX_VALUE : i;
        }));
        return out;
    }

    /**
     * The user's arrangement. Apps uninstalled since drop out and the rest close up; apps installed since
     * go at the end, by name, as tvOS does. An app whose launcher activity was renamed in an update keeps
     * its place, matched by package.
     */
    static List<String> bySaved(List<String> alphabetical, List<String> saved) {
        Set<String> installed = new HashSet<>(alphabetical);
        Set<String> known = new HashSet<>(saved);
        Map<String, String> unsavedByPackage = new HashMap<>();
        for (String key : alphabetical) {
            if (!known.contains(key) && !unsavedByPackage.containsKey(packageOf(key))) {
                unsavedByPackage.put(packageOf(key), key);
            }
        }
        List<String> out = new ArrayList<>(alphabetical.size());
        Set<String> placed = new HashSet<>();
        for (String key : saved) {
            String app = installed.contains(key) ? key : unsavedByPackage.remove(packageOf(key));
            if (app != null && placed.add(app)) out.add(app);
        }
        for (String key : alphabetical) {
            if (placed.add(key)) out.add(key);
        }
        return out;
    }

    /** One key per line: component names never contain a line break. */
    static String encode(List<String> keys) {
        return String.join("\n", keys);
    }

    /** The saved order, or null when nothing was saved. */
    static List<String> decode(String saved) {
        if (saved == null) return null;
        List<String> out = new ArrayList<>();
        for (String line : saved.split("\n")) {
            if (!line.isEmpty()) out.add(line);
        }
        return out;
    }

    /** Moves the item at {@code from} to {@code to}; the ones in between shift over by one. */
    static <T> void move(List<T> list, int from, int to) {
        list.add(to, list.remove(from));
    }

    /**
     * Where a D-pad press goes from {@code position} in a grid of {@code count} items, {@code columns} wide.
     * Used both to move focus and to move an app being rearranged, so an app moves where focus would go.
     * Left and Right stay in the row and stop at its ends, as on tvOS; Up and Down move one row, Down to the
     * last item when the row below is shorter. Returns {@code position} itself when there is nowhere to go
     * and -1 for Up from the first row (the dock), which leaves the grid.
     */
    static int step(int position, int count, int columns, int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return position % columns == 0 ? position : position - 1;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return position % columns == columns - 1 || position >= count - 1 ? position : position + 1;
            case KeyEvent.KEYCODE_DPAD_UP:
                return position < columns ? -1 : position - columns;
            case KeyEvent.KEYCODE_DPAD_DOWN: {
                int lastRowStart = ((count - 1) / columns) * columns;
                return position >= lastRowStart ? position : Math.min(position + columns, count - 1);
            }
            default:
                return position;
        }
    }

    /**
     * Where "Move to Dock" / "Move out of Dock" puts the app at {@code position}: from the grid to the
     * dock's last slot, from the dock to the grid's first. Returns {@code position} when there is no grid.
     */
    static int dockToggleTarget(int position, int count, int dockSize) {
        if (position >= dockSize) return dockSize - 1;
        return count > dockSize ? dockSize : position;
    }
}
