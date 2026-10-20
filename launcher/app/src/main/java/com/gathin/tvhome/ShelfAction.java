package com.gathin.tvhome;

import java.util.Locale;
import java.util.function.Predicate;

/**
 * What OK on the top shelf's button does with a title, so the button can say so: Resume, Play, Open in the app
 * (the title's page), Search in the app (where it can't be opened at the title), or Open the app. TopShelf takes
 * the label and what OK opens from the same answer, for the title showing at that moment. Pure, for JVM tests.
 */
final class ShelfAction {
    /** Opens the app where it was: there's no link for the title, or none the app has an activity for. */
    static final int APP = 0;
    /** Opens the title's page in its app. */
    static final int OPEN = 1;
    /** Opens the app's search for the title's name: the app can't be opened at the title itself. */
    static final int SEARCH = 2;
    static final int PLAY = 3;
    static final int RESUME = 4;

    /** What a link does, as far as it says: plays, opens a page, or doesn't say. */
    static final int UNSAID = 0, PLAYS = 1, OPENS = 2;

    final int kind;
    /** What OK opens, or null for the app itself. */
    final String link;

    private ShelfAction(int kind, String link) {
        this.kind = kind;
        this.link = link;
    }

    /** Play and Resume start playback; the others only open something. */
    boolean plays() {
        return kind == PLAY || kind == RESUME;
    }

    /**
     * {@code it}: the title showing (null: none, so the app); {@code template}: the app's line in config.xml's
     * top_shelf_links, or null; {@code opens}: whether the app has an activity for a link.
     * <ul>
     *   <li>The link the app published for the title, if the app opens it: Resume for something you were watching,
     *   Play, or Open, by what the link says ({@link #how}). A link that doesn't say plays only as Resume, the
     *   promise of a Watch Next or Continue watching entry, and otherwise counts as Open: Play only when it plays.</li>
     *   <li>Else the app's template: Search if it goes by the title's name alone, else Open.</li>
     *   <li>Else the app.</li>
     * </ul>
     */
    static ShelfAction of(ShelfFeeds.Item it, String template, Predicate<String> opens) {
        if (it == null) return new ShelfAction(APP, null);
        if (it.link != null && opens.test(it.link)) {
            int how = how(it.link);
            int kind = how == OPENS ? OPEN : it.resume ? RESUME : how == PLAYS ? PLAY : OPEN;
            return new ShelfAction(kind, it.link);
        }
        String link = ShelfFeeds.link(template, it);
        if (link != null && opens.test(link)) return new ShelfAction(searches(template) ? SEARCH : OPEN, link);
        return new ShelfAction(APP, null);
    }

    /** A template that goes by the title's name and nothing else can only search for it. */
    static boolean searches(String template) {
        return template.contains("{title}") && !template.contains("{id}");
    }

    /**
     * What a link says it does: an autoPlay parameter (Stremio's ?autoPlay=true resumes, ?autoPlay=false opens the
     * title's streams), else a path segment: watch, play or player (Netflix's /watch, YouTube's /watch, Stremio's
     * /player) plays; detail, details, title, show, movie or episode (Stremio's /detail, Netflix's /title, Apple
     * TV's /show and /movie) opens the title's page. Anything else: {@link #UNSAID}.
     */
    static int how(String link) {
        String l = link.toLowerCase(Locale.ROOT);
        // An intent: URI's extras come after its data, in the fragment.
        int hash = l.indexOf('#');
        if (hash >= 0) l = l.substring(0, hash);
        int q = l.indexOf('?');
        String path = q >= 0 ? l.substring(0, q) : l;
        if (q >= 0) {
            for (String p : l.substring(q + 1).split("&")) {
                if (p.equals("autoplay=false") || p.equals("autoplay=0")) return OPENS;
                if (p.equals("autoplay=true") || p.equals("autoplay=1")) return PLAYS;
            }
        }
        int scheme = path.indexOf("://");
        if (scheme >= 0) {
            // Past the host, if any: "stremio:///detail/..." has none.
            int slash = path.indexOf('/', scheme + 3);
            path = slash >= 0 ? path.substring(slash) : "";
        }
        int how = UNSAID;
        for (String s : path.split("/")) {
            switch (s) {
                case "watch":
                case "play":
                case "player":
                    return PLAYS;
                case "detail":
                case "details":
                case "title":
                case "show":
                case "movie":
                case "episode":
                    how = OPENS;
                    break;
                default:
                    break;
            }
        }
        return how;
    }
}
