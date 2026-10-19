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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    /** Where each app's carousel is, so coming back to it shows the next title. Survives the activity. */
    private static final Map<String, Integer> carousel = new HashMap<>();

    private final TopShelfView art;
    private final ShelfInfoView info;
    /** The button under a suggested title, with focus after Up from the dock. */
    private final TextView action;
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
    private final Runnable advance = this::advance;
    private final Runnable preloadDock = this::preloadDock;

    /** {@code enabled}: config.xml's top_shelf_art; {@code feeds}: its top_shelf_feeds. */
    TopShelf(android.content.Context ctx, TopShelfView art, ShelfInfoView info, TextView action, Host host,
             boolean enabled, String[] feeds, String[] links) {
        this.art = art;
        this.info = info;
        this.action = action;
        this.host = host;
        this.source = enabled ? new ShelfSource(ctx, feeds, links, this) : null;
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
        // Focus coming to an app starts at the top of its list: what you were watching most recently.
        if (app != null && (current == null || !current.key.equals(app.key))) carousel.remove(app.pkg);
        current = app;
        handler.removeCallbacks(advance);
        if (source == null || app == null) {
            if (artDone != null) artDone.run();
            return false;
        }
        boolean wasShown = shown;
        shown = true;
        Choice c = choose(app);
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
     * Up from the dock: focus goes to the button under the title showing, if it has something to open (Play,
     * Resume, Open in the app), and the carousel holds still. Returns false if there's nothing to act on.
     */
    boolean enterActions() {
        if (!shown || tier != CONTENT || showingItem == null || current == null) return false;
        acting = true;
        handler.removeCallbacks(advance);
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

    /** The button says what it does with the title showing. */
    private void labelAction() {
        ShelfFeeds.Item it = showingItem;
        if (it == null || current == null) return;
        String link = source.link(current.pkg, it);
        int res = it.resume ? R.string.shelf_resume
                : it.link != null ? (it.link.contains("autoPlay=false") ? R.string.shelf_open : R.string.shelf_play)
                : 0;
        action.setText(res != 0 ? action.getResources().getString(res)
                : action.getResources().getString(R.string.shelf_open_in, current.label));
        action.setContentDescription(it.title.isEmpty() ? action.getText()
                : action.getText() + ", " + it.title);
        action.setTag(link);
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

    /** Left and Right on the button page through the app's titles, as swiping does on tvOS's Top Shelf. */
    private void step(int dir) {
        if (current == null || tier != CONTENT) return;
        List<ShelfFeeds.Item> items = source.items(current.pkg);
        if (items == null || items.size() < 2) return;
        int at = Math.max(0, items.indexOf(showingItem));
        for (int k = 1; k < items.size(); k++) {
            int i = Math.floorMod(at + dir * k, items.size());
            Choice c = content(current, items.get(i));
            if (c == null) {
                loadItem(items.get(i), null);
                continue;
            }
            carousel.put(current.pkg, i);
            slideTo(c, dir);
            labelAction();
            return;
        }
    }

    /** OK on the button: opens the title in its app, or the app if it can't be opened at the title. */
    private void act() {
        if (current == null) return;
        Object tag = action.getTag();
        Intent i = intentFor(tag instanceof String ? (String) tag : null, current.pkg);
        PackageManager pm = action.getContext().getPackageManager();
        if (i == null || i.resolveActivity(pm) == null) i = pm.getLeanbackLaunchIntentForPackage(current.pkg);
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
        handler.removeCallbacks(advance);
        art.animate().cancel();
        info.animate().cancel();
        art.animate().alpha(0f).setDuration(HIDE_MS).setInterpolator(DECELERATE).start();
        info.animate().alpha(0f).setDuration(HIDE_MS).setInterpolator(DECELERATE).start();
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
        if (current != null && current.pkg.equals(pkg)) want(current);
        for (ShelfSource.App a : dock) {
            if (a.pkg.equals(pkg)) {
                preloadDock();
                break;
            }
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
        List<ShelfFeeds.Item> items = source.items(app.pkg);
        if (items != null) {
            // The title the list is at, in its order (what you were watching, then saved, then popular): if it's
            // still loading the cover art shows meanwhile, rather than a later title jumping the queue. Only a
            // title whose picture failed is skipped.
            int start = index(app.pkg, items.size());
            for (int k = 0; k < items.size(); k++) {
                ShelfFeeds.Item it = items.get((start + k) % items.size());
                Choice c = content(app, it);
                if (c != null) return c;
                if (!source.failedLately(it.backdrop)) break;
            }
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

    private static int index(String pkg, int size) {
        Integer i = carousel.get(pkg);
        return i == null || size == 0 ? 0 : Math.floorMod(i, size);
    }

    /** Starts loading what the app's best art needs; it fades in if focus is still there. */
    private void want(final ShelfSource.App app) {
        if (source == null || !started) return;
        source.makeCover(app, c -> arrived(app));
        List<ShelfFeeds.Item> items = source.items(app.pkg);
        if (items == null) return;
        int i = index(app.pkg, items.size());
        loadItem(items.get(i), () -> arrived(app));
        // And the next one along, for the carousel.
        if (items.size() > 1) loadItem(items.get((i + 1) % items.size()), null);
    }

    private void loadItem(ShelfFeeds.Item it, final Runnable done) {
        final int[] left = {it.logo == null ? 1 : 2};
        ShelfSource.Done<Bitmap> one = b -> {
            if (--left[0] == 0 && done != null) done.run();
        };
        source.load(it.backdrop, true, one);
        if (it.logo != null) source.load(it.logo, false, one);
    }

    /** Something the app's art needed has loaded: shows it if focus is still there and it's better. */
    private void arrived(ShelfSource.App app) {
        if (!shown || current == null || !current.key.equals(app.key)) return;
        Choice c = choose(app);
        if (c == null || c.tier < tier || (c.tier == tier && app.key.equals(showingKey))) return;
        display(c, ARRIVE_MS, null, true);
    }

    /** Decodes each dock app's current title (or cover art), so moving along the dock shows it at once. */
    private void preloadDock() {
        if (source == null || !started) return;
        List<String> keep = new ArrayList<>();
        for (ShelfSource.App a : dock) {
            List<ShelfFeeds.Item> items = source.items(a.pkg);
            if (items == null) continue;
            ShelfFeeds.Item it = items.get(index(a.pkg, items.size()));
            keep.add(it.backdrop);
            if (it.logo != null) keep.add(it.logo);
        }
        source.pin(keep);
        for (ShelfSource.App a : dock) preload(a);
    }

    private void preload(ShelfSource.App a) {
        source.makeCover(a, null);
        List<ShelfFeeds.Item> items = source.items(a.pkg);
        if (items != null) loadItem(items.get(index(a.pkg, items.size())), null);
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
        if (c != null && c.tier == CONTENT) {
            scheduleCarousel();
            List<ShelfFeeds.Item> items = source.items(current.pkg);
            if (items != null && items.size() > 1) {
                loadItem(items.get((Math.max(0, items.indexOf(c.item)) + 1) % items.size()), null);
            }
        }
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

    /** The carousel's next title: the picture pushes in from the right, its title a little behind it. */
    private void slideTo(final Choice c) {
        slideTo(c, 1);
    }

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
        List<ShelfFeeds.Item> items = source.items(current.pkg);
        if (items != null && items.size() > 1) {
            loadItem(items.get((Math.max(0, items.indexOf(c.item)) + 1) % items.size()), null);
        }
    }

    private void scheduleCarousel() {
        handler.removeCallbacks(advance);
        if (resumed && shown && !acting) handler.postDelayed(advance, CAROUSEL_MS);
    }

    /** The carousel: the app's next title, if it's ready; otherwise it loads and the carousel tries again soon. */
    private void advance() {
        if (!shown || !resumed || current == null || tier != CONTENT || source == null) return;
        final ShelfSource.App app = current;
        List<ShelfFeeds.Item> items = source.items(app.pkg);
        if (items == null || items.size() < 2) return;
        int at = Math.max(0, items.indexOf(showingItem));
        for (int k = 1; k < items.size(); k++) {
            int i = (at + k) % items.size();
            Choice c = content(app, items.get(i));
            if (c == null) {
                if (source.failedLately(items.get(i).backdrop)) continue;   // no picture: on to the next
                // Not decoded yet: load it, and look again in a moment.
                loadItem(items.get(i), null);
                if (k == 1) {
                    handler.postDelayed(advance, 1500L);
                    return;
                }
                continue;
            }
            carousel.put(app.pkg, i);
            slideTo(c);
            return;
        }
    }
}
