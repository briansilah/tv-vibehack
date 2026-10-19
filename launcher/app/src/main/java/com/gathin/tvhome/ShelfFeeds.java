package com.gathin.tvhome;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The top shelf's suggested content: which public catalogue feeds each dock app (res/values/config.xml,
 * top_shelf_feeds), and the titles read from them. Plain Java, so it runs in JVM unit tests.
 */
final class ShelfFeeds {

    /** In a feed URL: the TV's country (its locale's, lower case, e.g. "ke"), or {@link #GLOBAL} without one. */
    static final String COUNTRY = "{country}";
    static final String GLOBAL = "global";
    /** Titles kept per feed: the carousel shows a handful, and fewer means less to download and decode. */
    static final int MAX_ITEMS = 10;
    private static final String CACHE_HEADER = "tvhome-shelf 1";

    private ShelfFeeds() {
    }

    /** An app's suggestions: a caption ("Top 10 on Netflix") and one or more Stremio catalogue URLs. */
    static final class Feed {
        final String pkg;
        final String caption;
        final List<String> urls;

        Feed(String pkg, String caption, List<String> urls) {
            this.pkg = pkg;
            this.caption = caption;
            this.urls = urls;
        }

        /** Whether its titles depend on the TV's country. */
        boolean byCountry() {
            for (String u : urls) {
                if (u.contains(COUNTRY)) return true;
            }
            return false;
        }
    }

    /**
     * A suggested title: a full-screen backdrop, its title logo if any, its name, and what it is ("Continue
     * watching", "Recommended for you"; null: the feed's caption).
     */
    static final class Item {
        final String id;
        final String title;
        final String backdrop;
        final String logo;
        final String caption;
        /** A feed's title: "movie" or "series" (its catalogue's type), for its app's link. */
        final String type;
        /** What the app published to open it (an intent: or app URI), or null. */
        final String link;
        /** Something you were watching: it comes first, with Resume. */
        final boolean resume;
        /** Something you saved (a watchlist, a library): it comes next. */
        final boolean saved;

        Item(String id, String title, String backdrop, String logo) {
            this(id, title, backdrop, logo, null, null, null, false, false);
        }

        Item(String id, String title, String backdrop, String logo, String caption) {
            this(id, title, backdrop, logo, caption, null, null, false, false);
        }

        Item(String id, String title, String backdrop, String logo, String caption, String type, String link,
             boolean resume) {
            this(id, title, backdrop, logo, caption, type, link, resume, false);
        }

        Item(String id, String title, String backdrop, String logo, String caption, String type, String link,
             boolean resume, boolean saved) {
            this.id = id == null ? "" : id;
            this.title = title == null ? "" : title;
            this.backdrop = backdrop;
            this.logo = logo == null || logo.isEmpty() ? null : logo;
            this.caption = caption == null || caption.isEmpty() ? null : caption;
            this.type = type == null || type.isEmpty() ? null : type;
            this.link = link == null || link.isEmpty() ? null : link;
            this.resume = resume;
            this.saved = saved && !resume;
        }
    }

    /**
     * Links that open a feed's title in its app, by package, from config lines "package | link": {id} (the title's
     * IMDb id), {type} (movie or series) and {title} (its name) are filled in.
     */
    static Map<String, String> parseLinks(String[] lines) {
        Map<String, String> links = new LinkedHashMap<>();
        if (lines == null) return links;
        for (String line : lines) {
            if (line == null) continue;
            int bar = line.indexOf('|');
            if (bar <= 0) continue;
            String pkg = line.substring(0, bar).trim(), link = line.substring(bar + 1).trim();
            if (!pkg.isEmpty() && link.contains("://")) links.put(pkg, link);
        }
        return links;
    }

    /** A feed title's link from its app's template, or null if the template needs something the title lacks. */
    static String link(String template, Item it) {
        if (template == null) return null;
        String out = template;
        if (out.contains("{id}")) {
            if (it.id.isEmpty()) return null;
            out = out.replace("{id}", encode(it.id));
        }
        if (out.contains("{type}")) {
            if (it.type == null) return null;
            out = out.replace("{type}", encode(it.type));
        }
        if (out.contains("{title}")) {
            if (it.title.isEmpty()) return null;
            out = out.replace("{title}", encode(it.title));
        }
        return out;
    }

    private static String encode(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException e) {
            return s;
        }
    }

    /** TvContract's poster and thumbnail aspect ratios that suit a full-screen backdrop: 16:9, 3:2 and 4:3. */
    static boolean landscape(int aspectRatio) {
        return aspectRatio == 0 || aspectRatio == 1 || aspectRatio == 2;
    }

    /**
     * A TV programme's backdrop: its thumbnail if that's landscape, else its poster if that is, else none (a
     * portrait poster would be cropped to a sliver). A missing aspect ratio counts as 16:9, TvContract's default.
     */
    static String backdrop(String thumbnail, int thumbnailAspect, String poster, int posterAspect) {
        if (usable(thumbnail) && landscape(thumbnailAspect)) return thumbnail;
        if (usable(poster) && landscape(posterAspect)) return poster;
        return null;
    }

    private static boolean usable(String uri) {
        return uri != null && (uri.startsWith("https://") || uri.startsWith("content://")
                || uri.startsWith("android.resource://"));
    }

    /**
     * Where to fetch an image from, best first. YouTube's thumbnails come in several sizes and the apps publish a
     * small one (sddefault, 640 x 480 with bars), so its largest, 1280 x 720, is tried first. With {@code hd} (a TV
     * that shows art at 1920 x 1080), metahub's 3840 x 2160 backdrop comes before the 1280 x 720 one the feeds name:
     * scaled down to the screen it's as sharp as the screen can show.
     */
    static List<String> candidates(String url, boolean hd) {
        List<String> out = new ArrayList<>();
        java.util.regex.Matcher m = YT_THUMB.matcher(url);
        if (m.matches()) out.add(m.group(1) + "maxresdefault.jpg");
        if (hd && url.contains(METAHUB_MEDIUM)) out.add(url.replace(METAHUB_MEDIUM, "/background/large/"));
        out.add(url);
        return out;
    }

    private static final String METAHUB_MEDIUM = "/background/medium/";

    private static final java.util.regex.Pattern YT_THUMB = java.util.regex.Pattern.compile(
            "(https://i\\d?\\.ytimg\\.com/vi/[\\w-]+/)(?:sd|hq|mq)?default\\.jpg(?:\\?.*)?");

    /**
     * An app's suggestions in the order they're shown, at most {@code max}: what you were watching first
     * ({@link Item#resume}), so it's a press away; then what you saved ({@link Item#saved}: a watchlist, a
     * library); then its other suggestions and its feed's trending and popular titles, one of each in turn.
     */
    static List<Item> combine(List<Item> own, List<Item> feed, int max) {
        List<Item> resume = new ArrayList<>(), saved = new ArrayList<>(), others = new ArrayList<>();
        if (own != null) {
            for (Item it : own) (it.resume ? resume : it.saved ? saved : others).add(it);
        }
        List<Item> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (List<Item> first : java.util.Arrays.asList(resume, saved)) {
            for (Item it : first) {
                if (out.size() < max && seen.add(it.id.isEmpty() ? it.backdrop : it.id)) out.add(it);
            }
        }
        List<Item> rest = feed == null ? new ArrayList<>() : feed;
        for (int i = 0; out.size() < max && (i < others.size() || i < rest.size()); i++) {
            for (List<Item> l : java.util.Arrays.asList(others, rest)) {
                if (i >= l.size() || out.size() >= max) continue;
                Item it = l.get(i);
                if (seen.add(it.id.isEmpty() ? it.backdrop : it.id)) out.add(it);
            }
        }
        return out;
    }

    /**
     * Feeds by package from config lines "package | caption | url | url ...". Lines without a package, caption or
     * https URL are skipped; a later line for the same package replaces an earlier one.
     */
    static Map<String, Feed> parse(String[] lines) {
        Map<String, Feed> feeds = new LinkedHashMap<>();
        if (lines == null) return feeds;
        for (String line : lines) {
            if (line == null) continue;
            String[] parts = line.split("\\|");
            if (parts.length < 3) continue;
            String pkg = parts[0].trim();
            String caption = parts[1].trim();
            List<String> urls = new ArrayList<>();
            for (int i = 2; i < parts.length; i++) {
                String u = parts[i].trim();
                if (u.startsWith("https://")) urls.add(u);
            }
            if (pkg.isEmpty() || caption.isEmpty() || urls.isEmpty()) continue;
            feeds.put(pkg, new Feed(pkg, caption, urls));
        }
        return feeds;
    }

    /**
     * The TV's country for feed URLs, as a lower-case two-letter code: where its time zone is (set from the network,
     * so it's where the TV is, even with its language left on English (United States)), else its locale's region,
     * else {@link #GLOBAL}.
     */
    static String country(String timeZoneRegion, String localeCountry) {
        String tz = code(timeZoneRegion);
        if (tz != null) return tz;
        String loc = code(localeCountry);
        return loc != null ? loc : GLOBAL;
    }

    static String country(String localeCountry) {
        return country(null, localeCountry);
    }

    /** A two-letter country code, lower case, or null ("001", the world, and the like aren't countries). */
    private static String code(String s) {
        if (s == null) return null;
        String c = s.trim().toLowerCase(Locale.ROOT);
        if (c.length() != 2) return null;
        for (int i = 0; i < 2; i++) {
            if (c.charAt(i) < 'a' || c.charAt(i) > 'z') return null;
        }
        return c;
    }

    static String url(String template, String country) {
        return template.replace(COUNTRY, country);
    }

    /** "Top 10 on Netflix · Kenya" for a feed that follows the TV's country, else just the caption. */
    static String caption(Feed feed, String country, String countryName) {
        if (!feed.byCountry() || GLOBAL.equals(country) || countryName == null || countryName.isEmpty()) {
            return feed.caption;
        }
        return feed.caption + " · " + countryName;
    }

    /**
     * One list from several (a feed's series and films): first of each, then second of each, and so on, without
     * repeats or titles that have no backdrop, at most {@code max}.
     */
    static List<Item> interleave(List<List<Item>> lists, int max) {
        List<Item> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int longest = 0;
        for (List<Item> l : lists) longest = Math.max(longest, l.size());
        for (int i = 0; i < longest && out.size() < max; i++) {
            for (List<Item> l : lists) {
                if (i >= l.size() || out.size() >= max) continue;
                Item it = l.get(i);
                if (it.backdrop == null || it.backdrop.isEmpty()) continue;
                String id = it.id.isEmpty() ? it.backdrop : it.id;
                if (seen.add(id)) out.add(it);
            }
        }
        return out;
    }

    /** Whether a feed fetched at {@code fetchedAt} is still good at {@code now} (both wall-clock ms). */
    static boolean fresh(long fetchedAt, long now, long ttlMs) {
        return fetchedAt > 0 && fetchedAt <= now && now - fetchedAt < ttlMs;
    }

    /** A feed's titles saved on the TV: when they were fetched, for which country, then one title per line. */
    static String encode(long fetchedAt, String country, List<Item> items) {
        StringBuilder sb = new StringBuilder(CACHE_HEADER).append('\n')
                .append(fetchedAt).append('\t').append(clean(country)).append('\n');
        for (Item it : items) {
            sb.append(clean(it.id)).append('\t').append(clean(it.title)).append('\t')
                    .append(clean(it.backdrop)).append('\t').append(clean(it.logo)).append('\t')
                    .append(clean(it.type)).append('\n');
        }
        return sb.toString();
    }

    /** What {@link #encode} saved, or null if it isn't that. */
    static Cached decode(String text) {
        if (text == null) return null;
        String[] lines = text.split("\n");
        if (lines.length < 2 || !CACHE_HEADER.equals(lines[0])) return null;
        String[] head = lines[1].split("\t", -1);
        long fetchedAt;
        try {
            fetchedAt = Long.parseLong(head[0]);
        } catch (NumberFormatException e) {
            return null;
        }
        String country = head.length > 1 ? head[1] : "";
        List<Item> items = new ArrayList<>();
        for (int i = 2; i < lines.length; i++) {
            String[] f = lines[i].split("\t", -1);
            if (f.length < 4 || f[2].isEmpty()) continue;
            items.add(new Item(f[0], f[1], f[2], f[3], null, f.length > 4 ? f[4] : null, null, false));
        }
        return new Cached(fetchedAt, country, items);
    }

    static final class Cached {
        final long fetchedAt;
        final String country;
        final List<Item> items;

        Cached(long fetchedAt, String country, List<Item> items) {
            this.fetchedAt = fetchedAt;
            this.country = country;
            this.items = items;
        }
    }

    private static String clean(String s) {
        return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }
}
