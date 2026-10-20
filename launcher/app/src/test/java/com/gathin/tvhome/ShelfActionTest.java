package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.function.Predicate;

public class ShelfActionTest {

    private static final Predicate<String> ALL = l -> true;
    private static final Predicate<String> NONE = l -> false;
    private static final String STREMIO = "stremio:///detail/{type}/{id}";
    private static final String NETFLIX = "nflx://www.netflix.com/search?q={title}";

    /** A title the app published itself (TvProvider), with its link. */
    private static ShelfFeeds.Item own(String link, boolean resume) {
        return new ShelfFeeds.Item(null, "Bad Apples", "https://bg", null, null, null, link, resume, !resume);
    }

    /** A feed's title: an IMDb id, a type, no link of its own. */
    private static ShelfFeeds.Item feed(String id, String title) {
        return new ShelfFeeds.Item(id, title, "https://bg", null, null, "movie", null, false);
    }

    @Test
    public void stremioResumesOnlyWhatItAutoPlays() {
        // What Stremio publishes (its LibraryItem.toPreviewProgram): the streams with ?autoPlay=<started>, else
        // the title's page.
        ShelfAction a = ShelfAction.of(own("stremio:///detail/movie/tt1/tt1?autoPlay=true", true), STREMIO, ALL);
        assertEquals(ShelfAction.RESUME, a.kind);
        assertEquals("stremio:///detail/movie/tt1/tt1?autoPlay=true", a.link);
        assertTrue(a.plays());
        // In its Continue Watching channel, but not started: Stremio shows the streams, so no Resume.
        assertEquals(ShelfAction.OPEN,
                ShelfAction.of(own("stremio:///detail/series/tt2/tt2:1:2?autoPlay=false", true), STREMIO, ALL).kind);
        assertEquals(ShelfAction.OPEN, ShelfAction.of(own("stremio:///detail/series/tt2", true), STREMIO, ALL).kind);
        // Its library: was Play without the autoPlay hint, but it only opens the page.
        ShelfAction lib = ShelfAction.of(own("stremio:///detail/series/tt2", false), STREMIO, ALL);
        assertEquals(ShelfAction.OPEN, lib.kind);
        assertEquals("stremio:///detail/series/tt2", lib.link);
        assertFalse(lib.plays());
    }

    @Test
    public void playOnlyWhenTheLinkPlays() {
        String yt = "intent://www.youtube.com/watch?v=abc#Intent;scheme=https;package=com.google.android.youtube.tv;end";
        assertEquals(ShelfAction.PLAY, ShelfAction.of(own(yt, false), null, ALL).kind);
        assertEquals(ShelfAction.RESUME, ShelfAction.of(own(yt, true), null, ALL).kind);
        assertEquals(ShelfAction.PLAY, ShelfAction.of(own("https://www.netflix.com/watch/8001", false), null, ALL).kind);
        assertEquals(ShelfAction.OPEN, ShelfAction.of(own("https://www.netflix.com/title/8001", false), null, ALL).kind);
        assertEquals(ShelfAction.PLAY, ShelfAction.of(own("stremio:///player/abc/x/y/movie/tt1/tt1", false), null, ALL).kind);
        // A link that doesn't say: Resume keeps Watch Next's promise; anything else only opens.
        assertEquals(ShelfAction.RESUME, ShelfAction.of(own("myapp://item/42", true), null, ALL).kind);
        assertEquals(ShelfAction.OPEN, ShelfAction.of(own("myapp://item/42", false), null, ALL).kind);
    }

    @Test
    public void ownLinkTheAppCantOpenFallsBack() {
        // No activity for its own link: neither Resume nor Play, just the app.
        ShelfAction a = ShelfAction.of(own("https://example.com/watch/1", true), null, NONE);
        assertEquals(ShelfAction.APP, a.kind);
        assertNull(a.link);
        assertFalse(a.plays());
        // Its own link doesn't open, the template does (a title with an id).
        ShelfFeeds.Item it = new ShelfFeeds.Item("tt9", "X", "https://bg", null, null, "movie",
                "https://example.com/watch/1", true);
        ShelfAction b = ShelfAction.of(it, STREMIO, l -> l.startsWith("stremio:"));
        assertEquals(ShelfAction.OPEN, b.kind);
        assertEquals("stremio:///detail/movie/tt9", b.link);
    }

    @Test
    public void feedTitlesOpenSearchOrJustTheApp() {
        ShelfAction st = ShelfAction.of(feed("tt0111161", "The Shawshank Redemption"), STREMIO, ALL);
        assertEquals(ShelfAction.OPEN, st.kind);
        assertEquals("stremio:///detail/movie/tt0111161", st.link);
        ShelfAction nf = ShelfAction.of(feed("tt36429458", "Doing Life"), NETFLIX, ALL);
        assertEquals(ShelfAction.SEARCH, nf.kind);
        assertEquals("nflx://www.netflix.com/search?q=Doing%20Life", nf.link);
        assertFalse(nf.plays());
        // Netflix's https search link: the app has no activity for it, so the button only opens Netflix.
        ShelfAction https = ShelfAction.of(feed("tt1", "Doing Life"), "https://www.netflix.com/search?q={title}",
                l -> !l.startsWith("https://www.netflix.com/search"));
        assertEquals(ShelfAction.APP, https.kind);
        assertNull(https.link);
        // No template (Prime Video, Disney+), or one the title can't fill: the app.
        assertEquals(ShelfAction.APP, ShelfAction.of(feed("tt1", "X"), null, ALL).kind);
        assertEquals(ShelfAction.APP, ShelfAction.of(feed("", "X"), STREMIO, ALL).kind);
        // Nothing showing (a static picture): the app.
        assertEquals(ShelfAction.APP, ShelfAction.of(null, STREMIO, ALL).kind);
    }

    @Test
    public void searchTemplatesGoByNameAlone() {
        assertTrue(ShelfAction.searches(NETFLIX));
        assertTrue(ShelfAction.searches("https://tv.apple.com/search?term={title}"));
        assertFalse(ShelfAction.searches(STREMIO));
        assertFalse(ShelfAction.searches("app://x/{id}?name={title}"));
    }

    @Test
    public void whatALinkSays() {
        assertEquals(ShelfAction.OPENS, ShelfAction.how("stremio:///detail/movie/tt1/tt1?autoPlay=false"));
        assertEquals(ShelfAction.PLAYS, ShelfAction.how("stremio:///detail/movie/tt1/tt1?x=1&AUTOPLAY=TRUE"));
        assertEquals(ShelfAction.OPENS, ShelfAction.how("https://www.netflix.com/ke/title/8001?source=30"));
        assertEquals(ShelfAction.OPENS, ShelfAction.how("https://tv.apple.com/show/severance/umc.cmc.1"));
        assertEquals(ShelfAction.OPENS, ShelfAction.how("https://app.primevideo.com/detail?gti=amzn1"));
        // The host isn't the path: watch.amazon.com's /detail opens.
        assertEquals(ShelfAction.OPENS, ShelfAction.how("https://watch.amazon.com/detail?gti=amzn1"));
        assertEquals(ShelfAction.PLAYS, ShelfAction.how("https://www.youtube.com/watch?v=abc&launch=launcher"));
        // An intent: URI's extras say nothing about it.
        assertEquals(ShelfAction.UNSAID, ShelfAction.how("intent://example.com/item/1#Intent;S.mode=watch;end"));
        assertEquals(ShelfAction.UNSAID, ShelfAction.how("myapp://item/42"));
        assertEquals(ShelfAction.UNSAID, ShelfAction.how("https://www.netflix.com"));
    }
}
