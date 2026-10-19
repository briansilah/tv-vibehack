package com.gathin.tvhome;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewTreeObserver;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

/**
 * Arranging the home screen's apps with the remote, the way TV platforms do it (see the pull request for the
 * research): no dragging, no jiggle; the app being moved is lifted, ringed, and shows chevrons on the sides it
 * can still go to, while the others step back.
 *
 * <ul>
 * <li>Hold OK on an app for its actions, full screen as on Google TV: Move, Move to (or out of) Dock, App info.
 * <li>Move picks the app up. The arrows move it a slot or a row at a time, the dock being just the first row;
 *     OK puts it down and saves, Back puts it back where it was.
 * <li>The Arrange apps tile after the last app arranges several in one go: OK picks an app up and puts it down,
 *     and Back or the tile, now Done, finishes.
 * </ul>
 *
 * While arranging, a header in place of the top bar says what is going on and which keys do what.
 */
final class Rearranger {

    /** How long the other apps take to slide out of the way. */
    private static final long SHIFT_MS = 220L;

    private final Activity activity;
    private final FrameLayout root;
    private final RecyclerView grid;
    private final AppAdapter adapter;
    private final AppAdapter.TileListener tiles;
    private final int columns;
    private final float density;
    private final DefaultItemAnimator shifter = new DefaultItemAnimator();
    /**
     * Slides move tiles by their render properties, which doesn't redraw the grid, so the dock panel (an item
     * decoration) would stay behind; redraw it every frame while they run.
     */
    private final Runnable redraw = new Runnable() {
        @Override
        public void run() {
            grid.invalidate();
            if (SystemClock.uptimeMillis() < redrawUntil || shifter.isRunning()) grid.postOnAnimation(this);
        }
    };
    private long redrawUntil;

    private boolean arranging;
    /** Arranging several apps (from the Arrange apps tile), rather than moving the one whose Move was picked. */
    private boolean session;
    /** The app picked up, or null. */
    private String moving;
    /** Where it was picked up, to put it back. */
    private int pickedFrom;
    /** The order last saved, or when arranging started, so only real changes are saved. */
    private List<String> saved;
    /** Select went down while arranging, so its release acts. */
    private boolean selectDown;

    private View header;
    private TextView kicker;
    private TextView title;
    /** The legend's keys, built once: inflating text in the variable font on every move is slow on a TV. */
    private final View[] keys = new View[3];
    private View topBar;
    private PopupWindow sheet;

    Rearranger(Activity activity, FrameLayout root, RecyclerView grid, AppAdapter adapter,
               AppAdapter.TileListener tiles, int columns) {
        this.activity = activity;
        this.root = root;
        this.grid = grid;
        this.adapter = adapter;
        this.tiles = tiles;
        this.columns = columns;
        this.density = activity.getResources().getDisplayMetrics().density;
        shifter.setMoveDuration(SHIFT_MS);
        shifter.setSupportsChangeAnimations(false);
    }

    boolean isActive() {
        return arranging;
    }

    // ---- an app's actions (OK held) ---------------------------------------------------------------

    void showActions(int position) {
        if (arranging || sheet != null) return;
        final String app = adapter.keyAt(position);
        if (app == null) return;

        View view = activity.getLayoutInflater().inflate(R.layout.sheet_app_actions, root, false);
        View card = view.findViewById(R.id.sheetCard);
        final float radius = 22 * density;
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline outline) {
                outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
            }
        });
        card.setClipToOutline(true);
        Drawable banner = adapter.bannerAt(position);
        if (banner != null) {
            ((ImageView) view.findViewById(R.id.sheetBanner)).setImageDrawable(banner);
        } else {
            ImageView icon = view.findViewById(R.id.sheetIcon);
            icon.setImageDrawable(adapter.iconAt(position));
            icon.setVisibility(View.VISIBLE);
        }
        ((TextView) view.findViewById(R.id.sheetName)).setText(adapter.labelAt(position));

        final PopupWindow popup = new PopupWindow(view, root.getWidth(), root.getHeight(), true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));   // needed for Back to dismiss
        popup.setAnimationStyle(0);
        popup.setOnDismissListener(() -> {
            if (sheet == popup) sheet = null;
        });

        LinearLayout actions = view.findViewById(R.id.sheetActions);
        addAction(actions, popup, R.string.move, () -> begin(app, false));
        // Only the dock move that applies; there's nothing to show when there is no grid.
        if (AppOrder.dockToggleTarget(position, adapter.appCount(), columns) != position) {
            addAction(actions, popup, position < columns ? R.string.move_out_of_dock : R.string.move_to_dock,
                    () -> moveAcrossDock(app));
        }
        addAction(actions, popup, R.string.app_info, () -> adapter.openAppInfo(adapter.positionOf(app)));

        sheet = popup;
        int[] at = new int[2];
        root.getLocationOnScreen(at);
        popup.showAtLocation(root, Gravity.TOP | Gravity.START, at[0], at[1]);

        // In: the screen darkens, the app settles into place and its actions slide in after it.
        DecelerateInterpolator ease = new DecelerateInterpolator(1.8f);
        view.setAlpha(0f);
        view.animate().alpha(1f).setDuration(180).start();
        View appSide = view.findViewById(R.id.sheetApp);
        appSide.setScaleX(0.92f);
        appSide.setScaleY(0.92f);
        appSide.animate().scaleX(1f).scaleY(1f).setDuration(300).setInterpolator(ease).start();
        for (int i = 0; i < actions.getChildCount(); i++) {
            View row = actions.getChildAt(i);
            row.setAlpha(0f);
            row.setTranslationX(32 * density);
            row.animate().alpha(1f).translationX(0f).setStartDelay(60 + 40L * i).setDuration(260)
                    .setInterpolator(ease).start();
        }
        actions.getChildAt(0).requestFocus();
        // Likely next: build the header now, while the sheet covers the screen.
        root.post(this::ensureHeader);
    }

    private void addAction(LinearLayout actions, PopupWindow popup, int text, Runnable action) {
        TextView row = (TextView) activity.getLayoutInflater().inflate(R.layout.sheet_action, actions, false);
        row.setText(text);
        if (actions.getChildCount() == 0) ((ViewGroup.MarginLayoutParams) row.getLayoutParams()).topMargin = 0;
        row.setOnClickListener(v -> {
            popup.dismiss();
            action.run();
        });
        actions.addView(row);
    }

    /** Move to Dock (its last slot) or Move out of Dock (the grid's first), saved straight away. */
    private void moveAcrossDock(String app) {
        int from = adapter.positionOf(app);
        if (from < 0) return;
        grid.setItemAnimator(shifter);
        moveTo(app, from, AppOrder.dockToggleTarget(from, adapter.appCount(), columns));
        adapter.saveOrder();
        dropAnimator();
    }

    // ---- arranging --------------------------------------------------------------------------------

    /** The Arrange apps tile, or Done while arranging. */
    void onActionTile() {
        if (arranging) {
            end();
        } else {
            begin(null, true);
            follow(adapter.keyAt(0));
        }
    }

    /**
     * Starts arranging: picking up {@code app} for a single move, or, with {@code several}, waiting for one to
     * be picked up.
     */
    private void begin(String app, boolean several) {
        if (arranging) return;
        arranging = true;
        session = several;
        moving = null;
        selectDown = false;
        saved = adapter.keys();
        grid.setItemAnimator(shifter);
        showHeader(true);
        if (app != null) pickUp(app);
        else adapter.setArranging(true, null);
        updateHeader();
        refreshFocused();   // the header takes over from the top shelf
    }

    /** Stops arranging, keeping any app picked up where it is, and saves the order if it changed. */
    void end() {
        if (sheet != null) sheet.dismiss();
        if (!arranging) return;
        arranging = false;
        moving = null;
        selectDown = false;
        adapter.setArranging(false, null);
        saveIfChanged();
        showHeader(false);
        dropAnimator();
        refreshFocused();   // the top shelf returns if focus is on the dock
    }

    /** Reports the focused tile again, so the top shelf follows whether we're arranging. */
    private void refreshFocused() {
        View item = grid.getFocusedChild();
        if (item == null) return;
        int pos = grid.getChildAdapterPosition(item);
        View card = item.findViewById(R.id.card);
        if (pos != RecyclerView.NO_POSITION && card != null) tiles.onTileFocused(pos, card);
    }

    private void pickUp(String app) {
        moving = app;
        pickedFrom = adapter.positionOf(app);
        adapter.setArranging(true, app);
        updateHeader();
    }

    private void putDown() {
        moving = null;
        if (!session) {
            end();
            return;
        }
        adapter.setArranging(true, null);
        saveIfChanged();
        updateHeader();
    }

    /** Puts the app back where it was picked up: only it moved since, so that restores the order exactly. */
    private void putBack() {
        String app = moving;
        moving = null;
        int from = adapter.positionOf(app);
        if (from >= 0 && pickedFrom >= 0 && pickedFrom < adapter.appCount() && from != pickedFrom) {
            moveTo(app, from, pickedFrom);
        }
        if (!session) {
            end();
            return;
        }
        adapter.setArranging(true, null);
        updateHeader();
    }

    private void saveIfChanged() {
        List<String> now = adapter.keys();
        if (!now.equals(saved)) {
            adapter.saveOrder();
            saved = now;
        }
    }

    /** Back: puts down a picked-up app where it was, or finishes arranging. Returns false when not arranging. */
    boolean onBack() {
        if (!arranging) return false;
        if (moving != null) putBack();
        else end();
        return true;
    }

    /** Focus reached a tile. While an app is picked up only it should have focus; take it back if the grid moved it. */
    void onTileFocused(int position) {
        if (moving != null && !moving.equals(adapter.keyAt(position))) {
            final String app = moving;
            grid.post(() -> follow(app));
        }
    }

    /** Every key on a tile. Returns true when arranging handled it. */
    boolean onKey(int position, KeyEvent event) {
        if (!arranging) return false;
        int code = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        if (AppAdapter.isSelect(code)) {
            if (down) {
                if (event.getRepeatCount() == 0) selectDown = true;
                return true;
            }
            if (selectDown && !event.isCanceled()) {
                if (moving != null) putDown();
                else if (adapter.isActionTile(position)) end();
                else if (adapter.keyAt(position) != null) pickUp(adapter.keyAt(position));
            }
            selectDown = false;
            return true;
        }
        if (code != KeyEvent.KEYCODE_DPAD_LEFT && code != KeyEvent.KEYCODE_DPAD_RIGHT
                && code != KeyEvent.KEYCODE_DPAD_UP && code != KeyEvent.KEYCODE_DPAD_DOWN) {
            return false;
        }
        if (moving == null) {
            // Choosing an app: move focus as usual, but not up into the top bar, which the header replaces.
            return code == KeyEvent.KEYCODE_DPAD_UP && position < columns;
        }
        if (down) {
            int from = adapter.positionOf(moving);
            int to = AppOrder.step(from, adapter.appCount(), columns, code);
            // It stays put at the row ends, on the last row and at the top, as focus does.
            if (from >= 0 && to >= 0 && to != from) moveTo(moving, from, to);
        }
        return true;
    }

    private void moveTo(String app, int from, int to) {
        adapter.move(from, to);
        // Dock tiles have extra room above and below: recompute it for the slots that changed hands.
        if (Math.min(from, to) < columns && Math.max(from, to) >= columns) grid.invalidateItemDecorations();
        if (app.equals(moving)) updateHeader();
        // Once laid out, keep the app in view, as when focus moves.
        afterLayout(() -> follow(app));
        // The slide starts with the next layout; keep redrawing at least that long.
        redrawUntil = SystemClock.uptimeMillis() + SHIFT_MS + 150;
        grid.removeCallbacks(redraw);
        grid.postOnAnimation(redraw);
    }

    private void follow(String app) {
        int pos = adapter.positionOf(app);
        RecyclerView.LayoutManager lm = grid.getLayoutManager();
        if (pos < 0 || lm == null) return;
        // The layout manager's views exclude ones animating out, which are about to go.
        View item = lm.findViewByPosition(pos);
        if (item == null) {
            // Off screen, e.g. moved into the dock from far down the grid: bring it in, then focus it.
            grid.scrollToPosition(pos);
            afterLayout(() -> {
                int at = adapter.positionOf(app);
                View v = lm.findViewByPosition(at);
                if (v == null) return;
                // Usually still focused (the view was only animating out), so no focus change reports it.
                View c = v.findViewById(R.id.card);
                if (c.isFocused()) tiles.onTileFocused(at, c);
                else c.requestFocus();
            });
            return;
        }
        View card = item.findViewById(R.id.card);
        if (card.isFocused()) tiles.onTileFocused(pos, card);
        else card.requestFocus();
    }

    /** Runs {@code r} once the grid has laid out its pending changes (and queued their animations). */
    private void afterLayout(Runnable r) {
        grid.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                grid.getViewTreeObserver().removeOnPreDrawListener(this);
                grid.post(r);
                return true;
            }
        });
        grid.invalidate();
    }

    /** Outside arranging the grid has no item animations; let the last slide finish first. */
    private void dropAnimator() {
        afterLayout(() -> shifter.isRunning(() -> {
            if (!arranging && grid.getItemAnimator() == shifter) grid.setItemAnimator(null);
        }));
    }

    // ---- header -----------------------------------------------------------------------------------

    private void ensureHeader() {
        if (header != null) return;
        header = activity.getLayoutInflater().inflate(R.layout.arrange_header, root, false);
        kicker = header.findViewById(R.id.arrangeKicker);
        title = header.findViewById(R.id.arrangeTitle);
        LinearLayout legend = header.findViewById(R.id.arrangeLegend);
        for (int i = 0; i < keys.length; i++) {
            keys[i] = activity.getLayoutInflater().inflate(R.layout.legend_item, legend, false);
            if (i == 0) ((ViewGroup.MarginLayoutParams) keys[i].getLayoutParams()).setMarginStart(0);
            legend.addView(keys[i]);
        }
        root.addView(header);
        View left = root.findViewById(R.id.leftBlock);
        topBar = left != null ? (View) left.getParent() : null;
    }

    private void showHeader(boolean show) {
        if (header == null && !show) return;
        ensureHeader();
        header.animate().cancel();
        header.setTranslationY(show ? -10 * density : 0f);
        header.animate().alpha(show ? 1f : 0f).translationY(0f).setDuration(show ? 240 : 160)
                .setInterpolator(new DecelerateInterpolator(1.6f)).start();
        if (topBar != null) {
            topBar.animate().cancel();
            topBar.animate().alpha(show ? 0f : 1f).setDuration(show ? 160 : 240).start();
        }
    }

    /** What is going on, where the app is, and the keys that do something now. */
    private void updateHeader() {
        if (header == null) return;
        if (moving != null) {
            int pos = adapter.positionOf(moving);
            title.setText(activity.getString(R.string.moving_title, adapter.labelAt(pos)));
            kicker.setText(pos < columns ? activity.getString(R.string.location_dock)
                    : activity.getString(R.string.location_row, pos / columns));
            setKey(0, R.drawable.ic_dpad, R.string.legend_move);
            setKey(1, 0, session ? R.string.legend_drop : R.string.legend_done);
            setKey(2, R.drawable.ic_back, R.string.legend_cancel);
        } else {
            title.setText(R.string.arrange_title);
            kicker.setText(R.string.home_screen);
            setKey(0, 0, R.string.legend_pick_up);
            setKey(1, R.drawable.ic_back, R.string.legend_done);
            keys[2].setVisibility(View.GONE);
        }
    }

    /** Legend key {@code i}: the remote key's icon, or OK when {@code icon} is 0, and what it does. */
    private void setKey(int i, int icon, int label) {
        View item = keys[i];
        item.setVisibility(View.VISIBLE);
        ImageView glyph = item.findViewById(R.id.keyIcon);
        TextView ok = item.findViewById(R.id.keyText);
        if (icon != 0) glyph.setImageResource(icon);
        else ok.setText(R.string.key_ok);
        glyph.setVisibility(icon != 0 ? View.VISIBLE : View.GONE);
        ok.setVisibility(icon != 0 ? View.GONE : View.VISIBLE);
        ((TextView) item.findViewById(R.id.keyLabel)).setText(label);
    }
}
