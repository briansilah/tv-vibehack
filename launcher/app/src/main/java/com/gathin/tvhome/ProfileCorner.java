package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewTreeObserver;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.FloatPropertyCompat;
import androidx.dynamicanimation.animation.SpringAnimation;

import java.util.Locale;

/**
 * The top right of the home screen, as on Apple TV (tvOS 26): the time and the profile picture in a glass capsule.
 * When focus moves into it, it opens like the header of Control Center, as measured frame by frame on tvOS:
 * <ul>
 * <li>Opening: the capsule clears, the time glides left, and the top bar's buttons slide in from the right as one
 * row, passing under the picture, which shrinks to the size of a tab. Unlike tvOS, which puts the time much further
 * left (where Control Center's panel starts), the time stops just before the buttons, past a thin divider.</li>
 * <li>Closing: the buttons are gone at once and the picture is back at its size; the capsule is back too, still
 * stretched to the time, and its left end follows the time home.</li>
 * </ul>
 *
 * <p>Children: the capsule, the time (a TextView), the top bar (set with {@link #setBar}) and the picture
 * ({@code R.id.avatar}), drawn in that order so the buttons pass under the picture. They are laid out once, where
 * they sit when open; opening and closing change translations, alpha and scale (render properties), and redraw
 * only the capsule while it closes. Sizes are tvOS's, measured at 1080p, where 1 dp is 2 px, times {@link #SIZE}; the
 * PR that added this has the table and the recordings.
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
    /** The picture, 50 px across, 5 px inside the capsule's right end. */
    static final float AVATAR_REST_DP = 25f * SIZE;
    static final float AVATAR_REST_INSET_DP = 2.5f * SIZE;

    // ---- open: Control Center's header -------------------------------------------------------------------
    /** Each tab, and the picture, take a 60 px circle, side by side; the last ends 10 px inside the capsule's end. */
    static final float SLOT_DP = 30f * SIZE;
    static final float OPEN_INSET_DP = 5f * SIZE;
    /** The picture is 38 px across among the tabs, 52 px when focused. The view is laid out at the focused size. */
    static final float AVATAR_OPEN_DP = 19f * SIZE;
    static final float AVATAR_FOCUSED_DP = 26f * SIZE;
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
    /** The row of buttons slides in from 231 px right of where it ends, on the same spring as the time. */
    static final float TAB_TRAVEL_DP = 115.5f * SIZE;

    /**
     * The motion: a spring fitted to three recordings of tvOS 26 opening and closing Control Center (two at 60 fps,
     * one at 30 fps; 0.7 % RMS error). It settles without a visible overshoot, 95 % of the way 310 ms after the
     * first frame that moves. Being a spring, focus can turn it around mid-way.
     */
    static final float STIFFNESS = 88f;
    static final float DAMPING = 0.92f;
    /**
     * tvOS's first frame that moves is already this far into the spring: about 45 % of the way when opening and
     * 30 % when closing, in all three recordings. The rest of the motion is the spring's; so it starts there.
     */
    static final float OPEN_LEAP_S = 0.15f;
    static final float CLOSE_LEAP_S = 0.115f;
    /**
     * The capsule: 30 % there in the first frame of opening, gone 80 ms later; 75 % back in the first frame of
     * closing, all there 70 ms later.
     */
    static final float CAPSULE_OPEN_FROM = 0.3f;
    static final long CAPSULE_OUT_MS = 80L;
    static final float CAPSULE_CLOSE_FROM = 0.75f;
    static final long CAPSULE_IN_MS = 70L;

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

    private static final FloatPropertyCompat<ProfileCorner> AVATAR_FOCUS = new FloatPropertyCompat<>("avatarFocus") {
        @Override
        public float getValue(ProfileCorner c) {
            return c.avatarFocus;
        }

        @Override
        public void setValue(ProfileCorner c, float value) {
            c.avatarFocus = value;
            c.apply();
        }
    };

    private final float density;
    private final View capsule;
    private final GlassCapsule glass;
    private final View divider;
    private TextView time;
    private View avatar;
    private View bar;
    private final SpringAnimation openSpring;
    private final SpringAnimation avatarSpring;
    /** 0 at rest, 1 open. */
    private float openness;
    /** 0: the picture is a tab like the others; 1: it has focus and is at full size. */
    private float avatarFocus;
    private boolean open;

    // From the last layout: where things sit at rest (open positions are the children's own).
    private float timeRestLeft;
    private float avatarRestCenter;
    private int capsuleRestLeft;

    private final ViewTreeObserver.OnGlobalFocusChangeListener focusListener = (oldFocus, newFocus) -> {
        setOpen(contains(newFocus));
        setAvatarFocused(newFocus != null && newFocus == avatar);
    };

    public ProfileCorner(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        capsule = new View(context) {
            @Override
            public boolean hasOverlappingRendering() {
                return false;   // its fades draw the capsule straight to the screen, without an offscreen layer
            }
        };
        glass = new GlassCapsule(density);
        capsule.setBackground(glass);
        capsule.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(capsule, 0);   // first, so it is drawn under the time and the picture
        divider = new View(context);
        divider.setBackgroundColor(DIVIDER_COLOR);
        divider.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        divider.setAlpha(0f);
        addView(divider, 1);

        openSpring = new SpringAnimation(this, OPENNESS, 0f);
        openSpring.getSpring().setStiffness(STIFFNESS).setDampingRatio(DAMPING);
        openSpring.setMinimumVisibleChange(DynamicAnimation.MIN_VISIBLE_CHANGE_SCALE);
        avatarSpring = new SpringAnimation(this, AVATAR_FOCUS, 0f);
        avatarSpring.getSpring().setStiffness(TopBarButton.STIFFNESS).setDampingRatio(TopBarButton.DAMPING);
        avatarSpring.setMinimumVisibleChange(DynamicAnimation.MIN_VISIBLE_CHANGE_SCALE);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        time = findViewById(R.id.time);
        avatar = findViewById(R.id.avatar);
        avatar.setOutlineProvider(ViewOutlineProvider.BACKGROUND);   // an oval: the photo or silhouette is cut round
        avatar.setClipToOutline(true);
    }

    /** The top bar, once MainActivity has inflated it with its look. */
    void setBar(View bar) {
        this.bar = bar;
        requestLayout();
    }

    View getAvatar() {
        return avatar;
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
        avatarSpring.cancel();
        capsule.animate().cancel();
        capsule.setAlpha(1f);
        openness = 0f;
        avatarFocus = 0f;
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
        capsule.animate().cancel();
        if (open) {
            capsule.setAlpha(Math.min(capsule.getAlpha(), CAPSULE_OPEN_FROM));
            capsule.animate().alpha(0f).setDuration(CAPSULE_OUT_MS).start();
        } else {
            capsule.setAlpha(Math.max(capsule.getAlpha(), CAPSULE_CLOSE_FROM));
            capsule.animate().alpha(1f).setDuration(CAPSULE_IN_MS).start();
        }
        if (!openSpring.isRunning()) {
            // From rest: start where tvOS's first moving frame is, at the spring's speed there.
            float[] leap = leap(open ? OPEN_LEAP_S : CLOSE_LEAP_S);
            openSpring.setStartValue(open ? leap[0] : 1f - leap[0]);
            openSpring.setStartVelocity(open ? leap[1] : -leap[1]);
        }
        openSpring.animateToFinalPosition(open ? 1f : 0f);
        apply();   // the buttons and the picture change at once when closing
    }

    private void setAvatarFocused(boolean focused) {
        if (avatarSpring.isRunning() || avatarFocus != (focused ? 1f : 0f)) {
            avatarSpring.animateToFinalPosition(focused ? 1f : 0f);
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
        int avatarSize = dp(AVATAR_FOCUSED_DP);
        int exact = MeasureSpec.makeMeasureSpec(avatarSize, MeasureSpec.EXACTLY);
        avatar.measure(exact, exact);

        int dividerW = dividerWidth();
        divider.measure(MeasureSpec.makeMeasureSpec(dividerW, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(dp(DIVIDER_HEIGHT_DP), MeasureSpec.EXACTLY));
        int barWidth = hasBar ? bar.getMeasuredWidth() : 0;
        int openWidth = dp(OPEN_INSET_DP) + dp(SLOT_DP) + barWidth + dp(DIVIDER_TAB_GAP_DP) + dividerW
                + dp(TIME_DIVIDER_GAP_DP) + time.getMeasuredWidth();
        int restWidth = restWidth(dp(TIME_INSET_DP), time.getMeasuredWidth(), dp(TIME_GAP_DP), dp(AVATAR_REST_DP),
                dp(AVATAR_REST_INSET_DP));
        // Room on the left for the capsule's end, which follows the time home when closing.
        int w = Math.max(restWidth, openWidth) + dp(TIME_INSET_DP);
        int capsuleHeight = dp(CAPSULE_HEIGHT_DP);
        // The capsule view spans the whole width; its drawable is as wide as the time needs (see apply()).
        capsule.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(capsuleHeight, MeasureSpec.EXACTLY));
        int h = Math.max(Math.max(capsuleHeight, avatarSize), Math.max(time.getMeasuredHeight(),
                hasBar ? bar.getMeasuredHeight() : 0));
        setMeasuredDimension(resolveSize(w, widthMeasureSpec), resolveSize(h, heightMeasureSpec));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int w = r - l, cy = (b - t) / 2;
        int capsuleH = capsule.getMeasuredHeight();
        capsule.layout(0, cy - capsuleH / 2, w, cy - capsuleH / 2 + capsuleH);

        int slot = dp(SLOT_DP);
        int slotsRight = w - dp(OPEN_INSET_DP);   // the picture's slot ends here, the buttons' where it starts
        int a = avatar.getMeasuredWidth();
        int avatarLeft = slotsRight - slot / 2 - a / 2;
        avatar.layout(avatarLeft, cy - a / 2, avatarLeft + a, cy - a / 2 + a);

        int barLeft = slotsRight - slot;
        if (bar != null && bar.getVisibility() != GONE) {
            int bw = bar.getMeasuredWidth(), bh = bar.getMeasuredHeight();
            barLeft -= bw;
            bar.layout(barLeft, cy - bh / 2, barLeft + bw, cy - bh / 2 + bh);
        }

        // The digits, not the text box, centred on the capsule: whatever the font's ascent and descent.
        Rect digits = new Rect();
        time.getPaint().getTextBounds("0", 0, 1, digits);
        int tw = time.getMeasuredWidth(), th = time.getMeasuredHeight();
        int timeTop = Math.round(cy + digits.height() / 2f - time.getBaseline());
        int dividerW = divider.getMeasuredWidth(), dividerH = divider.getMeasuredHeight();
        int dividerLeft = barLeft - dp(DIVIDER_TAB_GAP_DP) - dividerW;
        divider.layout(dividerLeft, cy - dividerH / 2, dividerLeft + dividerW, cy - dividerH / 2 + dividerH);
        int timeLeft = openTimeLeft(dividerLeft, dp(TIME_DIVIDER_GAP_DP), tw);
        time.layout(timeLeft, timeTop, timeLeft + tw, timeTop + th);

        int restWidth = restWidth(dp(TIME_INSET_DP), tw, dp(TIME_GAP_DP), dp(AVATAR_REST_DP), dp(AVATAR_REST_INSET_DP));
        capsuleRestLeft = w - restWidth;
        timeRestLeft = capsuleRestLeft + dp(TIME_INSET_DP);
        avatarRestCenter = w - dp(AVATAR_REST_INSET_DP) - dp(AVATAR_REST_DP) / 2f;
        apply();
    }

    /** Puts everything where it is at the current openness. */
    private void apply() {
        if (time == null) return;
        float f = openness;
        float closed = 1f - f;
        float timeShift = (timeRestLeft - time.getLeft()) * closed;
        time.setTranslationX(timeShift);
        // The capsule's left end stays the same distance before the time, as it follows it home.
        glass.setLeft(Math.min(capsuleRestLeft, Math.round(time.getLeft() + timeShift - TIME_INSET_DP * density)));

        float avatarOpenCenter = (avatar.getLeft() + avatar.getRight()) / 2f;
        float avatarOpen = open ? f : 0f;   // closing, the picture is back at its place at once
        avatar.setTranslationX((avatarRestCenter - avatarOpenCenter) * (1f - avatarOpen));
        float s = avatarScale(avatarOpen, avatarFocus);
        avatar.setScaleX(s);
        avatar.setScaleY(s);

        // The divider fades in where it stands as the buttons come; closing, it goes with them at once.
        divider.setAlpha(open ? Math.max(0f, Math.min(1f, f)) : 0f);
        if (bar == null) return;
        bar.setTranslationX(TAB_TRAVEL_DP * density * closed);
        bar.setAlpha(open ? 1f : 0f);   // and the buttons are gone
    }

    // ---- geometry and motion, separate so they can be unit tested -------------------------------------------

    static int restWidth(int timeInset, int timeWidth, int timeGap, int avatarRest, int avatarInset) {
        return timeInset + timeWidth + timeGap + avatarRest + avatarInset;
    }

    /** Where the time's left edge goes when open: {@code gap} before the divider. */
    static int openTimeLeft(int dividerLeft, int gap, int timeWidth) {
        return Math.max(0, dividerLeft - gap - timeWidth);
    }

    /** Scale of the picture's view (laid out at its focused size): 50 px at rest, 38 px open, 52 px focused. */
    static float avatarScale(float openness, float focus) {
        float rest = AVATAR_REST_DP / AVATAR_FOCUSED_DP;
        float tab = AVATAR_OPEN_DP / AVATAR_FOCUSED_DP;
        float openScale = tab + (1f - tab) * focus;
        return rest + (openScale - rest) * openness;
    }

    /**
     * Position (0 to 1) and speed (per second) of the spring {@link #STIFFNESS}, {@link #DAMPING}, released from rest
     * at 0 towards 1, {@code seconds} later.
     */
    static float[] leap(float seconds) {
        double w = Math.sqrt(STIFFNESS), z = DAMPING;
        double wd = w * Math.sqrt(1 - z * z);
        double decay = Math.exp(-z * w * seconds);
        double x = 1 - decay * (Math.cos(wd * seconds) + z * w / wd * Math.sin(wd * seconds));
        double v = decay * w / Math.sqrt(1 - z * z) * Math.sin(wd * seconds);
        return new float[]{(float) x, (float) v};
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

    /**
     * tvOS's glass capsule over a dark screen: about 8 % white, with a brighter rim along the top that fades out
     * towards the bottom (sampled from Apple TV frames). Without the blur: behind it there is only black or the
     * dock app's soft glow, which a blur wouldn't change. Its left end can move ({@link #setLeft}); the right end is
     * the bounds'.
     */
    static final class GlassCapsule extends Drawable {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final float rimWidth;
        private int left;

        GlassCapsule(float density) {
            fill.setColor(0x14FFFFFF);
            rimWidth = Math.max(1f, 0.75f * density);
            rim.setStyle(Paint.Style.STROKE);
            rim.setStrokeWidth(rimWidth);
        }

        /** Left end, in the bounds' coordinates. Redraws only when it moves. */
        void setLeft(int left) {
            if (this.left == left) return;
            this.left = left;
            invalidateSelf();
        }

        @Override
        protected void onBoundsChange(@NonNull Rect b) {
            rim.setShader(new LinearGradient(0, b.top, 0, b.bottom,
                    new int[]{0x33FFFFFF, 0x00FFFFFF, 0x0AFFFFFF}, new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            Rect b = getBounds();
            float r = b.height() / 2f;
            rect.set(Math.max(b.left, left), b.top, b.right, b.bottom);
            canvas.drawRoundRect(rect, r, r, fill);
            float inset = rimWidth / 2f;
            rect.inset(inset, inset);
            canvas.drawRoundRect(rect, r - inset, r - inset, rim);
        }

        @Override
        public void getOutline(@NonNull Outline outline) {
            outline.setEmpty();   // no shadow, no clipping
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
