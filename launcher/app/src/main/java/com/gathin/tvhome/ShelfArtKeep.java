package com.gathin.tvhome;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Which full-screen pictures the top shelf keeps decoded, for {@link ShelfSource}: an artwork (8 MB at 1080p) only
 * while it may show (the dock app showing it, and those either side: TopShelf.keepArtwork), and an app's static image
 * as before, with the dock. Plain Java, so it runs in JVM unit tests. Main thread.
 */
final class ShelfArtKeep {

    /** The apps (by key) whose artwork may show now or next. */
    private final Set<String> artwork = new HashSet<>();
    /** Whether each app's cover was a static image when last made; absent: not made yet. */
    private final Map<String, Boolean> image = new HashMap<>();

    /** The apps whose artwork may show now or next; the others' is dropped, and not kept when it's done. */
    void keep(Collection<String> keys) {
        artwork.clear();
        artwork.addAll(keys);
    }

    /** Home is hidden: no artwork may show. */
    void none() {
        artwork.clear();
    }

    boolean kept(String key) {
        return artwork.contains(key);
    }

    /**
     * Whether a cover just made for {@code key} is kept: always, but an artwork that may no longer show (focus moved on
     * while it was painted, or Home went away). Whoever waited for it still gets it.
     */
    boolean store(String key, boolean isArtwork) {
        return !isArtwork || artwork.contains(key);
    }

    /** A cover was made for {@code key}: whether it was a static image. */
    void made(String key, boolean isImage) {
        image.put(key, isImage);
    }

    /** The app's cover is to be made again from scratch (its choice changed, or the apps did). */
    void forget(String key) {
        image.remove(key);
    }

    void forgetAll() {
        image.clear();
        artwork.clear();
    }

    /**
     * Whether a dock app's cover is made with the dock, ahead of focus: any but an artwork, which waits until it may
     * show; an app with a static image as before, as soon as it's known to have one, and once to find out
     * ({@code imageChoice}: its choice is a static image, which may turn out to be its artwork).
     */
    boolean preload(String key, boolean artworkWanted, boolean imageChoice) {
        if (!artworkWanted) return true;
        Boolean was = image.get(key);
        return was != null ? was : imageChoice;
    }
}
