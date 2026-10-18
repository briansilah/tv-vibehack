package com.gathin.tvhome;

import android.animation.ObjectAnimator;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.net.Uri;
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
import java.util.Map;
import java.util.Set;

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
    }

    static final class Entry {
        final String label;
        final ComponentName component;
        final String pkg;
        /** Identifies the app across reloads and in the saved order. */
        final String key;
        final Drawable banner;
        final Drawable icon;
        int ambient;

        Entry(String label, ComponentName component, Drawable banner, Drawable icon) {
            this.label = label;
            this.component = component;
            this.pkg = component.getPackageName();
            this.key = component.flattenToShortString();
            this.banner = banner;
            this.icon = icon;
        }
    }

    /** Focused tile size relative to the others, as measured on an Apple TV 4K (about 152 vs 124 px). */
    static final float FOCUS_SCALE = 1.22f;
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

    void reload() {
        PackageManager pm = ctx.getPackageManager();
        Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<ResolveInfo> found = pm.queryIntentActivities(probe, 0);
        List<Entry> fresh = new ArrayList<>();
        for (ResolveInfo ri : found) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(ctx.getPackageName()) || hidden.contains(pkg)) continue;
            Drawable banner = ri.activityInfo.loadBanner(pm);
            if (banner == null) banner = ri.activityInfo.applicationInfo.loadBanner(pm);
            Drawable icon = ri.loadIcon(pm);
            String label = String.valueOf(ri.loadLabel(pm));
            fresh.add(new Entry(label, new ComponentName(pkg, ri.activityInfo.name), banner, icon));
        }
        Collections.sort(fresh, (a, b) -> a.label.compareToIgnoreCase(b.label));
        Map<String, Entry> byKey = new HashMap<>();
        List<String> byName = new ArrayList<>(fresh.size());
        for (Entry e : fresh) {
            if (byKey.put(e.key, e) == null) byName.add(e.key);
        }
        List<String> order = AppOrder.arrange(byName, favourites, AppOrder.decode(prefs.getString(PREF_ORDER, null)));
        items.clear();
        for (String key : order) items.add(byKey.get(key));
        notifyDataSetChanged();
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
            h.label.setTextSize(14f);
            h.label.setFontVariationSettings("'wght' 500");
            h.card.setOnClickListener(view -> listener.onActionTile());
        }
        return h;
    }

    @Override
    public int getItemViewType(int position) {
        return isActionTile(position) ? TYPE_ACTION : TYPE_APP;
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
        h.card.setOnClickListener(v -> launch(e));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position, @NonNull List<Object> payloads) {
        if (payloads.isEmpty()) onBindViewHolder(h, position);
        else bindSlot(h, position, true);
    }

    private void bindSlot(Holder h, int position, boolean animate) {
        boolean action = isActionTile(position);
        // The dock has no names; the action tile carries its own.
        h.name.setVisibility(position < dockSize || action ? View.INVISIBLE : View.VISIBLE);
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
        return items.size() + 1;   // the apps, then the action tile
    }

    @Override
    public long getItemId(int position) {
        return isActionTile(position) ? ACTION_ID : items.get(position).key.hashCode();
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

    private static Drawable copy(Drawable d) {
        if (d == null) return null;
        return d.getConstantState() != null ? d.getConstantState().newDrawable().mutate() : d;
    }

    String labelAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).label : "";
    }

    /** The ambient glow colour sampled from the app's banner (or icon), cached per entry. See {@link AmbientColor}. */
    int ambientColorAt(int position) {
        if (position < 0 || position >= items.size()) return AmbientColor.FALLBACK;
        Entry e = items.get(position);
        if (e.ambient == 0) e.ambient = vividColor(e.banner != null ? e.banner : e.icon);
        return e.ambient;
    }

    private static int vividColor(Drawable live) {
        if (live == null) return AmbientColor.FALLBACK;
        // Draw a private copy: changing the bounds of the drawable an ImageView shows would blank the tile.
        Drawable d = live.getConstantState() != null ? live.getConstantState().newDrawable().mutate() : live;
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

            final float radius = density * 16f;
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
                } else if (event.isLongPress() && !longPressed) {
                    view.cancelLongPress();
                    view.performLongClick();
                }
                return false;
            }
            if (event.getAction() == KeyEvent.ACTION_UP && longPressed) {
                longPressed = false;
                view.setPressed(false);
                return true;
            }
            return false;
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
            return lifted ? LIFT_SCALE : focused ? FOCUS_SCALE : 1f;
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
