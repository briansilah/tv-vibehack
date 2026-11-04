package com.gathin.tvhome;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;

/**
 * Platform-only device regressions: assembleDebugAndroidTest, install both debug APKs, then
 * am instrument -w com.gathin.tvhome.test/com.gathin.tvhome.GlassSliderAccessibilityTest.
 * The attached test slider has no saved-setting listener; every adjustment leaves Home's settings alone.
 */
public class GlassSliderAccessibilityTest extends Instrumentation {
    private GlassSlider slider;
    private int changed, done, events;
    private AccessibilityEvent lastEvent;
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
            FrameLayout host = new FrameLayout(activity) {
                @Override
                public boolean requestSendAccessibilityEvent(View child, AccessibilityEvent event) {
                    if (child == slider && event.getEventType() == AccessibilityEvent.TYPE_VIEW_SELECTED) {
                        events++;
                        lastEvent = AccessibilityEvent.obtain(event);
                    }
                    return super.requestSendAccessibilityEvent(child, event);
                }
            };
            slider = new GlassSlider(activity, null);
            slider.setContentDescription(activity.getString(R.string.liquid_glass));
            slider.setListener(new GlassSlider.Listener() {
                @Override public void onGlassChanged(int value) { changed++; }
                @Override public void onGlassDone() { done++; }
            });
            host.addView(slider, new FrameLayout.LayoutParams(600, 96));
            activity.addContentView(host, new FrameLayout.LayoutParams(600, 96));
            slider.requestFocus();

            check("role, name and integer range", () -> {
                AccessibilityNodeInfo info = slider.createAccessibilityNodeInfo();
                require("android.widget.SeekBar".contentEquals(info.getClassName()), "SeekBar role");
                require("Liquid Glass".contentEquals(info.getContentDescription()), "existing label");
                AccessibilityNodeInfo.RangeInfo range = info.getRangeInfo();
                require(range != null, "range present");
                require(range.getType() == AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT, "integer range");
                require(range.getMin() == 0 && range.getMax() == 100 && range.getCurrent() == 50, "0..100, current 50");
                require(info.isScrollable() && info.isClickable(), "adjustable and activatable");
            });
            check("accessible steps match D-pad and notify once", () -> {
                require(action(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD), "increment accepted");
                require(slider.getValue() == 55 && changed == 1, "increment 5 and callback");
                require(action(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD), "decrement accepted");
                require(slider.getValue() == 50 && changed == 2, "decrement 5 and callback");
                slider.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT, new KeyEvent(0, KeyEvent.KEYCODE_DPAD_RIGHT));
                require(slider.getValue() == 55 && changed == 3, "D-pad parity");
            });
            check("endpoints expose only possible actions", () -> {
                slider.setValue(100);
                require(!hasAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD), "no forward at maximum");
                require(hasAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD), "backward at maximum");
                require(!action(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD), "no-op rejected");
                slider.setValue(0);
                require(!hasAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD), "no backward at minimum");
                require(hasAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD), "forward at minimum");
                require(!action(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD), "no-op rejected");
                require(changed == 0, "no callbacks for limits");
            });
            check("set progress rounds and clamps", () -> {
                require(progress(62.6f) && slider.getValue() == 63, "round to integer");
                require(progress(150) && slider.getValue() == 100, "clamp maximum");
                require(progress(-20) && slider.getValue() == 0, "clamp minimum");
                require(!progress(0) && changed == 3, "no duplicate callbacks");
            });
            check("invalid progress leaves value alone", () -> {
                int id = AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId();
                require(!slider.performAccessibilityAction(id, null), "null arguments rejected");
                require(!slider.performAccessibilityAction(id, new Bundle()), "missing value rejected");
                require(!progress(Float.NaN) && !progress(Float.POSITIVE_INFINITY)
                        && !progress(Float.NEGATIVE_INFINITY), "nonfinite values rejected");
                require(slider.getValue() == 50 && changed == 0, "invalid input unchanged");
            });
            check("disabled control cannot be adjusted or activated", () -> {
                slider.setEnabled(false);
                require(!action(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD), "disabled increment");
                require(!progress(75), "disabled set progress");
                require(!action(AccessibilityNodeInfo.ACTION_CLICK), "disabled activation");
                require(!hasAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                        && !hasAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId()),
                        "disabled actions absent");
                require(slider.getValue() == 50 && changed == 0 && done == 0, "no side effects");
            });
            check("value changes send native range events", () -> {
                slider.setValue(65);
                require(events > 0 && lastEvent != null, "event delivered");
                require(lastEvent.getEventType() == AccessibilityEvent.TYPE_VIEW_SELECTED, "value event");
                require("android.widget.SeekBar".contentEquals(lastEvent.getClassName()), "event role");
                require(lastEvent.getItemCount() == 101 && lastEvent.getCurrentItemIndex() == 65, "event value");
                int count = events;
                slider.setValue(65);
                require(events == count, "unchanged value is silent");
            });
            check("accessible click closes like OK without changing value", () -> {
                require(action(AccessibilityNodeInfo.ACTION_CLICK), "activation accepted");
                require(done == 1 && changed == 0 && slider.getValue() == 50, "close callback only");
                slider.onKeyUp(KeyEvent.KEYCODE_DPAD_CENTER, new KeyEvent(1, KeyEvent.KEYCODE_DPAD_CENTER));
                require(done == 2, "OK uses the same close callback");
            });
            host.removeView(slider);
            ((android.view.ViewGroup) host.getParent()).removeView(host);
        });
        Bundle result = new Bundle();
        result.putString("stream", "\n" + output + "Passed: " + passed + ", failed: " + failed + "\n");
        result.putInt("failures", failed);
        finish(failed == 0 ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    }

    private void check(String name, Runnable test) {
        slider.setEnabled(true);
        slider.setValue(50);
        changed = done = events = 0;
        lastEvent = null;
        try {
            test.run();
            passed++;
            output.append("PASS: ").append(name).append('\n');
        } catch (AssertionError error) {
            failed++;
            output.append("FAIL: ").append(name).append(": ").append(error.getMessage()).append('\n');
        }
    }

    private boolean action(int id) {
        return slider.performAccessibilityAction(id, null);
    }

    private boolean progress(float value) {
        Bundle args = new Bundle();
        args.putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, value);
        return slider.performAccessibilityAction(
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId(), args);
    }

    private boolean hasAction(int id) {
        for (AccessibilityNodeInfo.AccessibilityAction action : slider.createAccessibilityNodeInfo().getActionList()) {
            if (action.getId() == id) return true;
        }
        return false;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
