package com.gathin.tvhome;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

/**
 * Draws the tvOS dock: a full-width, translucent rounded panel behind the first (dock) row, with room
 * around the tiles for the focused one to grow, and adds a gap below it.
 */
public class DockDecoration extends RecyclerView.ItemDecoration {

    /** Panel padding above and below the tiles: the focused tile grows into about half of it. */
    static final float PAD_V_DP = 22f;
    /** How far the panel reaches past the grid's content edges (into the grid's side padding). */
    private static final float EDGE_DP = 16f;

    private final int dockSize;
    private final float density;
    private final Paint band = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float[] tops = new float[16];

    DockDecoration(int dockSize, float density) {
        this.dockSize = dockSize;
        this.density = density;
        // Translucent like the Apple TV dock, so the top shelf's ambient glow shows through it.
        band.setColor(0x17FFFFFF);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(density);
        edge.setColor(0x24FFFFFF);
    }

    @Override
    public void getItemOffsets(@NonNull Rect outRect, @NonNull View view, @NonNull RecyclerView parent,
                               @NonNull RecyclerView.State state) {
        int pos = parent.getChildAdapterPosition(view);
        if (pos >= 0 && pos < dockSize) {
            outRect.top = (int) (6 * density);
            outRect.bottom = (int) (12 * density);
        }
    }

    @Override
    public void onDraw(@NonNull Canvas c, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        int n = 0;
        float height = 0;
        for (int i = 0; i < parent.getChildCount() && n < tops.length; i++) {
            View child = parent.getChildAt(i);
            int pos = parent.getChildAdapterPosition(child);
            if (pos < 0 || pos >= dockSize) continue;
            View card = child.findViewById(R.id.card);
            if (card == null) continue;
            tops[n++] = child.getTop() + card.getTop() + child.getTranslationY();
            height = card.getHeight();
        }
        if (n == 0) return;
        // Where most dock tiles are drawn: while apps are rearranged the panel slides with the whole row,
        // but a tile sliding into or out of the row doesn't stretch it.
        float top = tops[0];
        int most = 0;
        for (int i = 0; i < n; i++) {
            int count = 0;
            for (int j = 0; j < n; j++) {
                if (Math.abs(tops[j] - tops[i]) < density) count++;
            }
            if (count > most) {
                most = count;
                top = tops[i];
            }
        }
        float bottom = top + height;
        // Full width whatever the number of dock apps, as on tvOS.
        float edgeOut = EDGE_DP * density;
        float padV = PAD_V_DP * density;
        rect.set(parent.getPaddingLeft() - edgeOut, top - padV,
                parent.getWidth() - parent.getPaddingRight() + edgeOut, bottom + padV);
        float radius = 26 * density;
        c.drawRoundRect(rect, radius, radius, band);
        c.drawRoundRect(rect, radius, radius, edge);
    }
}
