package com.gathin.tvhome;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.Outline;
import android.graphics.Rect;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.FloatPropertyCompat;
import androidx.dynamicanimation.animation.SpringAnimation;

/**
 * A top bar button: an icon and a label, in one of four looks (attribute topBarMode, set by the theme
 * overlay for config.xml's top_bar_style):
 * <ul>
 * <li>{@link #MODE_TAB}, Apple TV's Control Center: a round icon button; its background alone shows focus.</li>
 * <li>{@link #MODE_PILL}: a pill with icon and label, always.</li>
 * <li>{@link #MODE_EXPAND}, Apple TV: a round icon button that, when focused, widens into the pill and
 * closes back to the icon when focus leaves. The width follows a spring on an expansion fraction
 * (0 = circle, 1 = pill). The top bar is right-aligned, so a button grows leftward and only the buttons
 * before it glide aside.</li>
 * <li>{@link #MODE_CAPTION}, Google TV: a round icon button that grows a little and lifts when focused;
 * MainActivity shows its label under the bar.</li>
 * </ul>
 * The bar sits in {@link ProfileCorner}, which brings it out when focus moves up there.
 */
public class TopBarButton extends ViewGroup {

    static final int MODE_PILL = 0;
    static final int MODE_EXPAND = 1;
    static final int MODE_CAPTION = 2;
    static final int MODE_TAB = 3;

    /** Focused size in caption mode, as on the Google TV home screen (about 64 to 70 px). */
    static final float CAPTION_FOCUS_SCALE = 1.1f;
    private static final float CAPTION_LIFT_DP = 6f;

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
    private final int mode;
    private final SpringAnimation spring;
    private final SpringAnimation scaleX;
    private final SpringAnimation scaleY;
    private int iconRes;
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
        mode = a.getInt(R.styleable.TopBarButton_topBarMode, MODE_EXPAND);
        int iconSize = a.getDimensionPixelSize(R.styleable.TopBarButton_topBarIconSize, 0);
        if (iconSize > 0) {
            icon.getLayoutParams().width = iconSize;
            icon.getLayoutParams().height = iconSize;
        }
        ColorStateList tint = a.getColorStateList(R.styleable.TopBarButton_topBarIconTint);
        if (tint != null) icon.setImageTintList(tint);
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
        setClipToPadding(false);  // the paddings are the open pill's; the closed circle centres its icon over them

        spring = new SpringAnimation(this, EXPANSION, 0f);
        spring.getSpring().setStiffness(STIFFNESS).setDampingRatio(DAMPING);
        spring.setMinimumVisibleChange(DynamicAnimation.MIN_VISIBLE_CHANGE_SCALE);
        scaleX = new SpringAnimation(this, DynamicAnimation.SCALE_X, 1f);
        scaleY = new SpringAnimation(this, DynamicAnimation.SCALE_Y, 1f);
        setExpansion(mode == MODE_PILL ? 1f : 0f);
    }

    /** Whether MainActivity should show this button's label under the bar while it is focused. */
    boolean showsCaption() {
        return mode == MODE_CAPTION || mode == MODE_TAB;
    }

    /** Apple TV's Control Center tabs (the corner's default look): the caption fades in, in white. */
    boolean isTab() {
        return mode == MODE_TAB;
    }

    CharSequence getLabel() {
        return label.getText();
    }

    /** Text shown in the pill (or the caption). */
    void setLabel(CharSequence text) {
        // Unchanged (the network pill is refreshed on every Home press and every minute): no layout pass, no redraw.
        if (TextUtils.equals(label.getText(), text)) return;
        label.setText(text);
    }

    void setIcon(int drawableRes) {
        if (iconRes == drawableRes) return;
        iconRes = drawableRes;
        icon.setImageResource(drawableRes);
    }

    /** Width of the button when focused (open), for anchoring things to it. Valid after the first measure. */
    int getExpandedWidth() {
        return expandedWidth;
    }

    @Override
    protected void onFocusChanged(boolean gainFocus, int direction, Rect previouslyFocusedRect) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect);
        if (mode == MODE_EXPAND) {
            spring.animateToFinalPosition(gainFocus ? 1f : 0f);
        } else if (mode == MODE_CAPTION) {
            lift(gainFocus);
        }
        // MODE_PILL and MODE_TAB: the background alone shows focus.
    }

    /** Caption mode: grow and lift onto a shadow with the tile focus springs (AppAdapter). */
    private void lift(boolean focused) {
        float stiffness = focused ? 1100f : 500f;
        float damping = focused ? 0.68f : 1f;
        for (SpringAnimation a : new SpringAnimation[]{scaleX, scaleY}) {
            a.getSpring().setStiffness(stiffness).setDampingRatio(damping);
            a.animateToFinalPosition(focused ? CAPTION_FOCUS_SCALE : 1f);
        }
        float density = getResources().getDisplayMetrics().density;
        animate().translationZ(focused ? CAPTION_LIFT_DP * density : 0f)
                .setDuration(focused ? 120 : 220)
                .setInterpolator(new DecelerateInterpolator())
                .start();
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

    /** The theme overlay for config.xml's top_bar_style; Apple TV's Control Center (tvos) for anything unknown. */
    static int themeFor(String style) {
        switch (style == null ? "" : style.trim()) {
            case "pills":
                return R.style.ThemeOverlay_TopBar_Pills;
            case "apple":
                return R.style.ThemeOverlay_TopBar_Apple;
            case "google":
                return R.style.ThemeOverlay_TopBar_Google;
            default:
                return R.style.ThemeOverlay_TopBar_Tvos;
        }
    }

    static float labelAlpha(float expansion) {
        return Math.max(0f, Math.min(1f, (expansion - LABEL_FADE_FROM) / (1f - LABEL_FADE_FROM)));
    }
}
