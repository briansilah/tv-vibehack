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

    /** An app's cover art: a soft backdrop in its colours and its logo (null if it couldn't be cut out). */
    static final class Cover {
        final Bitmap backdrop;
        final Bitmap logo;

        Cover(Bitmap backdrop, Bitmap logo) {
            this.backdrop = backdrop;
            this.logo = logo;
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
    private final String country;
    private final String countryName;
    private final File dir;
    /** Titles from the feeds, and those the apps published on the TV, by package. */
    private final Map<String, List<ShelfFeeds.Item>> feedItems = new HashMap<>();
    private final Map<String, List<ShelfFeeds.Item>> ownItems = new HashMap<>();
    private final String continueWatching;
    private long lastOwnRead;
    private final Map<String, Long> fetchedAt = new HashMap<>();
    private final Map<String, String> fetchedFor = new HashMap<>();
    private final Map<String, Cover> covers = new HashMap<>();
    private final Set<String> coversLoading = new HashSet<>();
    private final Map<String, List<Done<Bitmap>>> loading = new HashMap<>();
    /** URLs that failed, and when: not retried for {@link #RETRY_MS}. */
    private final Map<String, Long> failed = new HashMap<>();
    private final LruCache<String, Bitmap> bitmaps;
    private boolean started;
    /** The dock's apps: only their feeds are fetched. */
    private final Set<String> wanted = new HashSet<>();
    private long lastFetch;
    private int fetching;

    ShelfSource(Context ctx, String[] feedLines, String[] linkLines, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.listener = listener;
        this.feeds = ShelfFeeds.parse(feedLines);
        this.links = ShelfFeeds.parseLinks(linkLines);
        Locale locale = Locale.getDefault();
        this.country = ShelfFeeds.country(locale.getCountry());
        this.countryName = ShelfFeeds.GLOBAL.equals(country) ? "" : locale.getDisplayCountry();
        this.dir = new File(this.ctx.getCacheDir(), "shelf");
        this.continueWatching = ctx.getString(R.string.continue_watching);
        // Full-screen art at 1080p takes 8 MB a picture: on a TV with little memory, 720p and fewer of them.
        android.app.ActivityManager am = ctx.getSystemService(android.app.ActivityManager.class);
        android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
        if (am != null) am.getMemoryInfo(mi);
        hd = am != null && !am.isLowRamDevice() && mi.totalMem >= 1536L << 20;
        BACKDROP_W = hd ? 1920 : 1280;
        BACKDROP_H = hd ? 1080 : 720;
        // Decoded pictures: the dock apps' first backdrops and logos and the next one along. Hardware bitmaps
        // aren't on the Java heap, but they are memory all the same.
        int budget = hd ? 44 << 20 : 18 << 20;
        bitmaps = new LruCache<String, Bitmap>(budget) {
            @Override
            protected int sizeOf(String key, Bitmap b) {
                return b.getWidth() * b.getHeight() * (b.getConfig() == Bitmap.Config.RGB_565 ? 2 : 4);
            }
        };
    }

    /** The app's suggested titles, its own first, or null if none (yet). */
    List<ShelfFeeds.Item> items(String pkg) {
        List<ShelfFeeds.Item> l = combined.get(pkg);
        if (l == null) {
            l = ShelfFeeds.combine(ownItems.get(pkg), feedItems.get(pkg), ShelfFeeds.MAX_ITEMS);
            combined.put(pkg, l);
        }
        return l.isEmpty() ? null : l;
    }

    private final Map<String, List<ShelfFeeds.Item>> combined = new HashMap<>();

    /** What opens a title in its app: the link the app published, else one from config.xml's top_shelf_links. */
    String link(String pkg, ShelfFeeds.Item it) {
        return it.link != null ? it.link : ShelfFeeds.link(links.get(pkg), it);
    }

    String caption(String pkg) {
        ShelfFeeds.Feed f = feeds.get(pkg);
        return f == null ? null : ShelfFeeds.caption(f, country, countryName);
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
        if (feeds.isEmpty()) return;
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
    }

    /**
     * Home is showing again: reads what the apps published since (a quick local query), and fetches the feeds that
     * are stale or for another country.
     */
    void refresh() {
        if (!started) return;
        if (System.currentTimeMillis() - lastOwnRead > OWN_READ_MS) readOwn();
        if (fetching > 0) return;
        long now = System.currentTimeMillis();
        if (now - lastFetch < RETRY_MS) return;
        List<ShelfFeeds.Feed> stale = new ArrayList<>();
        for (ShelfFeeds.Feed f : feeds.values()) {
            if (!wanted.contains(f.pkg)) continue;
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
                Set<Long> continuing = new HashSet<>();
                try (Cursor c = cr.query(Uri.parse("content://android.media.tv/channel"),
                        new String[]{"_id", "display_name", "internal_provider_id"}, null, null, null)) {
                    while (c != null && c.moveToNext()) {
                        channels.put(c.getLong(0), c.getString(1));
                        // A channel of what you were watching (Stremio's "Continue Watching").
                        String id = c.getString(2), name = c.getString(1);
                        if ((id != null && id.toLowerCase(Locale.ROOT).contains("continue"))
                                || (name != null && name.toLowerCase(Locale.ROOT).startsWith("continue"))) {
                            continuing.add(c.getLong(0));
                        }
                    }
                }
                // Watch Next, most recently watched first.
                List<Row> watchNext = readPrograms(cr, "watch_next_program");
                watchNext.sort((a, b) -> Long.compare(b.engaged, a.engaged));
                for (Row r : watchNext) {
                    own(got, r.pkg).add(new ShelfFeeds.Item(null, r.title, r.backdrop, r.logo, continueWatching,
                            null, r.link, true));
                }
                // Then the preview channels, in the order the app made them, a title from each in turn.
                Map<String, Map<Long, List<ShelfFeeds.Item>>> preview = new HashMap<>();
                // A preview programme you've started (it has a position) or in a "continue" channel counts as
                // something you were watching, too.
                List<Row> previews = readPrograms(cr, "preview_program");
                previews.sort((a, b) -> Long.compare(b.position > 0 ? 1 : 0, a.position > 0 ? 1 : 0));
                for (Row r : previews) {
                    boolean resume = r.position > 0 || continuing.contains(r.channel);
                    if (resume) {
                        own(got, r.pkg).add(new ShelfFeeds.Item(null, r.title, r.backdrop, r.logo, continueWatching,
                                null, r.link, true));
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
    }

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
                    positionCol = c.getColumnIndex("last_playback_position_millis");
            if (pkgCol < 0) return rows;
            while (c.moveToNext()) {
                Row r = new Row();
                r.pkg = c.getString(pkgCol);
                r.backdrop = ShelfFeeds.backdrop(str(c, thumbCol), num(c, thumbAr), str(c, posterCol), num(c, posterAr));
                if (r.pkg == null || r.backdrop == null) continue;
                r.title = str(c, titleCol);
                r.link = str(c, linkCol);
                r.position = positionCol >= 0 && !c.isNull(positionCol) ? c.getLong(positionCol) : 0L;
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
        return url == null ? null : bitmaps.get(url);
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
        if (b != null) bitmaps.put(url, b);
        else failed.put(url, System.currentTimeMillis());
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
        int sample = 1;
        int tw = backdrop ? BACKDROP_W : LOGO_W, th = backdrop ? BACKDROP_H : LOGO_H;
        while (o.outWidth / (sample * 2) >= tw && o.outHeight / (sample * 2) >= th) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        if (backdrop) o.inPreferredConfig = Bitmap.Config.RGB_565;
        Bitmap b = BitmapFactory.decodeFile(f.getPath(), o);
        if (b == null) throw new IOException("undecodable");
        return b;
    }

    private static Bitmap decodeLocal(ContentResolver cr, Uri uri, final boolean backdrop) throws IOException {
        if (Build.VERSION.SDK_INT >= 28) return decode(ImageDecoder.createSource(cr, uri), backdrop);
        try (InputStream in = cr.openInputStream(uri)) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            if (backdrop) o.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap b = BitmapFactory.decodeStream(in, null, o);
            if (b == null) throw new IOException("undecodable");
            return b;
        }
    }

    /**
     * Android 9+: a logo straight into graphics memory; a backdrop first in memory the CPU can read, to cut off
     * black bars baked into it (a widescreen video's thumbnail), then moved there.
     */
    @android.annotation.TargetApi(28)
    private static Bitmap decode(ImageDecoder.Source source, final boolean backdrop) throws IOException {
        Bitmap b = ImageDecoder.decodeBitmap(source, (decoder, info, src) -> {
            int w = info.getSize().getWidth(), h = info.getSize().getHeight();
            float s = backdrop ? Math.max(BACKDROP_W / (float) w, BACKDROP_H / (float) h)
                    : Math.min(LOGO_W / (float) w, LOGO_H / (float) h);
            if (s < 1f) decoder.setTargetSize(Math.max(1, Math.round(w * s)), Math.max(1, Math.round(h * s)));
            decoder.setAllocator(backdrop ? ImageDecoder.ALLOCATOR_SOFTWARE : ImageDecoder.ALLOCATOR_HARDWARE);
        });
        return backdrop ? hardware(trimBars(b)) : b;
    }

    /** The picture without black bars across its top and bottom, if it has them. */
    static Bitmap trimBars(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        int[] row = new int[w];
        int top = 0, bottom = 0;
        while (top < h / 3 && blackRow(b, top, row)) top++;
        while (bottom < h / 3 && blackRow(b, h - 1 - bottom, row)) bottom++;
        // A sliver isn't a bar; and leave a picture that's mostly black (a night scene) alone.
        if (top + bottom < h / 25 || top + bottom > h / 2) return b;
        Bitmap cut = Bitmap.createBitmap(b, 0, top, w, h - top - bottom);
        if (cut != b) b.recycle();
        return cut;
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

    /** The app's cover art if made already, or null. */
    Cover cover(String key) {
        return covers.get(key);
    }

    /** Makes the app's cover art in the background, unless it has it; {@code done} gets it (null on failure). */
    void makeCover(final App app, final Done<Cover> done) {
        Cover c = covers.get(app.key);
        if (c != null || !coversLoading.add(app.key)) {
            if (c != null && done != null) done.done(c);
            return;
        }
        WORK.execute(() -> {
            Cover made = null;
            try {
                made = renderCover(ctx, app);
            } catch (RuntimeException | OutOfMemoryError e) {
                // A banner that can't be drawn: the glow it is.
            }
            final Cover result = made;
            main.post(() -> {
                coversLoading.remove(app.key);
                if (result != null) covers.put(app.key, result);
                if (done != null) done.done(result);
            });
        });
    }

    /** Apps changed: their banners may have too. */
    void forgetCovers() {
        covers.clear();
    }

    private static Cover renderCover(Context ctx, App app) {
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
        int[] px = draw(d, w, h, false);
        int[] palette = CoverArt.palette(px, 3);
        // An adaptive banner (Stremio's) has its logo on its own layer: no background to cut away.
        int[] logoPx = d instanceof AdaptiveIconDrawable && ((AdaptiveIconDrawable) d).getForeground() != null
                ? draw(((AdaptiveIconDrawable) d).getForeground(), w, h, true) : px;
        CoverArt.Logo cut = CoverArt.cutOut(logoPx, w, h);
        Bitmap logo = cut == null ? null : Bitmap.createBitmap(cut.argb, cut.w, cut.h, Bitmap.Config.ARGB_8888);

        Bitmap back = Bitmap.createBitmap(COVER_W, COVER_H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(back);
        c.drawColor(CoverArt.base(palette));
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
        return new Cover(hardware(back), logo == null ? null : hardware(logo));
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
        if (Build.VERSION.SDK_INT < 28) return b;
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
