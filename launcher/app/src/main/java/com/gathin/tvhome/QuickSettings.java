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
 * The TV's quick settings panel. On Google TV that is the dashboard: the right-hand panel with the profile, Inputs,
 * Picture and Sound. It lives inside the stock launcher (com.google.android.apps.tv.launcherx), and only SystemUI may
 * open it: the remote's Settings button (KEYCODE_NOTIFICATION on TCL) and a long press on Home both go through
 * SystemUI, which starts the launcher's panel with {@link #ACTION_OPEN_PANEL}. An app can ask SystemUI for the same
 * thing with StatusBarManager.expandNotificationsPanel(), which needs only the normal EXPAND_STATUS_BAR permission.
 */
final class QuickSettings {

    /** NotificationManager.ACTION_OPEN_NOTIFICATION_HANDLER_PANEL, hidden from the SDK. */
    static final String ACTION_OPEN_PANEL = "android.app.action.OPEN_NOTIFICATION_HANDLER_PANEL";

    private QuickSettings() {
    }

    /**
     * True when another launcher (Google TV's) provides the panel. Plain Android TV's SystemUI also answers
     * {@link #ACTION_OPEN_PANEL}, but only with a bare notification list; the full Settings app is better there.
     */
    static boolean available(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        List<ResolveInfo> panels = pm.queryIntentActivities(new Intent(ACTION_OPEN_PANEL), PackageManager.MATCH_DEFAULT_ONLY);
        if (panels.isEmpty()) return false;
        List<ResolveInfo> homes = pm.queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY);
        for (ResolveInfo panel : panels) {
            if (panel.activityInfo == null || panel.activityInfo.packageName.equals(ctx.getPackageName())) continue;
            for (ResolveInfo home : homes) {
                if (home.activityInfo != null && home.activityInfo.packageName.equals(panel.activityInfo.packageName)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Opens the panel the way the remote's Settings button does. False when this TV has no such panel, or the call
     * failed, so the caller can open the full Settings app instead.
     */
    @SuppressLint("WrongConstant")   // "statusbar" is Context.STATUS_BAR_SERVICE, hidden from the SDK
    static boolean open(Context ctx) {
        if (!available(ctx)) return false;
        try {
            // Hidden but not blocked for apps (@UnsupportedAppUsage); needs EXPAND_STATUS_BAR.
            Object statusBar = ctx.getSystemService("statusbar");
            if (statusBar == null) return false;
            statusBar.getClass().getMethod("expandNotificationsPanel").invoke(statusBar);
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    /**
     * The activity Android sends Home to when it isn't this app, or null when Home already lands here. On Google TV
     * that is the stock HomeActivity whenever its launcher is enabled: its HOME filter has priority 2 and a sideloaded
     * app's is always 0, and a higher priority wins before the default home (the HOME role) is even looked at.
     */
    static ComponentName stockHome(Context ctx) {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo ri = ctx.getPackageManager().resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
        if (ri == null || ri.activityInfo == null) return null;
        ActivityInfo ai = ri.activityInfo;
        // "android" is the chooser shown when several homes tie; leave that to the system.
        if (ai.packageName.equals(ctx.getPackageName()) || ai.packageName.equals("android")) return null;
        return new ComponentName(ai.packageName, ai.targetActivity != null ? ai.targetActivity : ai.name);
    }
}
