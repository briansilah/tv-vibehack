package com.gathin.tvhome;

import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
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
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.ContextThemeWrapper;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewStub;
import android.view.ViewTreeObserver;
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
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;

/**
 * tvOS style home: black, a pinned dock row, an app grid; the focused tile grows on a soft shadow.
 */
public class MainActivity extends Activity implements AppAdapter.TileListener {

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
    private static final TimeInterpolator LINEAR = new LinearInterpolator();
    private String liveTvPkg;

    private AppAdapter adapter;
    private RecyclerView grid;
    private FrameLayout root;
    private View ambient;
    private TextView heroName;
    private GradientDrawable ambientGlow;
    /** The glow's three stops, reused for every frame of a colour blend. */
    private final int[] glowStops = new int[3];
    private int ambientColor;
    private ValueAnimator ambientFade;
    private boolean heroShown;
    /** App the top shelf shows, or is changing to. */
    private String shelfKey;
    private boolean tilesEntered;
    private TextView greeting;
    private TopBarButton btnWifi;
    private View topBar;
    /** Google TV look: the focused top bar button's name, under the bar. */
    private TextView topCaption;
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
            handler.post(MainActivity.this::updateWifiLabel);
        }

        @Override
        public void onLost(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }
    };
    /**
     * Set when a TV app was installed, updated, removed, enabled or disabled while Home was in the background:
     * the next onResume reloads the app list. Otherwise Home comes back without reloading every banner.
     */
    private boolean appsChanged = true;
    private boolean resumed;
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
            if (resumed) {
                handler.removeCallbacks(reloadNow);   // an update sends several broadcasts
                handler.post(reloadNow);
            }
        }
    };
    private final Runnable reloadNow = () -> {
        final String returnKey = lastFocusedKey;
        final int returnSlot = lastFocused;
        appsChanged = false;
        adapter.reload();
        grid.post(() -> {
            int pos = adapter.positionOf(returnKey);
            focusTile(pos >= 0 ? pos : returnSlot);
        });
    };
    private final Runnable minuteTick = new Runnable() {
        @Override
        public void run() {
            updateGreeting();
            updateWifiLabel();
            handler.postDelayed(this, MINUTE_MS);
        }
    };
    /** From a dock focus change until the top shelf shows that app (name and glow); tools/perf.py's dock scenario. */
    private final Perf.Span shelfTiming = new Perf.Span("shelf", "text", "glow");
    private static final int SHELF_TEXT = 0, SHELF_GLOW = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        long t0 = Perf.now();
        Perf.begin("tvhome:onCreate");
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        density = getResources().getDisplayMetrics().density;
        COLUMNS = Math.max(1, getResources().getInteger(R.integer.columns));
        liveTvPkg = getString(R.string.live_tv_package).trim();

        root = findViewById(R.id.root);
        ambient = findViewById(R.id.ambient);
        heroName = findViewById(R.id.heroName);
        ambientGlow = new GradientDrawable();
        ambientGlow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        ambientGlow.setGradientCenter(0.32f, 0.42f);
        ambient.setBackground(ambientGlow);
        // One drawable, so fading the view can just fade it: without this, every frame of a fade draws the
        // full-screen glow into an offscreen buffer first.
        ambient.forceHasOverlappingRendering(false);
        ambient.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) ->
                ambientGlow.setGradientRadius((r - l) * 0.62f));
        greeting = findViewById(R.id.greeting);
        ViewStub topBarStub = findViewById(R.id.topBarStub);
        topBarStub.setLayoutInflater(LayoutInflater.from(new ContextThemeWrapper(this,
                TopBarButton.themeFor(getString(R.string.top_bar_style)))));
        topBar = topBarStub.inflate();
        topCaption = findViewById(R.id.topCaption);
        btnWifi = topBar.findViewById(R.id.btnWifi);

        grid = findViewById(R.id.grid);
        grid.setLayoutManager(new GridLayoutManager(this, COLUMNS));
        grid.setHasFixedSize(true);
        grid.setItemAnimator(null);
        grid.addItemDecoration(new DockDecoration(COLUMNS, density));
        grid.setLayoutAnimation(new LayoutAnimationController(
                AnimationUtils.loadAnimation(this, R.anim.tile_enter), 0.06f));
        adapter = new AppAdapter(this, this, COLUMNS);
        grid.setAdapter(adapter);

        // tvOS layout: push the dock row to the bottom of the screen; the grid hides below until you press Down.
        grid.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int h = b - t;
            int dockRow = (int) ((145 + 6 + 12) * density);   // item height plus DockDecoration offsets
            int want = Math.max(0, h - dockRow - (int) (30 * density));
            if (grid.getPaddingTop() != want) {
                grid.setPadding(grid.getPaddingLeft(), want, grid.getPaddingRight(), grid.getPaddingBottom());
            }
        });

        TopBarButton btnInputs = topBar.findViewById(R.id.btnInputs);
        TopBarButton btnSettings = topBar.findViewById(R.id.btnSettings);
        btnInputs.setOnClickListener(v -> {
            Perf.begin("tvhome:showInputs");
            try {
                showInputs();
            } finally {
                Perf.end();
            }
        });
        btnWifi.setOnClickListener(v -> open(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        btnSettings.setOnClickListener(v -> openSettings());

        // Keep focus on the top bar at its ends and at the top: Android's focus finder would otherwise
        // jump to a tile far below when there is nothing in that direction. Down goes back to the dock app
        // you came up from, as on tvOS, instead of always the first one.
        View.OnKeyListener pillKeys = (v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) return true;
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && adapter.getItemCount() > 0) {
                focusTile(lastFocused % COLUMNS);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && v.getId() == R.id.btnInputs) return true;
            return keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && v.getId() == R.id.btnSettings;
        };
        btnInputs.setOnKeyListener(pillKeys);
        btnWifi.setOnKeyListener(pillKeys);
        btnSettings.setOnKeyListener(pillKeys);
        View.OnFocusChangeListener captionFollow = (v, focused) -> {
            if (focused) showCaption((TopBarButton) v);
            else topCaption.setAlpha(0f);
        };
        for (TopBarButton b : new TopBarButton[]{btnInputs, btnWifi, btnSettings}) {
            if (b.showsCaption()) b.setOnFocusChangeListener(captionFollow);
        }

        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm != null) cm.registerDefaultNetworkCallback(networkCallback);
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
        Perf.end();
        Perf.log("create", t0, null);
    }

    @Override
    protected void onResume() {
        final long t0 = Perf.now();
        Perf.begin("tvhome:onResume");
        super.onResume();
        resumed = true;
        // The top shelf stays as it was when an app was opened, so Home comes back showing it at once
        // instead of fading the name and glow in again; the focus restore below updates it if needed.
        // Read these before reload(): rebinding can move focus to the first tile and overwrite them.
        final String returnKey = lastFocusedKey;
        final int returnSlot = lastFocused;
        if (appsChanged) {
            appsChanged = false;
            adapter.reload();
        }
        // The tiles fly in once, when the launcher starts. Like tvOS, Home afterwards just shows the
        // home screen with focus where you left it, instead of replaying the entrance every time.
        if (!tilesEntered) {
            tilesEntered = true;
            grid.scheduleLayoutAnimation();
        }
        updateGreeting();
        updateWifiLabel();
        handler.postDelayed(minuteTick, MINUTE_MS);

        // Return to the app we left from, or the same slot if that app is gone. Always refocus: after
        // reload() the view that holds focus may no longer show the app it had.
        grid.post(() -> {
            int pos = adapter.positionOf(returnKey);
            focusTile(pos >= 0 ? pos : returnSlot);
            Choreographer.getInstance().postFrameCallback(frameNanos -> onFocusRestored(t0));
        });
        Perf.end();
        Perf.log("resume", t0, null);
    }

    /** The first frame after focus is back on a tile: Home is usable. Feeds tools/perf.py. */
    private void onFocusRestored(long resumeStartNanos) {
        Perf.log("focus_restored", resumeStartNanos, "first=" + (reportedFullyDrawn ? 0 : 1));
        if (reportedFullyDrawn) return;
        reportedFullyDrawn = true;
        try {
            reportFullyDrawn();   // "Fully drawn" in the system log, for cold-start measurements
        } catch (RuntimeException ignored) {
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        handler.removeCallbacks(reloadNow);   // appsChanged is still set, so the next onResume reloads
        handler.removeCallbacks(minuteTick);
        shelfTiming.cancel();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm != null) cm.unregisterNetworkCallback(networkCallback);
        unregisterReceiver(packageReceiver);
    }

    @Override
    public void onBackPressed() {
        // Home screen: Back goes nowhere.
    }

    // ---- focus -----------------------------------------------------------------------------------

    @Override
    public void onTileFocused(int position, View card) {
        Perf.begin("tvhome:onTileFocused");
        lastFocused = position;
        lastFocusedKey = adapter.keyAt(position);
        // Tile rect in root coordinates, using layout positions (ignores the scale animation).
        Rect r = new Rect(0, 0, card.getWidth(), card.getHeight());
        root.offsetDescendantRectToMyCoords(card, r);
        View item = (View) card.getParent();
        if (position < COLUMNS) {
            // Dock: park it at the bottom of the screen and show the top shelf for this app.
            int decoratedTop = item.getTop() - (int) (6 * density);
            int dy = decoratedTop - grid.getPaddingTop();
            if (Math.abs(dy) > 1) grid.smoothScrollBy(0, dy);
            showHero(position, r.top - dy);
        } else {
            hideHero();
            // Scroll just enough to show the whole grown tile, its shadow and its name.
            Rect want = new Rect(0, -(int) (12 * density), item.getWidth(), item.getHeight() + (int) (8 * density));
            grid.requestChildRectangleOnScreen(item, want, false);
        }
        Perf.end();
    }

    // ---- top shelf -------------------------------------------------------------------------------

    private void showHero(int position, float dockTopInRoot) {
        String key = adapter.keyAt(position);
        float y = dockTopInRoot - DockDecoration.PAD_V_DP * density - 26 * density - heroName.getHeight();

        if (!heroShown) {
            // Coming (back) to the dock: show this app straight away.
            heroShown = true;
            shelfKey = key;
            final int span = shelfTiming.start(Perf.enabled() ? "kind=show pkg=" + key : null);
            heroName.animate().cancel();
            heroName.setText(adapter.labelAt(position));
            heroName.setY(y);
            heroName.setTranslationX(0f);
            heroName.animate().alpha(1f).setDuration(SHELF_SHOW_NAME_MS).setInterpolator(DECELERATE)
                    .withEndAction(() -> shelfTiming.part(SHELF_TEXT, span)).start();
            if (ambientFade != null) ambientFade.cancel();
            setAmbient(adapter.ambientColorAt(position));
            ambient.animate().alpha(1f).setDuration(SHELF_SHOW_GLOW_MS).setInterpolator(DECELERATE)
                    .withEndAction(() -> shelfTiming.part(SHELF_GLOW, span)).start();
        } else if (key != null && !key.equals(shelfKey)) {
            swapShelf(position, y, shelfTiming.start(Perf.enabled() ? "kind=swap pkg=" + key : null));
        } else {
            heroName.setY(y);
        }
    }

    /** Changes the top shelf (name and ambient glow) to the dock app that just got focus. */
    private void swapShelf(int position, float y, int span) {
        final String label = adapter.labelAt(position);
        shelfKey = adapter.keyAt(position);
        heroName.animate().cancel();
        // Out from wherever the name is (a swap just before may still be fading it), then the new one in.
        heroName.animate().alpha(0f).translationX(-8 * density)
                .setDuration((long) (NAME_OUT_MS * heroName.getAlpha())).setInterpolator(LINEAR)
                .withEndAction(() -> {
                    heroName.setText(label);
                    heroName.setY(y);
                    heroName.setTranslationX(8 * density);
                    heroName.animate().alpha(1f).translationX(0f).setDuration(NAME_IN_MS)
                            .setInterpolator(DECELERATE).withEndAction(() -> shelfTiming.part(SHELF_TEXT, span))
                            .start();
                }).start();
        int color = adapter.ambientColorAt(position);
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

    private void setAmbient(int color) {
        ambientColor = color;
        int c = color & 0x00FFFFFF;
        glowStops[0] = 0x5C000000 | c;
        glowStops[1] = 0x26000000 | c;
        glowStops[2] = c;
        ambientGlow.setColors(glowStops);
    }

    private void hideHero() {
        shelfTiming.cancel();
        if (!heroShown) return;
        heroShown = false;
        heroName.animate().cancel();   // drops a pending name swap's end action, which would fade it back in
        heroName.animate().alpha(0f).setDuration(SHELF_HIDE_NAME_MS).setInterpolator(DECELERATE).start();
        ambient.animate().alpha(0f).setDuration(SHELF_HIDE_GLOW_MS).setInterpolator(DECELERATE).start();
    }

    // ---- navigation ------------------------------------------------------------------------------

    /**
     * All D-pad moves between tiles. Left and Right stay in the row and stop at its ends, as on tvOS:
     * Android's focus finder would otherwise jump from the right-most tile up to the Settings pill.
     */
    @Override
    public boolean onTileDpadKey(int position, int keyCode) {
        int count = adapter.getItemCount();
        int target;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (position % COLUMNS == 0) return true;      // first in its row: stay put
                target = position - 1;
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (position % COLUMNS == COLUMNS - 1 || position >= count - 1) return true;   // row end
                target = position + 1;
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (position < COLUMNS) return false;          // dock row: let focus move to the top bar
                target = position - COLUMNS;
                break;
            default: {
                int lastRowStart = ((count - 1) / COLUMNS) * COLUMNS;
                if (position >= lastRowStart) return true;     // already on the last row: stay put
                target = Math.min(position + COLUMNS, count - 1);
            }
        }
        focusTile(target);
        return true;
    }

    private void focusTile(int position) {
        if (adapter.getItemCount() == 0) return;
        final int pos = Math.max(0, Math.min(position, adapter.getItemCount() - 1));
        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(pos);
        if (vh != null) {
            View card = vh.itemView.findViewById(R.id.card);
            // Already focused (e.g. back from an app): no focus change fires, so restore the top shelf by hand.
            if (card.isFocused()) onTileFocused(pos, card);
            else card.requestFocus();
            return;
        }
        // Not laid out yet: scroll there, then focus once the scroll settles.
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) return;
                rv.removeOnScrollListener(this);
                RecyclerView.ViewHolder h = rv.findViewHolderForAdapterPosition(pos);
                View target = h != null ? h.itemView.findViewById(R.id.card) : rv.getChildAt(0);
                if (target != null) target.requestFocus();
            }
        });
        grid.smoothScrollToPosition(pos);
    }

    // ---- top bar ---------------------------------------------------------------------------------

    private void updateGreeting() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        int res;
        if (hour < 5) res = R.string.good_night;
        else if (hour < 12) res = R.string.good_morning;
        else if (hour < 17) res = R.string.good_afternoon;
        else if (hour < 22) res = R.string.good_evening;
        else res = R.string.good_night;
        greeting.setText(res);
    }

    /** Shows the Wi-Fi network name on the button (when open) if we can read it, otherwise the connection type. */
    private void updateWifiLabel() {
        Perf.begin("tvhome:updateWifiLabel");
        String text = getString(R.string.wifi);
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            Network net = cm != null ? cm.getActiveNetwork() : null;
            NetworkCapabilities caps = net != null ? cm.getNetworkCapabilities(net) : null;
            if (caps == null) {
                text = getString(R.string.offline);
            } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                text = getString(R.string.ethernet);
            } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                WifiInfo info = wm != null ? wm.getConnectionInfo() : null;
                String ssid = info != null ? info.getSSID() : null;
                if (ssid != null) {
                    ssid = ssid.replace("\"", "").trim();
                    if (!ssid.isEmpty() && !ssid.contains("unknown") && !ssid.equals("0x")) text = ssid;
                }
            }
        } catch (Exception ignored) {
        }
        btnWifi.setLabel(text);
        String wifi = getString(R.string.wifi);
        btnWifi.setContentDescription(text.equals(wifi) ? wifi : getString(R.string.wifi_status, text));
        if (btnWifi.showsCaption() && btnWifi.isFocused()) showCaption(btnWifi);
        Perf.end();
    }

    /**
     * Google TV look: the focused button's name, centred under it just below the bar. Like the stock
     * launcher, it moves with focus at once instead of animating.
     */
    private void showCaption(TopBarButton b) {
        topCaption.setText(b.getLabel());
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
        topCaption.setAlpha(1f);
    }

    /** Google TV's quick settings panel, like the remote's Settings button; the full Settings app elsewhere. */
    private void openSettings() {
        if (!QuickSettings.open(this)) open(new Intent(Settings.ACTION_SETTINGS));
    }

    private void open(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    /** Dropdown menu under the Inputs button: HDMI ports (with connection state), AV and Live TV. */
    private void showInputs() {
        TopBarButton anchor = topBar.findViewById(R.id.btnInputs);
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);

        // Compact: as wide as the open Inputs pill (a touch wider so "HDMI 1" fits), hanging right below it.
        // The open width, not the current one: a click can land while the button is still opening.
        int width = Math.max(anchor.getExpandedWidth(), (int) (150 * density));
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
                if (hdmi && !label.toUpperCase().contains("HDMI")) label = "HDMI " + label;
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
        popup.showAsDropDown(anchor, 0, yOff, Gravity.START);
        // Stay under the button while it finishes opening (the right-aligned row moves as it widens).
        ViewTreeObserver.OnGlobalLayoutListener follow = () -> {
            if (popup.isShowing()) popup.update(anchor, 0, yOff, -1, -1);
        };
        anchor.getViewTreeObserver().addOnGlobalLayoutListener(follow);
        // The Google TV caption would sit under the dropdown.
        topCaption.setAlpha(0f);
        popup.setOnDismissListener(() -> {
            anchor.getViewTreeObserver().removeOnGlobalLayoutListener(follow);
            if (anchor.showsCaption() && anchor.isFocused()) showCaption(anchor);
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
