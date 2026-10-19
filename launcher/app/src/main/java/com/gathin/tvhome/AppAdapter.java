package com.gathin.tvhome;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * App tiles. The first {@link MainActivity#COLUMNS} positions are the pinned dock (favourites, fixed order),
 * the rest is alphabetical.
 */
public class AppAdapter extends RecyclerView.Adapter<AppAdapter.Holder> {

    interface TileListener {
        void onTileFocused(int position, View card);

        /** Called for every D-pad direction on a tile. Return true when handled. */
        boolean onTileDpadKey(int position, int keyCode);
    }

    static final class Entry {
        final String label;
        final ComponentName component;
        final String pkg;
        final Drawable banner;
        final Drawable icon;
        int ambient;

        Entry(String label, ComponentName component, Drawable banner, Drawable icon) {
            this.label = label;
            this.component = component;
            this.pkg = component.getPackageName();
            this.banner = banner;
            this.icon = icon;
        }
    }

    /** Focused tile size relative to the others, as measured on an Apple TV 4K (about 152 vs 124 px). */
    static final float FOCUS_SCALE = 1.22f;
    /** Lift of the focused tile, for the soft drop shadow tvOS draws under it. */
    private static final float FOCUS_LIFT_DP = 12f;
    /** The focused tile's name sits this far below its resting place, clear of the scaled tile. */
    private static final float NAME_SHIFT_DP = 12f;

    private final Context ctx;
    private final TileListener listener;
    private final int dockSize;
    /** Dock order (res/values/config.xml, dock_apps). */
    private final List<String> favourites;
    /** Never shown as tiles (res/values/config.xml, hidden_apps). */
    private final Set<String> hidden;
    private final List<Entry> items = new ArrayList<>();

    AppAdapter(Context ctx, TileListener listener, int dockSize) {
        this.ctx = ctx;
        this.listener = listener;
        this.dockSize = dockSize;
        this.favourites = Arrays.asList(ctx.getResources().getStringArray(R.array.dock_apps));
        this.hidden = new HashSet<>(Arrays.asList(ctx.getResources().getStringArray(R.array.hidden_apps)));
        // Tiles keep their identity across reload(), so a focused tile stays on its app instead of
        // its view being rebound to whatever app now sits in that slot.
        setHasStableIds(true);
    }

    void reload() {
        long t0 = Perf.now();
        Perf.begin("tvhome:reload");
        try {
            reloadTraced(t0);
        } finally {
            Perf.end();
        }
    }

    private void reloadTraced(long t0) {
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
        List<Entry> fresh = new ArrayList<>();
        for (ResolveInfo ri : found) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(ctx.getPackageName()) || hidden.contains(pkg)) continue;
            Perf.begin("tvhome:loadApp " + pkg);
            try {
                Drawable banner = ri.activityInfo.loadBanner(pm);
                if (banner == null) banner = ri.activityInfo.applicationInfo.loadBanner(pm);
                Drawable icon = ri.loadIcon(pm);
                String label = String.valueOf(ri.loadLabel(pm));
                fresh.add(new Entry(label, new ComponentName(pkg, ri.activityInfo.name), banner, icon));
            } finally {
                Perf.end();
            }
        }
        long tLoad = Perf.now();
        Collections.sort(fresh, (a, b) -> {
            int fa = favourites.indexOf(a.pkg), fb = favourites.indexOf(b.pkg);
            if (fa >= 0 || fb >= 0) {
                if (fa < 0) return 1;
                if (fb < 0) return -1;
                return Integer.compare(fa, fb);
            }
            return a.label.compareToIgnoreCase(b.label);
        });
        items.clear();
        items.addAll(fresh);
        notifyDataSetChanged();
        sampleDockColours();
        if (Perf.enabled()) {
            Perf.log("reload", t0, String.format(Locale.US, "apps=%d query_ms=%.1f load_ms=%.1f",
                    fresh.size(), Perf.ms(t0, tQuery), Perf.ms(tQuery, tLoad)));
        }
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_app, parent, false);
        return new Holder(v, listener);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        Entry e = items.get(position);
        h.name.setText(e.label);
        h.name.setVisibility(position < dockSize ? View.INVISIBLE : View.VISIBLE);   // the dock has no labels
        h.settle(h.card.isFocused());   // a rebind after reload() can hit the focused tile
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
        h.card.setOnClickListener(v -> launch(e));
        h.card.setOnLongClickListener(v -> {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + e.pkg));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                ctx.startActivity(i);
            } catch (ActivityNotFoundException ignored) {
            }
            return true;
        });
    }

    private void launch(Entry e) {
        Intent i = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setComponent(e.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            ctx.startActivity(i);
        } catch (Exception ex) {
            Toast.makeText(ctx, ctx.getString(R.string.cant_open, e.label), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @Override
    public long getItemId(int position) {
        return items.get(position).component.flattenToShortString().hashCode();
    }

    /** Stable key for the app at a position (its component), or null. */
    String keyAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).component.flattenToShortString() : null;
    }

    /** Position of the app with this key, or -1 when it is gone. */
    int positionOf(String key) {
        if (key == null) return -1;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).component.flattenToShortString().equals(key)) return i;
        }
        return -1;
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
    private static final Executor BACKGROUND = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            r.run();
        }, "tvhome-colours");
        t.setDaemon(true);
        return t;
    });

    private static int vividColor(Drawable live) {
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
        private final SpringAnimation scaleX;
        private final SpringAnimation scaleY;
        private final float density;

        Holder(View v, TileListener listener) {
            super(v);
            card = v.findViewById(R.id.card);
            banner = v.findViewById(R.id.banner);
            icon = v.findViewById(R.id.icon);
            label = v.findViewById(R.id.label);
            name = v.findViewById(R.id.name);
            density = v.getResources().getDisplayMetrics().density;
            scaleX = new SpringAnimation(card, DynamicAnimation.SCALE_X, 1f);
            scaleY = new SpringAnimation(card, DynamicAnimation.SCALE_Y, 1f);

            final float radius = density * 16f;
            card.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                }
            });
            card.setClipToOutline(true);

            card.setOnKeyListener((view, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode != KeyEvent.KEYCODE_DPAD_UP && keyCode != KeyEvent.KEYCODE_DPAD_DOWN
                        && keyCode != KeyEvent.KEYCODE_DPAD_LEFT && keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) {
                    return false;
                }
                int pos = getBindingAdapterPosition();
                return listener != null && pos != RecyclerView.NO_POSITION && listener.onTileDpadKey(pos, keyCode);
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
            // Fitted to an Apple TV 4K screen recording (frame-by-frame tile widths).
            float stiffness = focused ? 1100f : 500f;
            float damping = focused ? 0.68f : 1f;
            float s = focused ? FOCUS_SCALE : 1f;
            for (SpringAnimation a : new SpringAnimation[]{scaleX, scaleY}) {
                a.getSpring().setStiffness(stiffness).setDampingRatio(damping);
                a.animateToFinalPosition(s);
            }
            card.animate().translationZ(focused ? FOCUS_LIFT_DP * density : 0f)
                    .setDuration(focused ? 120 : 220)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
            name.animate().alpha(focused ? 1f : 0f)
                    .translationY(nameShift(focused))
                    .setDuration(focused ? 200 : 140).start();
        }

        /** Jumps to the resting focused or unfocused state, without animation. */
        void settle(boolean focused) {
            scaleX.cancel();
            scaleY.cancel();
            card.animate().cancel();
            name.animate().cancel();
            float s = focused ? FOCUS_SCALE : 1f;
            card.setScaleX(s);
            card.setScaleY(s);
            card.setTranslationZ(focused ? FOCUS_LIFT_DP * density : 0f);
            name.setAlpha(focused ? 1f : 0f);
            name.setTranslationY(nameShift(focused));
        }

        private float nameShift(boolean focused) {
            return (focused ? NAME_SHIFT_DP : NAME_SHIFT_DP - 10f) * density;
        }
    }
}
