package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class ShelfFeedsTest {

    private static ShelfFeeds.Item item(String id, String bg) {
        return new ShelfFeeds.Item(id, "Title " + id, bg, "https://logo/" + id);
    }

    @Test
    public void parsesConfigLines() {
        Map<String, ShelfFeeds.Feed> feeds = ShelfFeeds.parse(new String[]{
                "com.netflix.ninja | Top 10 on Netflix | https://a/{country}.json | https://b/{country}.json",
                " com.stremio.one|Popular|https://c/top.json ",
                "broken line",
                "pkg | caption | http://insecure | ftp://nope",
                "| no package | https://x",
                "com.stremio.one | Later wins | https://d/top.json",
        });
        assertEquals(2, feeds.size());
        ShelfFeeds.Feed nf = feeds.get("com.netflix.ninja");
        assertEquals("Top 10 on Netflix", nf.caption);
        assertEquals(Arrays.asList("https://a/{country}.json", "https://b/{country}.json"), nf.urls);
        assertTrue(nf.byCountry());
        ShelfFeeds.Feed st = feeds.get("com.stremio.one");
        assertEquals("Later wins", st.caption);
        assertFalse(st.byCountry());
        assertTrue(ShelfFeeds.parse(null).isEmpty());
    }

    @Test
    public void countryFromLocaleOrGlobal() {
        assertEquals("ke", ShelfFeeds.country("KE"));
        assertEquals("us", ShelfFeeds.country("us"));
        assertEquals("global", ShelfFeeds.country(""));
        assertEquals("global", ShelfFeeds.country(null));
        assertEquals("global", ShelfFeeds.country("419"));   // Latin America, a UN M.49 region
        assertEquals("https://a/netflix-top10-ke.json", ShelfFeeds.url("https://a/netflix-top10-{country}.json", "ke"));
        // The time zone's country wins: a TV in Nairobi with its language on English (United States) is in Kenya.
        assertEquals("ke", ShelfFeeds.country("KE", "US"));
        assertEquals("us", ShelfFeeds.country("001", "US"));   // UTC and the like: no country
        assertEquals("us", ShelfFeeds.country(null, "US"));
        assertEquals("global", ShelfFeeds.country("001", ""));
    }

    @Test
    public void captionNamesTheCountryOnlyForCountryFeeds() {
        ShelfFeeds.Feed byCountry = ShelfFeeds.parse(new String[]{"p | Top 10 on Netflix | https://a/{country}"}).get("p");
        ShelfFeeds.Feed fixed = ShelfFeeds.parse(new String[]{"p | Popular | https://a/top"}).get("p");
        assertEquals("Top 10 on Netflix · Kenya", ShelfFeeds.caption(byCountry, "ke", "Kenya"));
        assertEquals("Top 10 on Netflix", ShelfFeeds.caption(byCountry, "global", ""));
        assertEquals("Popular", ShelfFeeds.caption(fixed, "ke", "Kenya"));
    }

    @Test
    public void interleavesSkipsRepeatsAndTitlesWithoutBackdrops() {
        List<ShelfFeeds.Item> series = Arrays.asList(item("s1", "https://bg/s1"), item("s2", null), item("s3", "https://bg/s3"));
        List<ShelfFeeds.Item> films = Arrays.asList(item("m1", "https://bg/m1"), item("s1", "https://bg/s1"));
        List<ShelfFeeds.Item> out = ShelfFeeds.interleave(Arrays.asList(series, films), 10);
        assertEquals(Arrays.asList("s1", "m1", "s3"), ids(out));
        assertEquals(2, ShelfFeeds.interleave(Arrays.asList(series, films), 2).size());
    }

    @Test
    public void watchingThenSavedThenOwnAndFeedInTurn() {
        ShelfFeeds.Item watching = new ShelfFeeds.Item(null, "Watching", "https://bg/w", null, "Continue watching",
                null, "app://w", true);
        ShelfFeeds.Item saved = new ShelfFeeds.Item("s1", "Saved", "https://bg/s", null, "Library", null, "app://s",
                false, true);
        ShelfFeeds.Item rec1 = new ShelfFeeds.Item("r1", "Rec 1", "https://bg/r1", null, "For you");
        ShelfFeeds.Item rec2 = new ShelfFeeds.Item("r2", "Rec 2", "https://bg/r2", null, "For you");
        List<ShelfFeeds.Item> own = Arrays.asList(rec1, saved, watching, rec2);
        List<ShelfFeeds.Item> feed = Arrays.asList(item("a", "https://bg/a"), item("b", "https://bg/b"),
                item("c", "https://bg/c"));
        List<ShelfFeeds.Item> out = ShelfFeeds.combine(own, feed, 10);
        assertEquals(Arrays.asList("", "s1", "r1", "a", "r2", "b", "c"), ids(out));
        assertTrue(out.get(0).resume);
        assertTrue(out.get(1).saved);
        assertEquals(3, ShelfFeeds.combine(own, feed, 3).size());
        assertEquals(3, ShelfFeeds.combine(null, feed, 10).size());
        assertTrue(ShelfFeeds.combine(null, null, 10).isEmpty());
        // Something you're watching isn't also "saved".
        assertFalse(new ShelfFeeds.Item(null, "x", "https://bg", null, null, null, null, true, true).saved);
    }

    @Test
    public void feedLinksFromTemplates() {
        Map<String, String> links = ShelfFeeds.parseLinks(new String[]{
                "com.stremio.one | stremio:///detail/{type}/{id}",
                "com.netflix.ninja | https://www.netflix.com/search?q={title}",
                "broken",
        });
        assertEquals(2, links.size());
        ShelfFeeds.Item it = new ShelfFeeds.Item("tt123", "The Diplomat & Co", "https://bg", null, null, "series",
                null, false);
        assertEquals("stremio:///detail/series/tt123", ShelfFeeds.link(links.get("com.stremio.one"), it));
        assertEquals("https://www.netflix.com/search?q=The%20Diplomat%20%26%20Co",
                ShelfFeeds.link(links.get("com.netflix.ninja"), it));
        assertNull(ShelfFeeds.link(links.get("com.stremio.one"), item("", "https://bg")));
        assertNull(ShelfFeeds.link(null, it));
    }

    @Test
    public void logosFromConfigAndSvg() {
        Map<String, ShelfFeeds.LogoSpec> logos = ShelfFeeds.parseLogos(new String[]{
                "com.netflix.ninja | netflix | E50914",
                "com.apple.atve.androidtv.appletv | appletv | FFFFFF | wordmark",
                "bad | Not-A-Slug | E50914",
                "bad2 | slug | red",
        });
        assertEquals(2, logos.size());
        assertEquals(0xFFE50914, logos.get("com.netflix.ninja").color);
        assertFalse(logos.get("com.netflix.ninja").wordmark);
        assertTrue(logos.get("com.apple.atve.androidtv.appletv").wordmark);
        String svg = "<svg role=\"img\" viewBox=\"0 0 24 24\" xmlns=\"http://www.w3.org/2000/svg\"><title>Netflix</title>"
                + "<path d=\"m5.398 0 8.348 23.602c2.346.059 4.856.398 4.856.398L10.113 0H5.398z\"/></svg>";
        assertEquals("m5.398 0 8.348 23.602c2.346.059 4.856.398 4.856.398L10.113 0H5.398z", ShelfFeeds.iconPath(svg));
        assertNull(ShelfFeeds.iconPath(svg.replace("0 0 24 24", "0 0 48 48")));
        assertNull(ShelfFeeds.iconPath(svg.replace("</svg>", "<path d=\"M0 0h1v1z\"/></svg>")));
        assertNull(ShelfFeeds.iconPath(svg.replace("H5.398z", "H5.398z<script>")));
        assertNull(ShelfFeeds.iconPath(null));
    }

    @Test
    public void staticImagesFromConfig() {
        Map<String, String> images = ShelfFeeds.parseImages(new String[]{
                "com.google.android.youtube.tv",
                " com.plexapp.android | https://example.com/plex.jpg ",
                "com.netflix.ninja | http://example.com/plain-http.jpg",
                "com.stremio.one | https://a | https://b",
                "not a package",
                null,
        });
        assertEquals(2, images.size());
        assertEquals("", images.get("com.google.android.youtube.tv"));
        assertEquals("https://example.com/plex.jpg", images.get("com.plexapp.android"));
        assertTrue(ShelfFeeds.parseImages(null).isEmpty());
    }

    @Test
    public void picksALandscapePicture() {
        // TvContract aspect ratios: 0 16:9, 1 3:2, 2 4:3, 3 1:1, 4 2:3, 5 movie poster.
        assertEquals("https://thumb", ShelfFeeds.backdrop("https://thumb", 0, "https://poster", 5));
        assertEquals("https://poster", ShelfFeeds.backdrop(null, 0, "https://poster", 0));
        assertEquals("https://poster", ShelfFeeds.backdrop("https://thumb", 4, "https://poster", 2));
        assertNull(ShelfFeeds.backdrop(null, 0, "https://poster", 5));
        assertNull(ShelfFeeds.backdrop("http://insecure", 0, null, 0));
        assertEquals("content://app/1", ShelfFeeds.backdrop("content://app/1", 0, null, 0));
    }

    @Test
    public void triesYouTubesLargestThumbnailFirst() {
        assertEquals(Arrays.asList("https://i.ytimg.com/vi/MxKFv4QrcWQ/maxresdefault.jpg",
                        "https://i.ytimg.com/vi/MxKFv4QrcWQ/hq720.jpg"),
                ShelfFeeds.candidates("https://i.ytimg.com/vi/MxKFv4QrcWQ/sddefault.jpg", true));
        assertEquals(2, ShelfFeeds.candidates("https://i3.ytimg.com/vi/a-b_c/hqdefault.jpg", false).size());
        assertEquals(Collections.singletonList("https://images.metahub.space/background/medium/tt1/img"),
                ShelfFeeds.candidates("https://images.metahub.space/background/medium/tt1/img", false));
        // A TV that shows art at 1080p gets the 4K backdrop first, scaled down.
        assertEquals(Arrays.asList("https://images.metahub.space/background/large/tt1/img",
                        "https://images.metahub.space/background/medium/tt1/img"),
                ShelfFeeds.candidates("https://images.metahub.space/background/medium/tt1/img", true));
    }

    @Test
    public void cacheRoundTrips() {
        List<ShelfFeeds.Item> items = Arrays.asList(
                new ShelfFeeds.Item("tt1", "Tab\there", "https://bg/1", "https://logo/1"),
                new ShelfFeeds.Item("tt2", "No logo", "https://bg/2", null));
        ShelfFeeds.Cached c = ShelfFeeds.decode(ShelfFeeds.encode(1234L, "ke", items));
        assertNotNull(c);
        assertEquals(1234L, c.fetchedAt);
        assertEquals("ke", c.country);
        assertEquals(2, c.items.size());
        assertEquals("Tab here", c.items.get(0).title);
        assertEquals("https://logo/1", c.items.get(0).logo);
        assertNull(c.items.get(1).logo);
        assertNull(ShelfFeeds.decode("something else"));
        assertNull(ShelfFeeds.decode(null));
    }

    @Test
    public void freshness() {
        long ttl = ShelfSourceTtl.TTL;
        assertTrue(ShelfFeeds.fresh(1000, 1000 + ttl - 1, ttl));
        assertFalse(ShelfFeeds.fresh(1000, 1000 + ttl, ttl));
        assertFalse(ShelfFeeds.fresh(0, 5, ttl));
        assertFalse(ShelfFeeds.fresh(2000, 1000, ttl));   // clock went back
    }

    /** Six hours, as ShelfSource uses (kept here so the test doesn't load Android classes). */
    private static final class ShelfSourceTtl {
        static final long TTL = 6 * 3600_000L;
    }

    private static List<String> ids(List<ShelfFeeds.Item> items) {
        String[] out = new String[items.size()];
        for (int i = 0; i < out.length; i++) out[i] = items.get(i).id;
        return Arrays.asList(out);
    }
}
