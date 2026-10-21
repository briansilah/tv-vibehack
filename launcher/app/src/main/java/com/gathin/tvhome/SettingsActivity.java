package com.gathin.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Home Settings: the launcher's own settings, on the TV. A tile in the grid like tvOS's Settings app (it's an app of
 * its own, so it lands at the end next to Arrange apps, and can be moved or hidden as any app), and the Top Shelf row
 * of an app's actions (hold OK), which opens straight on that app.
 *
 * <ul>
 * <li>Home Settings: each Dock app's top shelf choice, then Liquid Glass, then the other apps'.</li>
 * <li>An app: Suggestions, Static Image, Original (the glow in its colour) or Custom. The default is marked.</li>
 * <li>Custom: its artwork (when it has some), its cover art, or the glow in a colour of your choice.</li>
 * </ul>
 * On the left, Home in miniature shows what the top shelf would show for the choice in focus. A choice is saved as
 * it's picked ({@link ShelfChoices}) and Home shows it when it's back.
 */
public class SettingsActivity extends Activity implements ShelfSource.Listener {

    /** Opens on this app's top shelf choice; Back then goes straight back to Home. */
    static final String EXTRA_PACKAGE = "com.gathin.tvhome.PACKAGE";

    private static final int ROOT = 0, APP = 1, CUSTOM = 2;
    private static final DecelerateInterpolator EASE = new DecelerateInterpolator(1.8f);
    /** Previews kept made: the app's choices, as you move between them. */
    private static final int PREVIEWS_KEPT = 6;
    private static final int SWATCHES_PER_ROW = 4;

    private float density;
    private TextView title;
    private TextView about;
    private ShelfPreview preview;
    private ScrollView scroll;
    private LinearLayout list;
    /** Null with the top shelf's art off (config.xml, top_shelf_art). */
    private ShelfSource source;
    private ShelfChoices choices;
    private SharedPreferences prefs;

    private List<AppAdapter.Entry> apps;
    private int dockCount;
    private final List<Drawable> dockBanners = new ArrayList<>();
    private final Map<String, ShelfSource.App> shelfApps = new HashMap<>();
    private final Map<String, Integer> glows = new HashMap<>();
    private final Map<String, ShelfSource.Cover> previews = new LinkedHashMap<String, ShelfSource.Cover>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, ShelfSource.Cover> eldest) {
            return size() > PREVIEWS_KEPT;
        }
    };

    private int page = ROOT;
    /** The app whose choices show (APP and CUSTOM). */
    private AppAdapter.Entry app;
    /** Opened from an app's actions: on that app, and Back leaves. */
    private String direct;
    /** What the preview shows, so a late picture for something else is dropped. */
    private Object previewing;
    private AppAdapter.Entry previewApp;
    private ShelfChoice previewChoice;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        density = getResources().getDisplayMetrics().density;
        title = findViewById(R.id.settingsTitle);
        about = findViewById(R.id.settingsAbout);
        preview = findViewById(R.id.settingsPreview);
        scroll = findViewById(R.id.settingsScroll);
        list = findViewById(R.id.settingsList);
        prefs = getSharedPreferences("home", MODE_PRIVATE);
        String[] images = getResources().getStringArray(R.array.top_shelf_images);
        if (getResources().getBoolean(R.bool.top_shelf_art)) {
            source = new ShelfSource(this, getResources().getStringArray(R.array.top_shelf_feeds),
                    getResources().getStringArray(R.array.top_shelf_links),
                    getResources().getStringArray(R.array.top_shelf_logos), images, this);
            choices = source.choices();
            // What the apps published and the feeds saved on the TV, for the previews of their suggestions.
            source.start();
        } else {
            choices = new ShelfChoices(this, ShelfFeeds.parseImages(images).keySet());
        }
        direct = getIntent() != null ? getIntent().getStringExtra(EXTRA_PACKAGE) : null;
        title.setText(R.string.home_settings);
        final int columns = getResources().getInteger(R.integer.columns);
        new Thread(() -> {
            final AppAdapter.Ordered loaded = AppAdapter.loadInOrder(getApplicationContext(), columns);
            runOnUiThread(() -> loaded(loaded));
        }, "tvhome-settings").start();
    }

    private void loaded(AppAdapter.Ordered loaded) {
        if (isFinishing() || isDestroyed()) return;
        apps = loaded.apps;
        dockCount = loaded.dock;
        dockBanners.clear();
        for (int i = 0; i < dockCount; i++) dockBanners.add(copy(bannerOf(apps.get(i))));
        AppAdapter.Entry to = direct != null ? entry(direct) : null;
        if (to != null) {
            showApp(to, null);
        } else {
            direct = null;   // not a TV app here (any more): the whole of Home Settings, as from its tile
            showRoot(null);
        }
    }

    private AppAdapter.Entry entry(String pkg) {
        for (AppAdapter.Entry e : apps) {
            if (e.pkg.equals(pkg)) return e;
        }
        return null;
    }

    // ---- pages -----------------------------------------------------------------------------------------------

    /** Home Settings: the apps' top shelf choices and Liquid Glass. {@code focusPkg}: the row to focus. */
    private void showRoot(String focusPkg) {
        page = ROOT;
        app = null;
        title.setText(R.string.home_settings);
        clear();
        View first = null, focus = null;
        if (source == null) {
            header(R.string.top_shelf, R.string.top_shelf_off);
        } else {
            header(R.string.top_shelf, R.string.top_shelf_about);
            for (int i = 0; i < dockCount; i++) {
                View r = appRow(apps.get(i));
                if (first == null) first = r;
                if (apps.get(i).pkg.equals(focusPkg)) focus = r;
            }
        }
        header(R.string.appearance, 0);
        int glass = prefs.getInt(MainActivity.PREF_GLASS, getResources().getInteger(R.integer.liquid_glass));
        View glassRow = row(getString(R.string.liquid_glass), MainActivity.glassLabel(this, glass),
                R.drawable.ic_chevron_right, this::openGlass, () -> {
                    previewing = null;
                    preview.setDock(dockBanners, -1);
                    if (source == null || apps.isEmpty()) preview.showGlow(null, AmbientColor.FALLBACK);
                    about.setText(R.string.about_glass);
                });
        if (first == null) first = glassRow;
        if (source != null && apps.size() > dockCount) {
            header(R.string.other_apps, R.string.other_apps_about);
            for (int i = dockCount; i < apps.size(); i++) {
                View r = appRow(apps.get(i));
                if (apps.get(i).pkg.equals(focusPkg)) focus = r;
            }
        }
        enter(focus != null ? focus : first);
    }

    private View appRow(final AppAdapter.Entry e) {
        return row(e.label, name(choices.get(e.pkg)), R.drawable.ic_chevron_right, () -> showApp(e, null),
                () -> showPreview(e, choices.get(e.pkg)));
    }

    /** An app's choices. {@code focus}: the row to focus, else the current choice's. */
    private void showApp(final AppAdapter.Entry e, ShelfChoice.Kind focus) {
        page = APP;
        app = e;
        title.setText(e.label);
        clear();
        ShelfChoice now = choices.get(e.pkg);
        ShelfChoice def = choices.byDefault(e.pkg);
        boolean custom = !now.equals(ShelfChoice.SUGGESTIONS) && !now.equals(ShelfChoice.IMAGE)
                && !now.equals(ShelfChoice.GLOW);
        if (focus == null) focus = custom ? ShelfChoice.Kind.COLOUR : now.kind;
        View target = null;
        for (ShelfChoice c : new ShelfChoice[]{ShelfChoice.SUGGESTIONS, ShelfChoice.IMAGE, ShelfChoice.GLOW}) {
            final ShelfChoice pick = c;
            View r = row(name(c), c.equals(def) ? getString(R.string.settings_default) : null,
                    c.equals(now) ? R.drawable.ic_check : 0, () -> pick(pick), () -> showPreview(e, pick));
            if (c.kind == focus) target = r;
        }
        final ShelfChoice customLook = custom ? now : firstCustom(e);
        View r = row(getString(R.string.shelf_custom), custom ? name(now) : null, R.drawable.ic_chevron_right,
                () -> showCustom(e), () -> {
                    showPreview(e, customLook);
                    about.setText(R.string.about_custom);
                });
        if (target == null) target = r;
        enter(target);
    }

    /** Custom: the artwork, the cover art, or a colour. */
    private void showCustom(final AppAdapter.Entry e) {
        page = CUSTOM;
        app = e;
        title.setText(getString(R.string.shelf_custom));
        clear();
        ShelfChoice now = choices.get(e.pkg);
        View target = null;
        List<ShelfChoice> pictures = new ArrayList<>();
        if (ShelfSource.hasArtwork(e.pkg)) pictures.add(ShelfChoice.ARTWORK);
        pictures.add(ShelfChoice.COVER_ART);
        View lastPicture = null;
        for (ShelfChoice c : pictures) {
            final ShelfChoice pick = c;
            View r = row(name(c), null, c.equals(now) ? R.drawable.ic_check : 0, () -> pick(pick),
                    () -> showPreview(e, pick));
            if (target == null || c.equals(now)) target = r;
            lastPicture = r;
        }
        lastPicture.setId(View.generateViewId());
        header(R.string.colour, 0);
        String[] names = getResources().getStringArray(R.array.colour_names);
        LinearLayout swatches = null;
        for (int i = 0; i < ShelfChoice.PALETTE.length; i++) {
            // Two rows of four, so they fit the list's width with room to grow when focused.
            if (i % SWATCHES_PER_ROW == 0) {
                swatches = new LinearLayout(this);
                swatches.setOrientation(LinearLayout.HORIZONTAL);
                swatches.setPadding(Math.round(24 * density), Math.round(10 * density), 0, Math.round(6 * density));
                swatches.setClipChildren(false);
                swatches.setClipToPadding(false);
                list.addView(swatches);
            }
            final ShelfChoice pick = ShelfChoice.colour(ShelfChoice.PALETTE[i]);
            View s = swatch(pick, pick.equals(now), i < names.length ? names[i] : null, e);
            swatches.addView(s);
            if (pick.equals(now)) target = s;
            // Down from the rows above goes to the first colour, as down a list; up from the first row, back up.
            if (i < SWATCHES_PER_ROW) s.setNextFocusUpId(lastPicture.getId());
            if (i == 0) {
                s.setId(View.generateViewId());
                lastPicture.setNextFocusDownId(s.getId());
            }
        }
        enter(target);
    }

    /** The first look Custom offers for an app: its artwork if it has some, else its cover art. */
    private static ShelfChoice firstCustom(AppAdapter.Entry e) {
        return ShelfSource.hasArtwork(e.pkg) ? ShelfChoice.ARTWORK : ShelfChoice.COVER_ART;
    }

    /** Saves the app's choice, then back to where it was chosen from (Home, from an app's actions). */
    private void pick(ShelfChoice c) {
        if (app == null) return;
        choices.set(app.pkg, c);
        if (direct != null) {
            finish();
            return;
        }
        showRoot(app.pkg);
    }

    /** Liquid Glass: on Home, over the dock, whose glass it changes as you move it. */
    private void openGlass() {
        startActivity(new Intent(this, MainActivity.class).putExtra(MainActivity.EXTRA_GLASS_SLIDER, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        finish();
    }

    @Override
    public void onBackPressed() {
        if (page == CUSTOM && app != null) {
            showApp(app, ShelfChoice.Kind.COLOUR);
        } else if (page == APP && app != null && direct == null) {
            showRoot(app.pkg);
        } else {
            super.onBackPressed();
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Left and Right don't leave the list for the preview, which can't be focused.
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            View f = getCurrentFocus();
            if (f == null || !(f.getParent() instanceof LinearLayout)
                    || ((LinearLayout) f.getParent()).getOrientation() != LinearLayout.HORIZONTAL) {
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    // ---- rows ------------------------------------------------------------------------------------------------

    private void clear() {
        list.removeAllViews();
        scroll.scrollTo(0, 0);
    }

    private void header(int name, int aboutText) {
        View h = LayoutInflater.from(this).inflate(R.layout.settings_header, list, false);
        ((TextView) h.findViewById(R.id.headerName)).setText(name);
        if (aboutText != 0) {
            TextView a = h.findViewById(R.id.headerAbout);
            a.setText(aboutText);
            a.setVisibility(View.VISIBLE);
        }
        if (list.getChildCount() == 0) ((ViewGroup.MarginLayoutParams) h.getLayoutParams()).topMargin = 0;
        list.addView(h);
    }

    private View row(CharSequence label, CharSequence value, int icon, Runnable onClick, Runnable onFocus) {
        View r = LayoutInflater.from(this).inflate(R.layout.settings_row, list, false);
        ((TextView) r.findViewById(R.id.rowLabel)).setText(label);
        TextView v = r.findViewById(R.id.rowValue);
        if (value != null) v.setText(value);
        else v.setVisibility(View.GONE);
        ImageView i = r.findViewById(R.id.rowIcon);
        if (icon != 0) i.setImageResource(icon);
        else i.setVisibility(View.INVISIBLE);
        r.setContentDescription(value != null ? label + ", " + value : label);
        r.setOnClickListener(x -> onClick.run());
        r.setOnFocusChangeListener((x, has) -> {
            if (has) onFocus.run();
        });
        list.addView(r);
        return r;
    }

    /** A colour to pick: a round swatch, ringed when focused, with a check when it's the choice. */
    private View swatch(final ShelfChoice c, boolean chosen, String name, final AppAdapter.Entry e) {
        int size = Math.round(48 * density);
        FrameLayout s = new FrameLayout(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.setMarginEnd(Math.round(26 * density));
        s.setLayoutParams(lp);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(c.colour);
        s.setBackground(dot);
        if (chosen) {
            ImageView check = new ImageView(this);
            check.setImageResource(R.drawable.ic_check);
            check.setImageTintList(android.content.res.ColorStateList.valueOf(0xFF111111));
            int p = Math.round(10 * density);
            check.setPadding(p, p, p, p);
            s.addView(check, new FrameLayout.LayoutParams(size, size));
        }
        s.setFocusable(true);
        s.setClickable(true);
        s.setContentDescription(name);
        s.setOnClickListener(x -> pick(c));
        s.setOnFocusChangeListener((x, has) -> {
            dot.setStroke(has ? Math.round(3 * density) : 0, 0xFFFFFFFF);
            x.animate().scaleX(has ? 1.25f : 1f).scaleY(has ? 1.25f : 1f).setDuration(150).setInterpolator(EASE)
                    .start();
            if (has) showPreview(e, c);
        });
        return s;
    }

    /** The page's rows slide in after one another, as an app's actions do, and focus goes to {@code target}. */
    private void enter(View target) {
        for (int i = 0; i < list.getChildCount(); i++) {
            View row = list.getChildAt(i);
            row.setAlpha(0f);
            row.setTranslationX(32 * density);
            row.animate().alpha(1f).translationX(0f).setStartDelay(30L * Math.min(i, 8)).setDuration(240)
                    .setInterpolator(EASE).start();
        }
        if (target != null) target.requestFocus();   // the list scrolls to it once laid out
    }

    // ---- the preview -----------------------------------------------------------------------------------------

    /** Home in miniature, with the app's top shelf as {@code c} would have it. */
    private void showPreview(final AppAdapter.Entry e, final ShelfChoice c) {
        final Object token = new Object();
        previewing = token;
        previewApp = e;
        previewChoice = c;
        preview.setDock(dockWith(e), slotOf(e));
        about.setText(aboutText(e, c));
        if (source == null || !c.picture()) {
            preview.showGlow(e.label, c.kind == ShelfChoice.Kind.COLOUR ? c.colour : glowOf(e));
            return;
        }
        final ShelfSource.App a = shelfApp(e);
        if (c.suggests()) {
            List<ShelfFeeds.Item> titles = source.titles(e.pkg);
            if (titles != null && ShelfOrder.first(titles, it -> source.failedLately(it.backdrop)) >= 0) {
                previewTitle(e, a, c, titles, 0, token);
                return;
            }
            about.setText(getString(R.string.about_suggestions_none, e.label));
        }
        previewCover(a, c, token);
    }

    /**
     * The app's first title whose picture can be had, as Home starts with it; one whose picture fails hands over to
     * the next, and with none the app's art shows.
     */
    private void previewTitle(final AppAdapter.Entry e, final ShelfSource.App a, final ShelfChoice c,
                              final List<ShelfFeeds.Item> titles, int from, final Object token) {
        int i = from;
        while (i < titles.size() && (titles.get(i).backdrop == null || source.failedLately(titles.get(i).backdrop))) i++;
        if (i >= titles.size()) {
            about.setText(getString(R.string.about_suggestions_none, e.label));
            previewCover(a, c, token);
            return;
        }
        final ShelfFeeds.Item first = titles.get(i);
        final int next = i + 1;
        source.load(first.backdrop, true, bg -> {
            if (previewing != token) return;
            if (bg == null) {
                previewTitle(e, a, c, titles, next, token);
                return;
            }
            final String caption = first.caption != null ? first.caption : source.caption(e.pkg);
            if (first.logo == null) {
                preview.showContent(bg, null, first.title, caption);
                return;
            }
            source.load(first.logo, false, logo -> {
                if (previewing == token) preview.showContent(bg, logo, first.title, caption);
            });
        });
    }

    private void previewCover(ShelfSource.App a, ShelfChoice c, final Object token) {
        final String key = a.pkg + "|" + c.encode();
        ShelfSource.Cover kept = previews.get(key);
        if (kept != null) {
            preview.showCover(kept.backdrop, kept.logo, kept.image);
            return;
        }
        source.previewCover(a, c, cover -> {
            if (cover != null) previews.put(key, cover);
            if (previewing != token) return;
            if (cover != null) preview.showCover(cover.backdrop, cover.logo, cover.image);
            else preview.showGlow(a.label, glowOf(previewApp));
        });
    }

    private CharSequence aboutText(AppAdapter.Entry e, ShelfChoice c) {
        switch (c.kind) {
            case SUGGESTIONS:
                return getString(R.string.about_suggestions, e.label);
            case IMAGE:
                return getString(R.string.about_static, e.label);
            case ARTWORK:
                return getString(R.string.about_artwork, e.label);
            case COVER_ART:
                return getString(R.string.about_cover_art, e.label);
            case COLOUR:
                return getString(R.string.about_colour, e.label);
            default:
                return getString(R.string.about_original, e.label);
        }
    }

    /**
     * The dock as the preview draws it: the app in its slot, or, for one that isn't in the dock, in its last slot,
     * as if moved there (its top shelf shows only from the dock).
     */
    private List<Drawable> dockWith(AppAdapter.Entry e) {
        List<Drawable> out = new ArrayList<>(dockBanners);
        if (dockIndex(e) >= 0) return out;
        if (out.isEmpty()) out.add(copy(bannerOf(e)));
        else out.set(out.size() - 1, copy(bannerOf(e)));
        return out;
    }

    private int slotOf(AppAdapter.Entry e) {
        int i = dockIndex(e);
        return i >= 0 ? i : Math.max(0, dockBanners.size() - 1);
    }

    /** Where the app is in the dock, or -1. */
    private int dockIndex(AppAdapter.Entry e) {
        int i = apps.indexOf(e);
        return i < dockCount ? i : -1;
    }

    private ShelfSource.App shelfApp(AppAdapter.Entry e) {
        ShelfSource.App a = shelfApps.get(e.key);
        if (a == null) {
            a = new ShelfSource.App(e.key, e.pkg, e.label, e.appInfo, e.bannerRes, copy(bannerOf(e)));
            shelfApps.put(e.key, a);
        }
        return a;
    }

    /** The app's own glow colour, as Home picks it from its banner. */
    private int glowOf(AppAdapter.Entry e) {
        if (e == null) return AmbientColor.FALLBACK;
        Integer c = glows.get(e.pkg);
        if (c == null) {
            c = AppAdapter.vividColor(bannerOf(e));
            glows.put(e.pkg, c);
        }
        return c;
    }

    private String name(ShelfChoice c) {
        switch (c.kind) {
            case SUGGESTIONS:
                return getString(R.string.shelf_suggestions);
            case IMAGE:
                return getString(R.string.shelf_static);
            case ARTWORK:
                return getString(R.string.shelf_artwork);
            case COVER_ART:
                return getString(R.string.shelf_cover_art);
            case COLOUR:
                return getString(R.string.colour);
            default:
                return getString(R.string.shelf_original);
        }
    }

    private static Drawable bannerOf(AppAdapter.Entry e) {
        return e.banner != null ? e.banner : e.icon;
    }

    private static Drawable copy(Drawable d) {
        if (d == null) return null;
        return d.getConstantState() != null ? d.getConstantState().newDrawable().mutate() : d;
    }

    // ---- ShelfSource.Listener ----------------------------------------------------------------------------------

    @Override
    public void onItems(String pkg) {
        // Titles arrived for the app in the preview: show them, if that's its choice there.
        if (previewApp != null && previewApp.pkg.equals(pkg) && previewChoice != null && previewChoice.suggests()) {
            showPreview(previewApp, previewChoice);
        }
    }

    @Override
    public void onCover(String key) {
    }
}
