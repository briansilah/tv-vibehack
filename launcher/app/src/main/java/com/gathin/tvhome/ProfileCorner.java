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
 * When focus moves into it, it opens like the header of Control Center: the capsule clears, the time glides left,
 * and the top bar's buttons come out from under the picture and line up before it, the picture shrinking to one of
 * them. Focus leaving closes it the same way back.
 *
 * <p>Children: the time (a TextView), the top bar (set with {@link #setBar}) and the picture ({@code R.id.avatar}),
 * drawn in that order so the buttons pass under the picture. They are laid out once, where they sit when open;
 * opening and closing only change translations, alpha and scale, render properties that don't lay out or redraw
 * anything. Sizes are tvOS's, measured at 1080p (1 dp = 2 px on a 1080p TV); the PR that added this has the table.
 */
public class ProfileCorner extends ViewGroup {

    // ---- at rest: the capsule ------------------------------------------------------------------------------
    /** Capsule height: 60 px on tvOS. */
    static final float CAPSULE_HEIGHT_DP = 30f;
    /** From the capsule's left end to the time: 24 px. */
    static final float TIME_INSET_DP = 12f;
    /** From the time to the picture: 12 px. */
    static final float TIME_GAP_DP = 6f;
    /** The picture, 50 px across, 5 px inside the capsule's right end. */
    static final float AVATAR_REST_DP = 25f;
    static final float AVATAR_REST_INSET_DP = 2.5f;

    // ---- open: Control Center's header -------------------------------------------------------------------
    /** Each tab, and the picture, take a 60 px circle, side by side; the last ends 10 px inside the capsule's end. */
    static final float SLOT_DP = 30f;
    static final float OPEN_INSET_DP = 5f;
    /** The picture is 38 px across among the tabs, 52 px when focused. The view is laid out at the focused size. */
    static final float AVATAR_OPEN_DP = 19f;
    static final float AVATAR_FOCUSED_DP = 26f;
    /** The time moves to 444 px from the capsule's right end (the left edge of Control Center's panel). */
    static final float HEADER_WIDTH_DP = 222f;
    /** Never closer than this to the first button, when the buttons or the time are wider than tvOS's. */
    static final float MIN_TIME_GAP_DP = 16f;
    /** The buttons start one slot right of the picture, under which they pass. */
    static final float TAB_START_SLOTS = 1f;
    /** They are fully visible once 30 % of the way out (and go when 30 % from home, closing). */
    static final float TAB_FADE = 0.3f;

    /**
     * The opening: a spring fitted to a 60 fps recording of tvOS 26 opening and closing Control Center (ζ 0.9;
     * there 95 % of the way 320 to 340 ms after the first moving frame, and settled by 550 ms). It starts with
     * some speed instead of from rest, so the first frames move; it is a spring so focus can turn it around mid-way.
     */
    static final float OPEN_STIFFNESS = 120f;
    static final float OPEN_DAMPING = 0.9f;
    static final float OPEN_START_VELOCITY = 4f;
    /** The capsule clears within 80 ms of opening and is back within 120 ms of closing, as on tvOS. */
    static final long CAPSULE_OUT_MS = 80L;
    static final long CAPSULE_IN_MS = 120L;

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
    private TextView time;
    private View avatar;
    private View bar;
    private final SpringAnimation openSpring;
    private final SpringAnimation avatarSpring;
    /** 0 at rest, 1 open; the spring may take it a little past either end. */
    private float openness;
    /** 0: the picture is a tab like the others; 1: it has focus and is at full size. */
    private float avatarFocus;
    private boolean open;

    // Where things sit at rest, from the last layout (open positions are the children's own).
    private float timeRestLeft;
    private float avatarRestCenter;
    private float tabStart;

    private final ViewTreeObserver.OnGlobalFocusChangeListener focusListener = (oldFocus, newFocus) -> {
        setOpen(contains(newFocus));
        setAvatarFocused(newFocus != null && newFocus == avatar);
    };

    public ProfileCorner(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        capsule = new View(context);
        capsule.setBackground(new GlassCapsule(density));
        capsule.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(capsule, 0);   // first, so it is drawn under the time and the picture

        openSpring = new SpringAnimation(this, OPENNESS, 0f);
        openSpring.getSpring().setStiffness(OPEN_STIFFNESS).setDampingRatio(OPEN_DAMPING);
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
        capsule.animate().alpha(open ? 0f : 1f).setDuration(open ? CAPSULE_OUT_MS : CAPSULE_IN_MS).start();
        if (!openSpring.isRunning()) openSpring.setStartVelocity(open ? OPEN_START_VELOCITY : -OPEN_START_VELOCITY);
        openSpring.animateToFinalPosition(open ? 1f : 0f);
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

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int unspecified = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
        time.measure(unspecified, unspecified);
        boolean hasBar = bar != null && bar.getVisibility() != GONE;
        if (hasBar) bar.measure(unspecified, unspecified);
        int avatarSize = dp(AVATAR_FOCUSED_DP);
        int exact = MeasureSpec.makeMeasureSpec(avatarSize, MeasureSpec.EXACTLY);
        avatar.measure(exact, exact);

        int restWidth = restWidth(dp(TIME_INSET_DP), time.getMeasuredWidth(), dp(TIME_GAP_DP), dp(AVATAR_REST_DP),
                dp(AVATAR_REST_INSET_DP));
        int capsuleHeight = dp(CAPSULE_HEIGHT_DP);
        capsule.measure(MeasureSpec.makeMeasureSpec(restWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(capsuleHeight, MeasureSpec.EXACTLY));

        int barWidth = hasBar ? bar.getMeasuredWidth() : 0;
        int openWidth = dp(OPEN_INSET_DP) + dp(SLOT_DP) + barWidth + dp(MIN_TIME_GAP_DP) + time.getMeasuredWidth();
        int w = Math.max(restWidth, Math.max(dp(HEADER_WIDTH_DP), openWidth));
        int h = Math.max(Math.max(capsuleHeight, avatarSize), Math.max(time.getMeasuredHeight(),
                hasBar ? bar.getMeasuredHeight() : 0));
        setMeasuredDimension(resolveSize(w, widthMeasureSpec), resolveSize(h, heightMeasureSpec));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int w = r - l, cy = (b - t) / 2;
        int capsuleW = capsule.getMeasuredWidth(), capsuleH = capsule.getMeasuredHeight();
        capsule.layout(w - capsuleW, cy - capsuleH / 2, w, cy - capsuleH / 2 + capsuleH);

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
        int timeLeft = openTimeLeft(w, dp(HEADER_WIDTH_DP), barLeft, dp(MIN_TIME_GAP_DP), tw);
        time.layout(timeLeft, timeTop, timeLeft + tw, timeTop + th);

        timeRestLeft = w - capsuleW + dp(TIME_INSET_DP);
        avatarRestCenter = w - dp(AVATAR_REST_INSET_DP) - dp(AVATAR_REST_DP) / 2f;
        tabStart = avatarRestCenter + TAB_START_SLOTS * slot;
        apply();
    }

    /** Puts everything where it is at the current openness. Render properties only. */
    private void apply() {
        if (time == null) return;
        float f = openness;
        float closed = 1f - f;
        time.setTranslationX((timeRestLeft - time.getLeft()) * closed);

        float avatarOpenCenter = (avatar.getLeft() + avatar.getRight()) / 2f;
        avatar.setTranslationX((avatarRestCenter - avatarOpenCenter) * closed);
        float s = avatarScale(f, avatarFocus);
        avatar.setScaleX(s);
        avatar.setScaleY(s);

        if (bar == null) return;
        float alpha = tabAlpha(f);
        if (bar.getBackground() != null) {
            // A look with a tray behind the buttons (google): the tray comes out as one piece.
            bar.setTranslationX((tabStart - (bar.getLeft() + bar.getRight()) / 2f) * closed);
            bar.setAlpha(alpha);
            return;
        }
        if (!(bar instanceof ViewGroup)) return;
        ViewGroup row = (ViewGroup) bar;
        for (int i = 0; i < row.getChildCount(); i++) {
            View tab = row.getChildAt(i);
            float center = bar.getLeft() + (tab.getLeft() + tab.getRight()) / 2f;
            tab.setTranslationX((tabStart - center) * closed);
            tab.setAlpha(alpha);
        }
    }

    // ---- geometry, separate so it can be unit tested --------------------------------------------------------

    static int restWidth(int timeInset, int timeWidth, int timeGap, int avatarRest, int avatarInset) {
        return timeInset + timeWidth + timeGap + avatarRest + avatarInset;
    }

    /**
     * Where the time's left edge goes when open: {@code headerWidth} from the right end as on tvOS, or further left
     * to keep {@code minGap} from the first button.
     */
    static int openTimeLeft(int width, int headerWidth, int barLeft, int minGap, int timeWidth) {
        return Math.max(0, Math.min(width - headerWidth, barLeft - minGap - timeWidth));
    }

    static float tabAlpha(float openness) {
        return Math.max(0f, Math.min(1f, openness / TAB_FADE));
    }

    /** Scale of the picture's view (laid out at its focused size): 50 px at rest, 38 px open, 52 px focused. */
    static float avatarScale(float openness, float focus) {
        float rest = AVATAR_REST_DP / AVATAR_FOCUSED_DP;
        float tab = AVATAR_OPEN_DP / AVATAR_FOCUSED_DP;
        float openScale = tab + (1f - tab) * focus;
        return rest + (openScale - rest) * openness;
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
     * dock app's soft glow, which a blur wouldn't change.
     */
    static final class GlassCapsule extends Drawable {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final float rimWidth;

        GlassCapsule(float density) {
            fill.setColor(0x14FFFFFF);
            rimWidth = Math.max(1f, 1.5f * density / 2f);
            rim.setStyle(Paint.Style.STROKE);
            rim.setStrokeWidth(rimWidth);
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
            rect.set(b);
            canvas.drawRoundRect(rect, r, r, fill);
            float inset = rimWidth / 2f;
            rect.inset(inset, inset);
            canvas.drawRoundRect(rect, r - inset, r - inset, rim);
        }

        @Override
        public void getOutline(@NonNull Outline outline) {
            Rect b = getBounds();
            outline.setRoundRect(b, b.height() / 2f);
            outline.setAlpha(0f);
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
