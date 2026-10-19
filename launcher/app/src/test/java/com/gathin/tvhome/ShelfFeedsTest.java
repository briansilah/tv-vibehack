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
    public void ownSuggestionsComeFirst() {
        List<ShelfFeeds.Item> own = Collections.singletonList(
                new ShelfFeeds.Item(null, "Watching", "https://bg/w", null, "Continue watching"));
        List<ShelfFeeds.Item> feed = Arrays.asList(item("a", "https://bg/a"), item("b", "https://bg/b"));
        List<ShelfFeeds.Item> out = ShelfFeeds.combine(own, feed, 2);
        assertEquals(2, out.size());
        assertEquals("Continue watching", out.get(0).caption);
        assertEquals("a", out.get(1).id);
        assertEquals(2, ShelfFeeds.combine(null, feed, 10).size());
        assertTrue(ShelfFeeds.combine(null, null, 10).isEmpty());
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
                        "https://i.ytimg.com/vi/MxKFv4QrcWQ/sddefault.jpg"),
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
