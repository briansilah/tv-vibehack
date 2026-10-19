package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Rect;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.FloatValueHolder;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

/**
 * Scrolls the home grid on a spring, as tvOS does when focus moves between rows: the page starts from rest,
 * glides and settles without overshoot, and a new move while it is still going carries its speed on instead of
 * starting over (holding Down). RecyclerView's own smooth scroll restarts with a quintic curve at every move.
 *
 * Fitted to tvOS 26 recordings (the page moving between the top shelf and the grid, both ways): a spring with
 * stiffness about 225 and damping ratio 1.35, 90 % of the way in about 390 ms and settled in about 750 ms.
 */
final class GridScroller {

    static final float STIFFNESS = 225f;
    static final float DAMPING = 1.35f;

    private final RecyclerView grid;
    private final FloatValueHolder position = new FloatValueHolder(0f);
    private final SpringAnimation spring;
    /** Pixels scrolled so far by the current run, in the spring's coordinates. */
    private int applied;
    /** Where the current run ends, in the spring's coordinates. */
    private float target;

    GridScroller(RecyclerView grid) {
        this.grid = grid;
        if (grid.getLayoutManager() instanceof Layout) ((Layout) grid.getLayoutManager()).scroller = this;
        spring = new SpringAnimation(position);
        spring.setSpring(new SpringForce().setStiffness(STIFFNESS).setDampingRatio(DAMPING));
        spring.setMinimumVisibleChange(DynamicAnimation.MIN_VISIBLE_CHANGE_PIXELS);
        spring.addUpdateListener((a, value, velocity) -> apply(value));
        spring.addEndListener((a, canceled, value, velocity) -> {
            if (!canceled) apply(target);
        });
    }

    /**
     * Scrolls by {@code dy} more than the current run will (positive: the tiles move up). Measure {@code dy} from
     * where things will be once the current run ends: see {@link #pending()}.
     */
    void scrollBy(int dy) {
        if (dy == 0) return;
        grid.stopScroll();   // RecyclerView's own smooth scroll (MainActivity.focusTile's fallback) would fight it
        if (!spring.isRunning()) {
            applied = 0;
            target = 0f;
            position.setValue(0f);
        }
        target += dy;
        spring.animateToFinalPosition(target);
    }

    /** How far the current run has still to go: the tiles are drawn this much lower than where it leaves them. */
    int pending() {
        return spring.isRunning() ? Math.round(target) - applied : 0;
    }

    void stop() {
        if (spring.isRunning()) spring.cancel();
    }

    private void apply(float value) {
        int step = Math.round(value) - applied;
        if (step == 0) return;
        applied += step;
        grid.scrollBy(0, step);
    }

    /**
     * How far to scroll to show a focused grid tile, after tvOS: the page prefers to have the dock row at the top
     * (where Down from the dock takes it, showing as many rows as fit), and otherwise moves just enough to show the
     * whole tile. All values are in the grid's coordinates, as they will be once any running scroll ends.
     *
     * @param top       top of the room the focused tile needs (its grown size)
     * @param bottom    bottom of that room (its name included)
     * @param viewTop   top of the area tiles show in
     * @param viewBottom bottom of that area
     * @param dockAtTop scroll that puts the dock row at the top, or {@link Integer#MIN_VALUE} when the dock row is
     *                  too far away to be laid out (then the page only moves as much as it must)
     * @param minDy     the furthest the grid can scroll back (zero or less)
     * @param maxDy     the furthest it can scroll on (zero or more)
     */
    static int rowTarget(int top, int bottom, int viewTop, int viewBottom, int dockAtTop, int minDy, int maxDy) {
        int lo = bottom - viewBottom;   // scroll at least this to show its bottom
        int hi = top - viewTop;         // and at most this to keep its top
        int want = dockAtTop == Integer.MIN_VALUE ? 0 : dockAtTop;
        int dy = lo > hi ? hi : Math.max(lo, Math.min(want, hi));
        return Math.max(minDy, Math.min(dy, maxDy));
    }

    /**
     * The grid's layout manager: lays out a row beyond each edge, and as far again as a running scroll still has to
     * go, so the row that focus moves to is there to take focus even while you hold the D-pad. It leaves scrolling
     * to focus to {@link GridScroller}.
     */
    static final class Layout extends GridLayoutManager {
        private final int extra;
        private GridScroller scroller;

        Layout(Context ctx, int columns, int extraPx) {
            super(ctx, columns);
            this.extra = extraPx;
        }

        @Override
        protected void calculateExtraLayoutSpace(@NonNull RecyclerView.State state, @NonNull int[] extraLayoutSpace) {
            super.calculateExtraLayoutSpace(state, extraLayoutSpace);
            int ahead = scroller != null ? scroller.pending() : 0;   // > 0: the tiles move up, rows come from below
            extraLayoutSpace[0] = Math.max(extraLayoutSpace[0], extra + Math.max(0, -ahead));
            extraLayoutSpace[1] = Math.max(extraLayoutSpace[1], extra + Math.max(0, ahead));
        }

        /** MainActivity scrolls on each focus change (onTileFocused); RecyclerView's own scroll would fight it. */
        @Override
        public boolean requestChildRectangleOnScreen(@NonNull RecyclerView parent, @NonNull View child,
                                                     @NonNull Rect rect, boolean immediate,
                                                     boolean focusedChildVisible) {
            return false;
        }
    }
}
