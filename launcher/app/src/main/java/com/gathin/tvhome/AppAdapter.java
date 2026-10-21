package com.gathin.tvhome;

import android.animation.ObjectAnimator;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * App tiles. The first {@link MainActivity#COLUMNS} positions are the dock. Until the user rearranges, the dock
 * holds the configured favourites and the rest is alphabetical; after that, their saved order (see {@link AppOrder}).
 */
public class AppAdapter extends RecyclerView.Adapter<AppAdapter.Holder> {

    interface TileListener {
        void onTileFocused(int position, View card);

        /** Called for every D-pad direction on a tile. Return true when handled. */
        boolean onTileDpadKey(int position, int keyCode);

        /** Select held on a tile. */
        void onTileLongPressed(int position, View card);

        /** Every key on a tile, before the tile handles it. Return true when handled. */
        boolean onTileKey(int position, KeyEvent event);

        /** The tile after the last app was selected: Arrange apps, or Done while arranging. */
        void onActionTile();

        /** Opens the app; Home animates out of the way. Throws if it can't be opened. */
        void onLaunchApp(Intent intent);
    }

    static final class Entry {
        final String label;
        final ComponentName component;
        final String pkg;
        /** Identifies the app across reloads and in the saved order. */
        final String key;
        final Drawable banner;
        final Drawable icon;
        /** For the top shelf's cover art, which loads the banner in its sharpest version. */
        final ApplicationInfo appInfo;
        final int bannerRes;
        ShelfSource.App shelfApp;
        int ambient;

        Entry(String label, ComponentName component, Drawable banner, Drawable icon, ApplicationInfo appInfo,
              int bannerRes) {
            this.label = label;
            this.component = component;
            this.pkg = component.getPackageName();
            this.key = component.flattenToShortString();
            this.banner = banner;
            this.icon = icon;
            this.appInfo = appInfo;
            this.bannerRes = bannerRes;
        }
    }

    /** Focused tile size relative to the others, as measured on an Apple TV 4K (about 152 vs 124 px). */
    static final float FOCUS_SCALE = 1.22f;
    /** The tiles' corner radius (also drawable/tile_bg.xml). */
    static final float TILE_RADIUS_DP = 16f;
    /**
     * Select pressed on a focused tile: it sinks to this much of its focused size, as tvOS's does when you click
     * (1.20x to 1.11x of the tiles around it in about 100 ms, while Home moves away).
     */
    static final float PRESS_SCALE = 0.92f;
    /** The app being moved is picked up: larger than a focused tile, on a deeper shadow. */
    static final float LIFT_SCALE = 1.27f;
    private static final float LIFT_DP = 28f;
    /** While an app is being moved the others step back, so it and the gap it leaves stand out. */
    private static final float DIM_ALPHA = 0.45f;
    /** Lift of the focused tile, for the soft drop shadow tvOS draws under it. */
    private static final float FOCUS_LIFT_DP = 12f;
    /** The focused tile's name sits this far below its resting place, clear of the scaled tile. */
    private static final float NAME_SHIFT_DP = 12f;
    /** The user's arrangement (see {@link AppOrder#encode}); absent until they first rearrange. */
    private static final String PREF_ORDER = "app_order";
    /** Rebind payload: the tile's slot changed (dock or grid), or arranging started, stopped or picked up an app. */
    private static final Object PAYLOAD_SLOT = new Object();
    private static final int TYPE_APP = 0;
    /** The tile after the last app: Arrange apps, or Done while arranging. */
    private static final int TYPE_ACTION = 1;
    /** Home Settings: the launcher's own, so it looks like the action tile (glass, its gear and name), not an app. */
    private static final int TYPE_SETTINGS = 2;
    private static final long ACTION_ID = Long.MAX_VALUE;

    private final Context ctx;
    private final TileListener listener;
    private final int dockSize;
    /** Dock order (res/values/config.xml, dock_apps). */
    private final List<String> favourites;
    /** Never shown as tiles (res/values/config.xml, hidden_apps). */
    private final Set<String> hidden;
    private final List<Entry> items = new ArrayList<>();
    private final SharedPreferences prefs;
    /** Arranging apps: the action tile reads Done. */
    private boolean arranging;
    /** The app picked up to move, lifted while the others dim; null when none is. */
    private String movingKey;
    /** The tile whose app was just opened, held pressed until Home is back. */
    private Holder pressed;
    /** The app list loading in the background since {@link #preload}, until the next reload takes it. */
    private Future<Loaded> preloaded;

    AppAdapter(Context ctx, TileListener listener, int dockSize) {
        this.ctx = ctx;
        this.listener = listener;
        this.dockSize = dockSize;
        this.favourites = Arrays.asList(ctx.getResources().getStringArray(R.array.dock_apps));
        this.hidden = new HashSet<>(Arrays.asList(ctx.getResources().getStringArray(R.array.hidden_apps)));
        this.prefs = ctx.getSharedPreferences("home", Context.MODE_PRIVATE);
        // Tiles keep their identity across reload(), so a focused tile stays on its app instead of
        // its view being rebound to whatever app now sits in that slot.
        setHasStableIds(true);
    }

    /** Shows the installed TV apps; main thread. Takes the list {@link #preload} started, if any. */
    void reload() {
        long t0 = Perf.now();
        Perf.begin("tvhome:reload");
        try {
            Future<Loaded> early = preloaded;
            preloaded = null;
            Loaded apps = null;
            long waitNanos = 0;
            if (early != null) {
                try {
                    apps = early.get();
                } catch (ExecutionException e) {
                    // Load it again below, so a failure surfaces as it would have without the preload.
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                waitNanos = Perf.now() - t0;
            }
            if (apps == null) apps = load();
            show(apps, t0, early != null ? waitNanos : -1);
        } finally {
            Perf.end();
        }
    }

    /**
     * Starts loading the app list in the background for the next {@link #reload}: at a cold start it then loads
     * while the screen inflates, instead of after it on the main thread.
     */
    void preload() {
        FutureTask<Loaded> task = new FutureTask<>(this::load);
        new Thread(task, "tvhome-preload").start();
        preloaded = task;
    }

    /** A TV app changed: the next reload loads the list again rather than use one preloaded before the change. */
    void dropPreload() {
        if (preloaded != null) preloaded.cancel(false);
        preloaded = null;
    }

    /** The TV apps with their banners, icons and labels, and how long the query and the loading took. */
    private static final class Loaded {
        final List<Entry> apps;
        final long queryNanos;
        final long loadNanos;

        Loaded(List<Entry> apps, long queryNanos, long loadNanos) {
            this.apps = apps;
            this.queryNanos = queryNanos;
            this.loadNanos = loadNanos;
        }
    }

    /**
     * Queries the TV apps and loads each one's banner, icon and label: the slow part of a reload, mostly reading other
     * apps' resources from storage. The apps load in parallel on {@link #LOADERS}. Any thread; it doesn't touch the
     * adapter's state.
     */
    private Loaded load() {
        long t0 = Perf.now();
        PackageManager pm = ctx.getPackageManager();
        Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        Perf.begin("tvhome:reload.query");
        List<ResolveInfo> found;
        try {
            found = pm.queryIntentActivities(probe, 0);
        } finally {
            Perf.end();
        }
        long tQuery = Perf.now();
        List<Callable<Entry>> tasks = new ArrayList<>();
        for (ResolveInfo ri : found) {
            if (ri.activityInfo == null) continue;
            if (!shown(ctx, ri, hidden)) continue;
            tasks.add(() -> loadApp(pm, ri));
        }
        List<Entry> apps = new ArrayList<>(tasks.size());
        try {
            for (Future<Entry> f : LOADERS.invokeAll(tasks)) apps.add(f.get());
        } catch (ExecutionException e) {
            throw e.getCause() instanceof RuntimeException ? (RuntimeException) e.getCause()
                    : new RuntimeException(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
        return new Loaded(apps, tQuery - t0, Perf.now() - tQuery);
    }

    /** Whether an app is a tile: not hidden (config.xml), and of this app's own activities only Home Settings. */
    private static boolean shown(Context ctx, ResolveInfo ri, Set<String> hidden) {
        String pkg = ri.activityInfo.packageName;
        if (hidden.contains(pkg)) return false;
        return !pkg.equals(ctx.getPackageName()) || SettingsActivity.class.getName().equals(ri.activityInfo.name);
    }

    /** Home Settings' tile's key, as {@link Entry#key}. */
    static String settingsKey(Context ctx) {
        return new ComponentName(ctx, SettingsActivity.class).flattenToShortString();
    }

    /** The TV apps as Home has them: in the user's order, and how many of them are in the dock. */
    static final class Ordered {
        final List<Entry> apps;
        final int dock;

        Ordered(List<Entry> apps, int dock) {
            this.apps = apps;
            this.dock = dock;
        }
    }

    /**
     * The TV apps in the order Home shows them, without Home Settings itself, and how many are in the dock: for Home
     * Settings. Loads them all, so not on the main thread.
     */
    static Ordered loadInOrder(Context ctx, int dockSize) {
        PackageManager pm = ctx.getPackageManager();
        Set<String> hidden = new HashSet<>(Arrays.asList(ctx.getResources().getStringArray(R.array.hidden_apps)));
        Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<Callable<Entry>> tasks = new ArrayList<>();
        for (ResolveInfo ri : pm.queryIntentActivities(probe, 0)) {
            if (ri.activityInfo != null && shown(ctx, ri, hidden)) tasks.add(() -> loadApp(pm, ri));
        }
        List<Entry> fresh = new ArrayList<>(tasks.size());
        try {
            for (Future<Entry> f : LOADERS.invokeAll(tasks)) fresh.add(f.get());
        } catch (ExecutionException e) {
            throw new RuntimeException(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
        Collections.sort(fresh, (a, b) -> a.label.compareToIgnoreCase(b.label));
        Map<String, Entry> byKey = new HashMap<>();
        List<String> byName = new ArrayList<>(fresh.size());
        for (Entry e : fresh) {
            if (byKey.put(e.key, e) == null) byName.add(e.key);
        }
        SharedPreferences prefs = ctx.getSharedPreferences("home", Context.MODE_PRIVATE);
        List<String> order = AppOrder.arrange(byName,
                Arrays.asList(ctx.getResources().getStringArray(R.array.dock_apps)),
                AppOrder.decode(prefs.getString(PREF_ORDER, null)), settingsKey(ctx));
        List<Entry> out = new ArrayList<>(order.size());
        int dock = 0;
        for (int i = 0; i < order.size(); i++) {
            Entry e = byKey.get(order.get(i));
            if (e.pkg.equals(ctx.getPackageName())) continue;
            out.add(e);
            if (i < dockSize) dock++;
        }
        return new Ordered(out, dock);
    }

    private static Entry loadApp(PackageManager pm, ResolveInfo ri) {
        String pkg = ri.activityInfo.packageName;
        Perf.begin("tvhome:loadApp " + pkg);
        try {
            Drawable banner = ri.activityInfo.loadBanner(pm);
            if (banner == null) banner = ri.activityInfo.applicationInfo.loadBanner(pm);
            // Home Settings draws its gear and name on glass, as the Arrange apps tile (its banner is for other
            // launchers' app lists).
            if (SettingsActivity.class.getName().equals(ri.activityInfo.name)) banner = null;
            Drawable icon = ri.loadIcon(pm);
            String label = String.valueOf(ri.loadLabel(pm));
            return new Entry(label, new ComponentName(pkg, ri.activityInfo.name), banner, icon,
                    ri.activityInfo.applicationInfo, ri.activityInfo.getBannerResource());
        } finally {
            Perf.end();
        }
    }

    /** Shows a loaded list in the user's order; main thread. waitNanos: time spent waiting for a preload, or -1. */
    private void show(Loaded loaded, long t0, long waitNanos) {
        List<Entry> fresh = new ArrayList<>(loaded.apps);
        Collections.sort(fresh, (a, b) -> a.label.compareToIgnoreCase(b.label));
        Map<String, Entry> byKey = new HashMap<>();
        List<String> byName = new ArrayList<>(fresh.size());
        for (Entry e : fresh) {
            if (byKey.put(e.key, e) == null) byName.add(e.key);
        }
        List<String> order = AppOrder.arrange(byName, favourites, AppOrder.decode(prefs.getString(PREF_ORDER, null)),
                settingsKey(ctx));
        items.clear();
        for (String key : order) items.add(byKey.get(key));
        notifyDataSetChanged();
        sampleDockColours();
        if (Perf.enabled()) {
            Perf.log("reload", t0, String.format(Locale.US, "apps=%d query_ms=%.1f load_ms=%.1f%s",
                    fresh.size(), loaded.queryNanos / 1e6, loaded.loadNanos / 1e6,
                    waitNanos >= 0 ? String.format(Locale.US, " wait_ms=%.1f", waitNanos / 1e6) : ""));
        }
    }

    /** The apps' keys in their current order. */
    List<String> keys() {
        List<String> keys = new ArrayList<>(items.size());
        for (Entry e : items) keys.add(e.key);
        return keys;
    }

    /** Saves the current order as the user's arrangement; from now on it replaces the configured dock. */
    void saveOrder() {
        prefs.edit().putString(PREF_ORDER, AppOrder.encode(keys())).apply();
        if (orderSaved != null) orderSaved.run();
    }

    /** Runs when an arrangement is saved: the dock's apps may have changed. */
    private Runnable orderSaved;

    void setOnOrderSaved(Runnable r) {
        orderSaved = r;
    }

    /** The number of apps, without the action tile after them. */
    int appCount() {
        return items.size();
    }

    boolean isActionTile(int position) {
        return position == items.size();
    }

    String movingKey() {
        return movingKey;
    }

    /** Arranging on or off, and the app picked up to move (null for none). Restyles every tile. */
    void setArranging(boolean on, String moving) {
        if (on == arranging && (moving == null ? movingKey == null : moving.equals(movingKey))) return;
        arranging = on;
        movingKey = moving;
        notifyItemRangeChanged(0, getItemCount(), PAYLOAD_SLOT);
    }

    /** Moves an app being rearranged; the apps in between shift over, into or out of the dock. */
    void move(int from, int to) {
        if (from == to || from < 0 || to < 0 || from >= items.size() || to >= items.size()) return;
        AppOrder.move(items, from, to);
        notifyItemMoved(from, to);
        notifyItemChanged(to, PAYLOAD_SLOT);   // its chevrons: which ways it can still go
        if (Math.min(from, to) < dockSize && Math.max(from, to) >= dockSize) {
            // An app crossed between the dock and the grid: dock tiles have no name underneath.
            notifyItemRangeChanged(dockSize - 1, 2, PAYLOAD_SLOT);
        }
    }

    void openAppInfo(int position) {
        if (position < 0 || position >= items.size()) return;
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + items.get(position).pkg));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ctx.startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(ctx, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_app, parent, false);
        Holder h = new Holder(v, listener);
        if (viewType == TYPE_ACTION) {
            h.card.setBackgroundResource(R.drawable.action_tile_bg);
            h.banner.setVisibility(View.GONE);
            h.icon.setVisibility(View.VISIBLE);
            h.label.setVisibility(View.VISIBLE);
            h.card.setOnClickListener(view -> listener.onActionTile());
        } else if (viewType == TYPE_SETTINGS) {
            h.card.setBackgroundResource(R.drawable.action_tile_bg);
        }
        return h;
    }

    @Override
    public int getItemViewType(int position) {
        if (isActionTile(position)) return TYPE_ACTION;
        return items.get(position).pkg.equals(ctx.getPackageName()) ? TYPE_SETTINGS : TYPE_APP;
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        if (isActionTile(position)) {
            h.settle(h.card.isFocused());
            bindSlot(h, position, false);
            return;
        }
        Entry e = items.get(position);
        h.name.setText(e.label);
        h.settle(h.card.isFocused());   // a rebind after reload() can hit the focused tile
        bindSlot(h, position, false);
        if (e.banner != null) {
            h.banner.setImageDrawable(e.banner);
            h.banner.setVisibility(View.VISIBLE);
            h.icon.setVisibility(View.GONE);
            h.label.setVisibility(View.GONE);
        } else {
            h.banner.setImageDrawable(null);
            h.banner.setVisibility(View.GONE);
            h.icon.setImageDrawable(e.icon);
            h.icon.setVisibility(View.VISIBLE);
            h.label.setText(e.label);
            h.label.setVisibility(View.VISIBLE);
        }
        h.card.setOnClickListener(v -> launch(h, e));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position, @NonNull List<Object> payloads) {
        if (payloads.isEmpty()) onBindViewHolder(h, position);
        else bindSlot(h, position, true);
    }

    private void bindSlot(Holder h, int position, boolean animate) {
        boolean action = isActionTile(position);
        // The dock has no names; the action tile and Home Settings carry their own.
        boolean ownName = action || getItemViewType(position) == TYPE_SETTINGS;
        h.name.setVisibility(position < dockSize || ownName ? View.INVISIBLE : View.VISIBLE);
        if (action) {
            h.icon.setImageResource(arranging ? R.drawable.ic_check : R.drawable.ic_arrange);
            h.label.setText(arranging ? R.string.done : R.string.arrange_apps);
        }
        boolean lifted = !action && items.get(position).key.equals(movingKey);
        if (lifted) {
            int n = items.size();
            int mask = 0;
            if (AppOrder.step(position, n, dockSize, KeyEvent.KEYCODE_DPAD_LEFT) != position) mask |= MoveMarks.LEFT;
            if (AppOrder.step(position, n, dockSize, KeyEvent.KEYCODE_DPAD_RIGHT) != position) mask |= MoveMarks.RIGHT;
            int up = AppOrder.step(position, n, dockSize, KeyEvent.KEYCODE_DPAD_UP);
            if (up >= 0 && up != position) mask |= MoveMarks.UP;
            if (AppOrder.step(position, n, dockSize, KeyEvent.KEYCODE_DPAD_DOWN) != position) mask |= MoveMarks.DOWN;
            h.marks.setDirections(mask);
        }
        h.setLifted(lifted, animate);
        h.setDimmed(movingKey != null && !lifted, animate);
    }

    static boolean isSelect(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER;
    }

    private void launch(Holder h, Entry e) {
        Intent i = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setComponent(e.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        // The tile stays pressed in while Home moves away, as on tvOS. Select's key down has mostly pressed it in
        // already; finish now, so Home moves away as one still picture that the GPU draws only once.
        releasePress(true);
        h.press(true);
        h.finishScale();
        pressed = h;
        try {
            listener.onLaunchApp(i);
        } catch (Exception ex) {
            releasePress(true);
            Toast.makeText(ctx, ctx.getString(R.string.cant_open, e.label), Toast.LENGTH_SHORT).show();
        }
    }

    /** Lets the tile of the app just opened back out: at once while Home is hidden, or animated as it returns. */
    void releasePress(boolean animate) {
        Holder h = pressed;
        pressed = null;
        if (h == null) return;
        if (animate) h.press(false);
        else h.settle(h.card.isFocused());
    }

    @Override
    public int getItemCount() {
        return items.size() + 1;   // the apps, then the action tile
    }

    @Override
    public long getItemId(int position) {
        return isActionTile(position) ? ACTION_ID : items.get(position).key.hashCode();
    }

    /** The package of the app at a position, or null. */
    String pkgAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).pkg : null;
    }

    /** Stable key for the app at a position (its component), or null. */
    String keyAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).key : null;
    }

    /** Position of the app with this key, or -1 when it is gone. */
    int positionOf(String key) {
        if (key == null) return -1;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).key.equals(key)) return i;
        }
        return -1;
    }

    /** A copy of the app's banner, or null without one, to show it somewhere else too. */
    Drawable bannerAt(int position) {
        return position >= 0 && position < items.size() ? copy(items.get(position).banner) : null;
    }

    Drawable iconAt(int position) {
        return position >= 0 && position < items.size() ? copy(items.get(position).icon) : null;
    }

    /** What the top shelf needs to know about the app at a position, or null. */
    ShelfSource.App shelfAppAt(int position) {
        if (position < 0 || position >= items.size()) return null;
        Entry e = items.get(position);
        if (e.shelfApp == null) {
            e.shelfApp = new ShelfSource.App(e.key, e.pkg, e.label, e.appInfo, e.bannerRes,
                    copy(e.banner != null ? e.banner : e.icon));
        }
        return e.shelfApp;
    }

    /** The dock's apps, for the top shelf to prepare their art. */
    List<ShelfSource.App> dockApps() {
        List<ShelfSource.App> out = new ArrayList<>();
        for (int i = 0; i < Math.min(dockSize, items.size()); i++) out.add(shelfAppAt(i));
        return out;
    }

    private static Drawable copy(Drawable d) {
        if (d == null) return null;
        return d.getConstantState() != null ? d.getConstantState().newDrawable().mutate() : d;
    }

    /** Whether a tile shows an app of this package. */
    boolean contains(String pkg) {
        for (Entry e : items) {
            if (e.pkg.equals(pkg)) return true;
        }
        return false;
    }

    String labelAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).label : "";
    }

    /** The ambient glow colour sampled from the app's banner (or icon), cached per entry. See {@link AmbientColor}. */
    int ambientColorAt(int position) {
        if (position < 0 || position >= items.size()) return AmbientColor.FALLBACK;
        Entry e = items.get(position);
        if (e.ambient == 0) {
            long t0 = Perf.now();
            Perf.begin("tvhome:vividColor");
            try {
                e.ambient = vividColor(e.banner != null ? e.banner : e.icon);
            } finally {
                Perf.end();
            }
            Perf.log("ambient", t0, "pkg=" + e.pkg);
        }
        return e.ambient;
    }

    /**
     * Samples the dock apps' glow colours on a background thread, so focusing a dock app for the first time
     * doesn't draw and scan its banner on the main thread. A focus that comes first samples it there, as before.
     */
    private void sampleDockColours() {
        final List<Entry> dock = new ArrayList<>();
        final List<Drawable> copies = new ArrayList<>();
        for (int i = 0; i < Math.min(dockSize, items.size()); i++) {
            Entry e = items.get(i);
            Drawable live = e.banner != null ? e.banner : e.icon;
            if (e.ambient != 0 || live == null || live.getConstantState() == null) continue;
            dock.add(e);
            copies.add(live.getConstantState().newDrawable().mutate());   // private copies, drawn off the main thread
        }
        if (dock.isEmpty()) return;
        BACKGROUND.execute(() -> {
            final int[] colours = new int[copies.size()];
            for (int i = 0; i < colours.length; i++) colours[i] = sample(copies.get(i));
            MAIN.post(() -> {
                for (int i = 0; i < colours.length; i++) {
                    if (dock.get(i).ambient == 0) dock.get(i).ambient = colours[i];
                }
            });
        });
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /**
     * Loads apps for {@link #load} in parallel: each one mostly waits on storage, and TVs have several cores.
     * The threads end when idle.
     */
    private static final ExecutorService LOADERS;

    static {
        int threads = Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors() - 1));
        ThreadPoolExecutor pool = new ThreadPoolExecutor(threads, threads, 2, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(), r -> {
                    Thread t = new Thread(r, "tvhome-apps");
                    t.setDaemon(true);
                    return t;
                });
        pool.allowCoreThreadTimeOut(true);
        LOADERS = pool;
    }
    private static final Executor BACKGROUND = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            r.run();
        }, "tvhome-colours");
        t.setDaemon(true);
        return t;
    });

    static int vividColor(Drawable live) {
        if (live == null) return AmbientColor.FALLBACK;
        // Draw a private copy: changing the bounds of the drawable an ImageView shows would blank the tile.
        return sample(live.getConstantState() != null ? live.getConstantState().newDrawable().mutate() : live);
    }

    /** Draws {@code d} small and picks its glow colour. Any thread, if no one else uses {@code d}. */
    private static int sample(Drawable d) {
        Rect saved = new Rect(d.getBounds());
        // Banner aspect (16:9), fine enough that small logos and accents are sampled in proportion to their area.
        int w = 64, h = 36;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        d.setBounds(0, 0, w, h);
        d.draw(c);
        d.setBounds(saved);
        int[] pixels = new int[w * h];
        bmp.getPixels(pixels, 0, w, 0, 0, w, h);
        bmp.recycle();
        return AmbientColor.pick(pixels);
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final View card;
        final ImageView banner;
        final ImageView icon;
        final TextView label;
        final TextView name;
        final MoveMarks marks;
        private final SpringAnimation scaleX;
        private final SpringAnimation scaleY;
        private final float density;
        /** Picked up to move: bigger and higher than focus alone. */
        private boolean lifted;
        /** Fades the card, not the item: the item animator cancels animations on the item view. */
        private ObjectAnimator dim;
        /** The current Select press has already long-pressed, so its release must not open the app. */
        private boolean longPressed;
        /** Select is down on it, or its app is opening. */
        private boolean pressedIn;

        Holder(View v, TileListener listener) {
            super(v);
            card = v.findViewById(R.id.card);
            banner = v.findViewById(R.id.banner);
            icon = v.findViewById(R.id.icon);
            label = v.findViewById(R.id.label);
            name = v.findViewById(R.id.name);
            marks = v.findViewById(R.id.marks);
            density = v.getResources().getDisplayMetrics().density;
            scaleX = new SpringAnimation(card, DynamicAnimation.SCALE_X, 1f);
            scaleY = new SpringAnimation(card, DynamicAnimation.SCALE_Y, 1f);
            // The ring and chevrons grow with the tile.
            scaleX.addUpdateListener((a, value, velocity) -> marks.setScaleX(value));
            scaleY.addUpdateListener((a, value, velocity) -> marks.setScaleY(value));

            final float radius = density * TILE_RADIUS_DP;
            card.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                }
            });
            card.setClipToOutline(true);

            card.setOnKeyListener((view, keyCode, event) -> {
                int pos = getBindingAdapterPosition();
                if (listener == null || pos == RecyclerView.NO_POSITION) return false;
                if (listener.onTileKey(pos, event)) return true;
                if (isSelect(keyCode)) return onSelectKey(view, event);
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode != KeyEvent.KEYCODE_DPAD_UP && keyCode != KeyEvent.KEYCODE_DPAD_DOWN
                        && keyCode != KeyEvent.KEYCODE_DPAD_LEFT && keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) {
                    return false;
                }
                return listener.onTileDpadKey(pos, keyCode);
            });

            card.setOnLongClickListener(view -> {
                longPressed = true;
                int pos = getBindingAdapterPosition();
                if (listener != null && pos != RecyclerView.NO_POSITION) listener.onTileLongPressed(pos, view);
                return true;
            });

            card.setOnFocusChangeListener((view, focused) -> {
                animateFocus(focused);
                int pos = getBindingAdapterPosition();
                if (focused && listener != null && pos != RecyclerView.NO_POSITION) {
                    listener.onTileFocused(pos, view);
                }
            });
        }

        /**
         * tvOS focus: the tile itself grows, quickly and with a hint of overshoot (about 110 ms to its peak),
         * and lifts onto a soft shadow; the tile losing focus eases back down over about 220 ms. Springs keep
         * the motion continuous when focus moves again mid-animation (holding the D-pad).
         */
        private void animateFocus(boolean focused) {
            if (!focused) pressedIn = false;
            // Fitted to an Apple TV 4K screen recording (frame-by-frame tile widths).
            float stiffness = focused ? 1100f : 500f;
            float damping = focused ? 0.68f : 1f;
            float s = scaleFor(focused);
            if (lifted) {
                // Picked up: a slower, softer rise than focus, like lifting the tile off the screen.
                stiffness = 600f;
                damping = 0.75f;
            }
            for (SpringAnimation a : new SpringAnimation[]{scaleX, scaleY}) {
                a.getSpring().setStiffness(stiffness).setDampingRatio(damping);
                a.animateToFinalPosition(s);
            }
            card.animate().translationZ(zFor(focused))
                    .setDuration(focused ? 120 : 220)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
            name.animate().alpha(nameAlpha(focused))
                    .translationY(nameShift(focused))
                    .setDuration(focused ? 200 : 140).start();
        }

        /**
         * Holding Select long-presses: the view's own long-click timer usually fires first, but an injected
         * long press (adb's {@code input keyevent --longpress}) can release before it does, so its long-press
         * repeat counts too. The release that ends a long press must not also open the app.
         */
        private boolean onSelectKey(View view, KeyEvent event) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) {
                    longPressed = false;
                    press(true);
                } else if (event.isLongPress() && !longPressed) {
                    view.cancelLongPress();
                    view.performLongClick();
                }
                return false;
            }
            if (event.getAction() == KeyEvent.ACTION_UP) {
                // Back out; opening its app (the click that follows) presses it in again before the next frame.
                press(false);
                if (longPressed) {
                    longPressed = false;
                    view.setPressed(false);
                    return true;
                }
            }
            return false;
        }

        /** Jumps to the end of a running grow, shrink or press. */
        void finishScale() {
            for (SpringAnimation a : new SpringAnimation[]{scaleX, scaleY}) {
                if (a.isRunning()) a.skipToEnd();
            }
        }

        /** Sinks in while Select is down (quickly, without bounce) and springs back out when it lets go. */
        void press(boolean in) {
            if (pressedIn == in) return;
            pressedIn = in && card.isFocused() && !lifted;
            for (SpringAnimation a : new SpringAnimation[]{scaleX, scaleY}) {
                a.getSpring().setStiffness(in ? 1500f : 1100f).setDampingRatio(in ? 1f : 0.68f);
                a.animateToFinalPosition(scaleFor(card.isFocused()));
            }
        }

        /** Picks the tile up to move it, or puts it down; it settles to its focused or resting size. */
        void setLifted(boolean on, boolean animate) {
            if (on == lifted) return;
            lifted = on;
            // Above its neighbours, so they can't cover its ring and chevrons.
            itemView.setTranslationZ(on ? density : 0f);
            marks.animate().cancel();
            if (animate) marks.animate().alpha(on ? 1f : 0f).setDuration(on ? 200 : 120).start();
            else marks.setAlpha(on ? 1f : 0f);
            if (animate) {
                animateFocus(card.isFocused());
            } else {
                settle(card.isFocused());
            }
        }

        /** Steps back while another app is being moved. */
        void setDimmed(boolean on, boolean animate) {
            float a = on ? DIM_ALPHA : 1f;
            if (dim != null) dim.cancel();
            dim = null;
            if (animate && card.getAlpha() != a) {
                dim = ObjectAnimator.ofFloat(card, View.ALPHA, a).setDuration(on ? 220 : 160);
                dim.start();
            } else {
                card.setAlpha(a);
            }
        }

        /** Jumps to the resting focused or unfocused state, without animation. */
        void settle(boolean focused) {
            pressedIn = false;
            scaleX.cancel();
            scaleY.cancel();
            card.animate().cancel();
            name.animate().cancel();
            float s = scaleFor(focused);
            card.setScaleX(s);
            card.setScaleY(s);
            marks.setScaleX(s);
            marks.setScaleY(s);
            card.setTranslationZ(zFor(focused));
            name.setAlpha(nameAlpha(focused));
            name.setTranslationY(nameShift(focused));
        }

        private float scaleFor(boolean focused) {
            return lifted ? LIFT_SCALE : focused ? FOCUS_SCALE * (pressedIn ? PRESS_SCALE : 1f) : 1f;
        }

        private float zFor(boolean focused) {
            return (lifted ? LIFT_DP : focused ? FOCUS_LIFT_DP : 0f) * density;
        }

        /** A lifted tile's name would sit on its Down chevron; the header names it instead. */
        private float nameAlpha(boolean focused) {
            return focused && !lifted ? 1f : 0f;
        }

        private float nameShift(boolean focused) {
            return (focused ? NAME_SHIFT_DP : NAME_SHIFT_DP - 10f) * density;
        }
    }
}
