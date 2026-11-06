package com.gathin.tvhome;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;

/**
 * Platform-only device regressions: assembleDebugAndroidTest, install both debug APKs, then
 * am instrument -w com.gathin.tvhome.test/com.gathin.tvhome.TopBarButtonAccessibilityTest.
 * Test buttons have their own click listeners and never open or change the TV's settings.
 */
public class TopBarButtonAccessibilityTest extends Instrumentation {
    private final StringBuilder output = new StringBuilder();
    private int passed, failed;

    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override
    public void onStart() {
        getUiAutomation();
        Activity activity = startActivitySync(new Intent(getTargetContext(), MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        runOnMainSync(() -> {
            FrameLayout host = new FrameLayout(activity);
            activity.addContentView(host, new FrameLayout.LayoutParams(1200, 200));
            for (String style : new String[]{"tvos", "pills", "apple", "google"}) {
                ContextThemeWrapper themed = new ContextThemeWrapper(activity, TopBarButton.themeFor(style));
                View bar = LayoutInflater.from(themed).inflate(R.layout.top_bar, host, false);
                host.addView(bar);
                check(style + ": three named buttons, nodes, events and clicks", () -> {
                    for (int id : new int[]{R.id.btnInputs, R.id.btnWifi, R.id.btnSettings}) {
                        TopBarButton button = bar.findViewById(id);
                        AccessibilityNodeInfo info = button.createAccessibilityNodeInfo();
                        require("android.widget.Button".contentEquals(info.getClassName()), "native button role");
                        require(button.getLabel().equals(info.getContentDescription()), "existing localized name");
                        require(info.isClickable() && info.isFocusable() && info.isEnabled(), "interactive state");
                        require(!info.isCheckable() && !info.isChecked(), "not a saved choice");
                        require(info.getChildCount() == 0, "no duplicate icon or label stops");
                        require(info.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK),
                                "native click action");
                        int[] clicks = {0};
                        button.setOnClickListener(v -> clicks[0]++);
                        require(button.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null),
                                "accessible click accepted");
                        require(clicks[0] == 1, "one listener invocation");
                        AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_CLICKED);
                        button.onInitializeAccessibilityEvent(event);
                        require("android.widget.Button".contentEquals(event.getClassName()), "event role matches node");
                        require(button.getContentDescription().equals(event.getContentDescription()), "event name");
                        event.recycle();
                    }
                });
                check(style + ": updated descriptions and disabled state", () -> {
                    TopBarButton button = bar.findViewById(R.id.btnWifi);
                    String name = activity.getString(R.string.network_status, activity.getString(R.string.ethernet));
                    button.setLabel(activity.getString(R.string.ethernet));
                    button.setContentDescription(name);
                    button.setEnabled(false);
                    AccessibilityNodeInfo info = button.createAccessibilityNodeInfo();
                    require("android.widget.Button".contentEquals(info.getClassName()), "disabled button role");
                    require(name.contentEquals(info.getContentDescription()), "updated network name preserved");
                    require(!info.isEnabled(), "disabled state preserved");
                });
                host.removeView(bar);
            }
            ((ViewGroup) host.getParent()).removeView(host);
        });
        Bundle result = new Bundle();
        result.putString("stream", "\n" + output + "Passed: " + passed + ", failed: " + failed + "\n");
        result.putInt("failures", failed);
        finish(failed == 0 ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    }

    private void check(String name, Runnable test) {
        try {
            test.run();
            passed++;
            output.append("PASS: ").append(name).append('\n');
        } catch (AssertionError error) {
            failed++;
            output.append("FAIL: ").append(name).append(": ").append(error.getMessage()).append('\n');
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
