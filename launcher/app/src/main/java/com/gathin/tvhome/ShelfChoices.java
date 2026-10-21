package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The top shelf choices saved on the TV (Home Settings), with config.xml's defaults for the rest: in the launcher's
 * "home" preferences, which updates keep and clearing the launcher's storage resets. Notes which apps' choices
 * changed since asked, so Home redoes only those. Main thread.
 */
final class ShelfChoices {

    private final SharedPreferences prefs;
    /** Apps config.xml gives a static image (top_shelf_images). */
    private final Set<String> images;
    /** With tools/perf.py's --ez default_config true: config.xml's choices, the saved ones kept but unused. */
    private boolean shipped;
    private final Set<String> changed = new HashSet<>();
    /** Held here: SharedPreferences keeps its listeners only weakly. */
    private final SharedPreferences.OnSharedPreferenceChangeListener watcher = (p, key) -> {
        // A null key is clear(), which the launcher never calls (clearing its storage ends the process too).
        String pkg = ShelfChoice.pkgOf(key);
        if (pkg != null) changed.add(pkg);
    };

    ShelfChoices(Context ctx, Collection<String> imageApps) {
        prefs = ctx.getSharedPreferences("home", Context.MODE_PRIVATE);
        images = new HashSet<>(imageApps);
    }

    /** From now on, notes changes made elsewhere (Home Settings) for {@link #takeChanged}. */
    void watch() {
        prefs.registerOnSharedPreferenceChangeListener(watcher);
    }

    /** What the app's top shelf shows. */
    ShelfChoice get(String pkg) {
        boolean listed = images.contains(pkg);
        return shipped ? ShelfChoice.byDefault(listed) : ShelfChoice.resolve(prefs.getString(ShelfChoice.key(pkg), null), listed);
    }

    ShelfChoice byDefault(String pkg) {
        return ShelfChoice.byDefault(images.contains(pkg));
    }

    /** Saves a choice; config.xml's own isn't saved, so a later change to config.xml applies. */
    void set(String pkg, ShelfChoice c) {
        SharedPreferences.Editor e = prefs.edit();
        if (c == null || c.equals(byDefault(pkg))) e.remove(ShelfChoice.key(pkg));
        else e.putString(ShelfChoice.key(pkg), c.encode());
        e.apply();
    }

    /** How many apps have a choice of their own in effect: 0 with config.xml's ({@link #useShipped}). */
    int inEffect() {
        if (shipped) return 0;
        int n = 0;
        for (Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
            String pkg = ShelfChoice.pkgOf(e.getKey());
            if (pkg != null && e.getValue() instanceof String
                    && !ShelfChoice.resolve((String) e.getValue(), images.contains(pkg)).equals(byDefault(pkg))) {
                n++;
            }
        }
        return n;
    }

    /** Whether to use config.xml's choices only (tools/perf.py measures the configuration the launcher ships). */
    void useShipped(boolean on) {
        if (on == shipped) return;
        shipped = on;
        for (String key : prefs.getAll().keySet()) {
            String pkg = ShelfChoice.pkgOf(key);
            if (pkg != null) changed.add(pkg);
        }
    }

    /** The apps whose choice changed since the last call. */
    Set<String> takeChanged() {
        if (changed.isEmpty()) return Collections.emptySet();
        Set<String> out = new HashSet<>(changed);
        changed.clear();
        return out;
    }
}
