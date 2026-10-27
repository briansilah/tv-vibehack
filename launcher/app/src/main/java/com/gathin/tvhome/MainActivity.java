package com.gathin.tvhome;

import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.Spanned;
import android.util.Log;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.ContextThemeWrapper;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewStub;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.AnimationUtils;
import android.view.animation.LayoutAnimationController;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * tvOS style home: black, a pinned dock row, an app grid; the focused tile grows on a soft shadow.
 */
public class MainActivity extends Activity implements AppAdapter.TileListener, TopShelf.Host {

    /** Tiles per row and dock slots (res/values/config.xml, columns). Set in onCreate. */
    static int COLUMNS = 4;
    private static final long MINUTE_MS = 60_000L;
    /**
     * The top shelf follows focus along the dock straight away: the name swaps with a quick fade and the
     * glow glides to the new app's colour, carrying on from wherever it is if focus moves again. (tvOS
     * waits for focus to settle first; the 280 ms wait plus a 320 ms cross-fade felt sluggish.) Sweeping
     * along the dock keeps the name hidden until focus stops, so no app's name flashes by.
     */
    private static final long NAME_OUT_MS = 70L;
    private static final long NAME_IN_MS = 150L;
    private static final long GLOW_BLEND_MS = 200L;
    /** Coming to the dock from elsewhere, and leaving it. */
    private static final long SHELF_SHOW_NAME_MS = 150L;
    private static final long SHELF_SHOW_GLOW_MS = 250L;
    private static final long SHELF_HIDE_NAME_MS = 120L;
    private static final long SHELF_HIDE_GLOW_MS = 200L;
    private static final TimeInterpolator DECELERATE = new DecelerateInterpolator(1.5f);
    /** Rows stay this far below the top of the screen: the TV's safe area (tvOS keeps 60 of 1080 px). */
    private static final float SAFE_TOP_DP = 24f;
    /** The top bar is gone once the page has moved this far up from the dock's place. */
    private static final float TOP_BAR_FADE_DP = 110f;
    private static final TimeInterpolator LINEAR = new LinearInterpolator();
    /** Apple TV look: the caption under the focused button fades in, after the corner has (nearly) opened. */
    private static final long CAPTION_FADE_MS = 150L;
    private static final long CAPTION_OPEN_DELAY_MS = 180L;
    private static final int TAB_CAPTION_COLOR = 0xE6FFFFFF;
    /**
     * How see-through the Liquid Glass is (see {@link LiquidGlass.Look}): saved from the slider, and an intent extra to
     * set it from a computer, {@code adb shell am start -n com.gathin.tvhome/.MainActivity --ei liquid_glass 50}
     * (-1 goes back to config.xml's).
     */
    static final String PREF_GLASS = "liquid_glass";
    static final String EXTRA_GLASS = "liquid_glass";
    /** Home Settings' Liquid Glass row: Home shows again with focus on the dock, then the slider over it. */
    static final String EXTRA_GLASS_SLIDER = "com.gathin.tvhome.GLASS_SLIDER";
    /**
     * {@code --ez default_config true}: the launcher as it ships, for tools/perf.py, until the process ends or
     * {@code --ez default_config false}: config.xml's Liquid Glass instead of the saved choice (which stays saved), and
     * no stand-in art. Meanwhile {@code --ei liquid_glass} isn't saved either, so a variant can be measured.
     */
    static final String EXTRA_DEFAULT_CONFIG = "default_config";
    /** Static, so it lasts exactly as long as the process. */
    private static boolean defaultConfig;
    private static final long GLASS_PANEL_IN_MS = 180L;
    private static final long GLASS_PANEL_OUT_MS = 140L;
    /** Settings.ACTION_USER_SETTINGS, hidden from the SDK. */
    private static final String USER_SETTINGS = "android.settings.USER_SETTINGS";
    private String liveTvPkg;
    private String vpnApp;

    private AppAdapter adapter;
    private RecyclerView grid;
    private Rearranger rearranger;
    private FrameLayout root;
    private View ambient;
    /** What shows through the dock's and the top right's glass: the top shelf. */
    private LiquidGlass.Backdrop backdrop;
    /** The Liquid Glass slider, once shown. */
    private View glassPanel;
    /** Stand-in top shelf art, when asked for (GlassTestArt.EXTRA). */
    private GlassTestArt testArt;
    private SharedPreferences prefs;
    private TextView heroName;
    /** The top shelf's art: suggested content or cover art, over the glow (see {@link TopShelf}). */
    private TopShelf topShelf;
    private ShelfInfoView shelfInfo;
    /** The name is fading out to change: its end decides whether it comes back (see {@link #swapShelf}). */
    private boolean nameSwapping;
    private GradientDrawable ambientGlow;
    private int ambientColor;
    private ValueAnimator ambientFade;
    private boolean heroShown;
    /** App the top shelf shows, or is changing to. */
    private String shelfKey;
    private boolean tilesEntered;
    /** Top right: the time and profile picture, which opens into the top bar's buttons. */
    private ProfileCorner corner;
    private TopBarButton btnWifi;
    private TopBarButton btnInputs;
    private TopBarButton btnSettings;
    /** Whether the Settings button's label says it opens Google TV's quick settings yet (see captionFor). */
    private boolean settingsLabelChecked;
    private View topBar;
    /** The top bar's container, over the grid: it scrolls away with the page (see {@link #followPage}). */
    private View topArea;
    /** The shade behind the top right, in the top shelf: it fades with the top right. */
    private View cornerShade;
    /** How far the page has scrolled from the dock's parked place, in pixels; 0 there. */
    private float pageTravel;
    /** Where the top shelf's name sits with the page at rest; it moves with the page. */
    private float heroBaseY;
    /** Google TV look: the focused top bar button's name, under the bar. */
    private TextView topCaption;
    private TextView shamsiDate;
    // Static so they survive the activity being destroyed while another app is open (low-memory TVs,
    // or Developer options → "Don't keep activities"); they only reset when the process dies.
    private static int lastFocused = 0;
    /** App of the last focused tile, so focus follows it when the app list changes. */
    private static String lastFocusedKey;
    private float density;
    /** reportFullyDrawn() once per activity instance, after its first focus restore. */
    private boolean reportedFullyDrawn;

    private final Handler handler = new Handler(Looper.getMainLooper());
    /** Refreshes the network pill when the connection changes: at boot Wi-Fi often connects after Home is shown. */
    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(@NonNull Network network) {
            handler.post(MainActivity.this::updateNetworkIndicator);
        }

        @Override
        public void onLost(@NonNull Network network) {
            handler.post(MainActivity.this::updateNetworkIndicator);
        }

        @Override
        public void onCapabilitiesChanged(@NonNull Network network, @NonNull NetworkCapabilities capabilities) {
            handler.post(MainActivity.this::updateNetworkIndicator);
        }
    };
    private final Set<Network> vpnNetworks = new HashSet<>();
    private final ConnectivityManager.NetworkCallback vpnCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(@NonNull Network network) {
            handler.post(() -> {
                vpnNetworks.add(network);
                updateNetworkIndicator();
            });
        }

        @Override
        public void onLost(@NonNull Network network) {
            handler.post(() -> {
                vpnNetworks.remove(network);
                updateNetworkIndicator();
            });
        }

        @Override
        public void onCapabilitiesChanged(@NonNull Network network, @NonNull NetworkCapabilities capabilities) {
            handler.post(MainActivity.this::updateNetworkIndicator);
        }
    };
    private final TvInputManager.TvInputCallback inputCallback = new TvInputManager.TvInputCallback() {
        @Override
        public void onInputAdded(String inputId) {
            updateInputsButton();
        }

        @Override
        public void onInputRemoved(String inputId) {
            updateInputsButton();
        }
    };
    /**
     * Set when a TV app was installed, updated, removed, enabled or disabled while Home was in the background:
     * the next onResume reloads the app list. Otherwise Home comes back without reloading every banner.
     */
    private boolean appsChanged = true;
    private boolean resumed;
    /** Home was hidden (stopped) since it last showed: it falls back into place when it shows again. */
    private boolean wasStopped;
    /** How Home is coming back (Transitions.SHOW_*): set by an intent, else Back from an app. */
    private int showMode = Transitions.SHOW_BACK;
    private HomeMotion homeMotion;
    private GridScroller scroller;
    /** An app opened but Home is still on screen this long after: it didn't cover Home, so bring Home back. */
    private static final long COME_BACK_MS = 3000L;
    private final Runnable comeBackIfShown = this::comeBack;
    /** The tile focusTile was last asked for, and its listener while the grid scrolls to it. */
    private int focusTarget = -1;
    private RecyclerView.OnScrollListener deferredFocus;
    /** Runs once focus lands on the tile focusTile was last asked for, if the grid had to scroll to it first. */
    private Runnable afterDeferredFocus;
    /** Liquid Glass was picked in Home Settings: the slider shows when Home is back ({@link #EXTRA_GLASS_SLIDER}). */
    private boolean glassOnReturn;
    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Uri data = intent.getData();
            String pkg = data != null ? data.getSchemeSpecificPart() : null;
            if (pkg != null && !adapter.contains(pkg)
                    && getPackageManager().getLeanbackLaunchIntentForPackage(pkg) == null) {
                return;   // not a TV app, and not one we show (e.g. a background service package changing)
            }
            appsChanged = true;
            adapter.dropPreload();
            if (resumed) {
                handler.removeCallbacks(reloadNow);   // an update sends several broadcasts
                handler.post(reloadNow);
            }
        }
    };
    /** A TV app changed while Home is showing. Focus only goes back to a tile if it was on one. */
    private final Runnable reloadNow = () -> {
        final String returnKey = lastFocusedKey;
        final int returnSlot = lastFocused;
        final boolean onTiles = grid.hasFocus();
        reloadApps();
        if (!onTiles) return;   // on the top bar or in the Inputs menu: leave it there
        grid.post(() -> {
            int pos = adapter.positionOf(returnKey);
            focusTile(pos >= 0 ? pos : returnSlot);
        });
    };
    private final Runnable minuteTick = new Runnable() {
        @Override
        public void run() {
            updateNetworkIndicator();
            updateDate();
            handler.postDelayed(this, MINUTE_MS);
        }
    };
    /**
     * From a dock focus change until the top shelf shows that app (name or title, glow, and art when it's ready);
     * tools/perf.py's dock scenario.
     */
    private final Perf.Span shelfTiming = new Perf.Span("shelf", "text", "glow", "art");
    private static final int SHELF_TEXT = 0, SHELF_GLOW = 1, SHELF_ART = 2;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        long t0 = Perf.now();
        Perf.begin("tvhome:onCreate");
        super.onCreate(savedInstanceState);
        COLUMNS = Math.max(1, getResources().getInteger(R.integer.columns));
        adapter = new AppAdapter(this, this, COLUMNS);
        // Listen for app changes before the loading starts, so none falls between its query and onResume.
        IntentFilter packages = new IntentFilter();
        packages.addAction(Intent.ACTION_PACKAGE_ADDED);
        packages.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packages.addAction(Intent.ACTION_PACKAGE_CHANGED);
        packages.addAction(Intent.ACTION_PACKAGE_REPLACED);
        packages.addDataScheme("package");
        registerReceiver(packageReceiver, packages);
        IntentFilter storage = new IntentFilter(Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE);
        storage.addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE);
        registerReceiver(packageReceiver, storage);
        // Start loading the apps (other apps' banners, icons and labels) now, in the background, so it overlaps
        // inflating the screen; onResume shows them.
        adapter.preload();
        setContentView(R.layout.activity_main);
        density = getResources().getDisplayMetrics().density;
        liveTvPkg = getString(R.string.live_tv_package).trim();
        vpnApp = getString(R.string.vpn_app).trim();

        root = findViewById(R.id.root);
        homeMotion = new HomeMotion(root);
        ambient = findViewById(R.id.ambient);
        heroName = findViewById(R.id.heroName);
        ambientGlow = new GradientDrawable();
        ambientGlow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        ambientGlow.setGradientCenter(0.32f, 0.42f);
        ambient.setBackground(ambientGlow);
        ambient.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) ->
                ambientGlow.setGradientRadius((r - l) * 0.62f));
        prefs = getSharedPreferences("home", MODE_PRIVATE);
        Intent start = getIntent();
        if (start != null && start.hasExtra(EXTRA_DEFAULT_CONFIG)) {
            defaultConfig = start.getBooleanExtra(EXTRA_DEFAULT_CONFIG, false);
        }
        glassOnReturn = start != null && start.getBooleanExtra(EXTRA_GLASS_SLIDER, false);
        backdrop = new LiquidGlass.Backdrop(findViewById(R.id.backdrop), LiquidGlass.tier(this),
                new LiquidGlass.Look(glassChoice()));
        shelfInfo = findViewById(R.id.shelfInfo);
        topShelf = new TopShelf(this, findViewById(R.id.shelfArt), shelfInfo, findViewById(R.id.shelfAction), this,
                getResources().getBoolean(R.bool.top_shelf_art), getResources().getStringArray(R.array.top_shelf_feeds),
                getResources().getStringArray(R.array.top_shelf_links),
                getResources().getStringArray(R.array.top_shelf_logos),
                getResources().getStringArray(R.array.top_shelf_images));
        // The process's configuration, as the glass's: a rebuilt activity (a configuration change it doesn't handle)
        // starts without the intent that set it.
        topShelf.useShippedChoices(defaultConfig);
        corner = findViewById(R.id.corner);
        corner.setBackdrop(backdrop);
        ViewStub topBarStub = findViewById(R.id.topBarStub);
        topBarStub.setLayoutInflater(LayoutInflater.from(new ContextThemeWrapper(this,
                TopBarButton.themeFor(getString(R.string.top_bar_style)))));
        topBar = topBarStub.inflate();
        corner.setBar(topBar);
        corner.setProfileName(getString(R.string.profile_name));
        topArea = findViewById(R.id.topArea);
        if (getResources().getBoolean(R.bool.shamsi_date)) {
            ViewStub dateStub = findViewById(R.id.shamsiDateStub);
            shamsiDate = dateStub.inflate().findViewById(R.id.shamsiDate);
        }
        cornerShade = findViewById(R.id.cornerShade);
        topCaption = findViewById(R.id.topCaption);
        btnWifi = topBar.findViewById(R.id.btnWifi);
        btnSettings = topBar.findViewById(R.id.btnSettings);

        grid = findViewById(R.id.grid);
        // A row's worth laid out past each edge, so the row focus moves to is there to take it.
        grid.setLayoutManager(new GridScroller.Layout(this, COLUMNS, (int) (150 * density)));
        scroller = new GridScroller(grid);
        grid.setHasFixedSize(true);
        grid.setItemAnimator(null);
        grid.addItemDecoration(new DockDecoration(COLUMNS, density, backdrop));
        grid.setLayoutAnimation(new LayoutAnimationController(
                AnimationUtils.loadAnimation(this, R.anim.tile_enter), 0.06f));
        grid.setAdapter(adapter);
        // Apps moved into or out of the dock: the top shelf fetches and prepares art for the new dock.
        adapter.setOnOrderSaved(() -> topShelf.setDock(adapter.dockApps()));
        rearranger = new Rearranger(this, root, grid, adapter, this, COLUMNS);
        rearranger.setGlassAction(this::showGlassSlider);
        if (getResources().getBoolean(R.bool.top_shelf_art)) rearranger.setTopShelfAction(this::openShelfSettings);
        applyAdbExtras(getIntent());

        // tvOS layout: push the dock row to the bottom of the screen; the grid hides below until you press Down.
        grid.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int h = b - t;
            int dockRow = (int) ((145 + 6 + 12) * density);   // item height plus DockDecoration offsets
            int want = Math.max(0, h - dockRow - (int) (30 * density));
            if (grid.getPaddingTop() != want) {
                grid.setPadding(grid.getPaddingLeft(), want, grid.getPaddingRight(), grid.getPaddingBottom());
            }
            followPage();
        });
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                followPage();
            }
        });

        btnInputs = topBar.findViewById(R.id.btnInputs);
        View avatar = corner.getAvatar();
        btnInputs.setOnClickListener(v -> {
            Perf.begin("tvhome:showInputs");
            try {
                showInputs();
            } finally {
                Perf.end();
            }
        });
        btnWifi.setOnClickListener(v -> openNetworkPill());
        btnWifi.setOnLongClickListener(v -> {
            open(new Intent(Settings.ACTION_WIFI_SETTINGS));
            return true;
        });
        btnSettings.setOnClickListener(v -> openSettings());
        avatar.setOnClickListener(v -> openProfile());

        // Keep focus in the top right corner at its ends and at the top: Android's focus finder would otherwise
        // jump to a tile far below when there is nothing in that direction. Down (and Back, which closes Control
        // Center on tvOS) goes back to the dock app you came up from, as on tvOS, instead of always the first one.
        View.OnKeyListener pillKeys = (v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) return true;
            if ((keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == KeyEvent.KEYCODE_BACK)
                    && adapter.getItemCount() > 0) {
                // Down passes the top shelf's button on the way, when it has one: it sits between here and the dock.
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && !rearranger.isActive() && topShelf.enterActions()) {
                    return true;
                }
                focusTile(lastFocused % COLUMNS);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                    && (v == btnInputs || (v == btnWifi && btnInputs.getVisibility() != View.VISIBLE))) return true;
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && v.getId() == R.id.btnSettings) {
                avatar.requestFocus();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && v == avatar) {
                btnSettings.requestFocus();
                return true;
            }
            return keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && v == avatar;
        };
        btnInputs.setOnKeyListener(pillKeys);
        btnWifi.setOnKeyListener(pillKeys);
        btnSettings.setOnKeyListener(pillKeys);
        avatar.setOnKeyListener(pillKeys);
        // What the focused button does, under it: Inputs, the network you're on, Quick settings, and the picture's
        // Accounts & profiles.
        View.OnFocusChangeListener captionFollow = (v, focused) -> {
            if (focused) showCaption(v);
            else handler.post(hideCaptionUnlessInCorner);   // moving along the bar, the next button keeps it
        };
        if (btnSettings.showsCaption()) {
            if (btnSettings.isTab()) topCaption.setTextColor(TAB_CAPTION_COLOR);
            for (View b : new View[]{btnInputs, btnWifi, btnSettings, avatar}) b.setOnFocusChangeListener(captionFollow);
        }

        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm != null) {
            cm.registerDefaultNetworkCallback(networkCallback);
            cm.registerNetworkCallback(new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build(), vpnCallback);
        }
        TvInputManager tim = getSystemService(TvInputManager.class);
        if (tim != null) tim.registerCallback(inputCallback, handler);
        Perf.end();
        Perf.log("create", t0, null);
    }

    @Override
    protected void onResume() {
        final long t0 = Perf.now();
        Perf.begin("tvhome:onResume");
        super.onResume();
        resumed = true;
        topShelf.resume();
        comeBackFromApp();
        // The top shelf stays as it was when an app was opened, so Home comes back showing it at once
        // instead of fading the name and glow in again; the focus restore below updates it if needed.
        // Read these before reload(): rebinding can move focus to the first tile and overwrite them.
        final String returnKey = lastFocusedKey;
        final int returnSlot = lastFocused;
        final boolean glass = glassOnReturn;
        glassOnReturn = false;
        if (appsChanged) reloadApps();
        // The tiles fly in once, when the launcher starts. Like tvOS, Home afterwards just shows the
        // home screen with focus where you left it, instead of replaying the entrance every time.
        if (!tilesEntered) {
            tilesEntered = true;
            grid.scheduleLayoutAnimation();
        }
        updateDate();
        handler.postDelayed(minuteTick, MINUTE_MS);

        // Return to the app we left from, or the same slot if that app is gone. Always refocus: after
        // reload() the view that holds focus may no longer show the app it had.
        grid.post(() -> {
            int pos = adapter.positionOf(returnKey);
            int to = pos >= 0 ? pos : returnSlot;
            // Liquid Glass from Home Settings: on the dock, whose glass it changes, then the slider.
            if (glass) {
                focusTile(to % COLUMNS);
                if (deferredFocus != null) afterDeferredFocus = this::showGlassSlider;
                else showGlassSlider();
            } else {
                focusTile(to);
            }
            Choreographer.getInstance().postFrameCallback(frameNanos -> {
                onFocusRestored(t0);
                // After this first frame: the network pill's binder calls took 4-12 ms on a TV before it, and the
                // network callback has kept the pill up to date meanwhile.
                handler.post(this::updateNetworkIndicator);
                handler.post(this::updateInputsButton);
            });
        });
        Perf.end();
        Perf.log("resume", t0, null);
    }

    private void reloadApps() {
        appsChanged = false;
        adapter.reload();
        topShelf.appsChanged();
        topShelf.setDock(adapter.dockApps());
        // The app the top shelf shows may have a new name or banner: make the next focus on it swap it in again.
        shelfKey = null;
    }

    /** The first frame after focus is back on a tile: Home is usable. Feeds tools/perf.py. */
    private void onFocusRestored(long resumeStartNanos) {
        Perf.log("focus_restored", resumeStartNanos, "first=" + (reportedFullyDrawn ? 0 : 1));
        if (reportedFullyDrawn) return;
        reportedFullyDrawn = true;
        Transitions.prepare(getApplicationContext());
        // The top shelf's art reads the disk and the network: not before Home is up.
        handler.post(topShelf::start);
        try {
            reportFullyDrawn();   // "Fully drawn" in the system log, for cold-start measurements
        } catch (RuntimeException ignored) {
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        rearranger.end();   // Home pressed while arranging: keep the new arrangement
        showMode = intent.getIntExtra(Transitions.EXTRA_SHOW, Transitions.SHOW_INTENT);
        glassOnReturn = intent.getBooleanExtra(EXTRA_GLASS_SLIDER, false);
        applyAdbExtras(intent);
    }

    /**
     * Opening an app, after tvOS: the tile stays pressed in while Home grows toward you and fades, and the app comes
     * from behind it (see {@link Transitions}). An app that lets Home show through (a settings panel) opens over
     * Home as it is.
     */
    @Override
    public void onLaunchApp(Intent intent) {
        long t0 = Perf.now();
        Perf.begin("tvhome:launch");
        try {
            startActivity(intent, Transitions.launchOptions(root));
            // After starting it, so the app isn't kept waiting: Home's first frame of the push comes later anyway.
            long tStarted = Perf.now();
            boolean seeThrough = Transitions.seeThrough(this, intent.getComponent());
            if (!seeThrough) {
                homeMotion.push();
                handler.removeCallbacks(comeBackIfShown);
                handler.postDelayed(comeBackIfShown, COME_BACK_MS);
            }
            if (Perf.enabled()) {
                Perf.log("launch", t0, String.format(Locale.US, "see_through=%d start_ms=%.1f check_ms=%.1f",
                        seeThrough ? 1 : 0, Perf.ms(t0, tStarted), Perf.ms(tStarted, Perf.now())));
            }
        } finally {
            Perf.end();
        }
    }

    /** Home is back on screen without having been hidden: the app didn't cover it, or not yet. */
    private void comeBack() {
        handler.removeCallbacks(comeBackIfShown);
        adapter.releasePress(true);
        if (homeMotion.isAway()) homeMotion.fallBack();
    }

    /**
     * Home shows again after an app (onResume) and falls back into place, as on tvOS, inside a window the system
     * fades in: with Home from HomeButtonService because it asks for a fade (see {@link Transitions#homeOptions}),
     * otherwise where the system only fades for that way back (Back on Google TV). Where it slides or zooms windows
     * instead, Home just shows: its own motion on top would fight the system's.
     */
    private void comeBackFromApp() {
        handler.removeCallbacks(comeBackIfShown);
        adapter.releasePress(true);
        if (homeMotion.isAway()) {
            homeMotion.fallBack();
        } else if (wasStopped && (showMode == Transitions.SHOW_FADE || Transitions.systemFadeMs(showMode) > 0)) {
            homeMotion.land();
        }
        wasStopped = false;
        showMode = Transitions.SHOW_BACK;
    }

    @Override
    protected void onStop() {
        super.onStop();
        wasStopped = true;
        handler.removeCallbacks(comeBackIfShown);
        // Hidden behind the app now: be at rest for when Home shows again.
        homeMotion.reset();
        adapter.releasePress(false);
        // Opened Settings or the like from the top right: Home comes back with it closed, as tvOS closes Control
        // Center, instead of showing it close. onResume moves focus back to the dock.
        if (corner.hasFocus()) corner.snapClosed();
    }

    @Override
    protected void onPause() {
        super.onPause();
        rearranger.end();
        hideGlassSlider(false);   // Home pressed, or something opened over Home: the slider closes, as the sheet does
        resumed = false;
        handler.removeCallbacks(reloadNow);   // appsChanged is still set, so the next onResume reloads
        handler.removeCallbacks(minuteTick);
        shelfTiming.cancel();
        topShelf.pause();
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        topShelf.trim(level);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm != null) {
            cm.unregisterNetworkCallback(networkCallback);
            cm.unregisterNetworkCallback(vpnCallback);
        }
        TvInputManager tim = getSystemService(TvInputManager.class);
        if (tim != null) tim.unregisterCallback(inputCallback);
        unregisterReceiver(packageReceiver);
        backdrop.release();
    }

    @Override
    public void onBackPressed() {
        // Home screen: Back goes nowhere, except while arranging apps.
        rearranger.onBack();
    }

    // ---- focus -----------------------------------------------------------------------------------

    @Override
    public void onTileFocused(int position, View card) {
        Perf.begin("tvhome:onTileFocused");
        topShelf.leaveActions();
        rearranger.onTileFocused(position);
        lastFocused = position;
        lastFocusedKey = adapter.keyAt(position);
        // Tile rect in root coordinates, using layout positions (ignores the scale animation).
        Rect r = new Rect(0, 0, card.getWidth(), card.getHeight());
        root.offsetDescendantRectToMyCoords(card, r);
        View item = (View) card.getParent();
        // Positions below are where things will be once a scroll still running ends.
        int pending = scroller.pending();
        if (position < COLUMNS) {
            // Dock: park it at the bottom of the screen and show the top shelf for this app.
            int decoratedTop = item.getTop() - pending - (int) (6 * density);
            int dy = decoratedTop - grid.getPaddingTop();
            if (Math.abs(dy) > 1) scroller.scrollBy(dy);
            else dy = 0;
            // While arranging, the header says what's going on instead of the top shelf.
            if (rearranger.isActive() || adapter.isActionTile(position)) hideHero();
            else showHero(position, r.top - pending - dy);
        } else {
            hideHero();
            scroller.scrollBy(gridScroll(item, pending));
        }
        Perf.end();
    }

    /**
     * How far to scroll for a focused grid tile, as tvOS does: Down from the dock moves the page so the dock row is at
     * the top, showing as many rows as fit, and further down the page moves just enough to show the whole grown tile
     * and its name. See {@link GridScroller#rowTarget}.
     */
    private int gridScroll(View item, int pending) {
        RecyclerView.LayoutManager lm = grid.getLayoutManager();
        int top = item.getTop() - pending - (int) (12 * density);
        int bottom = item.getBottom() - pending + (int) (8 * density);
        // The grid fills the screen, and the top bar scrolls away, so rows may go up to the TV's safe area. While
        // arranging, the header stays in place of the top bar, so they stop below it.
        int viewTop = rearranger.isActive()
                ? Math.max(topArea.getBottom(), rearranger.headerBottom()) + (int) (4 * density)
                : (int) (SAFE_TOP_DP * density);
        int viewBottom = grid.getHeight() - (int) (16 * density);
        int dockAtTop = Integer.MIN_VALUE, minDy = Integer.MIN_VALUE, maxDy = Integer.MAX_VALUE;
        View dock = lm != null ? lm.findViewByPosition(0) : null;
        if (dock != null) {
            int dockTop = lm.getDecoratedTop(dock) - pending;
            // The dock panel reaches 8dp above its row.
            dockAtTop = dockTop - viewTop - (int) (8 * density);
            minDy = Math.min(0, dockTop - grid.getPaddingTop());
        }
        View last = lm != null ? lm.findViewByPosition(adapter.getItemCount() - 1) : null;
        if (last != null) {
            maxDy = Math.max(0, lm.getDecoratedBottom(last) - pending - (grid.getHeight() - grid.getPaddingBottom()));
        }
        return GridScroller.rowTarget(top, bottom, viewTop, viewBottom, dockAtTop, minDy, maxDy);
    }

    /**
     * The top bar and the top shelf's name scroll with the page, as on tvOS: going down from the dock they move up
     * with it and the top bar fades out, so the grid has the whole screen; coming back to the dock brings them back.
     * Runs on every scroll step: only translation and alpha, no layout.
     */
    private void followPage() {
        RecyclerView.LayoutManager lm = grid.getLayoutManager();
        View dock = lm != null ? lm.findViewByPosition(0) : null;
        // The dock row isn't laid out when the page is far down: everything above it is gone too.
        pageTravel = dock == null ? grid.getHeight() : Math.max(0, grid.getPaddingTop() - lm.getDecoratedTop(dock));
        topArea.setTranslationY(-Math.min(pageTravel, topArea.getBottom()));
        topArea.setAlpha(1f - Math.min(1f, pageTravel / (TOP_BAR_FADE_DP * density)));
        cornerShade.setAlpha(topArea.getAlpha());
        heroName.setY(heroBaseY - pageTravel);
        topShelf.followPage(pageTravel);
    }

    /** Puts the top shelf's name at {@code y} (for the page at rest), wherever the page is now. */
    private void placeHero(float y) {
        heroBaseY = y;
        heroName.setY(y - pageTravel);
    }

    // ---- top shelf -------------------------------------------------------------------------------

    private void showHero(int position, float dockTopInRoot) {
        String key = adapter.keyAt(position);
        float y = dockTopInRoot - DockDecoration.PAD_V_DP * density - 26 * density - heroName.getHeight();
        topShelf.place(heroName.getLeft(), y + heroName.getHeight(), dockTopInRoot - DockDecoration.PAD_V_DP * density);

        if (!heroShown) {
            // Coming (back) to the dock: show this app straight away.
            heroShown = true;
            shelfKey = key;
            final int span = shelfTiming.start(Perf.enabled() ? "kind=show pkg=" + key : null);
            boolean ownTitle = topShelf.show(adapter.shelfAppAt(position), true,
                    () -> shelfTiming.part(SHELF_ART, span));
            heroName.animate().cancel();
            nameSwapping = false;
            heroName.setText(adapter.labelAt(position));
            placeHero(y);
            heroName.setTranslationX(0f);
            // The art's own title or logo takes the name's place.
            heroName.animate().alpha(ownTitle ? 0f : 1f).setDuration(SHELF_SHOW_NAME_MS).setInterpolator(DECELERATE)
                    .withEndAction(() -> shelfTiming.part(SHELF_TEXT, span)).start();
            if (ambientFade != null) ambientFade.cancel();
            setAmbient(glowAt(position));
            if (testArt != null) testArt.show(position);
            ambient.animate().alpha(1f).setDuration(SHELF_SHOW_GLOW_MS).setInterpolator(DECELERATE)
                    .withEndAction(() -> shelfTiming.part(SHELF_GLOW, span)).start();
        } else if (key != null && !key.equals(shelfKey)) {
            swapShelf(position, y, shelfTiming.start(Perf.enabled() ? "kind=swap pkg=" + key : null));
        } else {
            placeHero(y);
        }
    }

    /**
     * Changes the top shelf (art, name and ambient glow) to the dock app that just got focus. The name comes back
     * only if the art has no title or logo of its own for the app.
     */
    private void swapShelf(int position, float y, int span) {
        final String label = adapter.labelAt(position);
        shelfKey = adapter.keyAt(position);
        topShelf.show(adapter.shelfAppAt(position), false, () -> shelfTiming.part(SHELF_ART, span));
        heroName.animate().cancel();
        nameSwapping = true;
        // Out from wherever the name is (a swap just before may still be fading it), then the new one in.
        heroName.animate().alpha(0f).translationX(-8 * density)
                .setDuration((long) (NAME_OUT_MS * heroName.getAlpha())).setInterpolator(LINEAR)
                .withEndAction(() -> {
                    nameSwapping = false;
                    heroName.setText(label);
                    placeHero(y);
                    if (topShelf.ownTitle(shelfKey)) {
                        heroName.setTranslationX(0f);
                        shelfTiming.part(SHELF_TEXT, span);
                        return;
                    }
                    heroName.setTranslationX(8 * density);
                    heroName.animate().alpha(1f).translationX(0f).setDuration(NAME_IN_MS)
                            .setInterpolator(DECELERATE).withEndAction(() -> shelfTiming.part(SHELF_TEXT, span))
                            .start();
                }).start();
        if (testArt != null) testArt.show(position);
        int color = glowAt(position);
        if (ambientFade != null) ambientFade.cancel();
        if (color == ambientColor) {
            shelfTiming.part(SHELF_GLOW, span);
            return;
        }
        // The glow's stops share their alpha, so blending the colour is a true cross-fade of the two glows.
        ambientFade = ValueAnimator.ofArgb(ambientColor, color);
        ambientFade.setDuration(GLOW_BLEND_MS);
        ambientFade.setInterpolator(DECELERATE);
        ambientFade.addUpdateListener(a -> {
            setAmbient((int) a.getAnimatedValue());
            if (a.getAnimatedFraction() >= 1f) shelfTiming.part(SHELF_GLOW, span);
        });
        ambientFade.start();
    }

    /** The art for the app on the shelf arrived (or changed) after focus did: the name makes way, or comes back. */
    @Override
    public void onShelfArt(String key, boolean ownTitle) {
        if (!heroShown || nameSwapping || key == null || !key.equals(shelfKey)) return;
        heroName.animate().cancel();
        heroName.setTranslationX(0f);
        heroName.animate().alpha(ownTitle ? 0f : 1f).setDuration(ownTitle ? SHELF_HIDE_NAME_MS : NAME_IN_MS)
                .setInterpolator(DECELERATE).start();
    }

    /** The shelf's button: opens the title in its app, Home moving away as it does for a tile. */
    @Override
    public void onShelfAction(Intent intent) {
        try {
            onLaunchApp(intent);
        } catch (RuntimeException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onShelfExit(boolean up) {
        if (up) btnSettings.requestFocus();
        else focusTile(lastFocused % COLUMNS);
    }

    /** The app's top shelf choice changed while it shows (Home Settings): its glow takes the colour now. */
    @Override
    public void onShelfGlow(String key) {
        if (!heroShown || key == null || !key.equals(shelfKey)) return;
        int pos = adapter.positionOf(key);
        if (pos < 0) return;
        if (ambientFade != null) ambientFade.cancel();
        setAmbient(glowAt(pos));
    }

    /** The glow's colour for the app at a position: the one chosen for it in Home Settings, else its own. */
    private int glowAt(int position) {
        int chosen = topShelf.chosenGlow(adapter.pkgAt(position));
        return chosen != 0 ? chosen : adapter.ambientColorAt(position);
    }

    /** Art fills the screen: the glow under it needn't be drawn. */
    @Override
    public void onShelfCovered(boolean covered) {
        ambient.setVisibility(covered ? View.INVISIBLE : View.VISIBLE);
    }

    private void setAmbient(int color) {
        ambientColor = color;
        int c = color & 0x00FFFFFF;
        ambientGlow.setColors(new int[]{0x5C000000 | c, 0x26000000 | c, 0x00000000 | c});
    }

    private void hideHero() {
        shelfTiming.cancel();
        if (!heroShown) return;
        heroShown = false;
        nameSwapping = false;
        topShelf.hide();
        heroName.animate().cancel();   // drops a pending name swap's end action, which would fade it back in
        heroName.animate().alpha(0f).setDuration(SHELF_HIDE_NAME_MS).setInterpolator(DECELERATE).start();
        ambient.animate().alpha(0f).setDuration(SHELF_HIDE_GLOW_MS).setInterpolator(DECELERATE).start();
        if (testArt != null) testArt.hide();
    }

    // ---- Liquid Glass ----------------------------------------------------------------------------

    /** How see-through the glass is, 0 to 100; saved, unless it comes from config.xml. */
    private void setGlass(int value, boolean save) {
        backdrop.setLook(new LiquidGlass.Look(value));
        if (save) prefs.edit().putInt(PREF_GLASS, backdrop.look().value).apply();
    }

    /** The glass to show: the saved choice, or config.xml's without one or with {@link #EXTRA_DEFAULT_CONFIG}. */
    private int glassChoice() {
        int shipped = getResources().getInteger(R.integer.liquid_glass);
        return defaultConfig ? shipped : prefs.getInt(PREF_GLASS, shipped);
    }

    /**
     * The adb extras: {@code --ez default_config true} (see {@link #EXTRA_DEFAULT_CONFIG}),
     * {@code --ei liquid_glass 50} (-1 for config.xml's value; saved, except with default_config on, when it lasts as
     * long as the process), {@code --ez glass_test_art true}. With tools/perf.py's logging on, an intent with any of
     * them logs what's in effect after it ("config"). perf.py passes them on every start: only what changes is redone,
     * since a new look re-records the glass.
     */
    private void applyAdbExtras(Intent intent) {
        if (intent == null) return;
        boolean art = intent.hasExtra(GlassTestArt.EXTRA);
        boolean config = intent.hasExtra(EXTRA_DEFAULT_CONFIG);
        Integer glassExtra = glassExtra(intent);
        if (config) {
            boolean on = intent.getBooleanExtra(EXTRA_DEFAULT_CONFIG, false);
            // Into or out of the shipped configuration: the stand-in art goes, unless this intent asks for it.
            if (on != defaultConfig && !art) showTestArt(false);
            defaultConfig = on;
            topShelf.useShippedChoices(on);
        }
        if (art) showTestArt(intent.getBooleanExtra(GlassTestArt.EXTRA, false));
        if (config || glassExtra != null) {
            int shipped = getResources().getInteger(R.integer.liquid_glass);
            boolean save = glassExtra != null && !defaultConfig;
            if (save && glassExtra < 0) prefs.edit().remove(PREF_GLASS).apply();
            int glass = glassExtra == null ? glassChoice() : glassExtra < 0 ? shipped : glassExtra;
            if (save && glassExtra >= 0) {
                setGlass(glass, true);
            } else if (glass != backdrop.look().value) {
                setGlass(glass, false);
            }
        }
        if ((art || config || glassExtra != null) && Perf.enabled()) {
            String[] tiers = {"flat", "blur", "lens"};
            Perf.log("config", Perf.now(), "default_config=" + (defaultConfig ? 1 : 0)
                    + " glass=" + backdrop.look().value
                    + " glass_default=" + getResources().getInteger(R.integer.liquid_glass)
                    + " glass_saved=" + prefs.getInt(PREF_GLASS, -1)
                    + " glass_tier=" + tiers[LiquidGlass.tier(this)]
                    + " test_art=" + (testArt != null ? 1 : 0)
                    + " shelf_choices=" + topShelf.choicesInEffect()
                    + " shelf_all=" + topShelf.allChoice());
        }
    }

    /** {@code --ei liquid_glass N} (or a string), or null. */
    private static Integer glassExtra(Intent intent) {
        if (!intent.hasExtra(EXTRA_GLASS) || intent.getExtras() == null) return null;
        Object extra = intent.getExtras().get(EXTRA_GLASS);
        try {
            return extra instanceof Number ? (Integer) ((Number) extra).intValue()
                    : Integer.valueOf(String.valueOf(extra).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void showTestArt(boolean on) {
        ViewGroup into = (ViewGroup) backdrop.view;
        if (on && testArt == null) {
            testArt = new GlassTestArt(this);
            into.addView(testArt, 1, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            if (heroShown) testArt.show(lastFocused % COLUMNS);
        } else if (!on && testArt != null) {
            into.removeView(testArt);
            testArt = null;
        }
    }

    /**
     * Apple's Liquid Glass slider, near the top of the screen so the dock and the top right show each step as you
     * take it. From the actions of any app (OK held on it).
     */
    private void showGlassSlider() {
        if (glassPanel == null) {
            glassPanel = getLayoutInflater().inflate(R.layout.glass_slider, root, false);
            root.addView(glassPanel);
            final GlassSlider slider = glassPanel.findViewById(R.id.glassSlider);
            final TextView label = glassPanel.findViewById(R.id.glassValue);
            slider.setListener(new GlassSlider.Listener() {
                @Override
                public void onGlassChanged(int value) {
                    setGlass(value, true);
                    label.setText(glassLabel(value));
                }

                @Override
                public void onGlassDone() {
                    hideGlassSlider(true);
                }
            });
        }
        GlassSlider slider = glassPanel.findViewById(R.id.glassSlider);
        int value = backdrop.look().value;
        slider.setValue(value);
        ((TextView) glassPanel.findViewById(R.id.glassValue)).setText(glassLabel(value));
        glassPanel.animate().cancel();
        glassPanel.setVisibility(View.VISIBLE);
        glassPanel.setAlpha(0f);
        glassPanel.setTranslationY(-8 * density);
        glassPanel.animate().alpha(1f).translationY(0f).setDuration(GLASS_PANEL_IN_MS).setInterpolator(DECELERATE)
                .start();
        slider.requestFocus();
    }

    private void hideGlassSlider(boolean animate) {
        if (glassPanel == null || glassPanel.getVisibility() != View.VISIBLE) return;
        // Focus goes back to the app first: hiding the focused slider would hand focus to the first tile.
        focusTile(lastFocused);
        glassPanel.animate().cancel();
        if (animate) {
            glassPanel.animate().alpha(0f).setDuration(GLASS_PANEL_OUT_MS).setInterpolator(DECELERATE)
                    .withEndAction(() -> glassPanel.setVisibility(View.GONE)).start();
        } else {
            glassPanel.setVisibility(View.GONE);
        }
    }

    private CharSequence glassLabel(int value) {
        return glassLabel(this, value);
    }

    /** The slider's value as it reads: Clear, Tinted or a percentage. Also Home Settings'. */
    static CharSequence glassLabel(Context ctx, int value) {
        if (value == LiquidGlass.Look.CLEAR) return ctx.getString(R.string.glass_clear);
        if (value == LiquidGlass.Look.TINTED) return ctx.getString(R.string.glass_tinted);
        return ctx.getString(R.string.glass_value, value);
    }

    // ---- navigation ------------------------------------------------------------------------------

    /**
     * All D-pad moves between tiles. Left and Right stay in the row and stop at its ends, as on tvOS:
     * Android's focus finder would otherwise jump from the right-most tile up to the Settings pill.
     */
    @Override
    public boolean onTileDpadKey(int position, int keyCode) {
        int target = AppOrder.step(position, adapter.getItemCount(), COLUMNS, keyCode);
        if (target < 0) {
            // Up from the dock: into the top shelf, as on tvOS, when it shows a suggested title (Play, Resume, Open);
            // otherwise (a static picture, artwork, cover art or the glow), and Up again from there, the top right
            // corner opens on Settings, next to the profile picture, as tvOS opens Control Center on its settings tab.
            if (!rearranger.isActive() && topShelf.enterActions()) return true;
            btnSettings.requestFocus();
            return true;
        }
        if (target != position) focusTile(target);         // otherwise stay put at the row ends and last row
        return true;
    }

    /** An app's Top Shelf in its actions: Home Settings opens on its choice, and Back comes straight back here. */
    private void openShelfSettings(String pkg) {
        Intent i = new Intent(this, SettingsActivity.class).putExtra(SettingsActivity.EXTRA_PACKAGE, pkg)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            onLaunchApp(i);
        } catch (RuntimeException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    /** Holding Select on an app opens its actions: Move, Move to Dock, App info. */
    @Override
    public void onTileLongPressed(int position, View card) {
        rearranger.showActions(position);
    }

    @Override
    public boolean onTileKey(int position, KeyEvent event) {
        return rearranger.onKey(position, event);
    }

    @Override
    public void onActionTile() {
        rearranger.onActionTile();
    }

    private void focusTile(int position) {
        if (adapter.getItemCount() == 0) return;
        final int pos = Math.max(0, Math.min(position, adapter.getItemCount() - 1));
        focusTarget = pos;
        afterDeferredFocus = null;
        if (deferredFocus != null) {
            grid.removeOnScrollListener(deferredFocus);
            deferredFocus = null;
        }
        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(pos);
        if (vh != null) {
            View card = vh.itemView.findViewById(R.id.card);
            // Already focused (e.g. back from an app): no focus change fires, so restore the top shelf by hand.
            if (card.isFocused()) onTileFocused(pos, card);
            else card.requestFocus();
            return;
        }
        // Not laid out yet (the grid lays out ahead of a running scroll, so this is rare): scroll there, then
        // focus once the scroll settles, unless focus has been sent elsewhere since.
        deferredFocus = new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) return;
                rv.removeOnScrollListener(this);
                if (deferredFocus == this) deferredFocus = null;
                if (focusTarget != pos) return;
                RecyclerView.ViewHolder h = rv.findViewHolderForAdapterPosition(pos);
                View target = h != null ? h.itemView.findViewById(R.id.card) : rv.getChildAt(0);
                if (target != null) target.requestFocus();
                Runnable then = afterDeferredFocus;
                afterDeferredFocus = null;
                if (then != null) then.run();
            }
        };
        grid.addOnScrollListener(deferredFocus);
        scroller.stop();
        grid.smoothScrollToPosition(pos);
    }

    // ---- top bar ---------------------------------------------------------------------------------

    private void updateDate() {
        if (shamsiDate != null) shamsiDate.setText(Shamsi.today());
    }

    /** Shows the active network type and its matching icon; a network name when connected over Wi-Fi. */
    private void updateNetworkIndicator() {
        Perf.begin("tvhome:updateNetworkIndicator");
        boolean wifiSupported = getPackageManager().hasSystemFeature(PackageManager.FEATURE_WIFI);
        String text = getString(wifiSupported ? R.string.wifi : R.string.ethernet);
        boolean vpn = !vpnNetworks.isEmpty(), online = false, ethernet = false, wifi = false;
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            // A VPN may be the active network, or exclude this app: inspect the underlying networks too.
            for (Network net : cm != null ? cm.getAllNetworks() : new Network[0]) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(net);
                if (caps == null) continue;
                // onLost can arrive while getAllNetworks still contains the old VPN: callbacks own its status.
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue;
                online = true;
                ethernet |= caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET);
                wifi |= caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
            }
            if (!online) {
                text = getString(R.string.offline);
                vpn = false;
            } else if (ethernet) {
                text = getString(R.string.ethernet);
            } else if (wifi) {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                WifiInfo info = wm != null ? wm.getConnectionInfo() : null;
                String ssid = info != null ? info.getSSID() : null;
                if (ssid != null) {
                    ssid = ssid.replace("\"", "").trim();
                    if (!ssid.isEmpty() && !ssid.contains("unknown") && !ssid.equals("0x")) text = ssid;
                }
            }
        } catch (SecurityException e) {
            Log.w("tvhome", "Cannot read network status", e);
        }
        btnWifi.setIcon(networkIcon(wifiSupported, ethernet));
        if (vpn) {
            String badge = getString(R.string.vpn);
            SpannableString label = new SpannableString(text + " " + badge);
            label.setSpan(new VpnBadge(getDrawable(R.drawable.ic_lock), density),
                    text.length() + 1, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            btnWifi.setLabel(label);
            text += " " + badge;
        } else {
            btnWifi.setLabel(text);
        }
        btnWifi.setContentDescription(getString(R.string.network_status, text));
        if (btnWifi.showsCaption() && btnWifi.isFocused()) showCaption(btnWifi);
        Perf.end();
    }

    static int networkIcon(boolean wifiSupported, boolean ethernet) {
        if (ethernet || !wifiSupported) return R.drawable.ic_ethernet;
        return R.drawable.ic_wifi;
    }

    private void openNetworkPill() {
        Intent i = vpnApp.isEmpty() ? null : getPackageManager().getLeanbackLaunchIntentForPackage(vpnApp);
        if (i == null && !vpnApp.isEmpty()) i = getPackageManager().getLaunchIntentForPackage(vpnApp);
        open(i != null ? i : new Intent(Settings.ACTION_WIFI_SETTINGS));
    }

    private final Runnable hideCaptionUnlessInCorner = () -> {
        if (corner.hasFocus()) return;
        topCaption.animate().cancel();
        topCaption.setAlpha(0f);
    };

    /** The caption under a top bar button or the profile picture: what it does. */
    private CharSequence captionFor(View v) {
        if (v == btnSettings && !settingsLabelChecked) {
            // On Google TV, Settings opens the quick settings panel (openSettings): say so. Checked the first time it
            // is needed, not at start-up: it asks the package manager twice.
            settingsLabelChecked = true;
            if (QuickSettings.available(this)) {
                btnSettings.setLabel(getString(R.string.quick_settings));
                btnSettings.setContentDescription(getString(R.string.quick_settings));
            }
        }
        return v instanceof TopBarButton ? ((TopBarButton) v).getLabel() : getString(R.string.accounts_profiles);
    }

    /**
     * The focused button's caption, centred under it just below the bar. Like Google TV's launcher, it moves with
     * focus at once. With Apple TV's tabs it fades in, once the corner has opened.
     */
    private void showCaption(View b) {
        topCaption.setText(captionFor(b));
        int unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        topCaption.measure(unspecified, unspecified);
        Rect button = new Rect(0, 0, b.getWidth(), b.getHeight());
        root.offsetDescendantRectToMyCoords(b, button);
        Rect bar = new Rect(0, 0, topBar.getWidth(), topBar.getHeight());
        root.offsetDescendantRectToMyCoords(topBar, bar);
        float w = topCaption.getMeasuredWidth();
        float x = Math.min(button.exactCenterX() - w / 2f, root.getWidth() - w - 24 * density);
        topCaption.setX(Math.max(0f, x));
        topCaption.setY(bar.bottom + 4 * density);
        if (!btnSettings.isTab()) {
            topCaption.setAlpha(1f);
        } else if (topCaption.getAlpha() == 0f) {
            // Opening: wait until the button has nearly arrived. Along the open corner: at once.
            topCaption.animate().alpha(1f).setStartDelay(corner.isOpen() ? 0 : CAPTION_OPEN_DELAY_MS)
                    .setDuration(CAPTION_FADE_MS).setInterpolator(DECELERATE).start();
        }
    }

    /** Google TV's quick settings panel, like the remote's Settings button; the full Settings app elsewhere. */
    private void openSettings() {
        if (!QuickSettings.open(this)) open(new Intent(Settings.ACTION_SETTINGS));
    }

    /**
     * The profile picture: the TV's accounts and profiles (on Google TV, "Accounts &amp; Profiles", where you switch
     * profiles), or Settings if it has no such page. Google TV's own profile chooser only answers Google Play
     * services.
     */
    private void openProfile() {
        Intent accounts = new Intent(USER_SETTINGS);
        if (accounts.resolveActivity(getPackageManager()) == null) accounts = new Intent(Settings.ACTION_SETTINGS);
        open(accounts);
    }

    private void open(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    private void updateInputsButton() {
        boolean any = !liveTvPkg.isEmpty()
                && getPackageManager().getLeanbackLaunchIntentForPackage(liveTvPkg) != null;
        TvInputManager tim = getSystemService(TvInputManager.class);
        if (!any && tim != null) {
            for (TvInputInfo info : tim.getTvInputList()) {
                if (info.isPassthroughInput()) {
                    any = true;
                    break;
                }
            }
        }
        if (!any && btnInputs.hasFocus()) btnWifi.requestFocus();
        btnInputs.setVisibility(any ? View.VISIBLE : View.GONE);
    }

    /** Dropdown menu under the Inputs button: HDMI ports (with connection state), AV and Live TV. */
    private void showInputs() {
        TopBarButton inputsButton = topBar.findViewById(R.id.btnInputs);
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);

        // Compact: as wide as the open Inputs pill (a touch wider so "HDMI 1" fits). It hangs from the top right
        // corner, right-aligned like Control Center's panel on tvOS. The open width, not the current one: a click
        // can land while the button is still opening.
        int width = Math.max(inputsButton.getExpandedWidth(), (int) (150 * density));
        final PopupWindow popup = new PopupWindow(panel, width, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));   // needed for Back to dismiss
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(0);
        popup.setElevation(0f);

        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        boolean sawNonHdmi = false;
        String tunerId = null;
        if (tim != null) {
            List<TvInputInfo> inputs = new ArrayList<>(tim.getTvInputList());
            // HDMI ports first in port order, then AV and the rest.
            Collections.sort(inputs, (a, b) -> {
                boolean ha = a.getType() == TvInputInfo.TYPE_HDMI, hb = b.getType() == TvInputInfo.TYPE_HDMI;
                if (ha != hb) return ha ? -1 : 1;
                return String.valueOf(a.loadLabel(this)).compareToIgnoreCase(String.valueOf(b.loadLabel(this)));
            });
            // Some TVs (Sony) report every HDMI port as connected whether or not anything is plugged in,
            // so drop the HDMI status then. A TV with every port really in use loses it too.
            int hdmiPorts = 0, hdmiConnected = 0;
            for (TvInputInfo info : inputs) {
                if (!info.isPassthroughInput() || info.getType() != TvInputInfo.TYPE_HDMI) continue;
                hdmiPorts++;
                if (tim.getInputState(info.getId()) == TvInputManager.INPUT_STATE_CONNECTED) hdmiConnected++;
            }
            boolean hdmiStateBogus = hdmiPorts > 1 && hdmiConnected == hdmiPorts;
            for (TvInputInfo info : inputs) {
                if (info.getType() == TvInputInfo.TYPE_TUNER
                        && (info.getServiceInfo().applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                        && (tunerId == null || info.getId().compareTo(tunerId) < 0)) {
                    tunerId = info.getId();
                }
                if (!info.isPassthroughInput()) continue;
                CharSequence l = info.loadLabel(this);
                String label = l == null ? info.getId() : l.toString();
                boolean hdmi = info.getType() == TvInputInfo.TYPE_HDMI;
                if (hdmi && !label.toUpperCase(Locale.ROOT).contains("HDMI")) label = "HDMI " + label;
                final String id = info.getId();
                int state = hdmi && hdmiStateBogus ? -1 : tim.getInputState(id);
                if (!hdmi && rows.getChildCount() > 0 && !sawNonHdmi) addSeparator(rows);
                if (!hdmi) sawNonHdmi = true;
                addInputRow(rows, popup, hdmi ? R.drawable.ic_input : R.drawable.ic_av, label, state,
                        () -> open(new Intent(Intent.ACTION_VIEW).setDataAndType(
                                TvContract.buildChannelUriForPassthroughInput(id), "vnd.android.cursor.item/channel")));
            }
        }
        Intent liveTv = liveTvPkg.isEmpty() ? null : getPackageManager().getLeanbackLaunchIntentForPackage(liveTvPkg);
        if (liveTv != null && tunerId != null) {
            liveTv = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(TvContract.buildChannelUriForPassthroughInput(tunerId), "vnd.android.cursor.item/channel")
                    .setPackage(liveTvPkg);
        }
        if (liveTv != null) {
            if (rows.getChildCount() > 0) addSeparator(rows);
            final Intent liveTvIntent = liveTv;
            addInputRow(rows, popup, R.drawable.ic_livetv, getString(R.string.live_tv), -1, () -> open(liveTvIntent));
        }
        if (rows.getChildCount() == 0) {
            Toast.makeText(this, R.string.no_inputs, Toast.LENGTH_SHORT).show();
            return;
        }

        // Fit the widest row: TVs that report a status on every port (Sony) would otherwise squeeze the names out.
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        popup.setWidth(Math.max(width, panel.getMeasuredWidth()));

        // Drop in: fade and unfold from the top edge.
        panel.setAlpha(0f);
        panel.setPivotY(0f);
        panel.setScaleY(0.92f);
        panel.setTranslationY(-8 * density);
        final int yOff = (int) (10 * density);
        popup.showAsDropDown(corner, 0, yOff, Gravity.END);
        // The Google TV caption would sit under the dropdown.
        topCaption.setAlpha(0f);
        popup.setOnDismissListener(() -> {
            if (inputsButton.showsCaption() && inputsButton.isFocused()) showCaption(inputsButton);
        });
        panel.animate().alpha(1f).scaleY(1f).translationY(0f).setDuration(220)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
        rows.getChildAt(0).requestFocus();
    }

    private void addSeparator(LinearLayout rows) {
        View line = new View(this);
        line.setBackgroundColor(0x1FFFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) density);
        int m = (int) (6 * density), h = (int) (12 * density);
        lp.setMargins(h, m, h, m);
        rows.addView(line, lp);
    }

    private void addInputRow(LinearLayout rows, PopupWindow popup, int iconRes, String title, int state, Runnable action) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(iconRes);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        TextView status = row.findViewById(R.id.status);
        View dot = row.findViewById(R.id.dot);
        // This TV reports "standby" for every port whether or not something is plugged in, so only
        // definite states are shown.
        if (state == TvInputManager.INPUT_STATE_CONNECTED) {
            status.setText(R.string.connected);
            dot.setVisibility(View.VISIBLE);
        } else if (state == TvInputManager.INPUT_STATE_DISCONNECTED) {
            status.setText(R.string.not_connected);
            dot.setVisibility(View.GONE);
        } else {
            status.setVisibility(View.GONE);
            dot.setVisibility(View.GONE);
        }
        row.setOnClickListener(v -> {
            popup.dismiss();
            action.run();
        });
        rows.addView(row);
    }
}
