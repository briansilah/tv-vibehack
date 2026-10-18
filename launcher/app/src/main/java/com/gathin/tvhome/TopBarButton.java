package com.gathin.tvhome;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Outline;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.FloatPropertyCompat;
import androidx.dynamicanimation.animation.SpringAnimation;

/**
 * A top bar button, tvOS style: a round icon button that, when focused, widens into a pill showing its
 * label, and closes back to the icon when focus leaves. The width follows a spring on an expansion
 * fraction (0 = circle, 1 = pill). The top bar is right-aligned, so a button grows leftward and only the
 * buttons before it glide aside.
 */
public class TopBarButton extends ViewGroup {

    /**
     * The tile focus spring ({@link AppAdapter}): quick, with a hint of overshoot. Closing uses the same
     * spring, so when focus moves along the bar one button's width flows into the next and the row keeps
     * its length, instead of the buttons before them sliding out and back.
     */
    static final float STIFFNESS = 1100f;
    static final float DAMPING = 0.68f;
    /** The label shows over the last part of the opening (and is gone early when closing), once it has room. */
    static final float LABEL_FADE_FROM = 0.4f;

    private static final FloatPropertyCompat<TopBarButton> EXPANSION = new FloatPropertyCompat<>("expansion") {
        @Override
        public float getValue(TopBarButton b) {
            return b.expansion;
        }

        @Override
        public void setValue(TopBarButton b, float value) {
            b.setExpansion(value);
        }
    };

    private final ImageView icon;
    private final TextView label;
    private final SpringAnimation spring;
    private float expansion;
    private int expandedWidth;

    public TopBarButton(Context context, AttributeSet attrs) {
        super(context, attrs);
        LayoutInflater.from(context).inflate(R.layout.top_bar_button, this, true);
        icon = findViewById(R.id.icon);
        label = findViewById(R.id.label);

        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.TopBarButton);
        icon.setImageDrawable(a.getDrawable(R.styleable.TopBarButton_android_src));
        CharSequence text = a.getText(R.styleable.TopBarButton_android_text);
        a.recycle();
        label.setText(text);
        if (getContentDescription() == null) setContentDescription(text);   // icon-only: name it for accessibility

        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), view.getHeight() / 2f);
            }
        });
        setClipToOutline(true);   // the label is uncovered by the growing pill, inside its rounded ends

        spring = new SpringAnimation(this, EXPANSION, 0f);
        spring.getSpring().setStiffness(STIFFNESS).setDampingRatio(DAMPING);
        spring.setMinimumVisibleChange(DynamicAnimation.MIN_VISIBLE_CHANGE_SCALE);
        setExpansion(0f);
    }

    /** Text shown when expanded. */
    void setLabel(CharSequence text) {
        label.setText(text);
    }

    /** Width of the button when focused (open), for anchoring things to it. Valid after the first measure. */
    int getExpandedWidth() {
        return expandedWidth;
    }

    @Override
    protected void onFocusChanged(boolean gainFocus, int direction, Rect previouslyFocusedRect) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect);
        spring.animateToFinalPosition(gainFocus ? 1f : 0f);
    }

    private void setExpansion(float value) {
        expansion = value;
        label.setAlpha(labelAlpha(value));
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        measureChild(icon, widthMeasureSpec, heightMeasureSpec);
        int unspecified = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
        label.measure(unspecified, unspecified);   // its natural width, capped by its maxWidth
        int h = resolveSize(Math.max(icon.getMeasuredHeight(), label.getMeasuredHeight()), heightMeasureSpec);
        expandedWidth = getPaddingStart() + icon.getMeasuredWidth() + labelGap() + label.getMeasuredWidth()
                + getPaddingEnd();
        setMeasuredDimension(width(h, expandedWidth, expansion), h);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int h = b - t;
        int iconW = icon.getMeasuredWidth(), iconH = icon.getMeasuredHeight();
        int iconLeft = iconLeft(h, iconW, getPaddingStart(), expansion);
        int iconTop = (h - iconH) / 2;
        icon.layout(iconLeft, iconTop, iconLeft + iconW, iconTop + iconH);
        int labelLeft = iconLeft + iconW + labelGap();
        int labelTop = (h - label.getMeasuredHeight()) / 2;
        label.layout(labelLeft, labelTop, labelLeft + label.getMeasuredWidth(), labelTop + label.getMeasuredHeight());
    }

    private int labelGap() {
        return ((MarginLayoutParams) label.getLayoutParams()).getMarginStart();
    }

    @Override
    protected boolean checkLayoutParams(ViewGroup.LayoutParams p) {
        return p instanceof MarginLayoutParams;
    }

    @Override
    public ViewGroup.LayoutParams generateLayoutParams(AttributeSet attrs) {
        return new MarginLayoutParams(getContext(), attrs);
    }

    @Override
    protected ViewGroup.LayoutParams generateDefaultLayoutParams() {
        return new MarginLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
    }

    @Override
    protected ViewGroup.LayoutParams generateLayoutParams(ViewGroup.LayoutParams p) {
        return new MarginLayoutParams(p);
    }

    // ---- geometry, separate so it can be unit tested --------------------------------------------

    /**
     * Width at an expansion: a circle (as wide as tall) at 0, the full pill at 1. The spring's overshoot
     * past 1 briefly widens the pill a little further, like the focused tiles; it never closes past the circle.
     */
    static int width(int height, int expandedWidth, float expansion) {
        int open = Math.max(height, expandedWidth);
        return height + Math.round((open - height) * Math.max(0f, expansion));
    }

    /** The icon sits centred in the circle and moves to the pill's start padding as it opens. */
    static int iconLeft(int height, int iconWidth, int paddingStart, float expansion) {
        float f = Math.max(0f, Math.min(1f, expansion));
        float centred = (height - iconWidth) / 2f;
        return Math.round(centred + (paddingStart - centred) * f);
    }

    static float labelAlpha(float expansion) {
        return Math.max(0f, Math.min(1f, (expansion - LABEL_FADE_FROM) / (1f - LABEL_FADE_FROM)));
    }
}
