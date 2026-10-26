package com.gathin.tvhome;

import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ImageDecoder;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.DisplayMetrics;
import android.util.JsonReader;
import android.util.JsonToken;
import android.util.LruCache;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Where the top shelf's pictures come from, all off the main thread: suggested titles, their backdrops and title
 * logos, and each app's cover art, made from its own banner. Suggestions come first from the apps themselves (what
 * they publish on the TV for the home screen: Android TV's Watch Next and preview channels, read from TvProvider
 * with READ_TV_LISTINGS, which build-and-install.sh grants), then from public catalogue feeds
 * (res/values/config.xml, top_shelf_feeds). Feeds and images are cached on the TV, so the shelf works offline from what it last saw; decoded
 * pictures are kept in memory for the dock apps. Public methods are for the main thread; results come back on it.
 *
 * <p>The network: a feed is fetched at most every {@link #FEED_TTL_MS} (no polling: only when Home shows and
 * it's stale), and only its first few titles are read. Requests are plain GETs without cookies or identifiers;
 * a feed URL may carry the TV's country.
 */
final class ShelfSource {

    interface Listener {
        /** An app's suggested titles changed (arrived, or were refreshed). */
        void onItems(String pkg);

        /** An app's cover art changed (its crisp logo arrived). */
        void onCover(String key);

        /** The titles saved and published on the TV have been read: which apps have none, so show artwork, is known. */
        void onTitlesRead();
    }

    /** An app as the top shelf needs it, for its cover art. */
    static final class App {
        final String key;
        final String pkg;
        final String label;
        final ApplicationInfo info;
        final int bannerRes;
        /** A private copy of its banner (or icon) to fall back on. */
        final Drawable drawable;

        App(String key, String pkg, String label, ApplicationInfo info, int bannerRes, Drawable drawable) {
            this.key = key;
            this.pkg = pkg;
            this.label = label;
            this.info = info;
            this.bannerRes = bannerRes;
            this.drawable = drawable;
        }
    }

    /**
     * An app's cover art: a soft backdrop in its colours and its logo (null if it couldn't be cut out), or its
     * artwork ({@code artwork}, see ShelfArt) and logo; or its static Top Shelf image ({@code image}), full screen
     * with its logo in the picture.
     */
    static final class Cover {
        final Bitmap backdrop;
        final Bitmap logo;
        final boolean image;
        /** For an app with a static image: which file it was made from ("" for none), to notice a new one. */
        final String stamp;
        /** Its artwork: a full-screen picture, like a static image. */
        final boolean artwork;

        Cover(Bitmap backdrop, Bitmap logo) {
            this(backdrop, logo, false, null);
        }

        Cover(Bitmap backdrop, Bitmap logo, boolean image, String stamp) {
            this(backdrop, logo, image, stamp, false);
        }

        Cover(Bitmap backdrop, Bitmap logo, boolean image, String stamp, boolean artwork) {
            this.backdrop = backdrop;
            this.logo = logo;
            this.image = image;
            this.stamp = stamp;
            this.artwork = artwork;
        }
    }

    interface Done<T> {
        void done(T result);
    }

    static final long FEED_TTL_MS = 6 * 3600_000L;
    /** After a failed or recent fetch, wait this long before trying the network again. */
    private static final long RETRY_MS = 15 * 60_000L;
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 15000;
    private static final int MAX_IMAGE_BYTES = 6 << 20;
    /** Images kept on the TV: a few dozen titles' backdrops and logos. */
    private static final long DISK_CAP_BYTES = 60L << 20;
    /**
     * Backdrops are decoded to cover this: the screen's own 1920 x 1080 (Android TV draws apps at 1080p, even on a
     * 4K panel), or on a TV with little memory 1280 x 720, scaled up a little.
     */
    private static int BACKDROP_W = 1920, BACKDROP_H = 1080;
    private static final int LOGO_W = 800, LOGO_H = 300;
    /**
     * The least a backdrop may be (after any black bars are cut off): about 720p. A smaller picture (a 640 x 480
     * thumbnail, a 240-pixel poster) would show blurred across the screen, so its title is skipped.
     */
    static final int MIN_BACKDROP_W = 1200, MIN_BACKDROP_H = 500;
    private static final int COVER_W = 480, COVER_H = 270;

    private static final ExecutorService NET = worker("tvhome-shelf-net");
    private static final ExecutorService WORK = worker("tvhome-shelf");

    private final Context ctx;
    private final Listener listener;
    /** Art at 1920 x 1080 from 4K masters; else 1280 x 720 (a TV with under 1.5 GB of memory). */
    private final boolean hd;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, ShelfFeeds.Feed> feeds;
    private final Map<String, String> links;
    private final Map<String, ShelfFeeds.LogoSpec> logos;
    /** Apps whose top shelf is a static image (config.xml, top_shelf_images): the URL it's fetched from, or "". */
    private final Map<String, String> images;
    /** What each app's top shelf shows: chosen in Home Settings, else config.xml's default. */
    private final ShelfChoices choices;
    /** Static images put on the TV: {@code <package>.jpg} (or .png, .webp) in the launcher's external files. */
    static final String IMAGE_DIR = "top-shelf";
    private static final String[] IMAGE_TYPES = {".jpg", ".png", ".webp"};
    private final Set<String> imagesFetching = new HashSet<>();
    /** Simple Icons' vector logos (CC0), pinned to a major version: fetched once per app, kept on the TV. */
    private static final String ICON_URL = "https://cdn.jsdelivr.net/npm/simple-icons@16/icons/%s.svg";
    private final String country;
    private final String countryName;
    private final File dir;
    /** Titles from the feeds, and those the apps published on the TV, by package. */
    private final Map<String, List<ShelfFeeds.Item>> feedItems = new HashMap<>();
    private final Map<String, List<ShelfFeeds.Item>> ownItems = new HashMap<>();
    private final String continueWatching, upNext, newEpisodes, watchlist;
    private long lastOwnRead;
    private final Map<String, Long> fetchedAt = new HashMap<>();
    private final Map<String, String> fetchedFor = new HashMap<>();
    private final Map<String, Cover> covers = new HashMap<>();
    /** The apps covers were made for, by key: to make one again. */
    private final Map<String, App> coverApps = new HashMap<>();
    /** Covers being made, and who's waiting for each. */
    private final Map<String, List<Done<Cover>>> coversLoading = new HashMap<>();
    /** The choice each cover was made for: one made for another is as good as none ({@link #cover}). */
    private final Map<String, ShelfChoice> madeFor = new HashMap<>();
    /** Counts forgetCovers: a cover begun before it isn't kept. */
    private int coverGen;
    /** Whether each cover was made for an app that may show its artwork (see {@link #artworkWanted}). */
    private final Map<String, Boolean> coverForArt = new HashMap<>();
    /** Which artworks are kept decoded, and which apps' static images are made with the dock. */
    private final ShelfArtKeep artKeep = new ShelfArtKeep();
    /**
     * The titles saved and published on the TV have been read once: until then, which apps have none isn't known, so
     * covers of apps with artwork wait ({@link #deferred}).
     */
    private boolean titlesRead;
    private final List<Deferred> deferred = new ArrayList<>();

    /** A cover asked for before the titles were read. */
    private static final class Deferred {
        final App app;
        final Done<Cover> done;

        Deferred(App app, Done<Cover> done) {
            this.app = app;
            this.done = done;
        }
    }
    /**
     * What may show next (TopShelf.keep): each dock app's first title, and those next to the title showing. Kept out
     * of the cache's turnover, so a dock move, Left, Right or the carousel finds them decoded.
     */
    private final Map<String, Bitmap> pinned = new HashMap<>();
    private final Set<String> pins = new HashSet<>();
    private final Map<String, List<Done<Bitmap>>> loading = new HashMap<>();
    /** URLs that failed, and when: not retried for {@link #RETRY_MS}. */
    private final Map<String, Long> failed = new HashMap<>();
    private final LruCache<String, Bitmap> bitmaps;
    private boolean started;
    /** The dock's apps: only their feeds are fetched. */
    private final Set<String> wanted = new HashSet<>();
    private long lastFetch;
    private int fetching;

    ShelfSource(Context ctx, String[] feedLines, String[] linkLines, String[] logoLines, String[] imageLines,
                Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.listener = listener;
        this.feeds = ShelfFeeds.parse(feedLines);
        this.links = ShelfFeeds.parseLinks(linkLines);
        this.logos = ShelfFeeds.parseLogos(logoLines);
        this.images = ShelfFeeds.parseImages(imageLines);
        this.choices = new ShelfChoices(this.ctx);
        this.country = ShelfFeeds.country(timeZoneRegion(), Locale.getDefault().getCountry());
        this.countryName = ShelfFeeds.GLOBAL.equals(country) ? ""
                : new Locale("", country.toUpperCase(Locale.ROOT)).getDisplayCountry();
        this.dir = new File(this.ctx.getCacheDir(), "shelf");
        this.continueWatching = ctx.getString(R.string.continue_watching);
        this.upNext = ctx.getString(R.string.up_next);
        this.newEpisodes = ctx.getString(R.string.new_episodes);
        this.watchlist = ctx.getString(R.string.watchlist);
        // Full-screen art at 1080p takes 8 MB a picture: on a TV with little memory, 720p and fewer of them.
        android.app.ActivityManager am = ctx.getSystemService(android.app.ActivityManager.class);
        android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
        if (am != null) am.getMemoryInfo(mi);
        hd = am != null && !am.isLowRamDevice() && mi.totalMem >= 1536L << 20;
        BACKDROP_W = hd ? 1920 : 1280;
        BACKDROP_H = hd ? 1080 : 720;
        // Decoded pictures beyond those pinned (see pin: what may show next): ones that showed lately, so going
        // back a little further needs no decoding. About one full-screen picture and a few logos. Hardware bitmaps
        // aren't on the Java heap, but they are memory all the same.
        int budget = hd ? 12 << 20 : 5 << 20;
        bitmaps = new LruCache<String, Bitmap>(budget) {
            @Override
            protected int sizeOf(String key, Bitmap b) {
                return b.getWidth() * b.getHeight() * (b.getConfig() == Bitmap.Config.RGB_565 ? 2 : 4);
            }
        };
    }

    /** The app's suggested titles, its own first, or null if none (yet) or it shows something else. */
    List<ShelfFeeds.Item> items(String pkg) {
        // An app set to anything but Suggestions shows only that, as on tvOS.
        return choices.get(pkg).suggests() ? titles(pkg) : null;
    }

    /** The app's suggested titles whatever its top shelf shows, or null if none (yet): for Home Settings' preview. */
    List<ShelfFeeds.Item> titles(String pkg) {
        List<ShelfFeeds.Item> l = combined.get(pkg);
        if (l == null) {
            l = ShelfFeeds.combine(ownItems.get(pkg), feedItems.get(pkg), ShelfFeeds.MAX_ITEMS);
            combined.put(pkg, l);
        }
        return l.isEmpty() ? null : l;
    }

    private final Map<String, List<ShelfFeeds.Item>> combined = new HashMap<>();

    /** The app's link for a feed's titles, from config.xml's top_shelf_links, or null. See {@link ShelfAction}. */
    String template(String pkg) {
        return links.get(pkg);
    }

    String caption(String pkg) {
        ShelfFeeds.Feed f = feeds.get(pkg);
        return f == null ? null : ShelfFeeds.caption(f, country, countryName);
    }

    /** What the app's top shelf shows. */
    ShelfChoice choice(String pkg) {
        return choices.get(pkg);
    }

    ShelfChoices choices() {
        return choices;
    }

    /**
     * The app's top shelf choice changed (Home Settings): its titles and cover are made again for it, and its feed
     * fetched if it now suggests titles and hasn't lately. Whoever shows it asks again.
     */
    void choiceChanged(String pkg) {
        combined.remove(pkg);
        // Its covers go (one being made for the old choice is dropped when it's done: see madeFor).
        java.util.Iterator<String> it = covers.keySet().iterator();
        while (it.hasNext()) {
            String key = it.next();
            if (!AppOrder.packageOf(key).equals(pkg)) continue;
            it.remove();
            madeFor.remove(key);
            artKeep.forget(key);
        }
        if (choices.get(pkg).suggests() && feeds.containsKey(pkg)) lastFetch = 0;
    }

    /** All apps' choice changed (Home Settings): every app's titles and cover are made again. */
    void allChoicesChanged() {
        combined.clear();
        covers.clear();
        madeFor.clear();
        artKeep.forgetAll();
        lastFetch = 0;
    }

    /** The dock's apps changed: fetches their feeds if they're new and stale. */
    void setWanted(Collection<String> pkgs) {
        boolean added = !wanted.containsAll(pkgs);
        wanted.clear();
        wanted.addAll(pkgs);
        if (added) {
            lastFetch = 0;
            refresh();
        }
    }

    /**
     * Reads what the apps published on the TV and the feeds saved on it, then fetches the feeds that are stale.
     * Once; after the first frame.
     */
    void start() {
        if (started) return;
        started = true;
        readOwn();
        if (feeds.isEmpty()) {
            afterReads();
            return;
        }
        final List<ShelfFeeds.Feed> all = new ArrayList<>(feeds.values());
        WORK.execute(() -> {
            final Map<String, ShelfFeeds.Cached> saved = new HashMap<>();
            for (ShelfFeeds.Feed f : all) {
                ShelfFeeds.Cached c = ShelfFeeds.decode(readText(feedFile(f.pkg)));
                if (c != null) saved.put(f.pkg, c);
            }
            main.post(() -> {
                for (Map.Entry<String, ShelfFeeds.Cached> e : saved.entrySet()) {
                    if (feedItems.containsKey(e.getKey())) continue;
                    feedItems.put(e.getKey(), e.getValue().items);
                    combined.remove(e.getKey());
                    fetchedAt.put(e.getKey(), e.getValue().fetchedAt);
                    fetchedFor.put(e.getKey(), e.getValue().country);
                    listener.onItems(e.getKey());
                }
                refresh();
            });
        });
        afterReads();
    }

    /** Once what start() reads has arrived (one worker, in order; then the main thread, in order). */
    private void afterReads() {
        WORK.execute(() -> main.post(() -> {
            titlesRead = true;
            List<Deferred> waiting = new ArrayList<>(deferred);
            deferred.clear();
            for (Deferred d : waiting) {
                // Artworks only for those asked for by name: the dock's others are decoded as they may show.
                if (d.done != null || preloads(d.app)) makeCover(d.app, d.done);
            }
            listener.onTitlesRead();
        }));
    }

    /**
     * Home is showing again: reads what the apps published since (a quick local query), and fetches the feeds that
     * are stale or for another country.
     */
    void refresh() {
        if (!started) return;
        checkImages();
        if (System.currentTimeMillis() - lastOwnRead > OWN_READ_MS) readOwn();
        if (fetching > 0) return;
        long now = System.currentTimeMillis();
        if (now - lastFetch < RETRY_MS) return;
        List<ShelfFeeds.Feed> stale = new ArrayList<>();
        for (ShelfFeeds.Feed f : feeds.values()) {
            if (!wanted.contains(f.pkg) || !choices.get(f.pkg).suggests()) continue;
            Long at = fetchedAt.get(f.pkg);
            boolean otherCountry = f.byCountry() && !country.equals(fetchedFor.get(f.pkg));
            if (otherCountry || !ShelfFeeds.fresh(at == null ? 0 : at, now, FEED_TTL_MS)) stale.add(f);
        }
        if (stale.isEmpty()) return;
        lastFetch = now;
        for (final ShelfFeeds.Feed f : stale) {
            fetching++;
            NET.execute(() -> {
                final List<ShelfFeeds.Item> got = fetchFeed(f);
                final long at = System.currentTimeMillis();
                if (got != null && !got.isEmpty()) {
                    writeText(feedFile(f.pkg), ShelfFeeds.encode(at, country, got));
                    // The first couple of titles' pictures, on disk for when the app is focused.
                    for (int i = 0; i < Math.min(2, got.size()); i++) {
                        download(got.get(i).backdrop);
                        if (got.get(i).logo != null) download(got.get(i).logo);
                    }
                }
                main.post(() -> {
                    fetching--;
                    if (got == null || got.isEmpty()) return;
                    feedItems.put(f.pkg, got);
                    combined.remove(f.pkg);
                    fetchedAt.put(f.pkg, at);
                    fetchedFor.put(f.pkg, country);
                    listener.onItems(f.pkg);
                });
            });
        }
    }

    /** What the apps publish is read again when Home shows at most this often. */
    private static final long OWN_READ_MS = 60_000L;

    /**
     * Reads the titles the apps published on the TV (Watch Next first: what you were watching; then their preview
     * channels, a title from each in turn), in the background. Needs READ_TV_LISTINGS: without it TvProvider
     * returns only the launcher's own rows, which is none, and the shelf goes on to the feeds and cover art.
     */
    private void readOwn() {
        lastOwnRead = System.currentTimeMillis();
        if (ctx.checkSelfPermission(READ_TV_LISTINGS) != PackageManager.PERMISSION_GRANTED) return;
        final ContentResolver cr = ctx.getContentResolver();
        WORK.execute(() -> {
            final Map<String, List<ShelfFeeds.Item>> got = new HashMap<>();
            try {
                Map<Long, String> channels = new HashMap<>();
                Set<Long> continuing = new HashSet<>(), saving = new HashSet<>();
                try (Cursor c = cr.query(Uri.parse("content://android.media.tv/channel"),
                        new String[]{"_id", "display_name", "internal_provider_id"}, null, null, null)) {
                    while (c != null && c.moveToNext()) {
                        channels.put(c.getLong(0), c.getString(1));
                        String id = c.getString(2), name = c.getString(1);
                        String both = ((id == null ? "" : id) + " " + (name == null ? "" : name)).toLowerCase(Locale.ROOT);
                        if (both.contains("continue")) {
                            // A channel of what you were watching (Stremio's "Continue Watching").
                            continuing.add(c.getLong(0));
                        } else if (both.contains("watchlist") || both.contains("watch list") || both.contains("library")
                                || both.contains("my list") || both.contains("watch later")) {
                            // One of what you saved: a watchlist, Stremio's "Library - Movies", Netflix's "My List".
                            saving.add(c.getLong(0));
                        }
                    }
                }
                // Watch Next, most recently watched first: carrying on (continue, the next episode) is something you
                // were watching; new episodes and watchlist entries are things you saved.
                List<Row> watchNext = readPrograms(cr, "watch_next_program");
                watchNext.sort((a, b) -> Long.compare(b.engaged, a.engaged));
                for (Row r : watchNext) {
                    boolean carryOn = r.watchNextType == WATCH_NEXT_CONTINUE || r.watchNextType == WATCH_NEXT_NEXT;
                    String caption = r.watchNextType == WATCH_NEXT_NEXT ? upNext
                            : r.watchNextType == WATCH_NEXT_NEW ? newEpisodes
                            : r.watchNextType == WATCH_NEXT_WATCHLIST ? watchlist : continueWatching;
                    own(got, r.pkg).add(new ShelfFeeds.Item(null, r.title, r.backdrop, r.logo, caption, null, r.link,
                            carryOn, !carryOn));
                }
                // Then the preview channels, in the order the app made them, a title from each in turn.
                Map<String, Map<Long, List<ShelfFeeds.Item>>> preview = new HashMap<>();
                // A preview programme you've started (it has a position) or in a "continue" channel counts as
                // something you were watching, too.
                List<Row> previews = readPrograms(cr, "preview_program");
                previews.sort((a, b) -> Long.compare(b.position > 0 ? 1 : 0, a.position > 0 ? 1 : 0));
                for (Row r : previews) {
                    boolean resume = r.position > 0 || continuing.contains(r.channel);
                    if (resume || saving.contains(r.channel)) {
                        own(got, r.pkg).add(new ShelfFeeds.Item(null, r.title, r.backdrop, r.logo,
                                resume ? continueWatching : channels.get(r.channel), null, r.link, resume, !resume));
                        continue;
                    }
                    Map<Long, List<ShelfFeeds.Item>> byChannel = preview.get(r.pkg);
                    if (byChannel == null) preview.put(r.pkg, byChannel = new java.util.TreeMap<>());
                    List<ShelfFeeds.Item> l = byChannel.get(r.channel);
                    if (l == null) byChannel.put(r.channel, l = new ArrayList<>());
                    l.add(new ShelfFeeds.Item(null, r.title, r.backdrop, r.logo, channels.get(r.channel), null, r.link,
                            false));
                }
                for (Map.Entry<String, Map<Long, List<ShelfFeeds.Item>>> e : preview.entrySet()) {
                    own(got, e.getKey()).addAll(
                            ShelfFeeds.interleave(new ArrayList<>(e.getValue().values()), ShelfFeeds.MAX_ITEMS));
                }
            } catch (RuntimeException e) {
                return;   // TvProvider missing or refusing: no own suggestions
            }
            main.post(() -> {
                Set<String> changed = new HashSet<>(ownItems.keySet());
                changed.addAll(got.keySet());
                ownItems.clear();
                ownItems.putAll(got);
                for (String pkg : changed) {
                    combined.remove(pkg);
                    listener.onItems(pkg);
                }
            });
        });
    }

    static final String READ_TV_LISTINGS = "android.permission.READ_TV_LISTINGS";

    /** The country of the TV's time zone ("KE" for Africa/Nairobi), from the system's own tables; or null. */
    private static String timeZoneRegion() {
        try {
            return android.icu.util.TimeZone.getRegion(java.util.TimeZone.getDefault().getID());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static List<ShelfFeeds.Item> own(Map<String, List<ShelfFeeds.Item>> m, String pkg) {
        List<ShelfFeeds.Item> l = m.get(pkg);
        if (l == null) m.put(pkg, l = new ArrayList<>());
        return l;
    }

    /** A TvProvider programme with a landscape picture. */
    private static final class Row {
        String pkg;
        String title;
        String backdrop;
        String logo;
        String link;
        long channel;
        long engaged;
        long position;
        int watchNextType;
    }

    /** TvContract.WatchNextPrograms' watch_next_type values. */
    private static final int WATCH_NEXT_CONTINUE = 0, WATCH_NEXT_NEXT = 1, WATCH_NEXT_NEW = 2,
            WATCH_NEXT_WATCHLIST = 3;

    /** A programme table's rows with a picture fit for a backdrop. All of them: filtered here, not by a selection. */
    private static List<Row> readPrograms(ContentResolver cr, String table) {
        List<Row> rows = new ArrayList<>();
        // No selection: TvProvider refuses one from apps without ACCESS_ALL_EPG_DATA.
        try (Cursor c = cr.query(Uri.parse("content://android.media.tv/" + table), null, null, null, null)) {
            if (c == null) return rows;
            int pkgCol = c.getColumnIndex("package_name"), titleCol = c.getColumnIndex("title"),
                    posterCol = c.getColumnIndex("poster_art_uri"),
                    posterAr = c.getColumnIndex("poster_art_aspect_ratio"),
                    thumbCol = c.getColumnIndex("thumbnail_uri"), thumbAr = c.getColumnIndex("thumbnail_aspect_ratio"),
                    logoCol = c.getColumnIndex("logo_uri"), channelCol = c.getColumnIndex("channel_id"),
                    engagedCol = c.getColumnIndex("last_engagement_time_utc_millis"),
                    linkCol = c.getColumnIndex("intent_uri"),
                    positionCol = c.getColumnIndex("last_playback_position_millis"),
                    typeCol = c.getColumnIndex("watch_next_type");
            if (pkgCol < 0) return rows;
            while (c.moveToNext()) {
                Row r = new Row();
                r.pkg = c.getString(pkgCol);
                r.backdrop = ShelfFeeds.backdrop(str(c, thumbCol), num(c, thumbAr), str(c, posterCol), num(c, posterAr));
                if (r.pkg == null || r.backdrop == null) continue;
                r.title = str(c, titleCol);
                r.link = str(c, linkCol);
                r.position = positionCol >= 0 && !c.isNull(positionCol) ? c.getLong(positionCol) : 0L;
                r.watchNextType = typeCol >= 0 && !c.isNull(typeCol) ? c.getInt(typeCol) : WATCH_NEXT_CONTINUE;
                r.logo = str(c, logoCol);
                if (r.logo != null && !r.logo.startsWith("https://")) r.logo = null;
                r.channel = channelCol >= 0 && !c.isNull(channelCol) ? c.getLong(channelCol) : 0L;
                r.engaged = engagedCol >= 0 && !c.isNull(engagedCol) ? c.getLong(engagedCol) : 0L;
                rows.add(r);
            }
        }
        return rows;
    }

    private static String str(Cursor c, int col) {
        return col < 0 || c.isNull(col) ? null : c.getString(col);
    }

    private static int num(Cursor c, int col) {
        return col < 0 || c.isNull(col) ? 0 : c.getInt(col);
    }

    /** A feed's titles: its catalogues' first few, interleaved; the global lists if the country's are empty. */
    private List<ShelfFeeds.Item> fetchFeed(ShelfFeeds.Feed f) {
        List<ShelfFeeds.Item> out = fetchAll(f, country);
        if ((out == null || out.isEmpty()) && f.byCountry() && !ShelfFeeds.GLOBAL.equals(country)) {
            out = fetchAll(f, ShelfFeeds.GLOBAL);
        }
        return out;
    }

    private List<ShelfFeeds.Item> fetchAll(ShelfFeeds.Feed f, String c) {
        List<List<ShelfFeeds.Item>> lists = new ArrayList<>();
        for (String u : f.urls) {
            try {
                lists.add(fetchCatalogue(ShelfFeeds.url(u, c)));
            } catch (IOException | RuntimeException e) {
                lists.add(new ArrayList<>());
            }
        }
        return ShelfFeeds.interleave(lists, ShelfFeeds.MAX_ITEMS);
    }

    /** A Stremio catalogue's first titles ({"metas": [{id, name, background, logo}, ...]}); stops reading there. */
    private static List<ShelfFeeds.Item> fetchCatalogue(String url) throws IOException {
        HttpURLConnection c = open(url);
        try (InputStream in = c.getInputStream();
             JsonReader r = new JsonReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            List<ShelfFeeds.Item> out = new ArrayList<>();
            r.beginObject();
            while (r.hasNext()) {
                if (!"metas".equals(r.nextName()) || r.peek() != JsonToken.BEGIN_ARRAY) {
                    r.skipValue();
                    continue;
                }
                r.beginArray();
                // Read a few more than are kept: some have no backdrop.
                while (r.hasNext() && out.size() < ShelfFeeds.MAX_ITEMS + 4) {
                    String id = null, name = null, bg = null, logo = null;
                    r.beginObject();
                    while (r.hasNext()) {
                        String k = r.nextName();
                        if (r.peek() != JsonToken.STRING) {
                            r.skipValue();
                            continue;
                        }
                        String v = r.nextString();
                        switch (k) {
                            case "id": id = v; break;
                            case "name": name = v; break;
                            case "background": bg = v; break;
                            case "logo": logo = v; break;
                            default: break;
                        }
                    }
                    r.endObject();
                    if (bg != null && bg.startsWith("https://")) {
                        out.add(new ShelfFeeds.Item(id, name, bg,
                                logo != null && logo.startsWith("https://") ? logo : null, null,
                                url.contains("/catalog/series/") ? "series" : "movie", null, false));
                    }
                }
                return out;   // the rest isn't needed: closing stops the download
            }
            return out;
        } finally {
            c.disconnect();
        }
    }

    // ---- pictures ------------------------------------------------------------------------------------------

    /** A picture already decoded, or null. */
    Bitmap cached(String url) {
        if (url == null) return null;
        Bitmap b = pinned.get(url);
        return b != null ? b : bitmaps.get(url);
    }

    /**
     * The pictures to keep decoded whatever else loads (what may show next); others drop back into the cache.
     */
    void pin(Collection<String> urls) {
        pins.clear();
        pins.addAll(urls);
        // The new pins out of the cache first: the ones let go, put back into it, could push them out.
        for (String u : pins) {
            Bitmap b = bitmaps.remove(u);
            if (b != null) pinned.put(u, b);
        }
        java.util.Iterator<Map.Entry<String, Bitmap>> it = pinned.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Bitmap> e = it.next();
            if (pins.contains(e.getKey())) continue;
            bitmaps.put(e.getKey(), e.getValue());
            it.remove();
        }
    }

    /** Whether loading {@code url} failed lately (and won't be tried again for a while). */
    boolean failedLately(String url) {
        Long at = failed.get(url);
        return at != null && System.currentTimeMillis() - at < RETRY_MS;
    }

    /**
     * Loads a backdrop or logo: from memory, else the TV's cache, else the network, decoded off the main thread.
     * {@code done} gets null when it can't be had.
     */
    void load(final String url, final boolean backdrop, Done<Bitmap> done) {
        Bitmap b = cached(url);
        if (b != null || url == null || failedLately(url)) {
            if (done != null) done.done(b);
            return;
        }
        List<Done<Bitmap>> waiting = loading.get(url);
        if (waiting != null) {
            if (done != null) waiting.add(done);
            return;
        }
        waiting = new ArrayList<>();
        if (done != null) waiting.add(done);
        loading.put(url, waiting);
        if (!url.startsWith("https://")) {
            // A picture an app serves itself (content:// or android.resource://): read it each time, no copy.
            final ContentResolver cr = ctx.getContentResolver();
            WORK.execute(() -> {
                Bitmap got = null;
                try {
                    got = decodeLocal(cr, Uri.parse(url), backdrop);
                } catch (IOException | RuntimeException e) {
                    // The app doesn't share it (Plex's): no picture.
                }
                final Bitmap result = got;
                main.post(() -> finished(url, result));
            });
            return;
        }
        final File f = imageFile(url);
        Runnable decode = () -> {
            Bitmap got = null;
            try {
                if (f.exists()) {
                    f.setLastModified(System.currentTimeMillis());
                    got = decode(f, backdrop);
                }
            } catch (IOException | RuntimeException e) {
                f.delete();   // a broken download: fetch it again next time
            }
            final Bitmap result = got;
            main.post(() -> finished(url, result));
        };
        if (f.exists()) {
            WORK.execute(decode);
        } else {
            NET.execute(() -> {
                if (download(url)) WORK.execute(decode);
                else main.post(() -> finished(url, null));
            });
        }
    }

    private void finished(String url, Bitmap b) {
        if (b != null) {
            if (pins.contains(url)) pinned.put(url, b);
            else bitmaps.put(url, b);
        } else {
            failed.put(url, System.currentTimeMillis());
        }
        List<Done<Bitmap>> waiting = loading.remove(url);
        if (waiting == null) return;
        for (Done<Bitmap> d : waiting) d.done(b);
    }

    /** Downloads {@code url} (or a better version of it) into the TV's cache unless it's there; any thread. */
    private boolean download(String url) {
        if (url == null || !url.startsWith("https://")) return false;
        if (imageFile(url).exists()) return true;
        for (String from : ShelfFeeds.candidates(url, hd)) {
            if (download(from, imageFile(url))) return true;
        }
        return false;
    }

    private boolean download(String url, File f) {
        HttpURLConnection c = null;
        File tmp = new File(dir, f.getName() + ".part");
        try {
            c = open(url);
            if (c.getContentLengthLong() > MAX_IMAGE_BYTES) return false;
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[16 << 10];
                long total = 0;
                for (int n; (n = in.read(buf)) > 0; ) {
                    total += n;
                    if (total > MAX_IMAGE_BYTES) throw new IOException("too large");
                    out.write(buf, 0, n);
                }
            }
            if (!tmp.renameTo(f)) return false;
            prune();
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        } finally {
            tmp.delete();
            if (c != null) c.disconnect();
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(CONNECT_TIMEOUT_MS);
        c.setReadTimeout(READ_TIMEOUT_MS);
        c.setInstanceFollowRedirects(true);
        c.setUseCaches(false);
        c.setRequestProperty("User-Agent", "tvhome");
        int code = c.getResponseCode();
        if (code / 100 != 2) {
            c.disconnect();
            throw new IOException("HTTP " + code);
        }
        return c;
    }

    /** Keeps the image cache under its cap, dropping the longest unused first. Network thread. */
    private void prune() {
        File[] files = dir.listFiles((d, name) -> name.startsWith("img-") && !name.endsWith(".part"));
        if (files == null) return;
        long total = 0;
        for (File f : files) total += f.length();
        if (total <= DISK_CAP_BYTES) return;
        Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (File f : files) {
            if (total <= DISK_CAP_BYTES * 3 / 4) break;
            total -= f.length();
            f.delete();
        }
    }

    /**
     * Decodes a backdrop to cover {@link #BACKDROP_W} x {@link #BACKDROP_H}, or a logo to fit {@link #LOGO_W} x
     * {@link #LOGO_H}: on Android 9+ straight into graphics memory (a hardware bitmap: nothing to upload when it's
     * first drawn), else in 16 bits for backdrops.
     */
    private static Bitmap decode(File f, final boolean backdrop) throws IOException {
        if (Build.VERSION.SDK_INT >= 28) return decode(ImageDecoder.createSource(f), backdrop);
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        if (backdrop && (o.outWidth < MIN_BACKDROP_W || o.outHeight < MIN_BACKDROP_H)) {
            throw new IOException("too small for the screen");
        }
        int sample = 1;
        int tw = backdrop ? BACKDROP_W : LOGO_W, th = backdrop ? BACKDROP_H : LOGO_H;
        while (o.outWidth / (sample * 2) >= tw && o.outHeight / (sample * 2) >= th) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        if (backdrop) o.inPreferredConfig = Bitmap.Config.RGB_565;
        Bitmap b = BitmapFactory.decodeFile(f.getPath(), o);
        if (b == null) throw new IOException("undecodable");
        return backdrop ? finishBackdrop(b, true, sample) : b;
    }

    private static Bitmap decodeLocal(ContentResolver cr, Uri uri, final boolean backdrop) throws IOException {
        if (Build.VERSION.SDK_INT >= 28) return decode(ImageDecoder.createSource(cr, uri), backdrop);
        try (InputStream in = cr.openInputStream(uri)) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            if (backdrop) o.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap b = BitmapFactory.decodeStream(in, null, o);
            if (b == null) throw new IOException("undecodable");
            if (backdrop && (b.getWidth() < MIN_BACKDROP_W || b.getHeight() < MIN_BACKDROP_H)) {
                b.recycle();
                throw new IOException("too small for the screen");
            }
            return backdrop ? finishBackdrop(b, true, 1f) : b;
        }
    }

    /**
     * Android 9+: a logo straight into graphics memory; a backdrop first in memory the CPU can read, to cut off
     * black bars baked into it (a widescreen video's thumbnail), then moved there.
     */
    @android.annotation.TargetApi(28)
    private static Bitmap decode(ImageDecoder.Source source, final boolean backdrop) throws IOException {
        final int[] size = new int[2];
        Bitmap b = ImageDecoder.decodeBitmap(source, (decoder, info, src) -> {
            int w = info.getSize().getWidth(), h = info.getSize().getHeight();
            size[0] = w;
            size[1] = h;
            float s = backdrop ? Math.max(BACKDROP_W / (float) w, BACKDROP_H / (float) h)
                    : Math.min(LOGO_W / (float) w, LOGO_H / (float) h);
            if (s < 1f) decoder.setTargetSize(Math.max(1, Math.round(w * s)), Math.max(1, Math.round(h * s)));
            decoder.setAllocator(backdrop ? ImageDecoder.ALLOCATOR_SOFTWARE : ImageDecoder.ALLOCATOR_HARDWARE);
        });
        if (backdrop && (size[0] < MIN_BACKDROP_W || size[1] < MIN_BACKDROP_H)) {
            b.recycle();
            throw new IOException("too small for the screen: " + size[0] + "x" + size[1]);
        }
        return backdrop ? hardware(finishBackdrop(b, true, size[1] / (float) b.getHeight())) : b;
    }

    /**
     * A decoded backdrop as the screen shows it: without black bars, and with the shelf's scrim (darker at the
     * bottom, under the dock and the title; for a title, darker on the left too) drawn into it here, once, rather
     * than over it in every frame: on a TV's GPU each full-screen layer costs a few milliseconds a frame.
     */
    static Bitmap finishBackdrop(Bitmap src, boolean content, float sourceScale) throws IOException {
        int w = src.getWidth(), h = src.getHeight();
        int[] bars = bars(src);
        int cw = w - bars[2] - bars[3], ch = h - bars[0] - bars[1];
        // With its bars cut off (a widescreen film's top and bottom, an old 4:3 video's sides), the picture must still
        // be big enough for the screen, in the source's own pixels ({@code sourceScale}: per decoded pixel).
        if (content && (cw * sourceScale < MIN_BACKDROP_W || ch * sourceScale < MIN_BACKDROP_H)) {
            src.recycle();
            throw new IOException("too small once its bars are cut off");
        }
        Bitmap out = Bitmap.createBitmap(cw, ch, src.getConfig() == Bitmap.Config.RGB_565
                ? Bitmap.Config.RGB_565 : Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        c.drawBitmap(src, new android.graphics.Rect(bars[2], bars[0], w - bars[3], h - bars[1]),
                new android.graphics.Rect(0, 0, cw, ch), new Paint(Paint.FILTER_BITMAP_FLAG));
        src.recycle();
        scrim(c, cw, ch, content ? 1f : 0.55f, content);
        return out;
    }

    /**
     * The shelf's scrim over a w x h picture, placed for the part a 16:9 screen shows of it (scaled to fill and
     * centred): the bottom from a third of the way down, the left over 70 % of the width.
     */
    static void scrim(Canvas c, int w, int h, float strength, boolean left) {
        float vw = Math.min(w, h * 16f / 9f), vh = Math.min(h, w * 9f / 16f);
        float vx = (w - vw) / 2f, vy = (h - vh) / 2f;
        Paint p = new Paint();
        p.setShader(new android.graphics.LinearGradient(0, vy + vh * 0.35f, 0, vy + vh,
                new int[]{0x00000000, alpha(0x8C, strength), alpha(0xD9, strength)}, new float[]{0f, 0.55f, 1f},
                Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        if (!left) return;
        p.setShader(new android.graphics.LinearGradient(vx, 0, vx + vw * 0.7f, 0,
                new int[]{alpha(0xC2, strength), alpha(0x80, strength), 0x00000000}, new float[]{0f, 0.45f, 1f},
                Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
    }

    private static int alpha(int a, float strength) {
        return Math.round(a * strength) << 24;
    }

    /** Black bars around the picture ({top, bottom, left, right}, in pixels), if it has them. */
    static int[] bars(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        int[] row = new int[w], col = new int[h];
        int top = 0, bottom = 0, left = 0, right = 0;
        while (top < h / 3 && blackRow(b, top, row)) top++;
        while (bottom < h / 3 && blackRow(b, h - 1 - bottom, row)) bottom++;
        // A sliver isn't a bar; and leave a picture that's mostly black (a night scene) alone.
        if (top + bottom < h / 25 || top + bottom > h / 2) top = bottom = 0;
        while (left < w / 3 && blackColumn(b, left, col)) left++;
        while (right < w / 3 && blackColumn(b, w - 1 - right, col)) right++;
        if (left + right < w / 25 || left + right > w / 2) left = right = 0;
        return new int[]{top, bottom, left, right};
    }

    private static boolean blackColumn(Bitmap b, int x, int[] col) {
        b.getPixels(col, 0, 1, x, 0, 1, col.length);
        for (int y = 0; y < col.length; y += 4) {
            int p = col[y];
            if (((p >> 16) & 0xFF) > 24 || ((p >> 8) & 0xFF) > 24 || (p & 0xFF) > 24) return false;
        }
        return true;
    }

    private static boolean blackRow(Bitmap b, int y, int[] row) {
        b.getPixels(row, 0, row.length, 0, y, row.length, 1);
        for (int x = 0; x < row.length; x += 4) {
            int p = row[x];
            if (((p >> 16) & 0xFF) > 24 || ((p >> 8) & 0xFF) > 24 || (p & 0xFF) > 24) return false;
        }
        return true;
    }

    // ---- cover art -----------------------------------------------------------------------------------------

    /** The app's cover art if made already for its top shelf choice, or null. */
    Cover cover(String key) {
        Cover c = covers.get(key);
        return c != null && choices.get(AppOrder.packageOf(key)).equals(madeFor.get(key)) ? c : null;
    }

    /**
     * Whether the app's cover may be its artwork: it has one, its choice allows it, and, with suggestions, it has no
     * titles to show (known once they're read). An app showing titles falls back on its cover art while one loads, as
     * before: a full-screen artwork decoded for each dock app (8 MB a picture) would be memory spent on what hardly
     * shows.
     */
    boolean artworkWanted(String pkg) {
        ShelfChoice c = choices.get(pkg);
        return ShelfArt.has(pkg) && c.triesArtwork() && (!c.suggests() || (titlesRead && items(pkg) == null));
    }

    /** Whether a dock app's cover is made with the dock, ahead of focus: see {@link ShelfArtKeep#preload}. */
    boolean preloads(App app) {
        return artKeep.preload(app.key, artworkWanted(app.pkg), choices.get(app.pkg).kind == ShelfChoice.Kind.IMAGE);
    }

    /**
     * Makes the app's cover art in the background, unless it has it: the picture its top shelf choice shows (none
     * for the glow), made for whether it has titles now. {@code done} gets it (null on failure, or for the glow).
     */
    void makeCover(final App app, final Done<Cover> done) {
        if (!titlesRead && ShelfArt.has(app.pkg) && choices.get(app.pkg).suggests()) {
            deferred.add(new Deferred(app, done));
            return;
        }
        final boolean art = artworkWanted(app.pkg);
        Cover c = cover(app.key);
        Boolean artFor = coverForArt.get(app.key);
        if (c != null && (c.image || artFor == null || artFor == art)) {
            if (done != null) done.done(c);
            return;
        }
        final ShelfChoice choice = choices.get(app.pkg);
        // Made again for titles that came or went: it shows meanwhile, and the new one changes in place.
        final boolean again = c != null;
        if (!again) {
            covers.remove(app.key);   // one made for another choice
            madeFor.remove(app.key);
        }
        if (!choice.picture()) {
            if (done != null) done.done(null);
            return;
        }
        List<Done<Cover>> waiting = coversLoading.get(app.key);
        if (waiting != null) {
            if (done != null) waiting.add(done);
            return;
        }
        final List<Done<Cover>> waiters = new ArrayList<>();
        if (done != null) waiters.add(done);
        coversLoading.put(app.key, waiters);
        coverApps.put(app.key, app);
        final ShelfFeeds.LogoSpec spec = logos.get(app.pkg);
        final File svgFile = spec == null ? null : new File(dir, "logo-" + spec.slug + ".svg");
        final String imageUrl = imageUrl(app.pkg, choice);
        final int gen = coverGen;
        WORK.execute(() -> {
            final Cover result = buildCover(app, choice, art, imageUrl, spec, svgFile);
            main.post(() -> {
                if (coversLoading.get(app.key) == waiters) coversLoading.remove(app.key);
                if (!choice.equals(choices.get(app.pkg))) {
                    // The choice changed meanwhile: make the new one for whoever's waiting.
                    for (Done<Cover> d : waiters) makeCover(app, d);
                    return;
                }
                // Not kept if the apps changed meanwhile (forgetCovers): it may be from an old banner.
                boolean keep = gen == coverGen;
                if (result != null && keep) artKeep.made(app.key, result.image);
                // An artwork that may no longer show (focus moved on, Home went away) isn't kept.
                if (result != null && keep && artKeep.store(app.key, result.artwork)) {
                    covers.put(app.key, result);
                    madeFor.put(app.key, choice);
                    coverForArt.put(app.key, art);
                    if (again) listener.onCover(app.key);
                }
                for (Done<Cover> d : waiters) d.done(result);
                if (!keep || (result != null && result.image)) return;
                if (imageUrl != null) fetchImage(app, imageUrl);
                if (result != null && svgFile != null && !svgFile.exists()) fetchLogo(app, spec, svgFile);
            });
        });
    }

    /**
     * Home Settings' preview: the picture the app's top shelf would show with {@code choice} (null for the glow),
     * from what's on the TV, without keeping it.
     */
    void previewCover(final App app, final ShelfChoice choice, final Done<Cover> done) {
        if (!choice.picture()) {
            done.done(null);
            return;
        }
        final ShelfFeeds.LogoSpec spec = logos.get(app.pkg);
        final File svgFile = spec == null ? null : new File(dir, "logo-" + spec.slug + ".svg");
        final String imageUrl = imageUrl(app.pkg, choice);
        // With suggestions, its artwork shows only if it has no titles, as on Home.
        final boolean art = ShelfArt.has(app.pkg) && choice.triesArtwork() && (!choice.suggests() || titles(app.pkg) == null);
        WORK.execute(() -> {
            final Cover result = buildCover(app, choice, art, imageUrl, spec, svgFile);
            main.post(() -> done.done(result));
        });
    }

    /** Where a static image may come from: config.xml's URL, or "" for one put on the TV only; null for no image. */
    private String imageUrl(String pkg, ShelfChoice choice) {
        if (choice.kind != ShelfChoice.Kind.IMAGE) return null;
        String url = images.get(pkg);
        return url != null ? url : "";
    }

    /**
     * The picture for the app's choice: for its static image, the one on the TV; then, if {@code art} (see
     * {@link #artworkWanted}), its artwork; else its cover art, but for a static image, which then has none (its colour
     * and name show); or null. Worker thread.
     */
    private Cover buildCover(App app, ShelfChoice choice, boolean art, String imageUrl, ShelfFeeds.LogoSpec spec,
                             File svgFile) {
        File f = imageUrl == null ? null : staticImageFile(app.pkg, imageUrl);
        Cover made = imageCover(f);
        try {
            if (made == null && art) made = artwork(app);
            if (made == null && choice.fallsBackToCoverArt()) made = renderCover(ctx, app);
            // The app's vector logo, if it's on the TV already, drawn sharp in place of the banner's.
            if (made != null && !made.image && svgFile != null && svgFile.exists()) {
                made = withLogo(ctx, made, app, spec, svgFile);
            }
        } catch (RuntimeException | OutOfMemoryError e) {
            return null;   // a banner that can't be drawn: the glow it is
        }
        if (made == null || imageUrl == null) return made;
        // Which picture it was made from (or failed to be), so a new one put on the TV is noticed (checkImages).
        return new Cover(made.backdrop, made.logo, made.image, stamp(f), made.artwork);
    }

    /**
     * The app's artwork (ShelfArt) and its logo cut from its banner, as for its cover art; or null if it has none
     * or it couldn't be painted. Painting takes a moment, so it's done once per install of the launcher and kept on
     * the TV, then read back like a static image. Worker thread.
     */
    private Cover artwork(App app) {
        String key = ShelfArtworks.key(app.pkg);
        if (key == null) return null;
        try {
            int w = BACKDROP_W, h = BACKDROP_H;
            File f = artworkFile(key, w, h);
            Bitmap art = null;
            long start = Perf.now();
            if (f.isFile()) {
                try {
                    art = decodeArtwork(f);
                    Perf.log("shelf_artwork", start, "pkg=" + app.pkg + " from=cache");
                } catch (IOException e) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
            if (art == null) {
                start = Perf.now();
                int[] px = ShelfArt.render(app.pkg, w, h);
                if (px == null) return null;
                art = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
                Perf.log("shelf_artwork", start, "pkg=" + app.pkg + " from=paint size=" + w + "x" + h);
                saveArtwork(art, f, key);
            }
            // Its logo: the vector one, if it's on the TV, is drawn over this in buildCover; else the banner's.
            ShelfFeeds.LogoSpec spec = logos.get(app.pkg);
            boolean vector = spec != null && new File(dir, "logo-" + spec.slug + ".svg").exists();
            return new Cover(hardware(art), vector ? null : bannerLogo(ctx, app), false, null, true);
        } catch (RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    /** Where an artwork is kept, per size and per install of the launcher (a new one may paint it differently). */
    private File artworkFile(String key, int w, int h) {
        if (installStamp == null) {
            long t = 0L;
            try {
                t = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).lastUpdateTime;
            } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
            }
            installStamp = Long.toString(t, 36);
        }
        return new File(dir, "artwork-" + key + "-" + w + "x" + h + "-" + installStamp + ".jpg");
    }

    private volatile String installStamp;

    private static Bitmap decodeArtwork(File f) throws IOException {
        if (Build.VERSION.SDK_INT >= 28) return ImageDecoder.decodeBitmap(ImageDecoder.createSource(f));
        Bitmap b = BitmapFactory.decodeFile(f.getPath());
        if (b == null) throw new IOException("undecodable");
        return b;
    }

    /** Keeps a painted artwork on the TV, in place of any older one of it. */
    private void saveArtwork(Bitmap art, File f, String key) {
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File tmp = new File(dir, f.getName() + ".part");
        try (OutputStream out = new FileOutputStream(tmp)) {
            if (!art.compress(Bitmap.CompressFormat.JPEG, 95, out)) throw new IOException("not encoded");
        } catch (IOException | RuntimeException e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return;
        }
        if (!tmp.renameTo(f)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return;
        }
        File[] old = dir.listFiles((d, name) -> name.startsWith("artwork-" + key + "-") && !name.equals(f.getName()));
        if (old != null) {
            for (File o : old) {
                //noinspection ResultOfMethodCallIgnored
                o.delete();
            }
        }
    }

    /**
     * Whether the app has a picture of its own on the TV (put there, or fetched from config.xml's URL), which its
     * Picture shows before its artwork. Worker thread.
     */
    boolean hasOwnPicture(String pkg) {
        String url = images.get(pkg);
        return staticImageFile(pkg, url != null ? url : "") != null;
    }

    /**
     * The app's static image on the TV, or null: one put there with tools/top_shelf_image.py, else the one fetched
     * from its URL. Worker thread.
     */
    private File staticImageFile(String pkg, String url) {
        File put = ctx.getExternalFilesDir(IMAGE_DIR);
        if (put != null) {
            for (String type : IMAGE_TYPES) {
                File f = new File(put, pkg + type);
                if (f.isFile()) return f;
            }
        }
        if (url.isEmpty()) return null;
        File f = artFile(url);
        return f.exists() ? f : null;
    }

    /** A static image fetched from its URL: kept, not pruned with the titles' pictures. */
    private File artFile(String url) {
        return new File(dir, "art-" + sha1(url));
    }

    private static String stamp(File f) {
        return f == null ? "" : f.getPath() + " " + f.lastModified() + " " + f.length();
    }

    /** Fetches an app's static image from its URL once, then shows it in place of its cover art. */
    private void fetchImage(final App app, final String url) {
        if (url.isEmpty() || failedLately(url) || !imagesFetching.add(url)) return;
        NET.execute(() -> {
            final File f = artFile(url);
            final boolean ok = f.exists() || download(url, f);
            WORK.execute(() -> {
                final Cover made = ok ? imageCover(f) : null;
                final String stamp = stamp(f);
                main.post(() -> {
                    imagesFetching.remove(url);
                    if (made == null) {
                        failed.put(url, System.currentTimeMillis());
                        return;
                    }
                    if (cover(app.key) == null || choices.get(app.pkg).kind != ShelfChoice.Kind.IMAGE) {
                        return;   // apps or the choice changed meanwhile
                    }
                    covers.put(app.key, new Cover(made.backdrop, null, true, stamp));
                    artKeep.made(app.key, true);
                    listener.onCover(app.key);
                });
            });
        });
    }

    /**
     * Home shows again: if an app's static image was put on the TV, replaced or removed since its cover was made
     * (tools/top_shelf_image.py), makes it again, and it changes in place.
     */
    private void checkImages() {
        final Map<String, String> made = new HashMap<>();
        final Map<String, String> pkgs = new HashMap<>();
        final Map<String, String> urls = new HashMap<>();
        for (Map.Entry<String, Cover> e : covers.entrySet()) {
            App a = coverApps.get(e.getKey());
            if (a != null && e.getValue().stamp != null && !coversLoading.containsKey(e.getKey())) {
                made.put(e.getKey(), e.getValue().stamp);
                pkgs.put(e.getKey(), a.pkg);
                urls.put(a.pkg, imageUrl(a.pkg, ShelfChoice.IMAGE));
            }
        }
        if (made.isEmpty()) return;
        WORK.execute(() -> {
            final List<String> changed = new ArrayList<>();
            for (Map.Entry<String, String> e : made.entrySet()) {
                String pkg = pkgs.get(e.getKey());
                if (!stamp(staticImageFile(pkg, urls.get(pkg))).equals(e.getValue())) changed.add(e.getKey());
            }
            if (!changed.isEmpty()) main.post(() -> {
                for (String key : changed) remakeCover(key);
            });
        });
    }

    /** Makes the app's cover again for its choice (a new static image, or its sharp logo arrived); it changes in place. */
    private void remakeCover(final String key) {
        final App app = coverApps.get(key);
        if (app == null || cover(key) == null || coversLoading.containsKey(key)) return;
        final List<Done<Cover>> waiters = new ArrayList<>();
        coversLoading.put(key, waiters);
        final ShelfChoice choice = choices.get(app.pkg);
        final ShelfFeeds.LogoSpec spec = logos.get(app.pkg);
        final File svgFile = spec == null ? null : new File(dir, "logo-" + spec.slug + ".svg");
        final String imageUrl = imageUrl(app.pkg, choice);
        final boolean art = artworkWanted(app.pkg);
        final int gen = coverGen;
        WORK.execute(() -> {
            final Cover result = buildCover(app, choice, art, imageUrl, spec, svgFile);
            main.post(() -> {
                if (coversLoading.get(key) == waiters) coversLoading.remove(key);
                if (!choice.equals(choices.get(app.pkg))) {
                    // The choice changed meanwhile: whoever's waiting gets the new one.
                    for (Done<Cover> d : waiters) makeCover(app, d);
                    return;
                }
                boolean fresh = gen == coverGen;
                if (result != null && fresh) artKeep.made(key, result.image);
                boolean stored = result != null && fresh && artKeep.store(key, result.artwork);
                if (stored) {
                    // Dropped meanwhile (choiceChanged, the choice back as it was): it's the cover all the same.
                    covers.put(key, result);
                    madeFor.put(key, choice);
                    coverForArt.put(key, art);
                }
                for (Done<Cover> d : waiters) d.done(cover(key));
                if (stored) listener.onCover(key);
            });
        });
    }

    /** A static image as the app's cover, or null if there's none or it can't be decoded. Worker thread. */
    private static Cover imageCover(File f) {
        if (f == null) return null;
        try {
            return new Cover(hardware(placeImage(decodeImage(f))), null, true, null);
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    /** A static image decoded no larger than it's drawn (placeImage), in memory the CPU can read. */
    private static Bitmap decodeImage(File f) throws IOException {
        if (Build.VERSION.SDK_INT >= 28) {
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(f), (decoder, info, src) -> {
                int w = info.getSize().getWidth(), h = info.getSize().getHeight();
                float s = imageScale(w, h);
                if (s < 1f) decoder.setTargetSize(Math.max(1, Math.round(w * s)), Math.max(1, Math.round(h * s)));
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            });
        }
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        float s = imageScale(o.outWidth, o.outHeight);
        int sample = 1;
        while (s * sample * 2 <= 1f) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        Bitmap b = BitmapFactory.decodeFile(f.getPath(), o);
        if (b == null) throw new IOException("undecodable");
        return b;
    }

    /** How much placeImage scales a w x h image. */
    private static float imageScale(int w, int h) {
        return w <= 0 || h <= 0 ? 1f : Math.max(BACKDROP_W / (float) w, BACKDROP_H * IMAGE_AREA / h);
    }

    /** On tvOS a static image fills the screen down to the dock's glass, 785 of its 1080 points (measured). */
    static final float IMAGE_AREA = 785f / 1080f;

    /**
     * A static image as tvOS shows it: from the top of the screen, scaled to fill the width and to reach the dock
     * (a wider one, an app's 2320 x 720 Top Shelf Image Wide, loses its sides), with nothing drawn over it. Below
     * that, behind the dock, its bottom edge carries on mirrored and blurred, as on tvOS. A near-black ground goes
     * true black; a picture without one stays as it is.
     */
    static Bitmap placeImage(Bitmap src) {
        int sw = BACKDROP_W, sh = BACKDROP_H, w = src.getWidth(), h = src.getHeight();
        Bitmap out = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        float s = imageScale(w, h);
        // The part of the image across the screen, in its own pixels.
        int vw = Math.min(w, Math.round(sw / s)), vx = (w - vw) / 2;
        int top = Math.min(sh, Math.round(h * s));
        c.drawBitmap(src, new android.graphics.Rect(vx, 0, vx + vw, Math.min(h, Math.round(top / s))),
                new android.graphics.Rect(0, 0, sw, top), p);
        if (top < sh) {
            // The image's bottom, as tall as what's left of the screen, flipped and shrunk to a sixteenth in halves
            // (each a 2 x 2 average), then drawn back up to size: a soft blur.
            int rest = sh - top, strip = Math.max(1, Math.min(h, Math.round(rest / s)));
            int bw = Math.max(1, sw / 2), bh = Math.max(1, rest / 2);
            Bitmap small = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            Canvas sc = new Canvas(small);
            sc.scale(1f, -1f, 0f, bh / 2f);
            sc.drawBitmap(src, new android.graphics.Rect(vx, h - strip, vx + vw, h),
                    new android.graphics.Rect(0, 0, bw, bh), p);
            for (int i = 0; i < 3 && bw > 1 && bh > 1; i++) {
                Bitmap half = Bitmap.createScaledBitmap(small, Math.max(1, bw / 2), Math.max(1, bh / 2), true);
                small.recycle();
                small = half;
                bw = small.getWidth();
                bh = small.getHeight();
            }
            c.drawBitmap(small, null, new android.graphics.Rect(0, top, sw, sh), p);
            small.recycle();
        }
        src.recycle();
        // A dark ground (tvOS's #242424 behind YouTube's) turns true black, for a mini-LED or OLED TV.
        long start = Perf.now();
        int knee = groundTop(out, top);
        if (knee >= 0) toe(out, knee, TrueBlack.toeEnd(knee));
        Perf.log("shelf_image_black", start, "knee=" + knee);
        return out;
    }

    /** Rows of a picture read at a time: a strip, not the whole picture, in memory at once. */
    private static final int STRIP_PIXELS = 1 << 16;

    /** The top of the near-black ground of a picture's first {@code rows} rows (TrueBlack.groundTop), or -1. */
    static int groundTop(Bitmap b, int rows) {
        int w = b.getWidth();
        int[] px = new int[w], hist = new int[256];
        rows = Math.min(rows, b.getHeight());
        // Every fourth row says as much about its ground as all of them.
        for (int y = 0; y < rows; y += 4) {
            b.getPixels(px, 0, w, 0, y, w, 1);
            TrueBlack.count(px, w, hist);
        }
        return TrueBlack.groundTop(hist);
    }

    /** A mutable picture with everything at or under {@code knee} black, eased into up to {@code end} (TrueBlack.toe). */
    static void toe(Bitmap b, int knee, int end) {
        int w = b.getWidth(), h = b.getHeight(), strip = Math.max(1, STRIP_PIXELS / Math.max(1, w));
        int[] px = new int[w * strip];
        for (int y = 0; y < h; y += strip) {
            int n = Math.min(strip, h - y);
            b.getPixels(px, 0, w, 0, y, w, n);
            TrueBlack.toe(px, w * n, knee, end);
            b.setPixels(px, 0, w, 0, y, w, n);
        }
    }

    /** Fetches an app's vector logo once, then redraws its cover with it. */
    private void fetchLogo(final App app, final ShelfFeeds.LogoSpec spec, final File svgFile) {
        if (failedLately("logo:" + spec.slug)) return;
        NET.execute(() -> {
            boolean ok = false;
            try {
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
                ok = download(String.format(Locale.ROOT, ICON_URL, spec.slug), svgFile)
                        && ShelfFeeds.iconPath(readText(svgFile)) != null;
                if (!ok) svgFile.delete();
            } catch (RuntimeException e) {
                svgFile.delete();
            }
            if (!ok) {
                main.post(() -> failed.put("logo:" + spec.slug, System.currentTimeMillis()));
                return;
            }
            // Its cover made again for its choice, now with the sharp logo (not over a static image: it has its own).
            // An artwork is read back from the TV, not painted again.
            main.post(() -> {
                Cover now = covers.get(app.key);
                if (now != null && !now.image) remakeCover(app.key);
            });
        });
    }

    /**
     * The cover with the app's vector logo in place of the one cut from its banner: the mark in the brand's colour
     * (white where that's too dark for the cover), and the app's name beside it unless the mark is a wordmark,
     * drawn at the size it shows, so it's sharp.
     */
    private static Cover withLogo(Context ctx, Cover cover, App app, ShelfFeeds.LogoSpec spec, File svgFile) {
        String d = ShelfFeeds.iconPath(readText(svgFile));
        if (d == null) return cover;
        android.graphics.Path path;
        try {
            path = androidx.core.graphics.PathParser.createPathFromPathData(d);
        } catch (RuntimeException e) {
            return cover;
        }
        if (path == null) return cover;
        float markH = 150f;
        android.graphics.RectF bounds = new android.graphics.RectF();
        path.computeBounds(bounds, true);
        float s = markH / 24f;
        if (spec.wordmark && bounds.width() > 0f && bounds.height() > 0f) {
            // A wordmark is the whole logo: as large as it fits, a wide flat one (F1's) as wide as a long name.
            s = Math.min(160f / bounds.height(), 440f / bounds.width());
            markH = bounds.height() * s;
        }
        float markW = Math.max(1f, bounds.width() * s);
        int color = spec.color;
        float[] hsv = new float[3];
        AmbientColor.rgbToHsv((color >> 16) & 0xFF, (color >> 8) & 0xFF, color & 0xFF, hsv);
        if (hsv[2] < 0.35f) color = 0xFFF2F2F2;
        android.text.TextPaint text = null;
        float textW = 0f, gap = 0f;
        String label = app.label;
        if (!spec.wordmark && label != null && !label.isEmpty()) {
            text = new android.text.TextPaint(Paint.ANTI_ALIAS_FLAG);
            try {
                text.setTypeface(ctx.getResources().getFont(R.font.display_medium));
            } catch (RuntimeException ignored) {
            }
            text.setColor(0xFFFFFFFF);
            text.setTextSize(markH * 0.66f);
            text.setLetterSpacing(-0.01f);
            textW = text.measureText(label);
            gap = markH * 0.24f;
        }
        int w = Math.round(markW + gap + textW) + 4, h = Math.round(markH) + 4;
        Bitmap lockup = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(lockup);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        android.graphics.Matrix m = new android.graphics.Matrix();
        m.setScale(s, s);
        m.postTranslate(2 - bounds.left * s, 2 - bounds.top * s + (markH - bounds.height() * s) / 2f);
        path.transform(m);
        c.drawPath(path, p);
        if (text != null) {
            Paint.FontMetrics fm = text.getFontMetrics();
            float baseline = 2 + markH / 2f - (fm.ascent + fm.descent) / 2f;
            c.drawText(label, 2 + markW + gap, baseline, text);
        }
        return new Cover(cover.backdrop, hardware(lockup), cover.image, cover.stamp, cover.artwork);
    }

    /** Apps changed: their banners may have too. Covers being made now aren't kept (coverGen). */
    void forgetCovers() {
        covers.clear();
        madeFor.clear();
        coverApps.clear();
        coversLoading.clear();
        coverGen++;
        coverForArt.clear();
        deferred.clear();
        artKeep.forgetAll();
    }

    /**
     * Keeps decoded only the artworks of {@code keys} (what shows, and what may show next: see TopShelf.keep); the
     * others are full-screen pictures, read back from the TV's copy when wanted again.
     */
    void keepArtwork(Collection<String> keys) {
        artKeep.keep(keys);
        Iterator<Map.Entry<String, Cover>> it = covers.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Cover> e = it.next();
            if (e.getValue().artwork && !keys.contains(e.getKey())) {
                it.remove();
                madeFor.remove(e.getKey());
                coverForArt.remove(e.getKey());
            }
        }
    }

    private static Cover renderCover(Context ctx, App app) {
        Banner banner = banner(ctx, app);
        if (banner == null) return null;
        int[] palette = CoverArt.palette(banner.px, 3);
        Bitmap logo = banner.logo();

        Bitmap back = Bitmap.createBitmap(COVER_W, COVER_H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(back);
        c.drawColor(CoverArt.GROUND);
        int n = 5;
        int[] colours = CoverArt.blobColours(palette, n);
        float[][] blobs = CoverArt.blobs(app.pkg.hashCode(), n);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        for (int i = 0; i < n; i++) {
            float cx = blobs[i][0] * COVER_W, cy = blobs[i][1] * COVER_H, r = blobs[i][2] * COVER_W;
            int col = colours[i] & 0x00FFFFFF;
            p.setShader(new RadialGradient(cx, cy, r, new int[]{0xC7000000 | col, 0x52000000 | col, col},
                    new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, r, p);
        }
        scrim(c, COVER_W, COVER_H, 0.55f, false);
        // The blobs' last breath over the black goes, so the black stays black.
        toe(back, TrueBlack.FAINT_KNEE, TrueBlack.FAINT_END);
        return new Cover(hardware(back), logo == null ? null : hardware(logo));
    }

    /** The app's logo cut out of its banner, as on its cover art, or null. Worker thread. */
    private static Bitmap bannerLogo(Context ctx, App app) {
        Banner banner = banner(ctx, app);
        Bitmap logo = banner == null ? null : banner.logo();
        return logo == null ? null : hardware(logo);
    }

    /** An app's banner (or icon), drawn. */
    private static final class Banner {
        final Drawable d;
        final int[] px;
        final int w, h;

        Banner(Drawable d, int[] px, int w, int h) {
            this.d = d;
            this.px = px;
            this.w = w;
            this.h = h;
        }

        /** Its logo without the background, or null. */
        Bitmap logo() {
            // An adaptive banner (Stremio's) has its logo on its own layer: no background to cut away.
            int[] logoPx = d instanceof AdaptiveIconDrawable && ((AdaptiveIconDrawable) d).getForeground() != null
                    ? draw(((AdaptiveIconDrawable) d).getForeground(), w, h, true) : px;
            CoverArt.Logo cut = CoverArt.cutOut(logoPx, w, h);
            return cut == null ? null : Bitmap.createBitmap(cut.argb, cut.w, cut.h, Bitmap.Config.ARGB_8888);
        }
    }

    private static Banner banner(Context ctx, App app) {
        Drawable d = null;
        if (app.info != null && app.bannerRes != 0) {
            // The banner's sharpest version: TV banners are often 320 x 180, which a logo the size of a third of the
            // screen would blur.
            try {
                PackageManager pm = ctx.getPackageManager();
                d = pm.getResourcesForApplication(app.info)
                        .getDrawableForDensity(app.bannerRes, DisplayMetrics.DENSITY_XXXHIGH, null);
            } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
            }
        }
        if (d == null) d = app.drawable;
        if (d == null) return null;
        int w, h;
        if (d instanceof BitmapDrawable && ((BitmapDrawable) d).getBitmap() != null) {
            w = ((BitmapDrawable) d).getBitmap().getWidth();
            h = ((BitmapDrawable) d).getBitmap().getHeight();
        } else {
            w = d.getIntrinsicWidth();
            h = d.getIntrinsicHeight();
            if (w <= 0 || h <= 0) {
                w = 640;
                h = 360;
            } else if (w < 640) {
                h = Math.round(h * 640f / w);
                w = 640;
            }
        }
        if (w > 960) {
            h = Math.round(h * 960f / w);
            w = 960;
        }
        w = Math.max(16, w);
        h = Math.max(9, h);
        return new Banner(d, draw(d, w, h, false), w, h);
    }

    /** {@code d} drawn at w x h; a layer of an adaptive drawable spreads past its bounds as the whole would. */
    private static int[] draw(Drawable d, int w, int h, boolean adaptiveLayer) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        if (adaptiveLayer) {
            int dx = Math.round(w * AdaptiveIconDrawable.getExtraInsetFraction());
            int dy = Math.round(h * AdaptiveIconDrawable.getExtraInsetFraction());
            d.setBounds(-dx, -dy, w + dx, h + dy);
        } else {
            d.setBounds(0, 0, w, h);
        }
        d.draw(c);
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        b.recycle();
        return px;
    }

    private static Bitmap hardware(Bitmap b) {
        if (Build.VERSION.SDK_INT < 28 || b.getConfig() == Bitmap.Config.HARDWARE) return b;
        Bitmap hw = b.copy(Bitmap.Config.HARDWARE, false);
        if (hw == null) return b;
        b.recycle();
        return hw;
    }

    // ---- memory --------------------------------------------------------------------------------------------

    /** Home is hidden or memory is short: keeps only the pictures on screen ({@code keep}). */
    void trim(Collection<Bitmap> keep) {
        Map<String, Bitmap> snapshot = bitmaps.snapshot();
        for (Map.Entry<String, Bitmap> e : snapshot.entrySet()) {
            if (!keep.contains(e.getValue())) bitmaps.remove(e.getKey());
        }
        pinned.values().removeIf(b -> !keep.contains(b));
        // Static images and artworks are full-screen pictures too: made again from the TV's copy when Home shows. An
        // artwork still being painted isn't kept either.
        artKeep.none();
        Iterator<Map.Entry<String, Cover>> it = covers.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Cover> e = it.next();
            if ((e.getValue().image || e.getValue().artwork) && !keep.contains(e.getValue().backdrop)) {
                it.remove();
                madeFor.remove(e.getKey());
                coverForArt.remove(e.getKey());
            }
        }
    }

    // ---- files ---------------------------------------------------------------------------------------------

    private File feedFile(String pkg) {
        return new File(dir, "feed-" + pkg + ".txt");
    }

    /** Per quality: a 1080p TV doesn't reuse the 720p picture it may have saved before. */
    private File imageFile(String url) {
        return new File(dir, "img-" + sha1(hd ? url + "|hd" : url));
    }

    private static String sha1(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format(Locale.ROOT, "%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    private static String readText(File f) {
        if (!f.exists()) return null;
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } catch (IOException e) {
            return null;
        }
    }

    private void writeText(File f, String text) {
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File tmp = new File(dir, f.getName() + ".part");
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            tmp.delete();
            return;
        }
        if (!tmp.renameTo(f)) tmp.delete();
    }

    private static ExecutorService worker(final String name) {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(() -> {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                r.run();
            }, name);
            t.setDaemon(true);
            return t;
        });
    }
}
