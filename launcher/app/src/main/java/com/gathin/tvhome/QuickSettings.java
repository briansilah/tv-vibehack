package com.gathin.tvhome;

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.util.List;

/**
 * The TV's own quick settings panel. On Google TV that is the dashboard (the right-hand panel with the profile,
 * Inputs, Picture and Sound): it lives inside the stock launcher, and SystemUI opens it with
 * {@link #ACTION_OPEN_PANEL}, the same path as long-pressing Home. The panel is only there while the stock launcher
 * is enabled, so the install script keeps it enabled on Google TV and {@link StockLauncherBridge} keeps this app
 * on Home.
 */
final class QuickSettings {

    /** NotificationManager.ACTION_OPEN_NOTIFICATION_HANDLER_PANEL, hidden from the SDK. */
    static final String ACTION_OPEN_PANEL = "android.app.action.OPEN_NOTIFICATION_HANDLER_PANEL";

    private QuickSettings() {
    }

    /** True when a launcher other than this one (Google TV's stock launcher) provides the panel. */
    static boolean available(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        List<ResolveInfo> handlers = pm.queryIntentActivities(new Intent(ACTION_OPEN_PANEL), PackageManager.MATCH_DEFAULT_ONLY);
        if (handlers.isEmpty()) return false;
        // Only a launcher's panel counts: plain Android TV's SystemUI has a bare notifications list for this action,
        // and there the full Settings app is the better target.
        List<ResolveInfo> homes = pm.queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY);
        for (ResolveInfo h : handlers) {
            if (h.activityInfo == null || h.activityInfo.packageName.equals(ctx.getPackageName())) continue;
            for (ResolveInfo home : homes) {
                if (home.activityInfo != null && home.activityInfo.packageName.equals(h.activityInfo.packageName)) return true;
            }
        }
        return false;
    }

    /**
     * Opens the panel like the stock Settings icon and the remote's Settings button do. Returns false when this TV
     * has no such panel (or it is disabled), so the caller can fall back to the full Settings app.
     */
    @SuppressLint("WrongConstant")   // "statusbar" is Context.STATUS_BAR_SERVICE, hidden from the SDK
    static boolean open(Context ctx) {
        if (!available(ctx)) return false;
        // Public API, works while the accessibility bridge is on.
        if (StockLauncherBridge.openNotificationsPanel()) return true;
        // StatusBarManager.expandNotificationsPanel() is hidden but allowed for this targetSdk; needs EXPAND_STATUS_BAR.
        try {
            Object statusBar = ctx.getSystemService("statusbar");
            if (statusBar == null) return false;
            statusBar.getClass().getMethod("expandNotificationsPanel").invoke(statusBar);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * The activity the system sends Home to when it isn't this app, e.g. Google TV's HomeActivity, which always wins
     * HOME while it is enabled because its intent filter has a higher priority. Null when Home already lands here.
     */
    static ComponentName stockHome(Context ctx) {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo ri = ctx.getPackageManager().resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
        if (ri == null || ri.activityInfo == null) return null;
        ActivityInfo ai = ri.activityInfo;
        // "android" is the chooser shown when several homes tie; nothing to redirect then.
        if (ai.packageName.equals(ctx.getPackageName()) || ai.packageName.equals("android")) return null;
        String cls = ai.targetActivity != null ? ai.targetActivity : ai.name;
        return new ComponentName(ai.packageName, cls);
    }
}
