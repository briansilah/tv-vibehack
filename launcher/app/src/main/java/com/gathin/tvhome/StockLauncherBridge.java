package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * Lets this launcher be Home while Google TV's stock launcher stays enabled, so its quick settings panel, the
 * remote's Settings button and the other remote buttons it handles keep working.
 *
 * Google TV's HomeActivity always wins HOME while it is enabled, so Home, boot and wake still bring it up; whenever
 * it comes to the front this service brings this launcher back over it. It also checks once when it starts, since
 * the stock home may already be in front then (after install, or at boot before the service is bound). Nothing else
 * is touched: no key is consumed, only the package of the window in front is read, and when Home already resolves
 * to this app (stock launcher disabled) the service does nothing.
 * build-and-install.sh turns it on for Google TV; tools/restore.sh turns it off.
 */
public class StockLauncherBridge extends AccessibilityService {

    /**
     * Wait this long before covering the stock home: on wake Google TV may go from its home straight to the last
     * TV input, and that must win. Any other activity coming up in the meantime cancels the redirect.
     */
    private static final long REDIRECT_DELAY_MS = 200;
    /** On connect, let the windows settle before looking at what is in front. */
    private static final long CONNECT_CHECK_DELAY_MS = 500;

    private static volatile StockLauncherBridge running;
    private final Handler handler = new Handler(Looper.getMainLooper());
    /** After a stock-home window event: redirect unless the active window is known to be something else. */
    private final Runnable redirect = () -> {
        if (stockHomeInFront() != Boolean.FALSE) showLauncher();
    };
    /** On connect there was no event, so only redirect when the active window is known to be the stock home. */
    private final Runnable connectCheck = () -> {
        if (stockHomeInFront() == Boolean.TRUE) showLauncher();
    };

    /** Opens the system notifications panel, which on Google TV is the dashboard. False if the service is off. */
    static boolean openNotificationsPanel() {
        StockLauncherBridge s = running;
        return s != null && s.performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS);
    }

    @Override
    protected void onServiceConnected() {
        running = this;
        if (QuickSettings.stockHome(this) != null) handler.postDelayed(connectCheck, CONNECT_CHECK_DELAY_MS);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = event.getPackageName();
        CharSequence cls = event.getClassName();
        if (pkg == null || cls == null) return;
        if (getPackageName().contentEquals(pkg)) {
            cancelPending();
            return;
        }
        ComponentName home = QuickSettings.stockHome(this);
        if (home == null) return;
        if (home.getPackageName().contentEquals(pkg) && home.getClassName().contentEquals(cls)) {
            cancelPending();
            handler.postDelayed(redirect, REDIRECT_DELAY_MS);
        } else if (!home.getPackageName().contentEquals(pkg) || TextUtils.indexOf(cls, "Activity") >= 0) {
            // Another app or another stock-launcher activity took over (TV input, dashboard): leave it alone.
            // Same-package events whose class is a view are just the home screen updating.
            cancelPending();
        }
    }

    /** TRUE/FALSE when the active window's app is/isn't the stock launcher, null when that can't be read. */
    private Boolean stockHomeInFront() {
        ComponentName home = QuickSettings.stockHome(this);
        if (home == null) return Boolean.FALSE;
        AccessibilityNodeInfo root;
        try {
            root = getRootInActiveWindow();
        } catch (RuntimeException e) {
            return null;
        }
        if (root == null) return null;
        CharSequence pkg = root.getPackageName();
        boolean inFront = pkg != null && home.getPackageName().contentEquals(pkg);
        if (Build.VERSION.SDK_INT < 33) root.recycle();
        return inFront;
    }

    private void cancelPending() {
        handler.removeCallbacks(redirect);
        handler.removeCallbacks(connectCheck);
    }

    private void showLauncher() {
        try {
            startActivity(new Intent(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
        } catch (RuntimeException ignored) {
        }
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onUnbind(Intent intent) {
        running = null;
        cancelPending();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        running = null;
        cancelPending();
        super.onDestroy();
    }
}
