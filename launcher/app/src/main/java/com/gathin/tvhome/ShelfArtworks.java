package com.gathin.tvhome;

import static com.gathin.tvhome.ShelfArt.band;
import static com.gathin.tvhome.ShelfArt.circle;
import static com.gathin.tvhome.ShelfArt.ellipse;
import static com.gathin.tvhome.ShelfArt.hsl;
import static com.gathin.tvhome.ShelfArt.ink;
import static com.gathin.tvhome.ShelfArt.poly;
import static com.gathin.tvhome.ShelfArt.rect;
import static com.gathin.tvhome.ShelfArt.ribbon;
import static com.gathin.tvhome.ShelfArt.tone;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The artworks, one per app, each its own composition from the app's brand: its hue with a few harmonised
 * neighbours and one accent, and abstract motifs from its identity (stripes, arcs, ribbons, petals). All in one
 * manner: six to nine big shapes overlapping in clusters that run off the screen's edges, around the calm dark middle
 * where the logo goes ({@link ShelfArt#CALM_X}); mostly flat paint, a few long dry-brush strokes in a close tone,
 * a bold scribble or two. All original: no app's own pictures or logos, traced or otherwise; the logo comes at
 * runtime, as for cover art.
 */
final class ShelfArtworks {

    private static final Map<String, ShelfArt.Recipe> BY_KEY = new LinkedHashMap<>();
    private static final Map<String, String> KEY_BY_PACKAGE = new HashMap<>();

    static {
        // On the TV this was made for.
        add("youtube", ShelfArtworks::youtube, "com.google.android.youtube.tv");
        add("youtubemusic", ShelfArtworks::youtubeMusic, "com.google.android.youtube.tvmusic");
        add("netflix", ShelfArtworks::netflix, "com.netflix.ninja");
        add("appletv", ShelfArtworks::appleTv, "com.apple.atve.androidtv.appletv");
        add("f1tv", ShelfArtworks::f1, "com.formulaone.production");
        add("plex", ShelfArtworks::plex, "com.plexapp.android");
        add("stremio", ShelfArtworks::stremio, "com.stremio.one");
        add("playstore", ShelfArtworks::playStore, "com.android.vending");
        add("playgames", ShelfArtworks::playGames, "com.google.android.play.games");
        add("settings", ShelfArtworks::settings, "com.android.tv.settings");
        add("tclbrowser", ShelfArtworks::tclBrowser, "com.tcl.browser");
        add("tclmedia", ShelfArtworks::tclMedia, "com.tcl.ui_mediaCenter");
        add("tcl", ShelfArtworks::tcl, "com.tcl.tv", "com.tcl.dashboard", "com.tcl.exhibit", "com.tcl.guard",
                "com.tcl.magiconnectfree", "com.tcl.ocean.instructions", "com.tcl.suspension", "com.tcl.t_solo",
                "com.tcl.waterfall.overseas");
        // The most watched elsewhere, and in Kenya.
        add("primevideo", ShelfArtworks::primeVideo, "com.amazon.amazonvideo.livingroom");
        add("disneyplus", ShelfArtworks::disneyPlus, "com.disney.disneyplus");
        add("hbomax", ShelfArtworks::hboMax, "com.wbd.stream");
        add("spotify", ShelfArtworks::spotify, "com.spotify.tv.android");
        add("twitch", ShelfArtworks::twitch, "tv.twitch.android.app");
        add("crunchyroll", ShelfArtworks::crunchyroll, "com.crunchyroll.crunchyroid");
        add("paramountplus", ShelfArtworks::paramountPlus, "com.cbs.ott");
        add("peacock", ShelfArtworks::peacock, "com.peacocktv.peacockandroid");
        add("hulu", ShelfArtworks::hulu, "com.hulu.livingroomplus");
        add("tubi", ShelfArtworks::tubi, "com.tubitv");
        add("plutotv", ShelfArtworks::plutoTv, "tv.pluto.android");
        add("dazn", ShelfArtworks::dazn, "com.dazn");
        add("jiohotstar", ShelfArtworks::jioHotstar, "in.startv.hotstar");
        add("dstv", ShelfArtworks::dstv, "com.dstvmobile.android");
        add("viusasa", ShelfArtworks::viusasa, "com.viusasatv");
        add("kodi", ShelfArtworks::kodi, "org.xbmc.kodi");
        add("vlc", ShelfArtworks::vlc, "org.videolan.vlc");
    }

    private ShelfArtworks() {
    }

    private static void add(String key, ShelfArt.Recipe r, String... packages) {
        BY_KEY.put(key, r);
        for (String p : packages) KEY_BY_PACKAGE.put(p, key);
    }

    static ShelfArt.Recipe recipe(String pkg) {
        String key = KEY_BY_PACKAGE.get(pkg);
        return key == null ? null : BY_KEY.get(key);
    }

    /** The artwork's name, shared by every package that shows it. */
    static String key(String pkg) {
        return KEY_BY_PACKAGE.get(pkg);
    }

    static Set<String> keys() {
        return Collections.unmodifiableSet(BY_KEY.keySet());
    }

    static ShelfArt.Recipe byKey(String key) {
        return BY_KEY.get(key);
    }

    static Map<String, String> packages() {
        return Collections.unmodifiableMap(KEY_BY_PACKAGE);
    }

    // ---- helpers -------------------------------------------------------------------------------------------

    /** The ground the reference picture has: a neutral charcoal, a breath of the app's hue in it. */
    private static int ground(float hue) {
        return hsl(hue, 0.06f, 0.125f);
    }

    /** Flat paint with a few long strokes of a close tone along {@code deg}: darker on light colours, else lighter. */
    private static ShelfArt.Ink paint(int c, float deg) {
        float l = (Math.max((c >> 16) & 0xFF, Math.max((c >> 8) & 0xFF, c & 0xFF))
                + Math.min((c >> 16) & 0xFF, Math.min((c >> 8) & 0xFF, c & 0xFF))) / 510f;
        return ink(c).strokes(tone(c, l > 0.55f ? -0.09f : 0.10f), deg);
    }

    private static ShelfArt.Ink flat(int c) {
        return ink(c);
    }

    /** A rounded pill from one point to another. */
    private static ShelfArt.Shape pill(float ax, float ay, float bx, float by, float t) {
        return band(ax, ay, bx, by, t);
    }

    /** A chevron pointing right: its arms {@code t} thick, from its back {@code s} behind the notch at cx, cy. */
    private static ShelfArt.Shape chevron(float cx, float cy, float s, float t) {
        return poly(cx - s, cy - s, cx - s + t, cy - s, cx + t, cy, cx - s + t, cy + s, cx - s, cy + s, cx, cy)
                .grow(t * 0.1f);
    }

    /** A four-pointed sparkle, its sides curving in. */
    private static ShelfArt.Shape sparkle(float cx, float cy, float r) {
        int n = 64;
        float[] xy = new float[2 * n];
        for (int i = 0; i < n; i++) {
            double t = 2 * Math.PI * i / n, c = Math.cos(t), sn = Math.sin(t);
            xy[2 * i] = cx + r * (float) (Math.signum(c) * Math.pow(Math.abs(c), 2.6));
            xy[2 * i + 1] = cy + r * (float) (Math.signum(sn) * Math.pow(Math.abs(sn), 2.6));
        }
        return poly(xy);
    }

    // ---- the artworks --------------------------------------------------------------------------------------

    /** YouTube (until its Apple TV image is on the TV): rounded screens and pills in its red, coral, pink and a
     * deep red, a cream accent. */
    private static void youtube(ShelfArt.Easel e) {
        int red = hsl(0, 0.90f, 0.50f), deep = hsl(352, 0.72f, 0.33f), coral = hsl(8, 0.90f, 0.64f),
                pink = hsl(342, 0.85f, 0.80f), cream = hsl(30, 0.45f, 0.92f);
        e.ground(ground(0));
        // Top left: pills stacked out of the edge, pink and coral, a cream pebble.
        e.fill(pill(-200f, 40f, 480f, -30f, 230f), paint(pink, -6f));
        e.fill(pill(-200f, 270f, 360f, 230f, 190f), paint(coral, -4f));
        e.fill(pill(-200f, 470f, 220f, 450f, 160f), paint(deep, -3f));
        e.fill(circle(600f, -40f, 130f), paint(cream, 0f));
        // Top right: a red rounded screen tilted out of the corner, deep red behind, a cream scribble on it.
        e.fill(circle(1960f, 560f, 230f), paint(deep, 30f));
        ShelfArt.Shape screen = rect(1810f, 150f, 780f, 540f, 12f, 140f);
        e.fill(screen, paint(red, 12f));
        e.scribble(1790f, 290f, 170f, 13f, 3.5f, 102f, flat(cream), screen);
        e.fill(circle(1460f, -40f, 120f), paint(pink, 0f));
        // Under the dock: a cream pill, a red one.
        e.fill(pill(-120f, 1020f, 640f, 940f, 260f), paint(red, -6f));
        e.fill(pill(1000f, 1080f, 1700f, 980f, 240f), paint(cream, -8f));
        e.fill(circle(840f, 900f, 90f), paint(coral, 0f));
    }

    /** YouTube Music: rings spreading like sound, ripples off the edge, a wave; red, plum, rose and coral, an
     * orange accent. */
    private static void youtubeMusic(ShelfArt.Easel e) {
        int red = hsl(352, 0.90f, 0.52f), plum = hsl(330, 0.52f, 0.28f), rose = hsl(345, 0.85f, 0.79f),
                coral = hsl(5, 0.88f, 0.63f), orange = hsl(26, 0.95f, 0.58f);
        e.ground(ground(345));
        // Top left: rings out of the corner, an orange scribble at their heart.
        e.fill(circle(40f, 150f, 480f), paint(plum, 40f));
        e.fill(ShelfArt.ring(40f, 150f, 340f, 100f), paint(red, 40f));
        ShelfArt.Shape heart = circle(40f, 150f, 190f);
        e.fill(heart, paint(rose, 40f));
        e.scribble(110f, 190f, 120f, 12f, 3f, 70f, flat(orange), heart);
        // Top right: ripples off the edge, an orange pebble.
        int[] rip = {coral, red, plum};
        for (int i = 2; i >= 0; i--) {
            e.fill(ShelfArt.arc(2100f, 300f, 230f + i * 130f, 96f, 112f, 248f), paint(rip[i], 90f));
        }
        e.fill(circle(2100f, 300f, 140f), paint(rose, 0f));
        e.fill(circle(1460f, -40f, 130f), paint(orange, 0f));
        // Under the dock: a wave across, a rose pebble.
        e.fill(ribbon(new float[]{-160f, 1020f, 520f, 740f, 1260f, 1180f, 2120f, 860f}, 240f, 220f), paint(plum, -6f));
        e.fill(ribbon(new float[]{-160f, 1100f, 560f, 860f, 1240f, 1220f, 2120f, 960f}, 60f, 50f), paint(coral, -6f));
        e.fill(circle(1840f, 1060f, 180f), paint(coral, 0f));
        e.fill(circle(-30f, 760f, 180f), paint(rose, 0f));
    }

    /** Apple TV: graphite, steel and silver squircles and a silver moon, a soft indigo accent. */
    private static void appleTv(ShelfArt.Easel e) {
        int graphite = hsl(222, 0.10f, 0.31f), steel = hsl(222, 0.10f, 0.46f), silver = hsl(220, 0.12f, 0.74f),
                white = hsl(220, 0.18f, 0.93f), indigo = hsl(238, 0.62f, 0.66f);
        e.ground(hsl(225, 0.06f, 0.10f));
        // Top left: squircles stacked and tilted, an indigo scribble on the biggest.
        ShelfArt.Shape big = rect(150f, 170f, 760f, 560f, -14f, 170f);
        e.fill(big, paint(graphite, -14f));
        e.scribble(150f, 170f, 220f, 13f, 3.5f, 76f, flat(indigo), big);
        e.fill(rect(-30f, 560f, 480f, 360f, -14f, 120f), paint(silver, -14f));
        e.fill(circle(600f, -40f, 150f), paint(steel, 0f));
        // Top right: a silver moon with a white face, a steel pebble.
        ShelfArt.Shape moon = circle(1850f, 150f, 370f);
        e.fill(moon, paint(silver, 30f));
        e.fill(circle(1720f, 40f, 250f).and(moon), paint(white, 30f));
        e.fill(circle(1960f, 610f, 170f), paint(steel, 0f));
        e.fill(circle(1520f, 580f, 70f), paint(indigo, 0f));
        // Under the dock.
        e.fill(pill(-100f, 1000f, 760f, 930f, 280f), paint(steel, -5f));
        e.fill(rect(1360f, 1000f, 980f, 320f, -6f, 150f), paint(graphite, -6f));
        e.fill(circle(900f, 1080f, 120f), paint(indigo, 0f));
    }

    /** Plex: its chevrons, big and stacked, in gold, amber and light gold with charcoal, a rust accent. */
    private static void plex(ShelfArt.Easel e) {
        int gold = hsl(40, 0.90f, 0.49f), amber = hsl(30, 0.80f, 0.44f), light = hsl(45, 0.95f, 0.68f),
                charcoal = hsl(210, 0.08f, 0.26f), rust = hsl(14, 0.68f, 0.46f);
        e.ground(ground(40));
        // Top left: two chevrons out of the corner, a charcoal disc below.
        e.fill(circle(-40f, 640f, 250f), paint(charcoal, 20f));
        e.fill(chevron(110f, 360f, 380f, 210f), paint(amber, 45f));
        ShelfArt.Shape front = chevron(270f, 160f, 400f, 240f);
        e.fill(front, paint(gold, -45f));
        e.scribble(40f, 40f, 150f, 13f, 3f, 45f, flat(charcoal), front);
        // Top right: chevrons running off the edge, a rust pebble.
        e.fill(chevron(2080f, 260f, 430f, 230f), paint(charcoal, 45f));
        e.fill(chevron(1960f, 330f, 330f, 150f), paint(light, -45f));
        e.fill(circle(1440f, -40f, 140f), paint(rust, 0f));
        // Under the dock: a chevron band.
        e.fill(pill(-120f, 1010f, 640f, 960f, 280f), paint(gold, -4f));
        e.fill(chevron(1240f, 990f, 300f, 170f), paint(amber, 45f));
        e.fill(chevron(1640f, 990f, 300f, 170f), paint(light, -45f));
        e.fill(circle(2000f, 820f, 170f), paint(rust, 0f));
    }

    /** Stremio: rounded diamonds in its violet, deep violet, lilac and magenta, a mint accent. */
    private static void stremio(ShelfArt.Easel e) {
        int violet = hsl(255, 0.86f, 0.65f), deep = hsl(258, 0.52f, 0.32f), lilac = hsl(255, 0.90f, 0.85f),
                magenta = hsl(292, 0.62f, 0.56f), mint = hsl(162, 0.62f, 0.62f);
        e.ground(ground(258));
        // Top left: a big diamond out of the corner, a deep one behind, a mint scribble on it.
        e.fill(rect(-60f, 560f, 520f, 520f, 45f, 90f), paint(deep, 45f));
        ShelfArt.Shape gem = rect(140f, 170f, 660f, 660f, 45f, 120f);
        e.fill(gem, paint(violet, -45f));
        e.scribble(130f, 200f, 210f, 13f, 3.5f, 45f, flat(mint), gem);
        e.fill(rect(600f, -50f, 240f, 240f, 45f, 50f), paint(lilac, 45f));
        // Top right: a deep diamond off the edge, a magenta one, a lilac pebble.
        e.fill(rect(1900f, 110f, 660f, 660f, 45f, 110f), paint(deep, -45f));
        e.fill(rect(1700f, 470f, 320f, 320f, 45f, 70f), paint(magenta, 45f));
        e.fill(circle(1960f, 640f, 140f), paint(lilac, 0f));
        // Under the dock.
        e.fill(rect(560f, 1100f, 560f, 560f, 45f, 100f), paint(violet, 45f));
        e.fill(rect(1300f, 1060f, 420f, 420f, 45f, 80f), paint(magenta, -45f));
        e.fill(rect(-20f, 980f, 300f, 300f, 45f, 60f), paint(lilac, 45f));
        e.fill(circle(1720f, 920f, 90f), paint(mint, 0f));
    }

    /** Play Games: capsules and four round buttons in greens and teal, a yellow accent. */
    private static void playGames(ShelfArt.Easel e) {
        int green = hsl(158, 0.78f, 0.36f), teal = hsl(178, 0.68f, 0.40f), lime = hsl(132, 0.55f, 0.52f),
                deep = hsl(165, 0.70f, 0.20f), yellow = hsl(44, 0.96f, 0.56f);
        e.ground(ground(160));
        // Top left: a big green capsule tilted, a deep one behind, a yellow scribble on it.
        e.fill(pill(-160f, 520f, 380f, 300f, 300f), paint(deep, -22f));
        ShelfArt.Shape cap = pill(-140f, 120f, 460f, 60f, 380f);
        e.fill(cap, paint(green, -6f));
        e.scribble(160f, 110f, 210f, 13f, 3.5f, 80f, flat(yellow), cap);
        e.fill(circle(620f, -50f, 120f), paint(lime, 0f));
        // Top right: four round buttons in a diamond, off the edge.
        e.fill(circle(1830f, 300f, 360f), paint(deep, 30f));
        e.fill(circle(1830f, 120f, 130f), paint(teal, 30f));
        e.fill(circle(2010f, 300f, 130f), paint(lime, 30f));
        e.fill(circle(1830f, 480f, 130f), paint(green, 30f));
        e.fill(circle(1650f, 300f, 130f), paint(yellow, 30f));
        // Under the dock.
        e.fill(pill(-120f, 980f, 700f, 1040f, 280f), paint(teal, 4f));
        e.fill(pill(1060f, 1060f, 1980f, 940f, 300f), paint(green, -8f));
        e.fill(circle(820f, 860f, 90f), paint(yellow, 0f));
    }

    /** Settings: rounded bars of different lengths and a dial, in blue-greys, a teal accent. */
    private static void settings(ShelfArt.Easel e) {
        int slate = hsl(214, 0.20f, 0.44f), steel = hsl(210, 0.26f, 0.64f), light = hsl(212, 0.36f, 0.84f),
                deep = hsl(218, 0.26f, 0.26f), teal = hsl(176, 0.56f, 0.48f);
        e.ground(ground(215));
        // Top left: bars out of the edge, stepping in, a deep disc below.
        e.fill(circle(-40f, 600f, 260f), paint(deep, 20f));
        ShelfArt.Shape top = pill(-200f, 50f, 600f, 50f, 250f);
        e.fill(top, paint(slate, 0f));
        e.scribble(260f, 70f, 130f, 13f, 3f, 80f, flat(light), top);
        e.fill(pill(-200f, 230f, 400f, 230f, 140f), paint(light, 0f));
        e.fill(pill(-200f, 380f, 260f, 380f, 120f), paint(teal, 0f));
        // Top right: a dial off the edge, a teal knob on its ring.
        e.fill(circle(1880f, 200f, 420f), paint(deep, 30f));
        e.fill(ShelfArt.ring(1880f, 200f, 300f, 120f), paint(steel, 30f));
        ShelfArt.Shape face = circle(1880f, 200f, 190f);
        e.fill(face, paint(light, 30f));
        e.fill(circle(1880f + 300f * (float) Math.cos(Math.toRadians(205)),
                200f + 300f * (float) Math.sin(Math.toRadians(205)), 78f), paint(teal, 0f));
        // Under the dock.
        e.fill(pill(-120f, 1000f, 820f, 1000f, 260f), paint(steel, 0f));
        e.fill(pill(1040f, 960f, 2100f, 960f, 300f), paint(slate, 0f));
        e.fill(circle(1260f, 960f, 110f), paint(light, 0f));
        e.fill(circle(1960f, 760f, 150f), paint(teal, 0f));
    }

    /** TCL's browser: a blue sphere and a coral swoosh, cards stacked like tabs; TCL red, sky, navy, coral. */
    private static void tclBrowser(ShelfArt.Easel e) {
        int red = hsl(349, 0.88f, 0.47f), sky = hsl(208, 0.78f, 0.58f), navy = hsl(218, 0.55f, 0.28f),
                coral = hsl(6, 0.85f, 0.66f), ice = hsl(205, 0.70f, 0.86f);
        e.ground(ground(215));
        // Top left: a sky sphere with orbits crossing it, a navy disc behind.
        e.fill(circle(-60f, 560f, 260f), paint(navy, 20f));
        ShelfArt.Shape sphere = circle(100f, 170f, 380f);
        e.fill(sphere, paint(sky, 30f));
        e.fill(ribbon(new float[]{-200f, 420f, 120f, 520f, 380f, 300f, 520f, -160f}, 120f, 70f), paint(coral, 60f));
        e.fill(circle(500f, -40f, 150f), paint(ice, 0f));
        e.scribble(80f, 130f, 160f, 13f, 3f, 70f, flat(navy), sphere);
        // Top right: cards stacked like tabs, off the edge.
        e.fill(rect(1830f, 60f, 600f, 300f, -8f, 50f), paint(navy, -8f));
        e.fill(rect(1890f, 260f, 560f, 280f, -8f, 50f), paint(red, -8f));
        e.fill(rect(1950f, 470f, 520f, 240f, -8f, 50f), paint(ice, -8f));
        e.fill(circle(1430f, -30f, 120f), paint(coral, 0f));
        // Under the dock.
        e.fill(rect(500f, 1000f, 1100f, 300f, -4f, 70f), paint(navy, -4f));
        e.fill(circle(1300f, 980f, 190f), paint(coral, 0f));
        e.fill(pill(1560f, 860f, 2100f, 820f, 200f), paint(sky, 0f));
    }

    /** TCL's media centre: photo frames stacked, a sun and its ring; orange, teal and peach, a red accent. */
    private static void tclMedia(ShelfArt.Easel e) {
        int orange = hsl(24, 0.92f, 0.57f), teal = hsl(180, 0.74f, 0.38f), deep = hsl(186, 0.68f, 0.23f),
                peach = hsl(28, 0.92f, 0.79f), red = hsl(349, 0.84f, 0.52f);
        e.ground(ground(30));
        // Top left: frames stacked and tilted.
        e.fill(rect(-10f, 520f, 520f, 380f, 12f, 40f), paint(peach, 12f));
        e.fill(rect(260f, 40f, 600f, 420f, 8f, 40f), paint(orange, 8f));
        ShelfArt.Shape front = rect(70f, 250f, 640f, 460f, -6f, 40f);
        e.fill(front, paint(teal, -6f));
        e.scribble(90f, 250f, 200f, 13f, 3.5f, 84f, flat(peach), front);
        // Top right: a sun with a ring round it, off the edge.
        e.fill(ShelfArt.ring(1870f, 220f, 400f, 90f), paint(deep, 30f));
        e.fill(circle(1870f, 220f, 290f), paint(orange, 30f));
        e.fill(circle(1960f, 330f, 170f), paint(peach, 30f));
        e.fill(circle(1460f, -30f, 120f), paint(red, 0f));
        // Under the dock.
        e.fill(rect(-40f, 1000f, 700f, 320f, 6f, 40f), paint(deep, 6f));
        e.fill(rect(1100f, 1020f, 900f, 300f, -4f, 40f), paint(teal, -4f));
        e.fill(circle(1840f, 940f, 200f), paint(red, 0f));
    }

    /** TCL's own apps: a red disc, bands like screens' edges; TCL red, deep red, slate and light grey, a blue
     * accent. */
    private static void tcl(ShelfArt.Easel e) {
        int red = hsl(349, 0.88f, 0.47f), deep = hsl(350, 0.72f, 0.30f), slate = hsl(220, 0.12f, 0.33f),
                light = hsl(220, 0.16f, 0.80f), blue = hsl(212, 0.76f, 0.57f);
        e.ground(ground(220));
        // Top left: a red disc, wide bands crossing under it, a light scribble.
        ShelfArt.Shape disc = circle(60f, 140f, 400f);
        e.fill(disc, paint(red, 30f));
        e.scribble(90f, 150f, 190f, 13f, 3.5f, 70f, flat(light), disc);
        e.fill(rect(40f, 480f, 820f, 190f, -10f, 60f), paint(slate, -10f));
        e.fill(rect(-60f, 680f, 560f, 160f, -10f, 50f), paint(light, -10f));
        e.fill(circle(580f, -50f, 130f), paint(deep, 0f));
        // Top right: a slate band off the edge, a deep red disc, a blue pebble.
        e.fill(circle(1960f, 460f, 300f), paint(deep, 30f));
        e.fill(rect(1820f, 80f, 760f, 300f, -10f, 70f), paint(slate, -10f));
        e.fill(rect(1900f, 300f, 560f, 200f, -10f, 60f), paint(light, -10f));
        e.fill(circle(1470f, -20f, 110f), paint(blue, 0f));
        // Under the dock.
        e.fill(rect(1300f, 980f, 1200f, 260f, -5f, 60f), paint(slate, -5f));
        e.fill(rect(300f, 1040f, 900f, 240f, 4f, 60f), paint(deep, 4f));
        e.fill(circle(800f, 880f, 90f), paint(blue, 0f));
    }

    /** Prime Video: its smile as a great arc under the dock, blue discs; blue, deep blue, navy and ice, an amber
     * accent. */
    private static void primeVideo(ShelfArt.Easel e) {
        int blue = hsl(197, 0.95f, 0.47f), deep = hsl(212, 0.78f, 0.33f), ice = hsl(195, 0.85f, 0.82f),
                navy = hsl(214, 0.55f, 0.21f), amber = hsl(36, 0.98f, 0.56f);
        e.ground(ground(205));
        // Top left: a navy disc, a deep blue squircle over it, an ice pebble.
        e.fill(circle(-40f, 520f, 300f), paint(navy, 20f));
        ShelfArt.Shape sq = rect(120f, 150f, 640f, 560f, 10f, 160f);
        e.fill(sq, paint(deep, 10f));
        e.scribble(130f, 160f, 200f, 13f, 3.5f, 80f, flat(ice), sq);
        e.fill(circle(600f, -50f, 140f), paint(ice, 0f));
        // Top right: a blue disc, a smile under it, an amber pebble.
        e.fill(circle(1960f, 520f, 260f), paint(navy, 30f));
        e.fill(circle(1840f, 120f, 360f), paint(blue, -30f));
        e.fill(ellipse(1560f, 470f, 140f, 80f, -30f), paint(ice, -30f));
        e.fill(circle(1430f, -30f, 110f), paint(amber, 0f));
        // Under the dock: the smile, from side to side.
        e.fill(ShelfArt.arc(960f, 220f, 840f, 160f, 34f, 146f), paint(blue, 0f));
        e.fill(circle(1660f, 700f, 70f), paint(amber, 0f));
        e.fill(circle(240f, 1060f, 170f), paint(ice, 0f));
    }

    /** Disney+: its arc of light over midnight, sparkles; royal blue, navy, teal and violet, a gold accent. */
    private static void disneyPlus(ShelfArt.Easel e) {
        int royal = hsl(226, 0.78f, 0.52f), navy = hsl(232, 0.68f, 0.24f), teal = hsl(190, 0.82f, 0.50f),
                violet = hsl(255, 0.58f, 0.52f), white = hsl(220, 0.60f, 0.96f), gold = hsl(44, 0.90f, 0.66f);
        e.ground(hsl(232, 0.32f, 0.10f));
        // The arc, over the top, in a glow.
        e.glow(960f, 60f, 700f, hsl(226, 0.6f, 0.30f), 0.35f);
        // Top left: navy and violet, a royal disc, sparkles.
        e.fill(circle(40f, 260f, 440f), paint(navy, 30f));
        e.fill(ellipse(240f, -30f, 330f, 180f, -12f), paint(violet, -12f));
        ShelfArt.Shape disc = circle(-20f, 470f, 260f);
        e.fill(disc, paint(royal, 30f));
        // Top right: a royal disc off the edge, teal in front, a gold scribble.
        ShelfArt.Shape big = circle(1880f, 180f, 400f);
        e.fill(big, paint(royal, -30f));
        e.scribble(1820f, 330f, 160f, 13f, 3.5f, 80f, flat(gold), big);
        e.fill(circle(1640f, 520f, 170f), paint(teal, 0f));
        e.fill(ShelfArt.arc(960f, 1320f, 1270f, 24f, 220f, 320f),
                ink(teal).gradient(white, 300f, 0f, 960f, 0f));
        float[][] stars = {{560f, 70f, 28f}, {250f, 600f, 22f}, {1420f, 620f, 26f}, {1320f, 30f, 18f},
                {120f, 120f, 36f}, {1960f, 520f, 30f}};
        for (float[] st : stars) e.fill(sparkle(st[0], st[1], st[2]), flat(white));
        // Under the dock.
        e.fill(ellipse(560f, 1060f, 560f, 220f, -6f), paint(royal, -6f));
        e.fill(ellipse(1460f, 1080f, 500f, 230f, 8f), paint(violet, 8f));
        e.fill(circle(1040f, 900f, 90f), paint(gold, 0f));
    }

    /** HBO Max: a great ring and its dot, pills of light; blue, violet, indigo and lilac, a cyan accent. */
    private static void hboMax(ShelfArt.Easel e) {
        int blue = hsl(232, 0.88f, 0.55f), violet = hsl(252, 0.84f, 0.70f), indigo = hsl(240, 0.62f, 0.26f),
                lilac = hsl(252, 0.95f, 0.86f), cyan = hsl(192, 0.90f, 0.62f);
        e.ground(hsl(240, 0.30f, 0.09f));
        // Top left: a great ring out of the corner, its dot, a cyan scribble on the ring.
        ShelfArt.Shape o = circle(80f, 200f, 440f).minus(circle(80f, 200f, 230f));
        e.fill(circle(80f, 200f, 440f), paint(indigo, 40f));
        e.fill(o, paint(blue, 40f));
        e.fill(circle(80f, 200f, 120f), paint(lilac, 0f));
        e.scribble(300f, 380f, 140f, 13f, 3f, 60f, flat(cyan), o);
        // Top right: violet pills falling off the edge, a lilac one.
        e.fill(pill(1660f, -120f, 1880f, 420f, 230f), paint(violet, 68f));
        e.fill(pill(1900f, -60f, 2100f, 500f, 200f), paint(indigo, 68f));
        e.fill(pill(1480f, 40f, 1540f, 180f, 80f), paint(lilac, 68f));
        e.fill(circle(1960f, 620f, 120f), paint(cyan, 0f));
        // Under the dock.
        e.fill(pill(-100f, 1000f, 840f, 940f, 280f), paint(violet, -4f));
        e.fill(circle(1260f, 1000f, 230f).minus(circle(1260f, 1000f, 110f)), paint(blue, 0f));
        e.fill(pill(1560f, 920f, 2100f, 880f, 240f), paint(indigo, -4f));
    }

    /** Spotify: sound spreading in arcs from a corner, a pink sun; greens and mint, a pink accent. */
    private static void spotify(ShelfArt.Easel e) {
        int green = hsl(141, 0.74f, 0.47f), deep = hsl(146, 0.80f, 0.25f), mint = hsl(140, 0.70f, 0.80f),
                lime = hsl(88, 0.72f, 0.55f), pink = hsl(334, 0.86f, 0.70f);
        e.ground(hsl(140, 0.04f, 0.10f));
        // Top left: arcs spreading out of the corner.
        e.fill(circle(-80f, -60f, 300f), paint(green, 40f));
        int[] waves = {deep, mint, green};
        for (int i = 0; i < 3; i++) {
            e.fill(ShelfArt.arc(-80f, -60f, 360f + i * 120f, 96f, -10f, 100f), paint(waves[i], 135f));
        }
        ShelfArt.Shape sun = circle(-80f, -60f, 300f);
        e.scribble(60f, 60f, 140f, 13f, 3f, 45f, flat(deep), sun);
        // Top right: a pink sun, a lime pebble, a deep green disc behind.
        e.fill(circle(2000f, 500f, 260f), paint(deep, 30f));
        e.fill(circle(1820f, 140f, 340f), paint(pink, -30f));
        e.fill(circle(1540f, 480f, 90f), paint(lime, 0f));
        // Under the dock: a wave across.
        e.fill(ribbon(new float[]{-160f, 960f, 600f, 1180f, 1300f, 820f, 2120f, 1060f}, 150f, 150f), paint(green, 0f));
        e.fill(circle(1960f, 820f, 140f), paint(mint, 0f));
        e.fill(circle(160f, 760f, 120f), paint(lime, 0f));
    }

    /** Twitch: glitching blocks, stepped and offset; its purple, deep purple, lilac and indigo, a coral accent. */
    private static void twitch(ShelfArt.Easel e) {
        int purple = hsl(265, 0.98f, 0.64f), deep = hsl(265, 0.70f, 0.40f), lilac = hsl(265, 0.98f, 0.82f),
                indigo = hsl(250, 0.45f, 0.24f), coral = hsl(352, 0.92f, 0.68f);
        e.ground(hsl(260, 0.08f, 0.09f));
        // Top left: a big block with a stepped corner, slices shifted off it.
        e.fill(rect(-20f, 560f, 560f, 280f, 0f, 30f), paint(indigo, 0f));
        ShelfArt.Shape block = poly(-200f, -200f, 600f, -200f, 600f, 160f, 500f, 160f, 500f, 300f, 360f, 300f, 360f,
                420f, -200f, 420f).grow(20f);
        e.fill(block, paint(purple, 0f));
        e.scribble(150f, 130f, 190f, 13f, 3.5f, 90f, flat(coral), block);
        e.fill(rect(660f, 60f, 120f, 120f, 0f, 18f), paint(lilac, 0f));
        // Top right: slices, shifted, off the edge.
        for (int i = 0; i < 5; i++) {
            float y = -20f + i * 120f, x = 1820f + (i % 2 == 0 ? 40f : -40f) + i * 20f;
            e.fill(rect(x, y, 520f, 100f, 0f, 14f), paint(i % 2 == 0 ? deep : i == 1 ? lilac : purple, 0f));
        }
        e.fill(rect(1540f, 560f, 70f, 70f, 0f, 10f), paint(coral, 0f));
        // Under the dock.
        e.fill(rect(600f, 1010f, 1100f, 300f, 0f, 30f), paint(deep, 0f));
        e.fill(rect(1500f, 960f, 600f, 300f, 0f, 30f), paint(lilac, 0f));
        e.fill(rect(1080f, 840f, 90f, 90f, 0f, 12f), paint(coral, 0f));
    }

    /** Crunchyroll: a crescent, speed lines and halftone dots; its orange, deep orange, peach and ember, a cream
     * accent. */
    private static void crunchyroll(ShelfArt.Easel e) {
        int orange = hsl(24, 0.90f, 0.54f), deep = hsl(16, 0.80f, 0.38f), peach = hsl(26, 0.95f, 0.76f),
                ember = hsl(6, 0.78f, 0.50f), cream = hsl(34, 0.70f, 0.93f);
        e.ground(ground(20));
        // Top left: a crescent out of the corner, deep orange behind, a cream scribble.
        e.fill(circle(60f, 200f, 440f), paint(deep, 30f));
        ShelfArt.Shape moon = circle(60f, 200f, 440f).minus(circle(270f, 90f, 300f));
        e.fill(moon, paint(orange, 30f));
        e.scribble(-40f, 330f, 170f, 13f, 3.5f, 60f, flat(cream), moon);
        // Top right: halftone dots fading out over a peach disc, speed lines.
        e.fill(circle(1880f, 160f, 340f), paint(peach, -30f));
        for (int gy = 0; gy < 7; gy++) {
            for (int gx = 0; gx < 9; gx++) {
                float x = 1500f + gx * 64f + (gy % 2) * 32f, y = -20f + gy * 64f;
                float r = 4f + 22f * Math.min(1f, (x - 1500f) / 520f) * (1f - gy / 8f);
                e.fill(circle(x, y, r), flat(ember));
            }
        }
        for (int i = 0; i < 6; i++) {
            float y = 470f + i * 34f, x0 = 1560f + (i % 3) * 80f;
            e.fill(pill(x0, y, 2100f, y, 12f + (i % 2) * 8f), flat(i % 2 == 0 ? orange : peach));
        }
        // Under the dock.
        e.fill(ellipse(400f, 1050f, 560f, 240f, -6f), paint(orange, -6f));
        e.fill(circle(1500f, 1040f, 260f), paint(deep, 0f));
        e.fill(circle(1180f, 880f, 90f), paint(cream, 0f));
    }

    /** Paramount+: an arch of stars over the top, blue discs; blue, sky, navy and ice, white stars. */
    private static void paramountPlus(ShelfArt.Easel e) {
        int blue = hsl(220, 0.98f, 0.52f), sky = hsl(212, 0.92f, 0.66f), navy = hsl(226, 0.80f, 0.22f),
                ice = hsl(214, 0.95f, 0.86f), white = hsl(220, 0.60f, 0.97f);
        e.ground(ground(222));
        // Top left: navy and blue discs, a sky one.
        e.fill(circle(40f, 260f, 440f), paint(navy, 30f));
        ShelfArt.Shape disc = circle(-30f, 60f, 330f);
        e.fill(disc, paint(blue, 30f));
        e.scribble(60f, 100f, 150f, 13f, 3f, 70f, flat(ice), disc);
        e.fill(circle(330f, 560f, 120f), paint(sky, 0f));
        // Top right: a sky disc, a blue one, ice.
        e.fill(circle(1900f, 200f, 380f), paint(sky, -30f));
        e.fill(circle(1960f, 590f, 220f), paint(blue, -30f));
        e.fill(circle(1600f, -30f, 130f), paint(ice, 0f));
        // The arch of stars, over the logo.
        for (int i = 0; i <= 12; i++) {
            double a = Math.toRadians(192 + i * 13);
            float x = 960f + 720f * (float) Math.cos(a), y = 470f + 470f * (float) Math.sin(a);
            e.fill(ShelfArt.star(x, y, 30f, 13f, 5, 0f).grow(3f), flat(white));
        }
        // Under the dock.
        e.fill(ellipse(600f, 1080f, 620f, 240f, -4f), paint(blue, -4f));
        e.fill(ellipse(1500f, 1060f, 520f, 220f, 6f), paint(navy, 6f));
    }

    /** Peacock: its six feather colours fanned from a corner and gathered as dots, on black. */
    private static void peacock(ShelfArt.Easel e) {
        int[] f = {hsl(44, 0.98f, 0.58f), hsl(16, 0.90f, 0.55f), hsl(350, 0.75f, 0.54f), hsl(262, 0.70f, 0.62f),
                hsl(199, 0.94f, 0.46f), hsl(158, 0.90f, 0.36f)};
        e.ground(hsl(0, 0f, 0.07f));
        // Top right: the fan, its feathers overlapping out of the corner.
        for (int i = 5; i >= 0; i--) {
            float deg = 100f + i * 20f;
            double a = Math.toRadians(deg);
            e.fill(ellipse(2080f + 420f * (float) Math.cos(a), -80f + 420f * (float) Math.sin(a), 320f, 115f, deg),
                    paint(f[i], deg));
        }
        // Top left: the colours gathered as dots, a scribble on the largest.
        float[][] dots = {{60f, 120f, 250f}, {330f, 380f, 140f}, {40f, 520f, 170f}, {420f, 30f, 110f},
                {-60f, 760f, 150f}, {250f, 640f, 70f}};
        for (int i = 0; i < dots.length; i++) {
            ShelfArt.Shape d = circle(dots[i][0], dots[i][1], dots[i][2]);
            e.fill(d, paint(f[i], 30f * i));
            if (i == 0) e.scribble(60f, 130f, 140f, 13f, 3f, 80f, flat(f[4]), d);
        }
        // Under the dock.
        e.fill(pill(500f, 1040f, 1300f, 1040f, 240f), paint(f[4], 0f));
        e.fill(circle(1500f, 960f, 120f), paint(f[0], 0f));
        e.fill(pill(1700f, 1080f, 2100f, 1000f, 200f), paint(f[3], 0f));
    }

    /** Hulu: rounded arches like its letters, and slabs; its green, deep green and mint, charcoal, a cream accent. */
    private static void hulu(ShelfArt.Easel e) {
        int green = hsl(151, 0.80f, 0.51f), deep = hsl(152, 0.76f, 0.24f), mint = hsl(150, 0.80f, 0.82f),
                charcoal = hsl(150, 0.05f, 0.24f), cream = hsl(40, 0.60f, 0.90f);
        e.ground(hsl(150, 0.04f, 0.08f));
        // Top left: an arch rising out of the dock's way, a charcoal disc behind, a mint pill.
        e.fill(circle(-80f, 120f, 330f), paint(charcoal, 30f));
        ShelfArt.Shape arch = pill(170f, 260f, 170f, 1100f, 460f).minus(pill(170f, 330f, 170f, 1100f, 190f));
        e.fill(arch, paint(green, 90f));
        e.scribble(150f, 110f, 150f, 13f, 3f, 90f, flat(deep), arch);
        e.fill(pill(520f, -40f, 760f, -40f, 160f), paint(mint, 0f));
        // Top right: an arch hanging from the top, a deep disc behind, a cream pebble.
        e.fill(circle(1980f, 560f, 230f), paint(deep, 30f));
        e.fill(pill(1800f, -400f, 1800f, 240f, 520f).minus(pill(1800f, -400f, 1800f, 200f, 220f)), paint(green, 90f));
        e.fill(circle(1480f, 540f, 90f), paint(cream, 0f));
        // Under the dock.
        e.fill(pill(560f, 1060f, 1360f, 1000f, 240f), paint(deep, -4f));
        e.fill(pill(1500f, 960f, 2100f, 960f, 220f), paint(mint, 0f));
    }

    /** Tubi: its bright yellow on deep purple, twin ovals like a pair of eyes, soft blobs; a pink accent. */
    private static void tubi(ShelfArt.Easel e) {
        int yellow = hsl(58, 0.98f, 0.56f), gold = hsl(46, 0.98f, 0.55f), purple = hsl(270, 0.88f, 0.56f),
                lilac = hsl(270, 0.90f, 0.80f), plum = hsl(272, 0.62f, 0.27f), pink = hsl(330, 0.90f, 0.68f);
        e.ground(hsl(270, 0.42f, 0.10f));
        // Top left: twin ovals, a plum disc behind, a purple scribble on the bigger.
        e.fill(circle(-40f, 560f, 280f), paint(plum, 30f));
        ShelfArt.Shape a = ellipse(80f, 170f, 200f, 300f, 14f);
        e.fill(a, paint(yellow, 104f));
        e.fill(ellipse(380f, 200f, 150f, 240f, 14f), paint(gold, 104f));
        e.scribble(60f, 200f, 120f, 13f, 3f, 104f, flat(purple), a);
        // Top right: a purple blob off the edge, a lilac oval, a pink pebble.
        e.fill(ellipse(1860f, 120f, 420f, 300f, -20f), paint(purple, -20f));
        e.fill(ellipse(1980f, 520f, 220f, 160f, 30f), paint(lilac, 30f));
        e.fill(circle(1500f, -20f, 120f), paint(pink, 0f));
        // Under the dock.
        e.fill(ellipse(560f, 1070f, 560f, 220f, -5f), paint(plum, -5f));
        e.fill(ellipse(1460f, 1060f, 460f, 210f, 6f), paint(yellow, 6f));
        e.fill(circle(1020f, 900f, 80f), paint(pink, 0f));
    }

    /** Pluto TV: a pink world with a yellow ring, moons, purple space; a cyan accent. */
    private static void plutoTv(ShelfArt.Easel e) {
        int pink = hsl(312, 0.96f, 0.62f), purple = hsl(262, 0.96f, 0.58f), yellow = hsl(54, 0.98f, 0.56f),
                cyan = hsl(184, 0.80f, 0.56f), deep = hsl(290, 0.55f, 0.27f);
        e.ground(hsl(255, 0.25f, 0.09f));
        // Top left: a pink world, its yellow ring passing behind it and in front.
        ShelfArt.Shape band = ellipse(160f, 220f, 480f, 120f, -20f).outline(52f);
        ShelfArt.Shape world = circle(160f, 220f, 310f);
        e.fill(band.and(ShelfArt.half(160f, 220f, -20f)), paint(yellow, -20f));
        e.fill(world, paint(pink, 40f));
        e.fill(band.minus(ShelfArt.half(160f, 220f, -20f)), paint(yellow, -20f));
        e.scribble(110f, 160f, 140f, 13f, 3f, 70f, flat(deep), world.minus(band));
        // Top right: a purple world, a deep one, moons.
        e.fill(circle(1960f, 520f, 230f), paint(deep, 30f));
        e.fill(circle(1830f, 140f, 340f), paint(purple, -30f));
        e.fill(circle(1520f, 400f, 70f), paint(cyan, 0f));
        e.fill(circle(1450f, -10f, 110f), paint(yellow, 0f));
        // Under the dock.
        e.fill(ellipse(500f, 1070f, 560f, 230f, -5f), paint(purple, -5f));
        e.fill(circle(1380f, 1020f, 230f), paint(pink, 0f));
        e.fill(circle(1840f, 920f, 110f), paint(cyan, 0f));
    }

    /** DAZN: black and white, hard slabs on the slant, a neon yellow slash; nothing rounded. */
    private static void dazn(ShelfArt.Easel e) {
        int white = hsl(0, 0f, 0.93f), neon = hsl(62, 0.98f, 0.55f), grey = hsl(0, 0f, 0.22f), mid = hsl(0, 0f, 0.46f),
                ink = hsl(0, 0f, 0.10f);
        e.ground(hsl(0, 0f, 0.065f));
        // Top left: slabs leaning right, a neon slash between them.
        e.fill(poly(-200f, -200f, 520f, -200f, 240f, 760f, -200f, 760f), paint(grey, 74f).wobble(2f));
        ShelfArt.Shape slab = poly(-200f, -200f, 330f, -200f, 60f, 760f, -200f, 760f);
        e.fill(slab, paint(white, 74f).wobble(2f));
        e.scribble(90f, 130f, 110f, 13f, 3f, 74f, flat(ink), slab);
        e.fill(poly(560f, -200f, 660f, -200f, 380f, 760f, 280f, 760f), flat(neon).wobble(2f));
        // Top right: a grey slab, a white and a neon slash.
        e.fill(poly(1700f, -200f, 2120f, -200f, 2120f, 760f, 1440f, 760f), paint(grey, 74f).wobble(2f));
        e.fill(poly(1820f, -200f, 1930f, -200f, 1660f, 760f, 1550f, 760f), paint(white, 74f).wobble(2f));
        e.fill(poly(1990f, -200f, 2050f, -200f, 1780f, 760f, 1720f, 760f), flat(neon).wobble(2f));
        // Under the dock.
        e.fill(poly(700f, 1200f, 1150f, 1200f, 1330f, 640f, 880f, 640f), paint(mid, 74f).wobble(2f));
        e.fill(poly(1350f, 1200f, 1440f, 1200f, 1620f, 640f, 1530f, 640f), flat(neon).wobble(2f));
    }

    /** JioHotstar: a glow from blue through violet to pink in big shapes, and its star's sparkle; a cyan accent. */
    private static void jioHotstar(ShelfArt.Easel e) {
        int pink = hsl(322, 0.98f, 0.46f), violet = hsl(272, 0.86f, 0.57f), blue = hsl(228, 0.92f, 0.56f),
                cyan = hsl(195, 0.94f, 0.62f), white = hsl(260, 0.60f, 0.97f);
        e.ground(hsl(260, 0.32f, 0.09f));
        // Top left: a disc from blue to violet, a pink petal, a cyan scribble.
        e.fill(ellipse(380f, -40f, 260f, 130f, -14f), ink(pink).gradient(violet, 200f, 0f, 600f, 0f));
        ShelfArt.Shape disc = circle(60f, 220f, 440f);
        e.fill(disc, ink(blue).gradient(violet, -200f, 0f, 400f, 500f).strokes(hsl(228, 0.92f, 0.64f), 40f));
        e.scribble(60f, 230f, 180f, 13f, 3.5f, 60f, flat(cyan), disc);
        // Top right: a disc from violet to pink, the star bright on it.
        e.fill(circle(1860f, 200f, 400f), ink(violet).gradient(pink, 1500f, 0f, 2000f, 500f)
                .strokes(hsl(300, 0.80f, 0.60f), -40f));
        e.fill(sparkle(1720f, 260f, 210f), flat(white));
        e.fill(sparkle(1460f, 40f, 46f), flat(cyan));
        // Under the dock.
        e.fill(ellipse(560f, 1070f, 600f, 230f, -4f), ink(blue).gradient(violet, 100f, 0f, 1100f, 0f));
        e.fill(ellipse(1500f, 1050f, 480f, 210f, 6f), ink(violet).gradient(pink, 1100f, 0f, 1900f, 0f));
        e.fill(sparkle(300f, 640f, 40f), flat(white));
    }

    /** DStv: a swirl of arcs, ripples round a disc; its blue, navy, cyan and ice, an orange accent. */
    private static void dstv(ShelfArt.Easel e) {
        int blue = hsl(210, 0.98f, 0.44f), navy = hsl(220, 0.82f, 0.24f), cyan = hsl(196, 0.98f, 0.50f),
                ice = hsl(200, 0.85f, 0.83f), orange = hsl(28, 0.95f, 0.58f);
        e.ground(ground(215));
        // Top left: a swirl of arcs curling out of the edge, round an ice heart.
        e.fill(ShelfArt.arc(-60f, 260f, 450f, 120f, -80f, 120f), paint(navy, 90f));
        e.fill(ShelfArt.arc(-60f, 260f, 320f, 110f, -60f, 150f), paint(blue, 90f));
        e.fill(ShelfArt.arc(-60f, 260f, 200f, 100f, -40f, 190f), paint(cyan, 90f));
        ShelfArt.Shape heart = circle(-60f, 260f, 120f);
        e.fill(heart, paint(ice, 0f));
        e.scribble(330f, 40f, 110f, 13f, 3f, 80f, flat(ice), ShelfArt.arc(-60f, 260f, 450f, 120f, -80f, 120f));
        // Top right: a blue disc, ripples spreading round it, an orange pebble.
        e.fill(circle(1960f, 580f, 200f), paint(navy, 30f));
        e.fill(ShelfArt.arc(1900f, 120f, 470f, 44f, 100f, 200f), paint(cyan, 0f));
        e.fill(ShelfArt.arc(1900f, 120f, 400f, 40f, 95f, 205f), paint(ice, 0f));
        e.fill(circle(1900f, 120f, 330f), paint(blue, -30f));
        e.fill(circle(1460f, 470f, 70f), paint(orange, 0f));
        // Under the dock.
        e.fill(ellipse(620f, 1070f, 600f, 230f, -4f), paint(blue, -4f));
        e.fill(circle(1460f, 1000f, 200f), paint(ice, 0f));
        e.fill(circle(1860f, 900f, 130f), paint(orange, 0f));
    }

    /**
     * Viusasa: Kenyan colours on its red: a tall shield with its stripe, a beaded collar's rows of colour, gold and
     * green.
     */
    private static void viusasa(ShelfArt.Easel e) {
        int red = hsl(356, 0.80f, 0.50f), deep = hsl(354, 0.70f, 0.30f), green = hsl(146, 0.80f, 0.33f),
                gold = hsl(44, 0.96f, 0.56f), white = hsl(36, 0.45f, 0.94f), blue = hsl(205, 0.85f, 0.52f);
        e.ground(ground(10));
        // Top left: a tall shield, a white band down it, a deep stripe in that.
        e.fill(circle(-60f, 620f, 240f), paint(green, 30f));
        ShelfArt.Shape shield = ellipse(170f, 220f, 270f, 520f, 10f);
        e.fill(shield, paint(red, 100f));
        e.fill(ellipse(170f, 220f, 110f, 470f, 10f), paint(white, 100f));
        e.fill(ellipse(170f, 220f, 46f, 440f, 10f), paint(deep, 100f));
        e.scribble(40f, 80f, 120f, 13f, 3f, 100f, flat(gold), shield.minus(ellipse(170f, 220f, 120f, 480f, 10f)));
        // Top right: a beaded collar's rows, round a deep disc.
        e.fill(circle(2000f, 120f, 330f), paint(deep, 30f));
        int[] beads = {red, white, green, gold, blue, white};
        for (int row = 0; row < 4; row++) {
            float r = 380f + row * 54f;
            int n = Math.round((float) (Math.PI * r / 44f));
            for (int k = 0; k <= n; k++) {
                double a = Math.toRadians(90 + 90.0 * k / n);
                e.fill(circle(2000f + r * (float) Math.cos(a), 120f + r * (float) Math.sin(a), 20f),
                        flat(beads[(k / 3 + row) % beads.length]));
            }
        }
        e.fill(circle(1460f, -20f, 120f), paint(gold, 0f));
        // Under the dock.
        e.fill(ribbon(new float[]{-160f, 1080f, 500f, 860f, 1200f, 1140f, 2120f, 880f}, 230f, 200f), paint(red, -5f));
        e.fill(circle(1840f, 1060f, 170f), paint(green, 0f));
        e.fill(circle(1180f, 880f, 70f), paint(gold, 0f));
    }

    /** Kodi: rounded tiles stacked and overlapping, in its sky blue, deep blue, light blue and navy, a coral
     * accent. */
    private static void kodi(ShelfArt.Easel e) {
        int sky = hsl(196, 0.82f, 0.50f), deep = hsl(200, 0.80f, 0.30f), light = hsl(195, 0.85f, 0.78f),
                navy = hsl(205, 0.55f, 0.18f), coral = hsl(12, 0.86f, 0.64f);
        e.ground(ground(200));
        // Top left: tiles stacked from the corner.
        e.fill(rect(-20f, 560f, 400f, 400f, 8f, 80f), paint(deep, 8f));
        e.fill(rect(320f, 20f, 340f, 340f, 8f, 70f), paint(light, 8f));
        ShelfArt.Shape big = rect(80f, 200f, 560f, 560f, 8f, 110f);
        e.fill(big, paint(sky, 8f));
        e.scribble(80f, 210f, 170f, 13f, 3.5f, 98f, flat(navy), big);
        // Top right: two tiles off the edge, a coral pebble.
        e.fill(rect(1900f, 80f, 600f, 600f, -8f, 110f), paint(deep, -8f));
        e.fill(rect(1700f, 470f, 300f, 300f, -8f, 64f), paint(sky, -8f));
        e.fill(circle(1460f, -20f, 120f), paint(coral, 0f));
        // Under the dock.
        e.fill(rect(900f, 1060f, 1300f, 380f, -4f, 110f), paint(light, -4f));
        e.fill(rect(1720f, 1000f, 420f, 420f, 8f, 90f), paint(sky, 8f));
        e.fill(circle(200f, 960f, 130f), paint(coral, 0f));
    }

    /** VLC: its cone's orange and white stripes on wide bands, discs; orange, deep and light orange, a slate
     * accent. */
    private static void vlc(ShelfArt.Easel e) {
        int orange = hsl(32, 0.98f, 0.52f), deep = hsl(24, 0.90f, 0.42f), light = hsl(36, 0.98f, 0.71f),
                cream = hsl(34, 0.60f, 0.93f), slate = hsl(215, 0.40f, 0.46f);
        e.ground(ground(30));
        // Top left: a wide band from the corner, striped orange and cream.
        ShelfArt.Shape band = pill(-200f, 520f, 460f, -200f, 380f);
        e.fill(band, paint(orange, -47f));
        for (int i = -4; i <= 4; i += 2) {
            float cx = 130f + i * 70f, cy = 160f + i * 76f;
            e.fill(pill(cx - 300f, cy - 276f, cx + 300f, cy + 276f, 70f).and(band), flat(cream));
        }
        e.fill(circle(-60f, 720f, 200f), paint(deep, 30f));
        e.fill(circle(560f, -50f, 140f), paint(slate, 0f));
        // Top right: discs, a scribble on the light one.
        e.fill(circle(1960f, 520f, 250f), paint(deep, 30f));
        ShelfArt.Shape sun = circle(1830f, 130f, 340f);
        e.fill(sun, paint(light, -30f));
        e.scribble(1820f, 270f, 140f, 13f, 3f, 70f, flat(deep), sun);
        // Under the dock: a band, striped.
        ShelfArt.Shape low = pill(400f, 1060f, 1700f, 960f, 220f);
        e.fill(low, paint(orange, -4f));
        for (int i = 0; i < 8; i++) {
            float x = 480f + i * 170f;
            e.fill(poly(x, 800f, x + 70f, 800f, x - 50f, 1200f, x - 120f, 1200f).and(low), flat(cream));
        }
        e.fill(circle(1960f, 1000f, 150f), paint(slate, 0f));
    }

    /**
     * Netflix: red ribbons curving through the dark like the N's, with their darker folds, like a stage's curtain;
     * crimson, coral and blush around them, an amber accent.
     */
    private static void netflix(ShelfArt.Easel e) {
        int red = hsl(357, 0.86f, 0.48f), crimson = hsl(351, 0.70f, 0.30f), fold = hsl(352, 0.80f, 0.36f),
                coral = hsl(7, 0.84f, 0.63f), blush = hsl(352, 0.72f, 0.82f), amber = hsl(36, 0.92f, 0.60f);
        e.ground(ground(355));
        // Top left: a crimson disc, a coral scribble on it; a red ribbon sweeping down past it, folding.
        e.fill(circle(130f, 120f, 330f), paint(crimson, 50f));
        float[] left = {420f, -200f, 520f, 160f, 280f, 420f, -160f, 560f};
        e.fill(ribbon(left, 230f, 290f), paint(red, 120f));
        e.fill(ribbon(new float[]{420f, -200f, 470f, 60f, 380f, 300f, 230f, 420f}, 90f, 4f), paint(fold, 110f));
        e.fill(ellipse(-30f, 650f, 220f, 120f, -18f), paint(blush, -18f));
        e.fill(circle(640f, -60f, 170f), paint(amber, 20f));
        // Top right: a ribbon the other way, a blush pebble and a coral one behind it.
        e.fill(ellipse(1330f, -40f, 280f, 140f, 8f), paint(coral, 8f));
        e.fill(circle(1840f, 540f, 220f), paint(amber, 60f));
        float[] right = {1500f, -200f, 1440f, 200f, 1760f, 420f, 2150f, 460f};
        ShelfArt.Shape curtain = ribbon(right, 250f, 320f);
        e.fill(curtain, paint(red, 60f));
        e.scribble(1820f, 360f, 150f, 13f, 3f, 60f, flat(blush), curtain);
        e.fill(ribbon(new float[]{1500f, -200f, 1470f, 120f, 1600f, 300f, 1760f, 380f}, 100f, 4f),
                paint(fold, 70f));
        e.fill(circle(1960f, 80f, 190f), paint(crimson, 0f));
        // Under the dock: a long ribbon across, folding at the left; coral and amber at its ends.
        e.fill(ellipse(140f, 1010f, 420f, 230f, -8f), paint(coral, -8f));
        float[] low = {-160f, 1130f, 520f, 770f, 1240f, 1150f, 2120f, 820f};
        e.fill(ribbon(low, 260f, 220f), paint(red, -8f));
        e.fill(ribbon(new float[]{-160f, 1130f, 200f, 930f, 420f, 900f, 640f, 950f}, 110f, 4f), paint(fold, -20f));
        e.fill(circle(1830f, 1060f, 200f), paint(amber, 0f));
    }

    /**
     * F1 TV: racing red with maroon, orange and a warm white on carbon, a yellow accent; the logo's speed lines,
     * red and white kerbs, a chequer.
     */
    private static void f1(ShelfArt.Easel e) {
        int red = hsl(2, 0.92f, 0.47f), maroon = hsl(352, 0.62f, 0.27f), orange = hsl(16, 0.94f, 0.58f),
                white = hsl(30, 0.30f, 0.93f), carbon = hsl(228, 0.12f, 0.24f), yellow = hsl(46, 0.98f, 0.58f);
        e.ground(ground(230));
        // Top left: a red slab rushing in, three speed lines streaming off it, a yellow scribble.
        ShelfArt.Shape slab = poly(-200f, -200f, 700f, -200f, 420f, 420f, -200f, 560f).grow(30f);
        e.fill(circle(-40f, 520f, 260f), paint(maroon, 30f));
        e.fill(slab, paint(red, -24f));
        e.scribble(180f, 160f, 190f, 13f, 3f, -24f, flat(yellow), slab);
        int[] lines = {white, orange, maroon};
        for (int i = 0; i < 3; i++) {
            // Long and thin, cut on the slant at the front like the logo's lines.
            float y = 450f + i * 78f, x = 470f - i * 120f;
            e.fill(poly(-200f, y + 34f, x, y - 26f, x + 70f, y - 26f, x - 10f, y + 26f, -200f, y + 80f).grow(6f),
                    paint(lines[i], -11f));
        }
        // Top right: a carbon disc, a red and white kerb curving round it, a yellow pebble, a chequered patch.
        ShelfArt.Shape disc = circle(1960f, 180f, 380f);
        e.fill(disc, paint(carbon, 30f));
        ShelfArt.Shape kerb = ShelfArt.ring(1960f, 180f, 420f, 110f);
        e.fill(ShelfArt.arc(1960f, 180f, 420f, 110f, 90f, 270f), flat(white));
        for (int i = 0; i < 14; i++) {
            double a0 = Math.toRadians(90 + i * 13), a1 = Math.toRadians(90 + i * 13 + 6.5);
            e.fill(kerb.and(poly(1960f, 180f, 1960f + 900f * (float) Math.cos(a0), 180f + 900f * (float) Math.sin(a0),
                    1960f + 900f * (float) Math.cos(a1), 180f + 900f * (float) Math.sin(a1))), flat(red));
        }
        ShelfArt.Shape chq = rect(1900f, 150f, 300f, 300f, 12f, 40f);
        float sq = 50f;
        for (int gy = -4; gy <= 4; gy++) {
            for (int gx = -4; gx <= 4; gx++) {
                if (((gx + gy) & 1) == 0) continue;
                float c = (float) Math.cos(Math.toRadians(12)), s = (float) Math.sin(Math.toRadians(12));
                e.fill(rect(1900f + (gx * c - gy * s) * sq, 150f + (gx * s + gy * c) * sq, sq, sq, 12f, 0f)
                        .and(chq).and(disc), flat(white));
            }
        }
        e.fill(ellipse(1310f, -40f, 250f, 130f, 6f), paint(yellow, 6f));
        // Under the dock: a long kerb across, an orange and a maroon pebble at its ends.
        e.fill(ellipse(380f, 1050f, 470f, 240f, -6f), paint(maroon, -6f));
        e.fill(circle(-20f, 860f, 200f), paint(orange, 40f));
        ShelfArt.Shape strip = rect(1300f, 930f, 1500f, 170f, -9f, 24f);
        e.fill(strip, flat(white));
        for (int i = -8; i <= 8; i += 2) {
            float x = 1300f + i * 85f;
            e.fill(rect(x, 930f - i * 85f * 0.158f, 85f, 240f, -9f, 0f).and(strip), flat(red));
        }
        e.fill(circle(1960f, 760f, 180f), paint(orange, 0f));
    }

    /** Play Store: blue with deep blue, teal and green, a yellow accent; the Play shape's rounded triangles, arcs. */
    private static void playStore(ShelfArt.Easel e) {
        int blue = hsl(214, 0.82f, 0.56f), deep = hsl(222, 0.66f, 0.36f), teal = hsl(178, 0.72f, 0.40f),
                green = hsl(146, 0.58f, 0.42f), yellow = hsl(44, 0.96f, 0.56f);
        e.ground(ground(215));
        // Top left: a deep blue triangle out of the corner, a green loop on it, a teal disc behind.
        e.fill(circle(470f, -40f, 210f), paint(teal, 20f));
        ShelfArt.Shape tri = poly(-260f, -260f, 600f, 170f, -260f, 640f).grow(50f);
        e.fill(tri, paint(deep, 28f));
        e.scribble(150f, 220f, 240f, 13f, 4.5f, 28f, flat(green), tri);
        e.fill(ellipse(40f, 620f, 250f, 140f, 20f), paint(yellow, 20f));
        // Along the top: a blue arc.
        e.fill(ellipse(1050f, -50f, 300f, 140f, 4f), paint(blue, 4f));
        // Top right: a green triangle pointing in, a yellow disc.
        ShelfArt.Shape g = poly(2240f, -200f, 1440f, 230f, 2240f, 700f).grow(56f);
        e.fill(g, paint(green, -30f));
        e.fill(circle(1730f, -40f, 170f), paint(yellow, 0f));
        e.fill(circle(2010f, 560f, 190f), paint(teal, 60f));
        // Under the dock: a long blue band and a teal triangle.
        e.fill(rect(1350f, 920f, 1300f, 300f, -8f, 120f), paint(blue, -8f));
        e.fill(poly(-160f, 760f, 640f, 1160f, -160f, 1160f).grow(30f), paint(teal, 30f));
        e.fill(circle(700f, 1060f, 170f), paint(yellow, 0f));
        e.fill(poly(1760f, 1160f, 2120f, 700f, 2120f, 1160f), paint(deep, -50f));
    }
}
