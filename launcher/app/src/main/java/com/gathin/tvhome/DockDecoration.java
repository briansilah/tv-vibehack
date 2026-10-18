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
        boolean any = false;
        float top = 0, bottom = 0;
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            int pos = parent.getChildAdapterPosition(child);
            if (pos < 0 || pos >= dockSize) continue;
            View card = child.findViewById(R.id.card);
            if (card == null) continue;
            float t = child.getTop() + card.getTop() + child.getTranslationY();
            float b = t + card.getHeight();
            if (!any) {
                top = t;
                bottom = b;
                any = true;
            } else {
                top = Math.min(top, t);
                bottom = Math.max(bottom, b);
            }
        }
        if (!any) return;
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
