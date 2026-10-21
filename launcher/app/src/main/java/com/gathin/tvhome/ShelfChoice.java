package com.gathin.tvhome;

import java.util.Locale;

/**
 * What an app's top shelf shows, as chosen in Home Settings ({@link SettingsActivity}): for all apps at once, else for
 * the app, saved on the TV, or else the default: its picture ({@link #IMAGE}), which stays put. Pure Java;
 * {@link ShelfChoices} keeps them.
 */
final class ShelfChoice {

    enum Kind {
        /** Suggestions: what the app suggests, a title at a time; its artwork or cover art until it has some. */
        SUGGESTIONS("suggestions"),
        /**
         * Picture, the default: one picture that stays, its Apple TV image put on the TV (or fetched), else its
         * artwork, else none (its colour and name, as GLOW).
         */
        IMAGE("image"),
        /** Its designed artwork (ShelfSource.artwork), else its cover art. */
        ARTWORK("artwork"),
        /** Its cover art, made from its banner and logo. */
        COVER_ART("cover_art"),
        /** App Colour: no picture, the glow in the app's colour, with its name. */
        GLOW("glow"),
        /** No picture: the glow in a colour of your choice, with its name. */
        COLOUR("colour");

        final String code;

        Kind(String code) {
            this.code = code;
        }
    }

    /** Where they're saved: the launcher's "home" preferences, one key per app. */
    static final String PREF_PREFIX = "shelf_choice.";
    /**
     * All apps' choice (Same for All Apps), over each app's own without erasing it: Off removes it, and theirs apply
     * again. Not under {@link #PREF_PREFIX}: no app's.
     */
    static final String PREF_ALL = "shelf_choice_all";

    /** Custom colours: red, orange, yellow, green, teal, blue, purple and pink, as tvOS's system colours. */
    static final int[] PALETTE = {
            0xFFFF453A, 0xFFFF9F0A, 0xFFFFD60A, 0xFF30D158, 0xFF40C8E0, 0xFF0A84FF, 0xFFBF5AF2, 0xFFFF375F,
    };

    static final ShelfChoice SUGGESTIONS = new ShelfChoice(Kind.SUGGESTIONS, 0);
    static final ShelfChoice IMAGE = new ShelfChoice(Kind.IMAGE, 0);
    static final ShelfChoice ARTWORK = new ShelfChoice(Kind.ARTWORK, 0);
    static final ShelfChoice COVER_ART = new ShelfChoice(Kind.COVER_ART, 0);
    static final ShelfChoice GLOW = new ShelfChoice(Kind.GLOW, 0);

    final Kind kind;
    /** COLOUR's colour, opaque; 0 for the other kinds. */
    final int colour;

    private ShelfChoice(Kind kind, int colour) {
        this.kind = kind;
        this.colour = colour;
    }

    static ShelfChoice colour(int rgb) {
        return new ShelfChoice(Kind.COLOUR, 0xFF000000 | rgb);
    }

    static ShelfChoice of(Kind kind) {
        switch (kind) {
            case SUGGESTIONS:
                return SUGGESTIONS;
            case IMAGE:
                return IMAGE;
            case ARTWORK:
                return ARTWORK;
            case COVER_ART:
                return COVER_ART;
            case GLOW:
                return GLOW;
            default:
                return colour(PALETTE[0]);
        }
    }

    static String key(String pkg) {
        return PREF_PREFIX + pkg;
    }

    /** The package a preference key is for, or null if it isn't a choice's. */
    static String pkgOf(String key) {
        return key != null && key.startsWith(PREF_PREFIX) && key.length() > PREF_PREFIX.length()
                ? key.substring(PREF_PREFIX.length()) : null;
    }

    /**
     * Every app's choice until one is made: its picture, which stays put. Suggestions, which change and fetch titles
     * from the network, are for the apps you choose them for.
     */
    static ShelfChoice byDefault() {
        return IMAGE;
    }

    /** The choice in effect: the saved one if it can be read, else the default. */
    static ShelfChoice resolve(String saved) {
        ShelfChoice c = decode(saved);
        return c != null ? c : byDefault();
    }

    /** The choice in effect with all apps' ({@link #PREF_ALL}, null for per app) over the app's own. */
    static ShelfChoice resolve(String savedAll, String saved) {
        ShelfChoice all = decode(savedAll);
        return all != null ? all : resolve(saved);
    }

    /** "suggestions", "image", "artwork", "cover_art", "glow" or "colour:RRGGBB". */
    String encode() {
        return kind == Kind.COLOUR ? kind.code + ":" + String.format(Locale.ROOT, "%06X", colour & 0xFFFFFF)
                : kind.code;
    }

    /** A saved choice, or null if there's none or it can't be read (from a newer build, say). */
    static ShelfChoice decode(String s) {
        if (s == null) return null;
        s = s.trim();
        String prefix = Kind.COLOUR.code + ":";
        if (s.startsWith(prefix)) {
            String hex = s.substring(prefix.length());
            if (!hex.matches("[0-9A-Fa-f]{6}")) return null;
            return colour(Integer.parseInt(hex, 16));
        }
        for (Kind k : Kind.values()) {
            if (k != Kind.COLOUR && k.code.equals(s)) return of(k);
        }
        return null;
    }

    /** Titles may show: the app's own and its feed's, so its feed is fetched. */
    boolean suggests() {
        return kind == Kind.SUGGESTIONS;
    }

    /** Some picture shows (cover art at least), rather than the glow alone. */
    boolean picture() {
        return kind != Kind.GLOW && kind != Kind.COLOUR;
    }

    /** Whether, without a picture of its own or artwork, it shows its cover art (else its colour alone). */
    boolean fallsBackToCoverArt() {
        return kind != Kind.IMAGE;
    }

    /** Whether the designed artwork is tried before the cover art made from the app's banner. */
    boolean triesArtwork() {
        return kind == Kind.SUGGESTIONS || kind == Kind.IMAGE || kind == Kind.ARTWORK;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof ShelfChoice)) return false;
        ShelfChoice c = (ShelfChoice) o;
        return kind == c.kind && colour == c.colour;
    }

    @Override
    public int hashCode() {
        return kind.hashCode() * 31 + colour;
    }

    @Override
    public String toString() {
        return encode();
    }
}
