package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.HashMap;
import java.util.Map;

/**
 * Makes Home land on this launcher while Google TV's stock launcher stays enabled for its quick settings panel.
 *
 * Google TV's HomeActivity wins HOME whenever its package is enabled (see {@link QuickSettings#stockHome}), and
 * disabling the package removes the panel. On a TCL Google TV it also makes setupwraith's RecoveryActivity the home,
 * which finishes straight away and loops until system_server crashes. So the stock launcher stays, and this service:
 * - takes the remote's Home press and opens this launcher instead, so the stock home never shows (also from the
 *   screensaver, which it closes);
 * - opens the quick settings panel on a long press of Home, as Google TV does;
 * - covers the stock home when it comes up some other way: Google TV sends the TV to its home on waking after more
 *   than 30 s in standby (launcherx's DispatchActivity, with home-app rights to start it), at boot, or when an app
 *   goes home itself. That one shows briefly first.
 * It only acts while Home resolves to another app; with the stock launcher disabled it passes everything through.
 * Keys sent with `adb shell input` skip accessibility services, so test Home with the real remote.
 * It reads no window text: only the package and class of the window that comes to the front, and once, when it
 * starts, the package of the active window. It runs in its own process (":home") because every key on the TV waits
 * for {@link #onKeyEvent} to answer, and the launcher's UI thread may be busy animating. TCL only lets the system
 * start that process with the AUTO_START app op allowed, which build-and-install.sh sets.
 */
public class HomeButtonService extends AccessibilityService {

    private static final String TAG = "TvHomeButton";
    /** Stop redirecting if the stock home keeps coming back, e.g. if this launcher crashes on start. */
    private static final int MAX_REDIRECTS = 3;
    private static final long REDIRECT_WINDOW_MS = 10_000;
    private static final long DEBOUNCE_MS = 1_000;
    /** Let the windows settle after the service starts before looking at what is in front. */
    private static final long CONNECT_CHECK_DELAY_MS = 500;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<ComponentName, Boolean> activityCache = new HashMap<>();
    private final long[] redirectTimes = new long[MAX_REDIRECTS];
    private int redirectNext;
    private long lastRedirect;
    /** We took the down of the current Home press, so its up (and repeats) are ours too. */
    private boolean homeHeld;
    private boolean longPressDone;
    private boolean dreaming;

    private final BroadcastReceiver dreamReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            dreaming = Intent.ACTION_DREAMING_STARTED.equals(intent.getAction());
        }
    };

    private final Runnable longPress = () -> {
        longPressDone = true;
        closeScreensaver();
        // Same as the stock long press on Home (LONG_PRESS_HOME_NOTIFICATION_PANEL): SystemUI opens the panel.
        if (!performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)) Log.w(TAG, "could not open the quick settings panel");
    };
    /**
     * At boot, or when this app was just updated, the stock home may already be in front when the service starts,
     * and no event comes for it then. Look once at which app the active window belongs to.
     */
    private final Runnable connectCheck = () -> {
        ComponentName home = QuickSettings.stockHome(this);
        if (home == null) return;
        AccessibilityNodeInfo root;
        try {
            root = getRootInActiveWindow();
        } catch (RuntimeException e) {
            return;
        }
        if (root == null) return;
        boolean stockInFront = home.getPackageName().contentEquals(root.getPackageName());
        if (Build.VERSION.SDK_INT < 33) root.recycle();
        if (stockInFront) coverStockHome();
    };

    @Override
    protected void onServiceConnected() {
        IntentFilter dreams = new IntentFilter(Intent.ACTION_DREAMING_STARTED);
        dreams.addAction(Intent.ACTION_DREAMING_STOPPED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(dreamReceiver, dreams, RECEIVER_NOT_EXPORTED);
        else registerReceiver(dreamReceiver, dreams);
        handler.postDelayed(connectCheck, CONNECT_CHECK_DELAY_MS);
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (event.getKeyCode() != KeyEvent.KEYCODE_HOME) return false;
        switch (event.getAction()) {
            case KeyEvent.ACTION_DOWN:
                if (event.getRepeatCount() == 0) {
                    homeHeld = QuickSettings.stockHome(this) != null;
                    longPressDone = false;
                    handler.removeCallbacks(longPress);
                    if (homeHeld) handler.postDelayed(longPress, ViewConfiguration.getLongPressTimeout());
                }
                return homeHeld;
            case KeyEvent.ACTION_UP:
                if (!homeHeld) return false;
                homeHeld = false;
                handler.removeCallbacks(longPress);
                if (!longPressDone) {
                    // Open this launcher first, so closing the screensaver reveals it rather than the app below.
                    showLauncher(false);
                    closeScreensaver();
                }
                return true;
            default:
                return homeHeld;
        }
    }

    /**
     * The system's Home wakes the screensaver, then shows the stock home. A Home we take never reaches it, and
     * starting an activity leaves the screensaver on top; Back closes it, as it does from the remote.
     */
    private void closeScreensaver() {
        if (dreaming) performGlobalAction(GLOBAL_ACTION_BACK);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = event.getPackageName();
        CharSequence cls = event.getClassName();
        if (pkg == null || cls == null) return;
        ComponentName window = new ComponentName(pkg.toString(), cls.toString());
        // Dialogs, toasts and the volume bar also send this event; only activities say what is in front.
        if (isActivity(window) && window.equals(QuickSettings.stockHome(this))) coverStockHome();
    }

    /**
     * The stock home came up without a Home press. On wake Google TV's DispatchActivity has already chosen between
     * its home and the last input by then, so there is nothing to wait for: every millisecond here is visible.
     */
    private void coverStockHome() {
        long now = SystemClock.uptimeMillis();
        // The stock home often reports itself twice as it settles; that is still one redirect.
        if (lastRedirect != 0 && now - lastRedirect < DEBOUNCE_MS) return;
        long oldest = redirectTimes[redirectNext];
        if (oldest != 0 && now - oldest < REDIRECT_WINDOW_MS) {
            Log.w(TAG, "stock home keeps coming back; leaving it");
            return;
        }
        lastRedirect = now;
        redirectTimes[redirectNext] = now;
        redirectNext = (redirectNext + 1) % MAX_REDIRECTS;
        Log.i(TAG, "stock home came up; showing this launcher");
        showLauncher(true);
    }

    private boolean isActivity(ComponentName c) {
        Boolean known = activityCache.get(c);
        if (known != null) return known;
        boolean activity;
        try {
            getPackageManager().getActivityInfo(c, PackageManager.MATCH_DISABLED_COMPONENTS);
            activity = true;
        } catch (PackageManager.NameNotFoundException e) {
            activity = false;
        }
        activityCache.put(c, activity);
        return activity;
    }

    /**
     * Brings this launcher to the front. On a Home press its window falls back into place over the app as it fades in,
     * as tvOS's Home does (see {@link Transitions#homeOptions}); covering the stock home is instant.
     */
    private void showLauncher(boolean instant) {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .setClass(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                .putExtra(Transitions.EXTRA_SHOW, instant ? Transitions.SHOW_INSTANT : Transitions.SHOW_FADE);
        if (instant) home.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
        try {
            startActivity(home, instant ? null : Transitions.homeOptions(this));
        } catch (RuntimeException e) {
            Log.w(TAG, "could not open the launcher", e);
        }
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        try {
            unregisterReceiver(dreamReceiver);
        } catch (IllegalArgumentException ignored) {
            // never connected
        }
        super.onDestroy();
    }
}
