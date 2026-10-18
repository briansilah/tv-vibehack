package com.gathin.tvhome;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

/**
 * Rearranging the home screen, as on tvOS 17 and later. Holding Select on an app opens its menu: Edit Home
 * Screen, Move to (or out of) Dock, and App Info (where Android keeps Uninstall; tvOS has Delete App there).
 * Edit Home Screen picks the app up: it jiggles, the D-pad moves it a slot or a row at a time and the other
 * apps make room. The dock is simply the first row, so apps move into and out of it the same way. Select saves
 * the new arrangement; Back puts the app back where it was.
 */
final class Rearranger {

    /** How long the other apps take to slide out of the way. */
    private static final long SHIFT_MS = 200L;
    /** Gap between the menu and the app it belongs to. */
    private static final float MENU_GAP_DP = 14f;

    private final Activity activity;
    private final FrameLayout root;
    private final RecyclerView grid;
    private final AppAdapter adapter;
    private final AppAdapter.TileListener tiles;
    private final int columns;
    private final float density;
    private final DefaultItemAnimator shifter = new DefaultItemAnimator();

    /** The app being moved; null when not rearranging. */
    private String key;
    /** The order when rearranging started: to put the app back, and so an unchanged order isn't saved. */
    private List<String> before;
    /** Select went down while rearranging, so its release saves. */
    private boolean selectDown;
    private TextView hint;
    private PopupWindow menu;

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
        return key != null;
    }

    // ---- the app's menu (held Select) -------------------------------------------------------------

    /** The menu for the app at {@code position}, popping up from its tile. */
    void showMenu(int position, View card) {
        if (key != null || menu != null) return;
        final String app = adapter.keyAt(position);
        if (app == null) return;

        View sheet = activity.getLayoutInflater().inflate(R.layout.popup_rearrange, root, false);
        View panel = sheet.findViewById(R.id.panel);
        ((TextView) sheet.findViewById(R.id.title)).setText(adapter.labelAt(position));
        LinearLayout rows = sheet.findViewById(R.id.rows);
        final PopupWindow popup = new PopupWindow(sheet, root.getWidth(), root.getHeight(), true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));   // needed for Back to dismiss
        popup.setAnimationStyle(0);
        popup.setOnDismissListener(() -> {
            if (menu == popup) menu = null;
        });

        addOption(rows, popup, R.string.edit_home_screen, () -> start(app));
        boolean inDock = position < columns;
        if (AppOrder.dockToggleTarget(position, adapter.getItemCount(), columns) != position) {
            addOption(rows, popup, inDock ? R.string.move_out_of_dock : R.string.move_to_dock, () -> {
                int from = adapter.positionOf(app);
                if (from < 0) return;
                int to = AppOrder.dockToggleTarget(from, adapter.getItemCount(), columns);
                grid.setItemAnimator(shifter);
                moveTo(app, from, to);
                adapter.saveOrder();
                dropAnimator();
            });
        }
        addOption(rows, popup, R.string.app_info, () -> adapter.openAppInfo(adapter.positionOf(app)));

        placeNextTo(panel, card);
        menu = popup;
        int[] at = new int[2];
        root.getLocationOnScreen(at);
        sheet.setAlpha(0f);
        panel.setScaleX(0.9f);
        panel.setScaleY(0.9f);
        popup.showAtLocation(root, Gravity.TOP | Gravity.START, at[0], at[1]);
        sheet.animate().alpha(1f).setDuration(140).start();
        panel.animate().scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(new DecelerateInterpolator(1.6f)).start();
        rows.getChildAt(0).requestFocus();
    }

    /** Above the tile (the focused row is always near the bottom of the screen), centred on it. */
    private void placeNextTo(View panel, View card) {
        int width = (int) (280 * density);
        panel.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        Rect tile = new Rect(0, 0, card.getWidth(), card.getHeight());
        root.offsetDescendantRectToMyCoords(card, tile);
        // The focused tile is scaled about its centre.
        float halfH = tile.height() * card.getScaleY() / 2f;
        float margin = 16 * density, gap = MENU_GAP_DP * density;
        int x = Math.round(tile.exactCenterX() - width / 2f);
        x = (int) Math.max(margin, Math.min(x, root.getWidth() - width - margin));
        int y = Math.round(tile.exactCenterY() - halfH - gap - panel.getMeasuredHeight());
        panel.setPivotY(panel.getMeasuredHeight());
        if (y < margin) {
            y = Math.round(tile.exactCenterY() + halfH + gap);   // no room above: hang below instead
            panel.setPivotY(0f);
        }
        panel.setPivotX(Math.max(0f, Math.min(width, tile.exactCenterX() - x)));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(width, FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.leftMargin = x;
        lp.topMargin = y;
        panel.setLayoutParams(lp);
    }

    private void addOption(LinearLayout rows, PopupWindow popup, int text, Runnable action) {
        TextView row = (TextView) activity.getLayoutInflater().inflate(R.layout.item_option, rows, false);
        row.setText(text);
        row.setOnClickListener(v -> {
            popup.dismiss();
            action.run();
        });
        rows.addView(row);
    }

    // ---- Edit Home Screen -------------------------------------------------------------------------

    /** Picks the app up: it jiggles and the D-pad moves it. */
    private void start(String app) {
        if (key != null || adapter.positionOf(app) < 0) return;
        key = app;
        before = adapter.keys();
        selectDown = false;
        grid.setItemAnimator(shifter);
        adapter.setEditing(key);
        showHint(true);
    }

    /** Drops the app: where it is, saving the order if it changed, or ({@code save} false) back where it was. */
    void finish(boolean save) {
        if (menu != null) menu.dismiss();
        if (key == null) return;
        String app = key;
        key = null;
        selectDown = false;
        adapter.setEditing(null);
        if (!save) {
            // Only this app moved, so putting it back at its old index restores the order exactly.
            int from = adapter.positionOf(app), to = before.indexOf(app);
            if (from >= 0 && to >= 0 && to < adapter.getItemCount()) moveTo(app, from, to);
        } else if (!adapter.keys().equals(before)) {
            adapter.saveOrder();
        }
        before = null;
        showHint(false);
        dropAnimator();
    }

    /**
     * Focus reached a tile. While rearranging only the app being moved should have it; if the grid hands
     * focus to another tile (say the app's view went off screen), take it back.
     */
    void onTileFocused(int position) {
        if (key != null && !key.equals(adapter.keyAt(position))) {
            final String app = key;
            grid.post(() -> follow(app));
        }
    }

    /** Every key on a tile. Returns true when rearranging handled it. */
    boolean onKey(KeyEvent event) {
        if (key == null) return false;
        int code = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        if (AppAdapter.isSelect(code)) {
            if (down) {
                if (event.getRepeatCount() == 0) selectDown = true;
                return true;
            }
            if (selectDown && !event.isCanceled()) finish(true);
            selectDown = false;
            return true;
        }
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) {
                    int from = adapter.positionOf(key);
                    int to = AppOrder.step(from, adapter.getItemCount(), columns, code);
                    // It stays put at the row ends, on the last row and below the top bar, as focus does.
                    if (from >= 0 && to >= 0 && to != from) moveTo(key, from, to);
                }
                return true;
            default:
                return false;
        }
    }

    private void moveTo(String app, int from, int to) {
        adapter.move(from, to);
        // Dock tiles have extra room above and below: recompute it for the slots that changed hands.
        if (Math.min(from, to) < columns && Math.max(from, to) >= columns) grid.invalidateItemDecorations();
        // Once laid out, keep the app in view and the top shelf on it, as when focus moves.
        afterLayout(() -> follow(app));
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

    /** Outside rearranging the grid has no item animations; let the last slide finish first. */
    private void dropAnimator() {
        afterLayout(() -> shifter.isRunning(() -> {
            if (key == null && grid.getItemAnimator() == shifter) grid.setItemAnimator(null);
        }));
    }

    /** How to finish: there's no Done button, and Back cancelling isn't obvious. */
    private void showHint(boolean show) {
        if (hint == null) {
            if (!show) return;
            hint = (TextView) activity.getLayoutInflater().inflate(R.layout.rearrange_hint, root, false);
            root.addView(hint);
        }
        hint.animate().cancel();
        hint.animate().alpha(show ? 1f : 0f).translationY(show ? 0f : 8 * density)
                .setDuration(show ? 220 : 160).start();
    }
}
