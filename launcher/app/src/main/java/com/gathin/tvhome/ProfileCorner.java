package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewTreeObserver;
import android.widget.TextView;

import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.FloatPropertyCompat;
import androidx.dynamicanimation.animation.SpringAnimation;

import java.util.Locale;

/**
 * The top right of the home screen, as on Apple TV (tvOS 26): the time and the profile picture in a capsule of Liquid
 * Glass ({@link LiquidGlass}), the same material as the dock's.
 * When focus moves into it, it opens like the header of Control Center:
 * <ul>
 * <li>The picture stays exactly where it is, at its size.</li>
 * <li>The top bar's buttons come out from behind the picture and slide left to their places, fading in as they clear
 * it, while the capsule fades away and the time glides left. A thin divider travels with the time and keeps it
 * apart from the buttons.</li>
 * <li>Closing is the same, back: the buttons slide right into the picture and the capsule fades back in.</li>
 * </ul>
 *
 * <p>Children: the capsule, the divider, the time (a TextView), the top bar (set with {@link #setBar}), the picture's
 * focus ring and the picture ({@code R.id.avatar}), drawn in that order so the buttons pass under the picture. They
 * are laid out once, where they sit when open; opening and closing change only translations and alpha (render
 * properties), so nothing is laid out or redrawn while it moves. Sizes are tvOS's, measured at 1080p, where 1 dp
 * is 2 px, times {@link #SIZE}; the PR that added this has the table and the recordings.
 */
public class ProfileCorner extends ViewGroup {

    /** Everything a fifth larger than on tvOS, which looked small on a TV across the room. layout/activity_main.xml
     *  and the TopButton.Tvos and Text.Time styles use the same factor. */
    static final float SIZE = 1.2f;

    // ---- at rest: the capsule ------------------------------------------------------------------------------
    /** Capsule height: 60 px on tvOS. */
    static final float CAPSULE_HEIGHT_DP = 30f * SIZE;
    /** From the capsule's left end to the time: 24 px. */
    static final float TIME_INSET_DP = 12f * SIZE;
    /** From the time to the picture: 12 px. */
    static final float TIME_GAP_DP = 6f * SIZE;
    /** The picture, 50 px across, 5 px inside the capsule's right end. It keeps this size and place when open. */
    static final float AVATAR_DP = 25f * SIZE;
    static final float AVATAR_INSET_DP = 2.5f * SIZE;

    // ---- open ----------------------------------------------------------------------------------------------
    /** Each button takes a 60 px circle, side by side, the last right before the picture's own circle. */
    static final float SLOT_DP = 30f * SIZE;
    /**
     * Open, the time sits this far before a thin divider, and the divider this far before the first button's circle;
     * the button's glyph is another 8.5 dp in (TopButton.Tvos), so the divider looks about centred between the time
     * and the glyph.
     */
    static final float TIME_DIVIDER_GAP_DP = 12f * SIZE;
    static final float DIVIDER_TAB_GAP_DP = 4f * SIZE;
    /** The divider: as tall as half the capsule, a quarter white. */
    static final float DIVIDER_HEIGHT_DP = 15f * SIZE;
    static final int DIVIDER_COLOR = 0x40FFFFFF;
    /** A button is fully there once it has moved this many slots out from behind the picture. */
    static final float TAB_FADE_SLOTS = 0.9f;
    /** The capsule is gone once the corner is this far open (and back from there, closing). */
    static final float CAPSULE_FADE = 0.6f;
    /** The picture's focus ring fades out like a button's circle (drawable/tab_bg.xml). */
    static final long RING_OUT_MS = 150L;

    /**
     * The motion: one spring for everything, settling without a visible overshoot, 95 % of the way after about
     * 300 ms, as long as tvOS takes from its first moving frame. It starts with some speed, so the first frame
     * moves, but without tvOS's jump to 45 % on that frame. Being a spring, focus can turn it around mid-way.
     */
    static final float STIFFNESS = 150f;
    static final float DAMPING = 0.9f;
    static final float START_VELOCITY = 4f;

    private static final FloatPropertyCompat<ProfileCorner> OPENNESS = new FloatPropertyCompat<>("openness") {
        @Override
        public float getValue(ProfileCorner c) {
            return c.openness;
        }

        @Override
        public void setValue(ProfileCorner c, float value) {
            c.openness = value;
            c.apply();
        }
    };

    private final float density;
    private final GlassView capsule;
    private final View divider;
    private final View ring;
    private TextView time;
    private View avatar;
    private View bar;
    private final SpringAnimation openSpring;
    /** 0 at rest, 1 open. */
    private float openness;
    private boolean open;
    /** From the last layout: where the time sits at rest (open, it is at its own place). */
    private float timeRestLeft;

    private final ViewTreeObserver.OnGlobalFocusChangeListener focusListener = (oldFocus, newFocus) -> {
        setOpen(contains(newFocus));
        setAvatarFocused(newFocus != null && newFocus == avatar);
    };

    public ProfileCorner(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        capsule = new GlassView(context);
        capsule.setGlass(new LiquidGlass(null, LiquidGlass.CAPSULE, density));
        divider = new FlatView(context);
        divider.setBackgroundColor(DIVIDER_COLOR);
        divider.setAlpha(0f);
        ring = new FlatView(context);
        ring.setBackground(context.getDrawable(R.drawable.avatar_ring));
        ring.setAlpha(0f);
        for (View v : new View[]{capsule, divider, ring}) v.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);

        openSpring = new SpringAnimation(this, OPENNESS, 0f);
        openSpring.getSpring().setStiffness(STIFFNESS).setDampingRatio(DAMPING);
        openSpring.setMinimumVisibleChange(DynamicAnimation.MIN_VISIBLE_CHANGE_SCALE);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        time = findViewById(R.id.time);
        avatar = findViewById(R.id.avatar);
        avatar.setOutlineProvider(ViewOutlineProvider.BACKGROUND);   // an oval: the photo or silhouette is cut round
        avatar.setClipToOutline(true);
        addView(capsule, 0);                         // under everything
        addView(divider, 1);
        addView(ring, indexOfChild(avatar));         // right under the picture, over the buttons
    }

    /** The top bar, once MainActivity has inflated it with its look. */
    void setBar(View bar) {
        this.bar = bar;
        requestLayout();
    }

    /** What shows through the capsule's glass: the dock's backdrop. */
    void setBackdrop(LiquidGlass.Backdrop backdrop) {
        capsule.setGlass(new LiquidGlass(backdrop, LiquidGlass.CAPSULE, density));
    }

    View getAvatar() {
        return avatar;
    }

    /** Open, or nearly: past the point where the buttons have all but arrived. */
    boolean isOpen() {
        return open && openness > 0.9f;
    }

    /**
     * The picture without a photo: the initials of {@code name} on Apple's grey circle, or the silhouette when it is
     * empty. Also names it for accessibility.
     */
    void setProfileName(String name) {
        String initials = initials(name);
        TextView text = avatar.findViewById(R.id.avatarInitials);
        View silhouette = avatar.findViewById(R.id.avatarImage);
        text.setText(initials);
        text.setVisibility(initials.isEmpty() ? GONE : VISIBLE);
        silhouette.setVisibility(initials.isEmpty() ? VISIBLE : GONE);
        avatar.setContentDescription(initials.isEmpty() ? getContext().getString(R.string.profile)
                : getContext().getString(R.string.profile_named, name.trim()));
    }

    /** Closes at once, without the animation: for when Home comes back from an app opened from here. */
    void snapClosed() {
        open = false;
        openSpring.cancel();
        ring.animate().cancel();
        ring.setAlpha(0f);
        openness = 0f;
        apply();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getViewTreeObserver().addOnGlobalFocusChangeListener(focusListener);
    }

    @Override
    protected void onDetachedFromWindow() {
        getViewTreeObserver().removeOnGlobalFocusChangeListener(focusListener);
        super.onDetachedFromWindow();
    }

    private boolean contains(View v) {
        for (View p = v; p != null; p = p.getParent() instanceof View ? (View) p.getParent() : null) {
            if (p == this) return true;
        }
        return false;
    }

    private void setOpen(boolean open) {
        if (this.open == open) return;
        this.open = open;
        // From rest, start with some speed; mid-way, the spring turns around with the speed it has.
        if (!openSpring.isRunning()) openSpring.setStartVelocity(open ? START_VELOCITY : -START_VELOCITY);
        openSpring.animateToFinalPosition(open ? 1f : 0f);
    }

    /** The picture keeps its size; focus puts a white ring round it, like a button's circle. */
    private void setAvatarFocused(boolean focused) {
        ring.animate().cancel();
        if (focused) {
            ring.setAlpha(1f);
        } else if (ring.getAlpha() > 0f) {
            ring.animate().alpha(0f).setDuration(RING_OUT_MS).start();
        }
    }

    // ---- layout ------------------------------------------------------------------------------------------

    private int dp(float v) {
        return Math.round(v * density);
    }

    /** A hairline: 1 px, or 2 px from 1080p on (1 dp there). */
    private int dividerWidth() {
        return Math.max(1, Math.round(density / 2f));
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int unspecified = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
        time.measure(unspecified, unspecified);
        boolean hasBar = bar != null && bar.getVisibility() != GONE;
        if (hasBar) bar.measure(unspecified, unspecified);
        int a = MeasureSpec.makeMeasureSpec(dp(AVATAR_DP), MeasureSpec.EXACTLY);
        avatar.measure(a, a);
        int slot = MeasureSpec.makeMeasureSpec(dp(SLOT_DP), MeasureSpec.EXACTLY);
        ring.measure(slot, slot);
        divider.measure(MeasureSpec.makeMeasureSpec(dividerWidth(), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(dp(DIVIDER_HEIGHT_DP), MeasureSpec.EXACTLY));

        int restWidth = restWidth(dp(TIME_INSET_DP), time.getMeasuredWidth(), dp(TIME_GAP_DP), dp(AVATAR_DP),
                dp(AVATAR_INSET_DP));
        int capsuleHeight = dp(CAPSULE_HEIGHT_DP);
        capsule.measure(MeasureSpec.makeMeasureSpec(restWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(capsuleHeight, MeasureSpec.EXACTLY));

        // Open: the time, the divider, the buttons, then the picture's slot, centred on the picture.
        int avatarSlotRight = dp(AVATAR_INSET_DP) + dp(AVATAR_DP) / 2 - dp(SLOT_DP) / 2;
        int openWidth = time.getMeasuredWidth() + dp(TIME_DIVIDER_GAP_DP) + dividerWidth() + dp(DIVIDER_TAB_GAP_DP)
                + (hasBar ? bar.getMeasuredWidth() : 0) + dp(SLOT_DP) + avatarSlotRight;
        int w = Math.max(restWidth, openWidth);
        int h = Math.max(Math.max(capsuleHeight, dp(SLOT_DP)), Math.max(time.getMeasuredHeight(),
                hasBar ? bar.getMeasuredHeight() : 0));
        setMeasuredDimension(resolveSize(w, widthMeasureSpec), resolveSize(h, heightMeasureSpec));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int w = r - l, cy = (b - t) / 2;
        int capsuleW = capsule.getMeasuredWidth(), capsuleH = capsule.getMeasuredHeight();
        capsule.layout(w - capsuleW, cy - capsuleH / 2, w, cy - capsuleH / 2 + capsuleH);

        int a = avatar.getMeasuredWidth();
        int avatarLeft = w - dp(AVATAR_INSET_DP) - a;
        avatar.layout(avatarLeft, cy - a / 2, avatarLeft + a, cy - a / 2 + a);
        int avatarCenter = avatarLeft + a / 2;
        int rs = ring.getMeasuredWidth();
        ring.layout(avatarCenter - rs / 2, cy - rs / 2, avatarCenter - rs / 2 + rs, cy - rs / 2 + rs);

        int barLeft = avatarCenter - dp(SLOT_DP) / 2;   // the buttons end where the picture's circle starts
        if (bar != null && bar.getVisibility() != GONE) {
            int bw = bar.getMeasuredWidth(), bh = bar.getMeasuredHeight();
            barLeft -= bw;
            bar.layout(barLeft, cy - bh / 2, barLeft + bw, cy - bh / 2 + bh);
        }

        int dw = divider.getMeasuredWidth(), dh = divider.getMeasuredHeight();
        int dividerLeft = barLeft - dp(DIVIDER_TAB_GAP_DP) - dw;
        divider.layout(dividerLeft, cy - dh / 2, dividerLeft + dw, cy - dh / 2 + dh);

        // The digits, not the text box, centred on the capsule: whatever the font's ascent and descent.
        Rect digits = new Rect();
        time.getPaint().getTextBounds("0", 0, 1, digits);
        int tw = time.getMeasuredWidth(), th = time.getMeasuredHeight();
        int timeTop = Math.round(cy + digits.height() / 2f - time.getBaseline());
        int timeLeft = openTimeLeft(dividerLeft, dp(TIME_DIVIDER_GAP_DP), tw);
        time.layout(timeLeft, timeTop, timeLeft + tw, timeTop + th);
        timeRestLeft = w - capsuleW + dp(TIME_INSET_DP);
        apply();
    }

    /** Puts everything where it is at the current openness. Render properties only. */
    private void apply() {
        if (time == null) return;
        float f = openness;
        float closed = 1f - f;
        float timeShift = (timeRestLeft - time.getLeft()) * closed;
        time.setTranslationX(timeShift);
        divider.setTranslationX(timeShift);   // it keeps its distance from the time, so never crosses it
        divider.setAlpha(clamp(f));
        capsule.setAlpha(capsuleAlpha(f));

        if (bar == null) return;
        float avatarCenter = (avatar.getLeft() + avatar.getRight()) / 2f;
        float slot = SLOT_DP * density;
        if (bar.getBackground() != null || !(bar instanceof ViewGroup)) {
            // A look with a tray behind the buttons (google): the tray comes out from the picture as one piece.
            float dx = avatarCenter - (bar.getLeft() + bar.getRight()) / 2f;
            bar.setTranslationX(dx * closed);
            bar.setAlpha(tabAlpha(dx * f, slot));
            return;
        }
        ViewGroup row = (ViewGroup) bar;
        for (int i = 0; i < row.getChildCount(); i++) {
            View tab = row.getChildAt(i);
            float dx = avatarCenter - (bar.getLeft() + (tab.getLeft() + tab.getRight()) / 2f);
            tab.setTranslationX(dx * closed);
            tab.setAlpha(tabAlpha(dx * f, slot));
        }
    }

    // ---- geometry and motion, separate so they can be unit tested -------------------------------------------

    private static float clamp(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    static int restWidth(int timeInset, int timeWidth, int timeGap, int avatar, int avatarInset) {
        return timeInset + timeWidth + timeGap + avatar + avatarInset;
    }

    /** Where the time's left edge goes when open: {@code gap} before the divider. */
    static int openTimeLeft(int dividerLeft, int gap, int timeWidth) {
        return Math.max(0, dividerLeft - gap - timeWidth);
    }

    /** A button's alpha once it has moved {@code moved} px out from behind the picture. */
    static float tabAlpha(float moved, float slot) {
        return clamp(moved / (TAB_FADE_SLOTS * slot));
    }

    static float capsuleAlpha(float openness) {
        return clamp(1f - openness / CAPSULE_FADE);
    }

    /**
     * Position (0 to 1) of the spring {@link #STIFFNESS}, {@link #DAMPING}, released at 0 towards 1 with
     * {@link #START_VELOCITY}, {@code seconds} later.
     */
    static float position(float seconds) {
        double w = Math.sqrt(STIFFNESS), z = DAMPING;
        double wd = w * Math.sqrt(1 - z * z);
        return (float) (1 - Math.exp(-z * w * seconds)
                * (Math.cos(wd * seconds) + (z * w - START_VELOCITY) / wd * Math.sin(wd * seconds)));
    }

    /** Up to two initials, as Apple shows them: of the first and last word ("Brian Silah" → "BS"). */
    static String initials(String name) {
        if (name == null) return "";
        String[] words = name.trim().split("\\s+");
        if (words.length == 0 || words[0].isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.appendCodePoint(words[0].codePointAt(0));
        if (words.length > 1) sb.appendCodePoint(words[words.length - 1].codePointAt(0));
        return sb.toString().toUpperCase(Locale.getDefault());
    }

    /** A view whose fades draw straight to the screen, without an offscreen layer: it is one flat shape. */
    private static class FlatView extends View {
        FlatView(Context context) {
            super(context);
        }

        @Override
        public boolean hasOverlappingRendering() {
            return false;
        }
    }

    /** The capsule: a flat shape too, of Liquid Glass with round ends. */
    private static final class GlassView extends FlatView {
        private LiquidGlass glass;

        GlassView(Context context) {
            super(context);
            setWillNotDraw(false);
        }

        void setGlass(LiquidGlass glass) {
            this.glass = glass;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float h = getHeight();
            glass.draw(canvas, this, 0f, 0f, getWidth(), h, h / 2f);
        }
    }
}
