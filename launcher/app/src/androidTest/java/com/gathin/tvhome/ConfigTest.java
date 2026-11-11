package com.gathin.tvhome;

import android.content.Context;
import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.Objects;

/**
 * Android's real resources/parser: am instrument -w -e suite config
 * com.gathin.tvhome.test/com.gathin.tvhome.GlassSliderAccessibilityTest.
 */
public class ConfigTest {
    private final Instrumentation instrumentation;
    private Context context;
    private File config;
    private File wallpaper;
    private byte[] oldConfig;
    private byte[] oldWallpaper;

    ConfigTest(Instrumentation instrumentation) {
        this.instrumentation = instrumentation;
    }

    void run() {
        StringBuilder output = new StringBuilder();
        int passed = 0, failed = 0;
        for (java.lang.reflect.Method method : ConfigTest.class.getDeclaredMethods()) {
            if (!method.getName().startsWith("test")) continue;
            try {
                setUp();
                try {
                    method.invoke(this);
                } finally {
                    tearDown();
                }
                passed++;
                output.append("PASS: ").append(method.getName()).append('\n');
            } catch (ReflectiveOperationException | java.io.IOException | AssertionError error) {
                failed++;
                output.append("FAIL: ").append(method.getName()).append(": ")
                        .append(error.getCause() != null ? error.getCause() : error).append('\n');
            }
        }
        Bundle result = new Bundle();
        result.putString("stream", "\n" + output + "Passed: " + passed + ", failed: " + failed + "\n");
        result.putInt("failures", failed);
        instrumentation.finish(failed == 0 ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    }

    private void setUp() throws java.io.IOException {
        context = instrumentation.getTargetContext();
        File dir = Config.dir(context);
        assertNotNull(dir);
        config = new File(dir, Config.FILE_NAME);
        wallpaper = new File(dir, Config.WALLPAPER_NAME);
        oldConfig = config.isFile() ? Files.readAllBytes(config.toPath()) : null;
        oldWallpaper = wallpaper.isFile() ? Files.readAllBytes(wallpaper.toPath()) : null;
        Files.deleteIfExists(config.toPath());
        Files.deleteIfExists(wallpaper.toPath());
    }

    private void tearDown() throws java.io.IOException {
        if (oldConfig == null) Files.deleteIfExists(config.toPath());
        else Files.write(config.toPath(), oldConfig);
        if (oldWallpaper == null) Files.deleteIfExists(wallpaper.toPath());
        else Files.write(wallpaper.toPath(), oldWallpaper);
    }

    private Config load(String text) throws Exception {
        Files.write(config.toPath(), text.getBytes(StandardCharsets.UTF_8));
        return Config.load(context);
    }

    public void testAbsentThemeUsesResources() {
        Config cfg = Config.load(context);
        assertFalse(cfg.overlayApplied);
        assertEquals(context.getResources().getInteger(R.integer.columns), cfg.columns);
        assertEquals(Arrays.asList(context.getResources().getStringArray(R.array.dock_apps)), cfg.dockApps);
        assertNull(cfg.wallpaper);
        assertEquals(0xFF000000, cfg.backgroundColor);
    }

    public void testEverySupportedOverlayKey() throws Exception {
        Config cfg = load("{\"dock_apps\":[\"test.app\"],\"hidden_apps\":[\"hidden.app\"],"
                + "\"live_tv_package\":\" live.app \",\"live_tv_action\":\"live.action\","
                + "\"source_panel_service\":\"source.app/.Service\",\"source_panel_action\":\"source.action\","
                + "\"columns\":6,\"shamsi_date\":true,\"vpn_app\":\"vpn.app\","
                + "\"show_home_settings_shortcut\":true,\"background_color\":\"#123456\"}");
        assertTrue(cfg.overlayApplied);
        assertEquals(Collections.singletonList("test.app"), cfg.dockApps);
        assertEquals(Collections.singletonList("hidden.app"), cfg.hiddenApps);
        assertEquals("live.app", cfg.liveTvPackage);
        assertEquals("live.action", cfg.liveTvAction);
        assertEquals("source.app/.Service", cfg.sourcePanelService);
        assertEquals("source.action", cfg.sourcePanelAction);
        assertEquals(6, cfg.columns);
        assertTrue(cfg.shamsiDate);
        assertEquals("vpn.app", cfg.vpnApp);
        assertTrue(cfg.showHomeSettingsShortcut);
        assertEquals(0xFF123456, cfg.backgroundColor);
    }

    public void testPartialOverlayPreservesOtherResources() throws Exception {
        Config cfg = load("{\"columns\":6,\"future_option\":true,\"rows_below_dock\":3}");
        assertEquals(6, cfg.columns);
        assertEquals(context.getString(R.string.live_tv_package).trim(), cfg.liveTvPackage);
        assertEquals(context.getResources().getBoolean(R.bool.shamsi_date), cfg.shamsiDate);
    }

    public void testMalformedOverlayIsNotApplied() throws Exception {
        assertFalse(load("{\"columns\":6,").overlayApplied);
        assertFalse(load("[1,2]").overlayApplied);
    }

    public void testBomIsAccepted() throws Exception {
        assertEquals(6, load("\uFEFF{\"columns\":6}").columns);
    }

    public void testOversizedOverlayIsNotApplied() throws Exception {
        byte[] bytes = new byte[64 * 1024 + 1];
        Arrays.fill(bytes, (byte) ' ');
        Files.write(config.toPath(), bytes);
        assertFalse(Config.load(context).overlayApplied);
    }

    public void testInvalidTypesKeepDefaults() throws Exception {
        Config defaults = Config.load(context);
        Config cfg = load("{\"columns\":\"6\",\"dock_apps\":null,\"hidden_apps\":{},"
                + "\"shamsi_date\":\"true\",\"vpn_app\":false,\"background_color\":42,"
                + "\"show_home_settings_shortcut\":1}");
        assertEquals(defaults.columns, cfg.columns);
        assertEquals(defaults.dockApps, cfg.dockApps);
        assertEquals(defaults.hiddenApps, cfg.hiddenApps);
        assertEquals(defaults.shamsiDate, cfg.shamsiDate);
        assertEquals(defaults.vpnApp, cfg.vpnApp);
        assertEquals(defaults.showHomeSettingsShortcut, cfg.showHomeSettingsShortcut);
        assertEquals(defaults.backgroundColor, cfg.backgroundColor);
    }

    public void testColumnBoundsRejectFractionsAndOverflow() throws Exception {
        int fallback = Config.load(context).columns;
        for (String value : new String[]{"0", "-1", "9", "1.5", "4294967300", "1e100"}) {
            assertEquals(value, fallback, load("{\"columns\":" + value + "}").columns);
        }
        assertEquals(1, load("{\"columns\":1}").columns);
        assertEquals(8, load("{\"columns\":8}").columns);
    }

    public void testListsFilterInvalidEntriesAndAllowEmpty() throws Exception {
        assertEquals(Arrays.asList("one", "two"),
                load("{\"dock_apps\":[\" one \",null,3,\"\",\"two\"]}").dockApps);
        assertTrue(load("{\"hidden_apps\":[]}").hiddenApps.isEmpty());
    }

    public void testListsCannotBeModified() throws Exception {
        Config cfg = load("{\"dock_apps\":[\"one\"]}");
        try {
            cfg.dockApps.add("two");
            fail("mutable dock list");
        } catch (UnsupportedOperationException expected) {
            assertEquals(1, cfg.dockApps.size());
        }
    }

    public void testColoursAreOpaqueAndInvalidColoursKeepBlack() throws Exception {
        assertEquals(0xFF123456, load("{\"background_color\":\"#00123456\"}").backgroundColor);
        assertEquals(0xFF000000, load("{\"background_color\":\"not a colour\"}").backgroundColor);
    }

    public void testWallpaperIsOptionalAndCanBeDisabled() throws Exception {
        assertNull(load("{}").wallpaper);
        Files.write(wallpaper.toPath(), new byte[]{1, 2, 3});
        assertEquals(wallpaper, load("{}").wallpaper);
        assertNull(load("{\"wallpaper\":false}").wallpaper);
        assertEquals(wallpaper, load("{\"wallpaper\":true}").wallpaper);
        Files.write(wallpaper.toPath(), new byte[0]);
        assertNull(load("{}").wallpaper);
    }

    public void testShippedModeIgnoresOverlayAndWallpaper() throws Exception {
        Files.write(wallpaper.toPath(), new byte[]{1, 2, 3});
        load("{\"columns\":6,\"hidden_apps\":[],\"background_color\":\"#123456\"}");
        Config cfg = Config.load(context, true);
        assertFalse(cfg.overlayApplied);
        assertNull(cfg.wallpaper);
        assertEquals(context.getResources().getInteger(R.integer.columns), cfg.columns);
        assertEquals(0xFF000000, cfg.backgroundColor);
        assertEquals(Arrays.asList(context.getResources().getStringArray(R.array.hidden_apps)), cfg.hiddenApps);
    }

    public void testHomeSettingsUsesThemedHiddenApps() throws Exception {
        android.content.Intent probe = new android.content.Intent(android.content.Intent.ACTION_MAIN)
                .addCategory(android.content.Intent.CATEGORY_LEANBACK_LAUNCHER);
        org.json.JSONArray hidden = new org.json.JSONArray();
        for (android.content.pm.ResolveInfo info : context.getPackageManager().queryIntentActivities(probe, 0)) {
            if (info.activityInfo != null) hidden.put(info.activityInfo.packageName);
        }
        load("{\"columns\":6,\"show_home_settings_shortcut\":false,\"hidden_apps\":" + hidden + "}");
        AppAdapter.Ordered ordered = AppAdapter.loadInOrder(context);
        assertTrue(ordered.apps.isEmpty());
        assertEquals(0, ordered.dock);
    }

    private static void assertEquals(Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) fail("expected " + expected + ", got " + actual);
    }

    private static void assertEquals(String message, int expected, int actual) {
        if (expected != actual) fail(message + ": expected " + expected + ", got " + actual);
    }

    private static void assertTrue(boolean value) {
        if (!value) fail("expected true");
    }

    private static void assertFalse(boolean value) {
        if (value) fail("expected false");
    }

    private static void assertNull(Object value) {
        if (value != null) fail("expected null, got " + value);
    }

    private static void assertNotNull(Object value) {
        if (value == null) fail("expected a value");
    }

    private static void fail(String message) {
        throw new AssertionError(message);
    }
}
