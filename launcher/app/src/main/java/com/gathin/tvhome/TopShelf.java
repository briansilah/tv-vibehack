package com.gathin.tvhome;

import android.content.ComponentCallbacks2;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.view.KeyEvent;
import android.view.View;
import android.widget.TextView;
import android.os.Handler;
import android.os.Looper;
import android.view.animation.DecelerateInterpolator;

import java.util.ArrayList;
import java.util.List;

/**
 * The top shelf's art for the focused dock app, after tvOS's Top Shelf: the best it has, in this order.
 * <ol>
 *   <li><b>Suggested content</b>, each title a full-screen backdrop with its logo or name, a few seconds each: what
 *   the app itself publishes on the TV (what you were watching, its recommendations), then its feed's titles
 *   (config.xml, top_shelf_feeds; e.g. Netflix's Top 10 in the TV's country). See {@link ShelfSource}.</li>
 *   <li><b>Cover art</b>: the app's own logo, cut out of its banner, on soft shapes in its colours, as tvOS shows an
 *   app's static Top Shelf image.</li>
 *   <li>Neither (yet): the ambient glow and the app's name, which MainActivity draws as before.</li>
 * </ol>
 * The art follows focus at once: a dock move cross-fades to what's already decoded and fetches the rest in the
 * background, fading it in if focus is still there when it arrives. Main thread.
 */
final class TopShelf implements ShelfSource.Listener {

    interface Host {
        /** Whether the shelf shows its own title or logo for this app now, so the app's name should hide. */
        void onShelfArt(String key, boolean ownTitle);

        /** Whether the art fills the screen, so the glow under it needn't be drawn. */
        void onShelfCovered(boolean covered);

        /** The shelf's button was pressed: open this in its app (Home moves away as for a tile). */
        void onShelfAction(Intent intent);

        /** Focus leaves the shelf's button: Up to the top right corner, else back to the dock. */
        void onShelfExit(boolean up);
    }

    private static final int NONE = 0, COVER = 1, CONTENT = 2;
    /** A dock move; matching the glow's blend. */
    private static final long SWAP_MS = 200L;
    /** Coming to the dock, and leaving it. */
    private static final long SHOW_MS = 250L;
    private static final long HIDE_MS = 200L;
    /** Art that arrives after focus did. */
    private static final long ARRIVE_MS = 300L;
    /**
     * The carousel: each title stays at least this long, so it can be taken in, before the next one pushes it out
     * (see TopShelfView.slide).
     */
    private static final long CAROUSEL_MS = 10_000L;
    private static final long CAROUSEL_SLIDE_MS = 1400L;
    private static final long INFO_OUT_MS = 70L;
    private static final long INFO_IN_MS = 150L;
    private static final DecelerateInterpolator DECELERATE = new DecelerateInterpolator(1.5f);

    private final TopShelfView art;
    private final ShelfInfoView info;
    /** The button under a suggested title, with focus after Up from the dock. */
    private final TextView action;
    /** Its ▶, shown only when OK plays (Play, Resume); taken from the layout when first labelled. */
    private android.graphics.drawable.Drawable playIcon;
    /** Focus is on the button. */
    private boolean acting;
    /** How far the title is lifted to make room for the button, in pixels, and how far the page has moved. */
    private float lift;
    private float pageTravel;
    private android.animation.ValueAnimator liftAnim;
    private final Host host;
    /** Null when the top shelf art is turned off (config.xml, top_shelf_art). */
    private final ShelfSource source;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<ShelfSource.App> dock = new ArrayList<>();
    private ShelfSource.App current;
    private boolean shown;
    private boolean started;
    private boolean resumed;
    /** What's on screen, or fading in. */
    private int tier = NONE;
    private String showingKey;
    private ShelfFeeds.Item showingItem;
    private boolean showingOwnTitle;
    private int infoMode = ShelfInfoView.CONTENT;
    /**
     * The focused app's titles while focus is on it, in the order they first showed: titles read again meanwhile
     * keep their places (see ShelfOrder.merge). Focus coming to an app starts again at the top of its newest list:
     * what you were watching most recently. Null: none (yet).
     */
    private List<ShelfFeeds.Item> titles;
    /**
     * Where in {@link #titles} the shelf is: the title showing, or the one Left, Right or the carousel moved to
     * whose picture is still on its way (it slides in when it arrives); -1 for none.
     */
    private int at = -1;
    /** The way the last move went (1: right, -1: left), when it began, and what made it: for passing over failed
     * pictures the same way, and for the log. */
    private int moveDir = 1;
    private long moveSince;
    private String moveHow = "carousel";
    /** The dock app focus was last on, so the pictures nearest it are decoded first. */
    private int dockAt;
    private final ShelfOrder.Failed failed = this::failed;
    private final Runnable advance = this::advance;
    private final Runnable preloadDock = this::preloadDock;
    private final Runnable keepAgain = this::keep;

    /** {@code enabled}: config.xml's top_shelf_art; {@code feeds}: its top_shelf_feeds. */
    TopShelf(android.content.Context ctx, TopShelfView art, ShelfInfoView info, TextView action, Host host,
             boolean enabled, String[] feeds, String[] links, String[] logos) {
        this.art = art;
        this.info = info;
        this.action = action;
        this.host = host;
        this.source = enabled ? new ShelfSource(ctx, feeds, links, logos, this) : null;
        action.setOnClickListener(v -> act());
        action.setOnKeyListener(this::onActionKey);
        // Grows from its left edge, which stays in line with the title above it.
        action.setPivotX(0f);
        action.setOnFocusChangeListener((v, focused) -> {
            // Not focusable once left, but only after focus has gone: dropping it while focused would hand focus
            // to the first tile on the screen.
            if (!focused && !acting) v.setFocusable(false);
            v.animate().scaleX(focused ? 1.08f : 1f).scaleY(focused ? 1.08f : 1f)
                    .translationZ(focused ? 10 * density() : 0f).setDuration(focused ? 150 : 120)
                    .setInterpolator(DECELERATE).start();
        });
        art.setAlpha(0f);
        info.setAlpha(0f);
    }

    /** After Home's first frame: from now on it may read the disk and the network. */
    void start() {
        if (source == null || started) return;
        started = true;
        source.start();
        preloadDock();
        if (current != null) want(current);
    }

    void resume() {
        resumed = true;
        if (!started) return;
        source.refresh();
        // The pictures dropped while Home was hidden; after Home's first frames.
        handler.removeCallbacks(preloadDock);
        handler.postDelayed(preloadDock, 400);
        if (shown && tier == CONTENT) scheduleCarousel();
    }

    void pause() {
        resumed = false;
        handler.removeCallbacks(advance);
        handler.removeCallbacks(preloadDock);
        handler.removeCallbacks(keepAgain);
    }

    /** The apps changed (installed, updated): their banners, so their covers, may have too. */
    void appsChanged() {
        if (source != null) source.forgetCovers();
    }

    /** The dock's apps: after the app list (re)loaded, or apps moved in or out of the dock. */
    void setDock(List<ShelfSource.App> apps) {
        dock.clear();
        dock.addAll(apps);
        if (source == null) return;
        List<String> pkgs = new ArrayList<>();
        for (ShelfSource.App a : apps) pkgs.add(a.pkg);
        source.setWanted(pkgs);
        if (started) preloadDock();
    }

    /** Whether the shelf shows its own title or logo for this app now, so the app's name should hide. */
    boolean ownTitle(String key) {
        return shown && key != null && key.equals(showingKey) && showingOwnTitle;
    }

    /**
     * Where things go with the page at rest: {@code left}, the name's left edge; {@code nameBottom}, where the app's
     * name ends, so a title sits where the name would; {@code dockTop}, the top of the dock's panel.
     */
    void place(float left, float nameBottom, float dockTop) {
        contentLeft = left;
        contentBottom = nameBottom;
        coverBottom = dockTop;
        info.place(left, infoMode == ShelfInfoView.COVER ? dockTop : nameBottom);
    }

    /**
     * Focus is on a dock app; {@code appearing}: it came to the dock from elsewhere. Shows the best art that's ready
     * and starts loading better. Returns whether the shelf shows its own title or logo (the app's name should
     * hide). {@code artDone} runs once the art has faded in, or now if there is none to show yet.
     */
    boolean show(ShelfSource.App app, boolean appearing, Runnable artDone) {
        boolean visit = app != null && (current == null || !current.key.equals(app.key));
        current = app;
        handler.removeCallbacks(advance);
        if (source == null || app == null) {
            if (artDone != null) artDone.run();
            return false;
        }
        // Focus coming to an app starts at the top of its list: what you were watching most recently.
        if (visit) visit(app);
        boolean wasShown = shown;
        shown = true;
        Choice c = choose(app);
        logFallback(app, c);
        if (appearing || !wasShown) {
            art.animate().cancel();
            info.animate().cancel();
            // Hidden: change straight away, then fade the whole shelf in.
            display(c, 0L, null, false);
            art.animate().alpha(1f).setDuration(SHOW_MS).setInterpolator(DECELERATE).start();
            info.animate().alpha(1f).setDuration(SHOW_MS).setInterpolator(DECELERATE).start();
            if (artDone != null) {
                if (c != null) handler.postDelayed(artDone, SHOW_MS);
                else artDone.run();
            }
        } else {
            display(c, SWAP_MS, artDone, false);
        }
        want(app);
        return c != null && c.ownTitle();
    }

    /** The page moved {@code travel} pixels from the dock: the title moves with it. */
    void followPage(float travel) {
        pageTravel = travel;
        info.setTranslationY(-pageTravel - lift);
    }

    /** Whether focus is on the shelf's button. */
    boolean acting() {
        return acting;
    }

    /**
     * Up from the dock: focus goes to the button under the title showing (Resume, Play, Open in the app, Search in
     * the app, or Open the app: see {@link ShelfAction}), and the carousel holds still. Returns false if there's
     * nothing to act on.
     */
    boolean enterActions() {
        if (!shown || tier != CONTENT || showingItem == null || current == null) return false;
        acting = true;
        handler.removeCallbacks(advance);
        // Left and Right go from the title you see, not from one the carousel was about to bring in.
        int i = ShelfOrder.indexOf(titles, showingItem);
        if (i >= 0) at = i;
        keep();
        labelAction();
        float d = density();
        float rowH = 40 * d;
        action.setX(contentLeft);
        action.setY(contentBottom - rowH);
        action.setVisibility(View.VISIBLE);
        action.setFocusable(true);
        action.animate().cancel();
        action.setScaleX(1f);
        action.setScaleY(1f);
        action.animate().alpha(1f).setDuration(SHOW_MS).setInterpolator(DECELERATE).start();
        liftTo(rowH + 18 * d);
        action.requestFocus();
        return true;
    }

    /** Focus left the button (for the dock, the top right corner, or an app): it goes, the title settles back. */
    void leaveActions() {
        if (!acting) return;
        acting = false;
        if (!action.isFocused()) action.setFocusable(false);
        action.animate().cancel();
        action.animate().alpha(0f).setDuration(HIDE_MS).setInterpolator(DECELERATE)
                .withEndAction(() -> {
                    if (!acting) action.setVisibility(View.INVISIBLE);
                }).start();
        liftTo(0f);
        keep();
        if (shown && tier == CONTENT) scheduleCarousel();
    }

    private void liftTo(float to) {
        if (liftAnim != null) liftAnim.cancel();
        liftAnim = android.animation.ValueAnimator.ofFloat(lift, to);
        liftAnim.setDuration(SHOW_MS);
        liftAnim.setInterpolator(DECELERATE);
        liftAnim.addUpdateListener(a -> {
            lift = (float) a.getAnimatedValue();
            info.setTranslationY(-pageTravel - lift);
        });
        liftAnim.start();
    }

    private float density() {
        return action.getResources().getDisplayMetrics().density;
    }

    /**
     * The button says what OK will do with the title showing. Cheap enough to call whenever that title changes
     * while the button is up; OK itself asks again ({@link #act}), so it never opens anything else.
     */
    private void labelAction() {
        if (current == null) return;
        ShelfFeeds.Item it = showingItem;
        ShelfAction a = actionFor(it);
        android.content.res.Resources r = action.getResources();
        String text;
        switch (a.kind) {
            case ShelfAction.RESUME:
                text = r.getString(R.string.shelf_resume);
                break;
            case ShelfAction.PLAY:
                text = r.getString(R.string.shelf_play);
                break;
            case ShelfAction.OPEN:
                text = r.getString(R.string.shelf_open_in, current.label);
                break;
            case ShelfAction.SEARCH:
                text = r.getString(R.string.shelf_search_in, current.label);
                break;
            default:
                text = r.getString(R.string.shelf_open_app, current.label);
                break;
        }
        action.setText(text);
        action.setContentDescription(it == null || it.title.isEmpty() ? text : text + ", " + it.title);
        boolean play = a.plays();
        android.graphics.drawable.Drawable icon = action.getCompoundDrawablesRelative()[0];
        if (playIcon == null) playIcon = icon;
        if ((icon != null) != play) {
            float d = density();
            action.setCompoundDrawablesRelative(play ? playIcon : null, null, null, null);
            // Without the ▶, the text sits in the middle of the pill.
            action.setPaddingRelative(Math.round((play ? 18 : 22) * d), action.getPaddingTop(),
                    action.getPaddingEnd(), action.getPaddingBottom());
        }
    }

    /** What OK does with this title in the focused app, by the links the app has an activity for. */
    private ShelfAction actionFor(ShelfFeeds.Item it) {
        final String pkg = current.pkg;
        final PackageManager pm = action.getContext().getPackageManager();
        return ShelfAction.of(it, source.template(pkg), link -> {
            Intent i = intentFor(link, pkg);
            return i != null && i.resolveActivity(pm) != null;
        });
    }

    private boolean onActionKey(View v, int keyCode, KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return keyCode == KeyEvent.KEYCODE_BACK;
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_BACK:
                host.onShelfExit(false);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                // Focus moves first, then the button goes.
                host.onShelfExit(true);
                leaveActions();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                step(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                step(1);
                return true;
            default:
                return false;
        }
    }

    /**
     * Left and Right on the button page through the app's titles, as swiping does on tvOS's Top Shelf: always to
     * the one next to it, so going back retraces your steps. One whose picture is still on its way slides in when
     * it arrives; pressing on meanwhile moves on from it.
     */
    private void step(int dir) {
        if (current == null || tier != CONTENT || titles == null || at < 0) return;
        int to = ShelfOrder.step(titles, at, dir, failed);
        if (to >= 0) move(to, dir, "step");
    }

    /** Moves to the title at {@code to}: it slides in now if its picture is decoded, else when it arrives. */
    private void move(int to, int dir, String how) {
        at = to;
        moveDir = dir;
        moveSince = Perf.now();
        moveHow = how;
        keep();
        ShelfFeeds.Item it = titles.get(at);
        // Back to the title showing, before the one you'd moved to arrived.
        if (ShelfOrder.same(it, showingItem)) return;
        Choice c = content(current, it);
        if (c != null) {
            slideIn(c);
            return;
        }
        final ShelfSource.App app = current;
        loadItem(it, () -> arrived(app));
    }

    /** Whether a title's picture couldn't be had lately (too small, or no download): it's passed over. */
    private boolean failed(ShelfFeeds.Item it) {
        return source.failedLately(it.backdrop);
    }

    private void slideIn(Choice c) {
        slideTo(c, moveDir);
        logTitle(moveHow, moveSince, current.pkg, titles, at, moveDir);
    }

    /**
     * With logging on: focus came to an app with a title to show, but its picture wasn't decoded, so its cover art
     * (or the glow) shows first and the title replaces it when it arrives.
     */
    private void logFallback(ShelfSource.App app, Choice c) {
        if (!Perf.enabled() || (c != null && c.tier == CONTENT) || titles == null || at < 0) return;
        Perf.log("shelf_fallback", Perf.now(), "pkg=" + app.pkg + " art=" + (c == null ? "glow" : "cover"));
    }

    /**
     * With logging on: a title came on screen, from Left or Right on the button ({@code step}), the carousel, or
     * after the fallback ({@code arrived}): how long after the move, where it is in the list, and its name.
     */
    private static void logTitle(String how, long since, String pkg, List<ShelfFeeds.Item> items, int i, int dir) {
        if (!Perf.enabled()) return;
        String title = items.get(i).title.isEmpty() ? "-" : items.get(i).title.replaceAll("\\s+", "_");
        Perf.log("shelf_" + how, since, "pkg=" + pkg + " i=" + i + " n=" + items.size() + " dir=" + dir
                + " title=" + title);
    }

    /**
     * OK on the button: what {@link ShelfAction} says for the title showing now (not whichever showed when the
     * button was labelled): the title's link, or the app.
     */
    private void act() {
        if (current == null) return;
        ShelfAction a = actionFor(showingItem);
        Intent i = a.link != null ? intentFor(a.link, current.pkg) : null;
        if (i == null) i = action.getContext().getPackageManager().getLeanbackLaunchIntentForPackage(current.pkg);
        if (i == null) return;
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        host.onShelfAction(i);
    }

    /**
     * An intent for an app's link that can only open that app: an "intent:" link (YouTube's) or a URI it handles
     * (Stremio's, Netflix's), held to the app's package, without any permission grants it might carry.
     */
    static Intent intentFor(String link, String pkg) {
        if (link == null) return null;
        Intent i;
        try {
            if (link.startsWith("intent:")) {
                i = Intent.parseUri(link, Intent.URI_INTENT_SCHEME);
                i.setSelector(null);
                if (i.getComponent() != null && !pkg.equals(i.getComponent().getPackageName())) i.setComponent(null);
            } else {
                i = new Intent(Intent.ACTION_VIEW, Uri.parse(link));
            }
        } catch (java.net.URISyntaxException | RuntimeException e) {
            return null;
        }
        i.setPackage(pkg);
        i.setFlags(i.getFlags() & ~(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION));
        return i;
    }

    /** Focus left the dock: the art fades out, kept for coming back. */
    void hide() {
        leaveActions();
        if (!shown) return;
        shown = false;
        current = null;
        titles = null;
        at = -1;
        handler.removeCallbacks(advance);
        art.animate().cancel();
        info.animate().cancel();
        art.animate().alpha(0f).setDuration(HIDE_MS).setInterpolator(DECELERATE).start();
        info.animate().alpha(0f).setDuration(HIDE_MS).setInterpolator(DECELERATE).start();
        keep();
    }

    /** Memory is short, or Home is hidden: keeps only the pictures on screen. */
    void trim(int level) {
        if (source == null || level < ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) return;
        List<Bitmap> keep = new ArrayList<>();
        art.collect(keep);
        if (info.logo() != null) keep.add(info.logo());
        source.trim(keep);
    }

    @Override
    public void onItems(String pkg) {
        if (shown && current != null && current.pkg.equals(pkg)) {
            if (tier == CONTENT && titles != null) {
                // You may be looking through them: the titles you have keep their places, new ones go at the end.
                titles = ShelfOrder.merge(titles, source.items(pkg));
                // The title showing as read again: its link may have changed.
                int i = ShelfOrder.indexOf(titles, showingItem);
                if (i >= 0) showingItem = titles.get(i);
                if (acting) labelAction();
            } else {
                // None of them has shown yet: its newest list, from the top.
                visit(current);
            }
            want(current);
        }
        for (ShelfSource.App a : dock) {
            if (a.pkg.equals(pkg)) {
                preloadDock();
                break;
            }
        }
    }

    @Override
    public void onCover(String key) {
        // Its sharp logo arrived: if the app is showing its cover, the logo changes in place.
        if (shown && current != null && current.key.equals(key) && tier == COVER) {
            Choice c = choose(current);
            if (c != null && c.tier == COVER) display(c, ARRIVE_MS, null, true);
        }
    }

    // ---- choosing ------------------------------------------------------------------------------------------

    private float coverBottom, contentBottom, contentLeft;

    private static final class Choice {
        final int tier;
        final Bitmap backdrop;
        final Bitmap logo;
        final ShelfFeeds.Item item;
        final String caption;
        final Object tag;

        Choice(int tier, Bitmap backdrop, Bitmap logo, ShelfFeeds.Item item, String caption, Object tag) {
            this.tier = tier;
            this.backdrop = backdrop;
            this.logo = logo;
            this.item = item;
            this.caption = caption;
            this.tag = tag;
        }

        /** Shows its own title or logo, so the app's name isn't needed. */
        boolean ownTitle() {
            return tier == CONTENT || logo != null;
        }
    }

    /** The best art for the app that's ready now, or null. */
    private Choice choose(ShelfSource.App app) {
        if (source == null || !started) return null;
        // The title the shelf is at, in its list's order (what you were watching, then saved, then popular): if
        // it's still loading the cover art shows meanwhile, rather than a later title jumping the queue.
        if (titles != null && at >= 0 && at < titles.size()) {
            Choice c = content(app, titles.get(at));
            if (c != null) return c;
        }
        ShelfSource.Cover cover = source.cover(app.key);
        if (cover != null) return new Choice(COVER, cover.backdrop, cover.logo, null, null, "cover:" + app.key);
        return null;
    }

    /** A suggested title, if its backdrop is decoded and its logo is too (or it has none, or it failed). */
    private Choice content(ShelfSource.App app, ShelfFeeds.Item it) {
        Bitmap bg = source.cached(it.backdrop);
        if (bg == null) return null;
        Bitmap logo = it.logo == null ? null : source.cached(it.logo);
        if (it.logo != null && logo == null && !source.failedLately(it.logo)) return null;
        String caption = it.caption != null ? it.caption : source.caption(app.pkg);
        return new Choice(CONTENT, bg, logo, it, caption, new TopShelfView.Content(it.backdrop));
    }

    /**
     * Focus came to {@code app} (or its titles arrived before any showed): its newest list, from its first title
     * whose picture hasn't failed. The same title {@link #keep} decodes for it ahead of time.
     */
    private void visit(ShelfSource.App app) {
        List<ShelfFeeds.Item> l = source.items(app.pkg);
        titles = l == null ? null : new ArrayList<>(l);
        at = ShelfOrder.first(titles, failed);
        moveDir = 1;
        moveSince = Perf.now();
        moveHow = "arrived";
        for (int i = 0; i < dock.size(); i++) {
            if (dock.get(i).key.equals(app.key)) dockAt = i;
        }
    }

    /**
     * Starts loading what the app's best art needs; it fades in if focus is still there. Not while Home is hidden:
     * resume does it again.
     */
    private void want(final ShelfSource.App app) {
        if (source == null || !started || !resumed) return;
        source.makeCover(app, c -> arrived(app));
        if (titles != null && at >= 0) loadItem(titles.get(at), () -> arrived(app));
        keep();
    }

    private void loadItem(ShelfFeeds.Item it, final Runnable done) {
        final int[] left = {it.logo == null ? 1 : 2};
        ShelfSource.Done<Bitmap> one = b -> {
            if (--left[0] == 0 && done != null) done.run();
        };
        source.load(it.backdrop, true, one);
        if (it.logo != null) source.load(it.logo, false, one);
    }

    /**
     * Something the app's art needed has loaded: shows it if focus is still there and it's better, or slides in
     * the title Left, Right or the carousel moved to.
     */
    private void arrived(ShelfSource.App app) {
        if (!shown || current == null || !current.key.equals(app.key)) return;
        if (titles != null && at >= 0 && failed.failed(titles.get(at))) {
            // Its picture can't be had (too small, or no download): on to the next title the same way.
            at = ShelfOrder.step(titles, at, moveDir, failed);
            if (at < 0) at = ShelfOrder.indexOf(titles, showingItem);
            keep();
            if (at >= 0 && !ShelfOrder.same(titles.get(at), showingItem) && content(app, titles.get(at)) == null) {
                loadItem(titles.get(at), () -> arrived(app));
            }
        }
        Choice c = choose(app);
        if (c == null) return;
        if (tier == CONTENT) {
            if (c.tier == CONTENT && !ShelfOrder.same(c.item, showingItem)) slideIn(c);
            return;
        }
        if (c.tier < tier || (c.tier == tier && app.key.equals(showingKey))) return;
        display(c, ARRIVE_MS, null, true);
        if (c.tier == CONTENT) logTitle("arrived", moveSince, app.pkg, titles, at, moveDir);
    }

    /**
     * Decodes what each dock app shows when focus comes to it, so moving along the dock shows it at once. With Home
     * back, the title the shelf is at too: it may have been dropped while Home was hidden, before it showed.
     */
    private void preloadDock() {
        if (source == null || !started) return;
        if (shown && current != null) want(current);
        else keep();
        for (ShelfSource.App a : dock) source.makeCover(a, null);
    }

    /**
     * Keeps decoded what may show next, and decodes it now, nearest first (see ShelfOrder.keep): each dock app's
     * first title, the one focus coming to it shows; and on the focused app, the title showing and the ones next
     * to it. Everything else may drop out of memory. A title whose picture can't be had hands its place on. Not
     * while Home is hidden: memory is another app's then, and resume does it again.
     */
    private void keep() {
        if (source == null || !started || !resumed) return;
        List<List<ShelfFeeds.Item>> lists = new ArrayList<>(dock.size());
        for (ShelfSource.App a : dock) lists.add(source.items(a.pkg));
        boolean focused = shown && current != null;
        List<ShelfFeeds.Item> want = ShelfOrder.keep(lists, dockAt, focused ? titles : null, at, focused && acting,
                failed);
        List<String> urls = new ArrayList<>();
        for (ShelfFeeds.Item it : want) {
            urls.add(it.backdrop);
            if (it.logo != null) urls.add(it.logo);
        }
        source.pin(urls);
        for (final ShelfFeeds.Item it : want) {
            loadItem(it, () -> {
                if (!source.failedLately(it.backdrop)) return;
                handler.removeCallbacks(keepAgain);
                handler.post(keepAgain);
            });
        }
    }

    // ---- showing -------------------------------------------------------------------------------------------

    /**
     * Shows {@code c} (null: none, the glow shows) over {@code ms}. {@code notify}: tell the host whether the app's
     * name should hide, for changes it didn't ask for (art arriving, the carousel).
     */
    private void display(final Choice c, long ms, final Runnable artDone, boolean notify) {
        String key = current != null ? current.key : null;
        boolean hadOwnTitle = showingOwnTitle && key != null && key.equals(showingKey);
        tier = c == null ? NONE : c.tier;
        showingKey = c == null ? null : key;
        showingItem = c == null ? null : c.item;
        showingOwnTitle = c != null && c.ownTitle();
        if (c == null) host.onShelfCovered(false);
        art.show(c == null ? null : c.backdrop, c == null ? null : c.tag, ms, () -> {
                    host.onShelfCovered(art.covered());
                    if (artDone != null) artDone.run();
                });
        swapInfo(c, ms);
        if (notify && key != null && showingOwnTitle != hadOwnTitle) host.onShelfArt(key, showingOwnTitle);
        if (c != null && c.tier == CONTENT) scheduleCarousel();
        // The button opens what's showing (enterActions labels it as it appears: setText would lay out again).
        if (acting) labelAction();
    }

    private void swapInfo(final Choice c, long ms) {
        final int mode = c != null && c.tier == COVER ? ShelfInfoView.COVER : ShelfInfoView.CONTENT;
        final Bitmap logo = c == null ? null : c.logo;
        final String title = c == null || c.item == null ? null : c.item.title;
        final String caption = c == null ? null : c.caption;
        if (ms == 0L) {
            info.setTranslationX(0f);
            infoMode = mode;
            info.place(contentLeft, mode == ShelfInfoView.COVER ? coverBottom : contentBottom);
            info.set(mode, logo, title, caption);
            return;
        }
        info.animate().cancel();
        info.setTranslationX(0f);
        info.animate().alpha(0f).setDuration((long) (INFO_OUT_MS * info.getAlpha())).setInterpolator(DECELERATE)
                .withEndAction(() -> {
                    infoMode = mode;
                    info.place(contentLeft, mode == ShelfInfoView.COVER ? coverBottom : contentBottom);
                    info.set(mode, logo, title, caption);
                    if (c == null) return;
                    info.animate().alpha(1f).setDuration(Math.max(INFO_IN_MS, ms - INFO_OUT_MS))
                            .setInterpolator(DECELERATE).start();
                }).start();
    }

    /**
     * The next title (the carousel's or Right's; {@code dir} -1: Left's): the picture pushes in from the side, its
     * title a little behind it.
     */
    private void slideTo(final Choice c, int dir) {
        String key = current.key;
        boolean hadOwnTitle = showingOwnTitle;
        tier = c.tier;
        showingKey = key;
        showingItem = c.item;
        showingOwnTitle = c.ownTitle();
        art.slide(c.backdrop, c.tag, dir, CAROUSEL_SLIDE_MS, () -> host.onShelfCovered(art.covered()));
        final float d = dir * 72 * info.getResources().getDisplayMetrics().density;
        info.animate().cancel();
        info.animate().alpha(0f).translationX(-d).setDuration(CAROUSEL_SLIDE_MS * 2 / 5)
                .setInterpolator(DECELERATE).withEndAction(() -> {
                    infoMode = ShelfInfoView.CONTENT;
                    info.place(contentLeft, contentBottom);
                    info.set(ShelfInfoView.CONTENT, c.logo, c.item == null ? null : c.item.title, c.caption);
                    info.setTranslationX(d);
                    info.animate().alpha(1f).translationX(0f).setDuration(CAROUSEL_SLIDE_MS * 3 / 5)
                            .setInterpolator(DECELERATE).start();
                }).start();
        if (showingOwnTitle != hadOwnTitle) host.onShelfArt(key, showingOwnTitle);
        if (!acting) scheduleCarousel();
        if (acting) labelAction();
    }

    private void scheduleCarousel() {
        handler.removeCallbacks(advance);
        if (resumed && shown && !acting) handler.postDelayed(advance, CAROUSEL_MS);
    }

    /**
     * The carousel: the app's next title. If its picture is still on its way it slides in when it arrives, and the
     * carousel goes on from there.
     */
    private void advance() {
        if (!shown || !resumed || current == null || tier != CONTENT || source == null || titles == null || at < 0) {
            return;
        }
        if (!ShelfOrder.same(titles.get(at), showingItem)) {
            // The title it moved to is still on its way (or was dropped while Home was hidden): it slides in when
            // it arrives.
            final ShelfSource.App app = current;
            loadItem(titles.get(at), () -> arrived(app));
            return;
        }
        int to = ShelfOrder.step(titles, at, 1, failed);
        if (to >= 0) move(to, 1, "carousel");
    }
}
