package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityEvent;

/**
 * Lets this launcher be Home while Google TV's stock launcher stays enabled, so its quick settings panel, the
 * remote's Settings button and the other remote buttons it handles keep working.
 *
 * Google TV's HomeActivity always wins HOME while it is enabled, so Home, boot and wake still bring it up; whenever
 * it comes to the front this service brings this launcher back over it. Nothing else is touched: no key is
 * consumed, and when Home already resolves to this app (stock launcher disabled) the service does nothing.
 * build-and-install.sh turns it on for Google TV; tools/restore.sh turns it off.
 */
public class StockLauncherBridge extends AccessibilityService {

    /**
     * Wait this long before covering the stock home: on wake Google TV may go from its home straight to the last
     * TV input, and that must win. Any other activity coming up in the meantime cancels the redirect.
     */
    private static final long REDIRECT_DELAY_MS = 200;

    private static volatile StockLauncherBridge running;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable redirect = this::showLauncher;

    /** Opens the system notifications panel, which on Google TV is the dashboard. False if the service is off. */
    static boolean openNotificationsPanel() {
        StockLauncherBridge s = running;
        return s != null && s.performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS);
    }

    @Override
    protected void onServiceConnected() {
        running = this;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = event.getPackageName();
        CharSequence cls = event.getClassName();
        if (pkg == null || cls == null) return;
        if (getPackageName().contentEquals(pkg)) {
            handler.removeCallbacks(redirect);
            return;
        }
        ComponentName home = QuickSettings.stockHome(this);
        if (home == null) return;
        if (home.getPackageName().contentEquals(pkg) && home.getClassName().contentEquals(cls)) {
            handler.removeCallbacks(redirect);
            handler.postDelayed(redirect, REDIRECT_DELAY_MS);
        } else if (!home.getPackageName().contentEquals(pkg) || TextUtils.indexOf(cls, "Activity") >= 0) {
            // Another app or another stock-launcher activity took over (TV input, dashboard): leave it alone.
            // Same-package events whose class is a view are just the home screen updating.
            handler.removeCallbacks(redirect);
        }
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
        handler.removeCallbacks(redirect);
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        running = null;
        handler.removeCallbacks(redirect);
        super.onDestroy();
    }
}
