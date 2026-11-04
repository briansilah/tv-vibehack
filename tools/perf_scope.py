"""Which tools/perf.py scenarios a change needs, read from its diff (docs/performance.md, "Measure only what you
changed").

    perf.py plan                          the plan for this branch: origin/main...the working tree
    perf.py plan --base 9a18332 --head 826f811 --json
    perf.py run --label fix --scenarios auto

Each changed file goes through FILE_RULES, first match wins. For a few files that touch most of the launcher
(MainActivity, AppAdapter, TopShelf, config.xml) MEMBER_RULES go finer: by the methods, fields and resources the
diff's lines are in, and in MainActivity.onCreate by the listener a line is in. Comment-only and import-only lines
count for nothing. Whatever has no rule is resolved through what uses it (a private method through the methods that
call it, a new class through the code that refers to it), and when that fails too, through everything: a missed
regression costs more than an extra minute on the TV.

No import of perf.py, so perf.py can import this; jank key presets are named here and resolved by perf.KEY_PRESETS.
"""
import bisect
import collections
import fnmatch
import os
import re
import subprocess

SCENARIOS = ("cold", "warm", "jank", "dock", "mem")   # perf.ALL_SCENARIOS, in run order
PRESETS = ("default", "grid", "dock", "corner", "arrange")   # jank key presets (perf.KEY_PRESETS), in press order
CHECKS = ("none", "local", "emulator", "tv")         # what a change needs besides perf numbers, least first
SOURCE = "launcher/app/src/main/"
JAVA = SOURCE + "java/com/gathin/tvhome/"
RES = SOURCE + "res/"

# The parameters each scenario's numbers depend on (perf.py run's options): a baseline measured with the same ones
# can be reused. mem also depends on the scenarios run before it in the same run.
SCENARIO_PARAMS = {
    "cold": ("runs", "settle", "other_app"),
    "warm": ("runs", "settle", "via_home", "other_app"),
    "jank": ("rounds", "keys", "key_interval"),
    "dock": ("rounds", "dock_moves", "dock_interval"),
    "mem": (),
}

Effect = collections.namedtuple("Effect", "scenarios keys check trace why variants")
# perf.py run --variant: stand-in art behind the glass, for changes to the glass over pictures
ART = "--ez glass_test_art true"


def E(scenarios="", keys="", check="local", trace="", why="", art=""):
    """An Effect from short strings: E("jank dock", keys="corner", why=...). scenarios="*" means all of them;
    jank without keys means the default keys. art: the scenarios to measure again over stand-in art (ART)."""
    s = SCENARIOS if scenarios == "*" else tuple(scenarios.split())
    k = tuple(keys.split()) or (("default",) if "jank" in s else ())
    a = tuple(art.split())
    return Effect(s, k, check, trace, why, ((ART, a, k if "jank" in a else ()),) if a else ())


ALL = E("*", trace="cold home keys dock", why="")
COMMENTS = E(why="comments, blank lines or imports only")

# ---- the map -----------------------------------------------------------------------------------------------------

FILE_RULES = [
    # Nothing to run.
    (("*.md", "docs/*", "examples/*", "PROMPT.md", "LICENSE", "launcher/FONT-LICENSE-OFL.txt", ".gitignore",
      ".gitattributes"), E(check="none", why="documentation")),
    ((".github/*",), E(check="none", why="CI and agent instructions")),
    ((RES + "mipmap*", RES + "drawable-nodpi/banner.*"),
     E(check="none", why="the launcher's own icon and banner, which other launchers show")),
    # Tools: local tests; the ones that talk to a TV are tried on an emulator.
    (("tools/tests/*",), E(why="the tools' offline tests")),
    (("tools/perf.py", "tools/perf_scope.py", "tools/perf_baseline.py", "tools/provenance.py", "tools/tv.py",
      "tools/b64push.py"),
     E(check="emulator", why="tools that talk to a TV: run them against your emulator; a harness change makes "
                             "numbers from before it incomparable, so re-measure both sides")),
    (("tools/*.sh", "tools/*.ps1"), E(check="emulator", why="scripts that change a TV's settings: your emulator only")),
    (("tools/*",), E(why="local tools")),
    (("launcher/build-and-install.*",), E(check="emulator", why="installing: try it on your emulator")),
    (("launcher/app/src/test/*",), E(why="JVM unit tests")),
    (("launcher/app/src/androidTest/*",), E(check="emulator", why="device tests, not shipped in the launcher")),
    # The build changes every number.
    (("launcher/build.gradle", "launcher/settings.gradle", "launcher/gradle.properties", "launcher/gradle/*",
      "launcher/gradlew*", "launcher/app/build.gradle", "launcher/app/proguard*", "launcher/app/*.pro",
      SOURCE + "AndroidManifest.xml", SOURCE + "baseline-prof*"),
     E("*", trace="cold", why="the build, ProGuard, the manifest or dependencies change every number")),
    # Java, by class.
    ((JAVA + "MainActivity.java",), E("*", why="MainActivity")),            # MEMBER_RULES go finer
    ((JAVA + "AppAdapter.java",), E("*", why="the tiles and the app list")),  # MEMBER_RULES go finer
    ((JAVA + "Perf.java",), E("*", why="the instrumentation perf.py reads")),
    ((JAVA + "Transitions.java", JAVA + "HomeMotion.java"),
     E("warm", trace="home launch", why="Home's motion as it comes back (warm) and as an app opens (no scenario: "
                                        "trace it)")),
    ((JAVA + "HomeButtonService.java", JAVA + "VolumeHold.java", RES + "xml/home_button_service.xml"),
     E(check="tv", trace="home remote",
       why="the Home button service: no scenario goes through it (warm starts the launcher directly, adb keys skip "
           "it, and it's off on an Android TV emulator), so check it on the TV with the remote")),
    ((JAVA + "ProfileCorner.java", JAVA + "TopBarButton.java", JAVA + "QuickSettings.java"),
     E("jank", keys="corner", trace="keys:corner", why="the top right corner")),
    ((JAVA + "CornerShade.java",),
     E("jank", keys="corner", trace="keys:corner", art="jank", why="the shade behind the top right, for bright "
                                                                   "pictures")),
    ((JAVA + "GridScroller.java",), E("jank", keys="grid", trace="keys:grid", why="scrolling the grid")),
    ((JAVA + "DockDecoration.java",),
     E("jank dock", keys="default dock", trace="dock", why="the dock's panel of glass", art="dock")),
    ((JAVA + "LiquidGlass.java",),
     E("jank dock mem", keys="default corner", trace="dock", art="dock",
       why="Liquid Glass on the dock and the top right, redrawn when the shelf changes or the page scrolls; and over "
           "pictures (stand-in art)")),
    ((JAVA + "Rearranger.java",),   # MEMBER_RULES go finer
     E("jank", keys="default arrange", trace="keys:arrange", why="arranging apps; it also sees every focus change "
                                                                "and key on a tile")),
    ((JAVA + "MoveMarks.java",), E("jank", keys="arrange", trace="keys:arrange", why="the marks on an app being "
                                                                                    "moved")),
    ((JAVA + "GlassSlider.java",),
     E(check="emulator", why="the Liquid Glass slider, on screen only while you choose: look at it on your "
                             "emulator (what it sets is LiquidGlass's)")),
    ((JAVA + "GlassTestArt.java",),
     E(check="emulator", art="dock", why="the stand-in art for measuring glass over pictures; not in the launcher "
                                         "as it ships")),
    ((JAVA + "ClockDream.java", RES + "layout/dream_clock.xml"),
     E(check="emulator", trace="dream",
       why="the screensaver: no run scenario; trace it while it runs (`trace --action none`)")),
    ((JAVA + "AppOrder.java",),
     E("jank", keys="default arrange", trace="keys:arrange",
       why="where a key moves focus or a moving app: plain logic with JVM tests")),
    ((JAVA + "TopShelf.java",), E("dock mem", trace="dock", why="the top shelf")),   # MEMBER_RULES go finer
    ((JAVA + "TopShelfView.java", JAVA + "ShelfSource.java", JAVA + "ShelfFeeds.java", JAVA + "ShelfInfoView.java",
      JAVA + "CoverArt.java", JAVA + "AmbientColor.java", JAVA + "ShelfArt.java", JAVA + "ShelfArtworks.java",
      JAVA + "ShelfArtKeep.java", JAVA + "TrueBlack.java"),
     E("dock mem", trace="dock", why="the top shelf: its art, glow and pictures")),
    ((JAVA + "ShelfOrder.java",),
     E("dock mem", trace="dock", why="the top shelf: which title shows next, and which pictures stay decoded "
                                     "(plain logic with JVM tests)")),
    ((JAVA + "ShelfChoice.java", JAVA + "ShelfChoices.java"),
     E("dock mem", trace="dock", why="the top shelf: what each app's shows, as chosen in Home Settings (ShelfChoice "
                                     "has JVM tests)")),
    ((JAVA + "SettingsActivity.java", JAVA + "ShelfPreview.java", RES + "layout/activity_settings.xml",
      RES + "layout/settings_*.xml", RES + "color/settings_*.xml", RES + "drawable/ic_chevron_right.xml"),
     E(check="emulator", why="Home Settings, on screen only while you use it: look at it on your emulator")),
    ((RES + "drawable/settings_banner.xml",),
     E("cold jank mem", keys="grid", trace="keys:grid", why="Home Settings' tile: its banner loads and binds with "
                                                            "the other apps'")),
    # Resources.
    ((RES + "values/config.xml",), E("*", why="the launcher's configuration")),   # MEMBER_RULES go finer
    ((RES + "layout/activity_main.xml", RES + "values/styles.xml"),     # MEMBER_RULES go finer
     E("*", why="the whole home screen's layout and styles")),
    ((RES + "layout/item_app.xml", RES + "drawable/tile_bg.xml", RES + "drawable/action_tile_bg.xml",
      RES + "drawable/ic_arrange.xml", RES + "drawable/ic_check.xml"),
     E("cold jank mem", keys="grid", trace="keys:grid", why="the tiles: inflated at start, bound while "
                                                                    "scrolling")),
    ((RES + "layout/top_bar*.xml", RES + "drawable/ic_input.xml", RES + "drawable/ic_settings.xml",
      RES + "drawable/ic_wifi.xml", RES + "drawable/tab_*", RES + "drawable/gtv_*", RES + "drawable/pill_bg.xml",
      RES + "drawable/pill_text.xml", RES + "drawable/avatar_*"),
     E("cold jank", keys="corner", trace="keys:corner", why="the top right's buttons: inflated at start")),
    ((RES + "drawable/ic_play.xml", RES + "drawable/shelf_button_bg.xml", RES + "color/shelf_button_text.xml"),
     E("dock", trace="dock", why="the top shelf's button")),
    ((RES + "layout/arrange_header.xml", RES + "layout/legend_item.xml", RES + "drawable/key_cap_bg.xml",
      RES + "drawable/ic_back.xml", RES + "drawable/ic_dpad.xml"),
     E("jank", keys="arrange", trace="keys:arrange", why="the header while arranging apps")),
    ((RES + "layout/popup_inputs.xml", RES + "layout/item_input.xml", RES + "drawable/input_row_bg.xml",
      RES + "drawable/pill_text_dim.xml", RES + "drawable/status_dot.xml", RES + "drawable/ic_av.xml",
      RES + "drawable/ic_livetv.xml", RES + "drawable/glass_panel.xml", RES + "layout/glass_slider.xml",
      RES + "layout/sheet_*.xml", RES + "drawable/sheet_action_bg.xml"),
     E(check="emulator", why="menus and sheets that open on request (Inputs, an app's actions, the glass slider): "
                             "look at them on your emulator")),
    ((RES + "anim/tile_enter.xml",), E("cold", trace="cold", why="the tiles' entrance, once at start")),
    ((RES + "font/*", RES + "font-*/*"), E("cold mem", trace="cold", why="fonts: loaded as views inflate")),
    ((RES + "values/strings.xml", RES + "values-*/strings.xml"),
     E(check="emulator", why="text: check that it fits, on your emulator")),
    ((RES + "values/attrs.xml",), E(why="attribute declarations: the styles that use them say more")),
]

MEMBER_RULES = {
    JAVA + "MainActivity.java": [
        (("onCreate>pillKeys", "onCreate>captionFollow"),
         E("jank", keys="corner", trace="keys:corner", why="keys and captions in the top right")),
        (("onCreate>*.setOnClickListener",),
         E(check="emulator", why="what a top right button opens")),
        (("onCreate>grid.addOnLayoutChangeListener",),
         E("cold jank", keys="grid", trace="keys:grid", why="the grid's padding, set at start")),
        (("onCreate>grid.addOnScrollListener",),
         E("jank", keys="grid", trace="keys:grid", why="the page following the grid's scroll")),
        # The rest of onCreate goes statement by statement (STATEMENT_MEMBERS): it runs at start, plus what each
        # statement sets up, by the fields, members and classes it names.
        (("onCreate",), E("cold mem", why="runs at start")),
        (("onCreate>*",), E(why="a listener set up in onCreate")),
        # What onCreate's statements set up, by name (fields and locals).
        (("corner", "topBar", "topBarStub", "btnInputs", "btnWifi", "btnSettings", "avatar", "topCaption",
          "cornerShade", "topArea"), E("jank", keys="corner", trace="keys:corner", why="the top right")),
        (("topShelf", "shelfInfo", "heroName", "ambient", "ambientGlow"),
         E("dock mem", trace="dock", why="the top shelf")),
        (("backdrop",), E("jank dock mem", keys="default corner", trace="dock", why="Liquid Glass's backdrop")),
        (("grid", "scroller"), E("jank dock", keys="grid", trace="keys:grid", why="the grid and the dock")),
        (("rearranger",), E("jank", keys="default arrange", trace="keys:arrange", why="arranging apps")),
        (("homeMotion",), E("warm", trace="home", why="Home's motion")),
        (("adapter", "root", "COLUMNS", "density"), E("*", why="the whole home screen")),
        (("handler", "Perf", "t0", "Log", "Toast"), E(why="")),   # neutral in a statement
        (("onResume", "onResume>*", "onFocusRestored", "reportedFullyDrawn", "tilesEntered"),
         E("cold warm", trace="home", why="showing Home: at start and coming back")),
        (("reloadApps", "appsChanged"), E("cold mem", trace="cold", why="loading the app list (at start, and after a "
                                                                        "TV app changes)")),
        (("packageReceiver", "reloadNow"),
         E(check="emulator", why="a TV app installed or removed while Home shows: try it on your emulator")),
        (("onNewIntent", "comeBackFromApp", "onStop", "onPause", "wasStopped", "showMode", "resumed",
          "glassOnReturn", "EXTRA_GLASS_SLIDER"),
         E("warm", trace="home", why="Home coming back from an app")),
        (("onLaunchApp", "comeBack", "comeBackIfShown", "COME_BACK_MS", "onShelfAction"),
         E(check="emulator", trace="launch", why="opening an app, which no scenario does: trace it")),
        (("open", "openSettings", "openProfile", "USER_SETTINGS"),
         E(check="emulator", why="what the top right's buttons open")),
        (("onTrimMemory",), E("mem", why="giving memory back")),
        (("onDestroy",), E(check="emulator", why="only when the system ends Home")),
        (("onBackPressed", "onActionTile"),
         E("jank", keys="arrange", trace="keys:arrange", why="arranging apps")),
        (("onTileLongPressed", "openShelfSettings"), E(check="emulator", why="an app's actions (OK held)")),
        (("onTileFocused",), E("jank dock", keys="grid", trace="keys", why="each focus change")),
        (("gridScroll", "followPage", "pageTravel", "SAFE_TOP_DP", "TOP_BAR_FADE_DP"),
         E("jank", keys="grid", trace="keys:grid", why="scrolling the grid and the page with it")),
        (("placeHero", "heroBaseY"), E("dock", trace="dock", why="where the top shelf's name sits")),
        (("showHero", "showHero>*", "swapShelf", "swapShelf>*", "onShelfArt", "setAmbient", "onShelfCovered",
          "shelfTiming", "SHELF_*", "NAME_*", "GLOW_*", "heroShown", "nameSwapping", "shelfKey", "ambientColor",
          "ambientFade", "glowAt", "onShelfGlow"),
         E("dock", trace="dock", why="the top shelf's name and glow on a dock move")),
        (("hideHero",), E("jank dock", keys="grid", trace="dock", why="the top shelf going, down into the grid")),
        (("onShelfExit", "onTileDpadKey"),
         E("jank", keys="default corner", trace="keys:corner", why="moving focus between tiles, the shelf and the "
                                                                   "top right")),
        (("focusTile", "focusTarget", "deferredFocus", "afterDeferredFocus", "onTileKey"),
         E("warm jank", keys="grid", trace="keys", why="moving focus to a tile (also when Home comes "
                                                               "back)")),
        (("setGlass", "applyGlassExtra", "PREF_GLASS", "EXTRA_GLASS", "glassChoice", "glassExtra"),
         E("jank dock", keys="default corner", trace="dock", why="the glass's look")),
        (("applyAdbExtras", "defaultConfig", "EXTRA_DEFAULT_CONFIG"),
         E("jank dock", keys="default corner", trace="dock",
           why="the configuration perf.py starts it in (default_config, the glass look) and reports")),
        (("showTestArt", "testArt"), E(check="emulator", art="dock", why="stand-in art behind the glass")),
        (("showGlassSlider", "hideGlassSlider", "glassLabel", "glassPanel", "GLASS_PANEL_*"),
         E(check="emulator", why="the Liquid Glass slider: look at it on your emulator")),
        (("updateNetworkIndicator", "networkIcon", "networkCallback", "minuteTick", "MINUTE_MS"),
         E("warm", trace="home", why="the network button, refreshed after Home's first frame")),
        (("hideCaptionUnlessInCorner", "captionFor", "showCaption", "settingsLabelChecked", "TAB_CAPTION_COLOR",
          "CAPTION_*"),
         E("jank", keys="corner", trace="keys:corner", why="the top right's captions")),
        (("showInputs", "showInputs>*", "addSeparator", "addInputRow", "addInputRow>*", "liveTvPkg"),
         E(check="emulator", why="the Inputs menu (`tvhome:showInputs` in a trace)")),
    ],
    JAVA + "AppAdapter.java": [
        (("reload", "preload", "dropPreload", "load", "loadApp", "shown", "show", "Loaded*", "Entry*"),
         E("cold mem", trace="cold", why="loading the apps' banners, icons and labels")),
        (("sampleDockColours", "vividColor", "sample", "ambientColorAt"),
         E("dock mem", trace="dock", why="the glow's colour for each dock app")),
        (("onCreateViewHolder", "onBindViewHolder", "bindSlot", "getItemViewType"),
         E("cold jank mem", keys="grid", trace="keys:grid", why="creating and binding tiles")),
        (("releasePress",), E("warm", why="the pressed tile, released when Home comes back")),
        (("launch", "isSelect", "Holder.press", "Holder.onSelectKey"),
         E(check="emulator", trace="launch", why="opening an app from a tile, which no scenario does: trace it")),
        (("keys", "saveOrder", "setOnOrderSaved", "move", "setArranging", "movingKey", "appCount", "isActionTile",
          "PREF_ORDER"),
         E("jank", keys="arrange", trace="keys:arrange", why="arranging apps")),
        (("openAppInfo",), E(check="emulator", why="an app's actions")),
        (("loadInOrder", "Ordered*"), E(check="emulator", why="Home Settings' list of apps")),
        (("Holder*",), E("jank dock", keys="grid", trace="keys", why="a tile's focus lift and springs")),
        (("keyAt", "positionOf", "getItemId", "getItemCount"),
         E("warm jank", trace="keys", why="looking tiles up on each focus change and on Home")),
        (("labelAt", "bannerAt", "iconAt", "pkgAt", "dockApps", "copy"),
         E("dock mem", trace="dock", why="the dock apps")),
        (("contains",), E(check="emulator", why="TV apps changing")),
        (("TileListener*",), E("*", why="how tiles talk to MainActivity")),
    ],
    JAVA + "Rearranger.java": [
        (("showActions", "showActions>*", "addAction", "addAction>*", "sheet", "glassAction", "setGlassAction",
          "shelfAction", "setTopShelfAction"),
         E(check="emulator", why="an app's actions (OK held): look at them on your emulator")),
        (("moveAcrossDock",), E("jank", keys="arrange", trace="keys:arrange", why="moving an app into or out of "
                                                                                "the dock")),
        (("onTileFocused", "onKey", "isActive"),
         E("jank", keys="default arrange", trace="keys:arrange", why="every focus change and key on a tile")),
    ],
    JAVA + "TopShelf.java": [
        (("resume", "pause", "appsChanged", "setDock", "applyChoices"),
         E("warm dock mem", trace="dock", why="the top shelf as Home shows and hides")),
        (("useShippedChoices", "choicesInEffect", "chosenGlow", "Host*"),
         E("dock mem", trace="dock", why="each app's top shelf choice (Home Settings), and the glow's colour")),
        (("enterActions", "leaveActions", "liftTo", "lift", "liftAnim", "acting", "onActionKey", "labelAction"),
         E("jank dock", keys="corner", trace="keys:corner", why="the top shelf's button, Up from the dock")),
        (("act", "intentFor"), E(check="emulator", why="what the top shelf's button opens")),
        (("followPage", "pageTravel"), E("jank", keys="grid", trace="keys:grid",
                                         why="the top shelf following the page")),
        (("trim",), E("mem", why="giving memory back")),
    ],
    SOURCE + "AndroidManifest.xml": [
        (("uses-permission:android.permission.INTERNET", "uses-permission:android.permission.READ_TV_LISTINGS"),
         E("dock mem", trace="dock", why="the top shelf's network and TV listings")),
        (("activity:.SettingsActivity", "category:android.intent.category.LEANBACK_LAUNCHER"),
         E("cold jank mem", keys="grid", trace="keys:grid", why="Home Settings, a TV app of the launcher's own: one "
                                                                "more tile, loaded at start")),
        (("uses-permission:android.permission.DUMP",),
         E(check="tv", trace="home remote", why="the Home button service's held volume keys: check them on the TV")),
    ],
    RES + "layout/activity_main.xml": [
        (("root",), E("*", why="the screen's root")),
        (("backdrop",), E("jank dock mem", keys="default corner", trace="dock", why="Liquid Glass's backdrop")),
        (("ambient", "shelfArt", "heroName", "shelfInfo"), E("dock mem", trace="dock", why="the top shelf")),
        (("shelfAction",), E("jank dock", keys="corner", trace="keys:corner", why="the top shelf's button")),
        (("cornerShade", "topArea", "corner", "time", "topBarStub", "avatar*", "topCaption"),
         E("cold jank", keys="corner", trace="keys:corner", why="the top right")),
        (("grid",), E("cold jank dock mem", keys="grid", trace="keys:grid", why="the grid and the dock")),
    ],
    RES + "values/styles.xml": [
        (("Theme.*", "Text", "Text.Callout"), E("*", why="the theme, or text styles most views use")),
        (("Text.Clock", "Text.Clock.Screensaver*"), E(check="emulator", trace="dream", why="the screensaver's clock")),
        (("Text.KeyCap", "Text.Overline", "Text.Title1"),
         E("jank", keys="arrange", trace="keys:arrange", why="the header while arranging apps")),
        (("Text.Title3", "Text.Headline", "Text.Caption2"),
         E(check="emulator", why="text in menus and sheets that open on request")),
        (("Text.Time", "Text.Caption1", "ThemeOverlay.TopBar*", "TopBar*", "TopButton*"),
         E("cold jank", keys="corner", trace="keys:corner", why="the top right's styles")),
        (("Text.LargeTitle",), E("cold dock", trace="dock", why="the top shelf's name")),
        (("Text.Body",), E("cold jank mem", keys="grid", why="the tiles' names")),
    ],
    RES + "values/config.xml": [
        (("dock_apps", "hidden_apps"), E("cold dock mem", why="which apps load and which the dock shows")),
        (("live_tv_package", "profile_name"), E(check="emulator", why="the Inputs menu, the profile picture")),
        (("top_bar_style",), E("cold jank", keys="corner", trace="keys:corner", why="the top right's look")),
        (("liquid_glass", "liquid_glass_blur"),
         E("jank dock mem", keys="default corner", trace="dock", why="Liquid Glass")),
        (("top_shelf_*",), E("dock mem", trace="dock", why="the top shelf")),
    ],
}

# Members that set up everything, read statement by statement: what a changed statement needs is its member's rule
# plus the rules of the fields, members and classes it names (MEMBER_RULES list fields and locals for this).
STATEMENT_MEMBERS = {
    JAVA + "MainActivity.java": ("onCreate", "onCreate>*"),
}

# ---- reading Java and XML -----------------------------------------------------------------------------------------

Span = collections.namedtuple("Span", "path start end private")   # lines are 1-based and inclusive


def mask_java(src):
    """(code, bare): src with comments blanked, and also with string and char literals blanked, keeping every
    newline and offset."""
    code, bare = list(src), list(src)
    i, n = 0, len(src)

    def blank(a, b, out):
        for j in range(a, b):
            if out[j] != "\n":
                out[j] = " "

    while i < n:
        c = src[i]
        if src.startswith("//", i):
            j = src.find("\n", i)
            j = n if j < 0 else j
            blank(i, j, code)
            blank(i, j, bare)
            i = j
        elif src.startswith("/*", i):
            j = src.find("*/", i + 2)
            j = n if j < 0 else j + 2
            blank(i, j, code)
            blank(i, j, bare)
            i = j
        elif src.startswith('"""', i):
            j = src.find('"""', i + 3)
            j = n if j < 0 else j + 3
            blank(i + 3, j - 3, bare)
            i = j
        elif c in "\"'":
            j = i + 1
            while j < n and src[j] != c and src[j] != "\n":
                j += 2 if src[j] == "\\" else 1
            blank(i + 1, min(j, n), bare)
            i = j + 1
        else:
            i += 1
    return "".join(code), "".join(bare)


_ANNOTATION = re.compile(r"@[\w.]+(\s*\([^()]*\))?")
_TYPE_DECL = re.compile(r"\b(class|interface|enum|record)\s+(\w+)")
_ANON_CLASS = re.compile(r"\bnew\s+[\w.<>?, \[\]]+\([^()]*\)\s*$")


def _field_names(header):
    """Names declared by a field declaration's text (without its ';')."""
    parts, depth, cur = [], 0, ""
    for ch in header:
        if ch in "(<[{":
            depth += 1
        elif ch in ")>]}":
            depth -= 1
        if ch == "," and depth == 0:
            parts.append(cur)
            cur = ""
        else:
            cur += ch
    parts.append(cur)
    names = []
    for k, part in enumerate(parts):
        part = part.split("=")[0].strip()
        ids = re.findall(r"[A-Za-z_$][\w$]*", part)
        if ids:
            names.append(ids[-1] if k == 0 else ids[0])
    return names


def _callback_name(header):
    """The name of a lambda's or anonymous class's body inside a method: the variable it is assigned to, else the
    call it is passed to (e.g. `grid.post`)."""
    m = re.match(r"\s*(?:final\s+)?[\w.<>?, \[\]]*?\b(\w+)\s*=[^=]", header)
    if m:
        return m.group(1)
    calls = re.findall(r"([\w.]+)\s*\(", header)
    calls = [c for c in calls if c not in ("new", "if", "for", "while", "switch", "synchronized", "catch")]
    return calls[0] if calls else "lambda"


def java_spans(src):
    """Members of the classes in a Java source: methods, fields, nested types ("Holder", "Holder.settle"), and the
    lambdas and anonymous classes directly in a method ("onCreate>pillKeys")."""
    _, text = mask_java(src)
    spans = []
    # Scopes: (kind, path, start line, private). kind: class, member, callback, block.
    stack = []
    line = 1
    header_start = 0

    def line_of_first_code(a, b):
        k = a
        while k < b and text[k].isspace():
            k += 1
        return text.count("\n", 0, k) + 1

    for i, ch in enumerate(text):
        if ch == "\n":
            line += 1
            continue
        if ch not in "{};":
            continue
        top = stack[-1] if stack else None
        header = _ANNOTATION.sub(" ", text[header_start:i])
        if ch == "{":
            start = line_of_first_code(header_start, i)
            if top is None or top[0] == "class":
                prefix = top[1] + "." if top and top[1] else ""
                t = _TYPE_DECL.search(header)
                private = bool(re.search(r"\bprivate\b", header))
                if t:
                    # the file's own class has no prefix; nested types do
                    stack.append(("class", prefix + t.group(2) if top else "", start, private))
                else:
                    eq, par = header.find("="), header.find("(")
                    if eq >= 0 and (par < 0 or eq < par):
                        names = _field_names(header[:eq + 1])
                    else:
                        names = re.findall(r"(\w+)\s*\(", header)[:1] or ["static"]
                    stack.append(("member", prefix + names[0], start, private))
            elif top[0] == "member" and (header.rstrip().endswith("->") or _ANON_CLASS.search(header)):
                stack.append(("callback", top[1] + ">" + _callback_name(header), start, False))
            else:
                stack.append(("block", top[1], start, False))
            header_start = i + 1
        elif ch == "}":
            if stack:
                kind, path, start, private = stack.pop()
                if kind in ("member", "callback") or (kind == "class" and path):
                    spans.append(Span(path, start, line, private))
            header_start = i + 1
        else:   # ';'
            if top is not None and top[0] == "class" and header.strip():
                prefix = top[1] + "." if top[1] else ""
                start = line_of_first_code(header_start, i)
                private = bool(re.search(r"\bprivate\b", header))
                eq, par = header.find("="), header.find("(")
                if par >= 0 and (eq < 0 or par < eq):
                    names = re.findall(r"(\w+)\s*\(", header)[:1]
                else:
                    names = _field_names(header)
                for name in names:
                    spans.append(Span(prefix + name, start, line, private))
            header_start = i + 1
    return spans


def innermost(spans, line):
    """The narrowest span holding a line, or None."""
    best = None
    for s in spans:
        if s.start <= line <= s.end and (best is None or s.end - s.start < best.end - best.start):
            best = s
    return best


def mask_xml(src):
    return re.sub(r"<!--.*?-->", lambda m: re.sub(r"[^\n]", " ", m.group(0)), src, flags=re.S)


_XML_TAG = re.compile(r"<(/?)([\w.:-]+)((?:[^>\"]|\"[^\"]*\")*?)(/?)>")


def xml_elements(src):
    """The elements of an XML file, in document order: dicts with tag, start and end lines, depth, attrs (the start
    tag's text) and children (indexes)."""
    text = mask_xml(src)
    elements, stack = [], []
    for m in _XML_TAG.finditer(text):
        ln = text.count("\n", 0, m.start()) + 1
        if m.group(1):
            if stack:
                e = elements[stack.pop()]
                e["end"] = text.count("\n", 0, m.end()) + 1
            continue
        e = {"tag": m.group(2), "start": ln, "end": text.count("\n", 0, m.end()) + 1, "depth": len(stack),
             "attrs": m.group(3), "children": [], "parent": stack[-1] if stack else None}
        if stack:
            elements[stack[-1]]["children"].append(len(elements))
        elements.append(e)
        if not m.group(4):
            stack.append(len(elements) - 1)
    return elements


def _attr(e, name):
    m = re.search(r"\b%s=\"([^\"]*)\"" % re.escape(name), e["attrs"])
    return m.group(1) if m else None


def xml_kind(path):
    if "/res/layout" in path:
        return "layout"
    if path.endswith("AndroidManifest.xml"):
        return "manifest"
    return "values"


def xml_spans(src, kind="values"):
    """Named parts of an XML file: a values file's resources (<string name="x">, <style name="y">, ...); a
    manifest's elements by tag and name ("uses-permission:android.permission.INTERNET"); or a layout's views by id,
    after their parents' ("root/topArea/corner"). A view without an id is named after the ids inside it ("a+b"),
    else after its parent."""
    elements = xml_elements(src)
    spans = []
    if kind == "values":
        for e in elements:
            name = _attr(e, "name")
            if e["depth"] == 1 and name:
                spans.append(Span(name, e["start"], e["end"], False))
        return spans
    if kind == "manifest":
        for e in elements:
            name = _attr(e, "android:name")
            spans.append(Span(e["tag"] + (":" + name if name else ""), e["start"], e["end"], False))
        return spans

    def ids_inside(i):
        own = _attr(elements[i], "android:id")
        if own:
            return [own.split("/")[-1]]
        return [x for c in elements[i]["children"] for x in ids_inside(c)]

    def name(i):
        """"root/topArea/corner": the view's id after its parents'; a view without one is named by those in it."""
        part = "+".join(ids_inside(i))
        p = elements[i]["parent"]
        head = name(p) if p is not None else ""
        if not part:
            return head or "?"
        return head + "/" + part if head else part

    for i, e in enumerate(elements):
        spans.append(Span(name(i), e["start"], e["end"], False))
    return spans


def is_layout(path):
    return xml_kind(path) == "layout"


def code_lines(path, src):
    """{line: its text without comments} for a file's lines, for telling code from comments and blank lines."""
    if path.endswith(".java"):
        src = mask_java(src)[0]
    elif path.endswith(".xml"):
        src = mask_xml(src)
    return {i + 1: t for i, t in enumerate(src.split("\n"))}


def _no_effect(path, text):
    t = text.strip()
    if not t:
        return True
    return path.endswith(".java") and bool(re.match(r"(import|package)\s[\w.\s*]+;$", t))


def _has_code_change(change):
    """False when every changed line of a Java or XML file is blank or a comment (or an import)."""
    if not (change.path.endswith(".java") or change.path.endswith(".xml")):
        return True
    for side_text, side_lines in ((change.old, change.old_lines), (change.new, change.new_lines)):
        if side_text is None:
            continue
        if side_lines is None:
            return True
        texts = code_lines(change.path, side_text)
        if any(not _no_effect(change.path, texts.get(ln, "")) for ln in side_lines):
            return True
    return False


# ---- planning ----------------------------------------------------------------------------------------------------

Change = collections.namedtuple("Change", "path old new old_lines new_lines")
"""A changed file: its old and new text (None when added or deleted) and the changed line numbers on each side
(None: the whole file, e.g. binary files)."""

Hit = collections.namedtuple("Hit", "path member effect why")


def file_rule(path):
    for globs, effect in FILE_RULES:
        if any(fnmatch.fnmatchcase(path, g) for g in globs):
            return effect
    return None


def member_rule(path, member, glob=False):
    """The MEMBER_RULES effect for a member name (and, with glob, the pattern that matched it)."""
    for globs, effect in MEMBER_RULES.get(path, ()):
        for g in globs:
            if fnmatch.fnmatchcase(member, g):
                return (effect, g) if glob else effect
    return (None, None) if glob else None


JAVA_WORDS = frozenset("""abstract assert boolean break byte case catch char class continue default do double else
enum extends final finally float for if implements import instanceof int interface long native new null package private
protected public return short static strictfp super switch synchronized this throw throws transient true false try var
void volatile while""".split())


class Planner:
    """Resolves changed lines to effects. `old` and `new` are (read(path) -> text or None, [paths]) for the
    launcher's sources on each side of the diff, for finding what uses a member, a class or a resource."""

    def __init__(self, old=None, new=None):
        self.sides = {"old": old or (lambda p: None, []), "new": new or (lambda p: None, [])}
        self._sources = {}
        # Resolutions, kept even when a cycle cut one short: what the cycle left out is what the member that
        # started it needs, which is in the plan already.
        self._memo = {}

    def _source(self, side, path, text=None):
        if (side, path) not in self._sources:
            text = text if text is not None else self.sides[side][0](path)
            self._sources[(side, path)] = Source(path, text) if text is not None else None
        return self._sources[(side, path)]

    def hits(self, change):
        """Hits for one changed file."""
        path = change.path
        rule = file_rule(path)
        unknown = rule is None and path.startswith(SOURCE) and (path.endswith(".java") or path.startswith(RES))
        if path in MEMBER_RULES or unknown:
            return self._line_hits(change, unknown)
        if rule is None:
            rule = ALL._replace(why="no rule for this file") if path.startswith("launcher/") \
                else E(why="outside the launcher")
        if rule.check != "none" and not _has_code_change(change):
            return [Hit(path, None, COMMENTS, COMMENTS.why)]
        return [Hit(path, None, rule, rule.why)]

    def _line_hits(self, change, unknown):
        path, out = change.path, []
        same_super = change.old is not None and change.new is not None and _superclass(change.old) == \
            _superclass(change.new)
        for side, text, lines in (("old", change.old, change.old_lines), ("new", change.new, change.new_lines)):
            if text is None:
                continue
            if lines is None:
                lines = range(1, text.count("\n") + 2)
            if unknown and not path.endswith((".java", ".xml")):
                out.append(self._file_users(path, side))   # e.g. a new picture
                continue
            src = Source(path, text)
            values = RES + "values" in path    # a new values file: each resource by what uses it
            for ln in lines:
                code = src.code.get(ln, "")
                if _no_effect(path, code):
                    continue
                if unknown and not values:
                    out.append(self._file_users(path, side))
                    break
                if src.java and same_super and innermost(src.spans, ln) is None and _TYPE_DECL.search(code):
                    out.append(Hit(path, None, E(why=""), "the class's declaration (its methods say what changed)"))
                    continue
                out.append(self._line_hit(src, ln, frozenset(), side))
        if not out:
            out.append(Hit(path, None, COMMENTS, COMMENTS.why))
        return _dedupe(out)

    def _line_hit(self, src, line, seen, side):
        """What a code line of a file with MEMBER_RULES needs: its member's rule; for a statement in a member listed
        in STATEMENT_MEMBERS, what the statement sets up; for a member or a resource without a rule, what uses
        it."""
        span = src.span_at(line)
        if span is None:
            return Hit(src.path, None, file_rule(src.path) or ALL, "outside any member")
        effect, glob = _rule_for(src.path, span.path)
        statement = effect is not None and glob in STATEMENT_MEMBERS.get(src.path, ()) and src.java
        key = (side, src.path, line if statement else span.path)
        if key not in self._memo:
            if statement:
                hit = self._statement_hit(src, span, line, effect, seen, side)
            elif effect is not None:
                hit = Hit(src.path, span.path, effect, effect.why)
            else:
                hit = self._unruled(src, span, seen, side)
            self._memo[key] = hit
        return self._memo[key]

    def _unruled(self, src, span, seen, side):
        """A member or resource without a rule: what uses it, else the file's rule."""
        path, name = src.path, span.path
        if name in seen:
            return Hit(path, name, E(why=""), "")   # a cycle: what started it counts already
        if len(seen) < 12:
            seen = seen | {name}
            if src.java:
                ident = re.split(r"[>.]", name)[-1]
                hits = [self._line_hit(src, ln, seen, side) for ln in src.users(ident, span)]
                if not span.private:
                    cls = name.split(">")[0].split(".")[-2] if "." in name.split(">")[0] else \
                        os.path.splitext(os.path.basename(path))[0]
                    h = self._users(path, name, None, side, seen, others=True, member=(cls, ident))
                    if h is not None:
                        hits.append(h)
                if hits:
                    return Hit(path, name, merge([h.effect for h in hits]),
                               "used by " + ", ".join(sorted({_where(h) for h in hits})))
            elif not is_layout(path):
                hit = self._users(path, name, _resource_patterns(src, span), side, seen)
                if hit is not None:
                    return Hit(path, name, hit.effect, hit.why)
        return Hit(path, name, file_rule(path) or ALL, "no rule for %s" % name)

    def _statement_hit(self, src, span, line, base, seen, side):
        """A statement in a member like onCreate, which sets up everything: what the things it names need. For
        `x.method(...)` with x of a class with MEMBER_RULES, that method's rule."""
        method = span.path.split(">")[0]
        idents = [(i, c or None) for i, c in src.statement_idents(line) if i not in JAVA_WORDS and i != method]
        if not idents:
            return Hit(src.path, span.path, base, base.why)
        effects, named, recognized = [base], [], False
        members = {s.path: s for s in src.spans}
        for ident, call in sorted(idents, key=lambda x: (x[0], x[1] or "")):
            if ident in seen:
                recognized = True
                continue
            effect = None
            typ = JAVA + src.types.get(ident, "") + ".java"
            if call and typ in MEMBER_RULES:
                effect = member_rule(typ, call)
                ident += "." + call
            if effect is None:
                effect = member_rule(src.path, ident)
            if effect is None and method + ">" + ident in members:   # a listener of this method, by its variable
                effect = member_rule(src.path, method + ">" + ident)
            if effect is None and ident in members:
                effect = self._line_hit(src, members[ident].start, seen | {span.path}, side).effect
            if effect is None:
                effect = file_rule(JAVA + ident + ".java")
            if effect is not None:
                effects.append(effect)
                if (effect.scenarios or effect.check != "local") and ident not in named:
                    named.append(ident)
                recognized = True
        if not recognized:
            return Hit(src.path, span.path, ALL, "a statement I can't place")
        return Hit(src.path, span.path, merge(effects), "sets up " + ", ".join(named) if named else base.why)

    def _file_users(self, path, side):
        """A file without a rule (e.g. a new class or drawable): what the code that refers to it needs."""
        name = os.path.splitext(os.path.basename(path))[0]
        if path.endswith(".java"):
            patterns = [r"\b%s\b" % re.escape(name)]
        else:
            kind = path[len(RES):].split("/")[0].split("-")[0]
            patterns = [r"@%s/%s\b" % (kind, re.escape(name)), r"\bR\.%s\.%s\b" % (kind, re.escape(name))]
        hit = self._users(path, None, patterns, side, frozenset([path]), others=True)
        if hit is None:
            return Hit(path, None, ALL, "no rule, and nothing I can see uses it")
        return Hit(path, None, hit.effect, "no rule; " + hit.why)

    def _users(self, path, name, patterns, side, seen, others=False, member=None):
        """What the lines on one side that match any of `patterns` need (only in other files with `others`, else
        outside `name`'s own span), or None when none do. With member=(class, name) instead of patterns: where
        other files use that member of that class (through variables of its type, or the class)."""
        rx = re.compile("|".join(patterns)) if patterns else None
        effects, where = [], set()
        for other in self.sides[side][1]:
            if others and other == path:
                continue
            src = self._source(side, other)
            if src is None:
                continue
            if member is not None:
                cls, ident = member
                if ident == cls:   # a constructor
                    rx = re.compile(r"\bnew\s+%s\s*[(<]" % re.escape(cls))
                else:
                    receivers = [v for v, t in src.types.items() if t == cls] + [cls]
                    rx = re.compile(r"\b(?:%s)\s*\.\s*%s\b" % ("|".join(map(re.escape, receivers)), re.escape(ident)))
            if not rx.search(src.bare):
                continue
            if other in MEMBER_RULES:
                for ln in src.lines_matching(rx):
                    span = src.span_at(ln)
                    if other == path and span is not None and span.path == name:
                        continue
                    h = self._line_hit(src, ln, seen, side)
                    effects.append(h.effect)
                    where.add(_where(h))
            else:
                rule = file_rule(other)
                if rule is None:
                    continue   # another file without a rule: don't chase it further
                effects.append(rule)
                where.add(_short(other))
        if not effects:
            return None
        return Hit(path, name, merge(effects), "used by " + ", ".join(sorted(where)))


def _where(hit):
    return label(hit) if hit.member or not hit.path.startswith(JAVA) else _short(hit.path)


def _superclass(text):
    m = re.search(r"\bclass\s+\w+(\s+extends\s+[\w.<>, ]+?)?\s*(implements\b|\{)", mask_java(text)[1])
    return m.group(1) if m else None


def _resource_patterns(src, span):
    """Patterns for what refers to a values resource: @type/name, R.type.name, and for a style its children."""
    name = span.path
    tag = src.tags.get(span.start, "")
    kind = {"string-array": "array", "integer-array": "array", "item": "id"}.get(tag, tag)
    pats = [r"@%s/%s\b" % (re.escape(kind), re.escape(name)),
            r"\bR\.%s\.%s\b" % (re.escape(kind), re.escape(name.replace(".", "_")))]
    if kind == "style":
        pats += [r"parent=\"%s\"" % re.escape(name), r"name=\"%s\.[^\"]+\"" % re.escape(name)]
    return pats


_DECLARATION = re.compile(r"\b([A-Z]\w*)(?:\.[A-Z]\w*)*(?:<[^;{}()=]*>)?(?:\[\])?\s+(\w+)\s*[;=,):]")


class Source:
    """One side of a changed file, parsed once."""

    def __init__(self, path, text):
        self.path, self.text = path, text
        self.java = path.endswith(".java")
        self.tags, self.types = {}, {}
        if self.java:
            self.spans = java_spans(text)
            code, self.bare = mask_java(text)
            # array initializers' braces aren't statement boundaries
            self.stmt = re.sub(r"(\]\s*)\{([^{}]*)\}", lambda m: m.group(1) + "(" + m.group(2) + ")", self.bare)
            # the declared types of fields and locals: "corner" -> "ProfileCorner"
            self.types = {m.group(2): m.group(1) for m in _DECLARATION.finditer(self.bare)}
        else:
            self.spans = xml_spans(text, xml_kind(path))
            code = self.bare = self.stmt = mask_xml(text)
            self.tags = {e["start"]: e["tag"] for e in xml_elements(text) if e["depth"] == 1}
        self.code = {i + 1: t for i, t in enumerate(code.split("\n"))}
        self.bare_lines = self.bare.split("\n")
        self.starts = [0]
        for t in self.bare_lines:
            self.starts.append(self.starts[-1] + len(t) + 1)
        self._span_at = {}

    def span_at(self, line):
        if line not in self._span_at:
            self._span_at[line] = innermost(self.spans, line)
        return self._span_at[line]

    def lines_matching(self, rx):
        """The lines (1-based, each once) where a pattern matches."""
        out = []
        for m in rx.finditer(self.bare):
            ln = bisect.bisect_right(self.starts, m.start())
            if not out or out[-1] != ln:
                out.append(ln)
        return out

    def users(self, ident, own):
        """Lines that refer to an identifier, outside its own span."""
        return [ln for ln in self.lines_matching(re.compile(r"\b%s\b" % re.escape(ident)))
                if not (own and own.start <= ln <= own.end)]

    def statement_idents(self, line):
        """(identifier, the method called on it or None) in the statement (or block header) a line is part of,
        not counting what follows a dot."""
        a, b = self.starts[line - 1], self.starts[line] - 1
        # a line that is only braces or a closing ");" changes nothing a neighbouring changed line doesn't
        if not re.search(r"[A-Za-z_$]", self.stmt[a:b]):
            return set()
        last = a + len(self.stmt[a:b].rstrip()) - 1
        lo = max(self.stmt.rfind(c, 0, a) for c in ";{}") + 1
        his = [x for x in (self.stmt.find(c, last) for c in ";{}") if x >= 0]
        hi = min(his) if his else len(self.stmt)
        return {(m.group(1), m.group(2)) for m in
                re.finditer(r"(?<![\w.$])([A-Za-z_$][\w$]*)(?:\s*\.\s*([A-Za-z_$][\w$]*)\s*\()?",
                            self.stmt[lo:hi])}


def _rule_for(path, name):
    """(effect, the pattern that matched) for a member, else for what holds it: a lambda's method, a nested type's
    member's type, a layout view's parent."""
    if is_layout(path):
        for part in reversed(name.split("/")):   # the view, then its parents
            effects = [member_rule(path, n) for n in part.split("+")]   # "+": a view without an id, by those in it
            if None not in effects:
                return merge(effects)._replace(why=effects[0].why), part
        return None, None
    parts = [name]
    while path.endswith(".java") and re.search(r"[>.]", parts[-1]):
        parts.append(re.split(r"[>.](?=[^>.]*$)", parts[-1])[0])
    for p in parts:
        effect, glob = member_rule(path, p, glob=True)
        if effect is not None:
            return effect, glob
    return None, None


def _dedupe(hits):
    seen, out = set(), []
    for h in hits:
        k = (h.path, h.member, h.effect)
        if k not in seen:
            seen.add(k)
            out.append(h)
    return out


def _keys(keys):
    keys = set(keys)
    if "grid" in keys:
        keys.discard("default")   # grid's keys make the default ones' moves too, along the dock and down the grid
    return tuple(k for k in PRESETS if k in keys)


def merge(effects):
    scen = {s for e in effects for s in e.scenarios}
    check = max((e.check for e in effects), key=CHECKS.index, default="none")
    trace = " ".join(dict.fromkeys(t for e in effects for t in e.trace.split()))
    variants = collections.OrderedDict()
    for e in effects:
        for v, vs, vk in e.variants:
            got = variants.setdefault(v, (set(), set()))
            got[0].update(vs)
            got[1].update(vk)
    return Effect(tuple(s for s in SCENARIOS if s in scen), _keys(k for e in effects for k in e.keys), check, trace,
                  "", tuple((v, tuple(s for s in SCENARIOS if s in vs), _keys(vk))
                            for v, (vs, vk) in variants.items()))


def build_plan(changes, planner=None, **meta):
    """The plan (a dict, JSON-ready) for a list of Changes."""
    planner = planner or Planner()
    hits = [h for c in changes for h in planner.hits(c)]
    total = merge([h.effect for h in hits])
    reasons = {}
    for h in hits:
        for s in h.effect.scenarios + (("",) if not h.effect.scenarios else ()):
            why = h.why or h.effect.why
            if s == "jank" and h.effect.keys:
                why += " (keys: %s)" % ", ".join(h.effect.keys)
            group = reasons.setdefault(s, collections.OrderedDict()).setdefault(why, [])
            if label(h) not in group:
                group.append(label(h))
    plan = dict(meta)
    plan.update({
        "version": 1,
        "tv": bool(total.scenarios or total.variants) or total.check == "tv",
        "check": "tv" if total.scenarios else total.check,
        "scenarios": list(total.scenarios),
        "keys": list(total.keys) if "jank" in total.scenarios else [],
        "traces": total.trace.split(),
        # runs of their own, with perf.py run --variant: [{variant, scenarios, keys}]
        "variants": [{"variant": v, "scenarios": list(vs), "keys": list(vk)} for v, vs, vk in total.variants],
        # why each scenario is in the plan ("": what needs none), and where: {scenario: [{why, where}]}
        "reasons": {s: [{"why": w, "where": where} for w, where in g.items()] for s, g in reasons.items()},
        "files": [{"path": h.path, "member": h.member, "scenarios": list(h.effect.scenarios),
                   "keys": list(h.effect.keys), "check": h.effect.check, "why": h.why,
                   "variants": [{"variant": v, "scenarios": list(vs)} for v, vs, _ in h.effect.variants]}
                  for h in hits],
    })
    return plan


def label(hit):
    """README.md, MainActivity.onCreate, layout/activity_main.xml:corner."""
    if hit.path.endswith(".java") and hit.path.startswith(JAVA):
        return _short(hit.path)[:-5] + ("." + hit.member if hit.member else "")
    member = hit.member.split("/")[-1] if hit.member and is_layout(hit.path) else hit.member
    return _short(hit.path) + (":" + member if member else "")


def _short(path):
    for prefix in (JAVA, RES, SOURCE):
        if path.startswith(prefix):
            return path[len(prefix):]
    return path


# ---- git ---------------------------------------------------------------------------------------------------------

def git(cwd, *args, check=True):
    p = subprocess.run(("git",) + args, cwd=cwd, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if check and p.returncode:
        raise RuntimeError("git %s: %s" % (" ".join(args), p.stderr.decode(errors="replace").strip()))
    return p.stdout.decode(errors="replace") if p.returncode == 0 else None


def parse_hunks(diff):
    """{path: (old changed lines, new changed lines)} from `git diff -U0` output. Binary files: (None, None)."""
    out, path = {}, None
    old_path = None
    for line in diff.splitlines():
        if line.startswith("diff --git "):
            path = old_path = None
        elif line.startswith("--- "):
            old_path = None if line[4:] == "/dev/null" else line[6:]
        elif line.startswith("+++ "):
            path = old_path if line[4:] == "/dev/null" else line[6:]
            out.setdefault(path, ([], []))
        elif line.startswith("Binary files ") and line.endswith(" differ"):
            m = re.match(r"Binary files (?:a/)?(.*?) and (?:b/)?(.*?) differ", line)
            if m:
                p = m.group(2) if m.group(2) != "/dev/null" else m.group(1)
                out[p] = (None, None)
        elif line.startswith("@@") and path is not None:
            m = re.match(r"@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@", line)
            a, b = int(m.group(1)), int(m.group(2) if m.group(2) is not None else 1)
            c, d = int(m.group(3)), int(m.group(4) if m.group(4) is not None else 1)
            out[path][0].extend(range(a, a + b))
            out[path][1].extend(range(c, c + d))
    return out


def repo_root(cwd=None):
    return git(cwd or os.getcwd(), "rev-parse", "--show-toplevel").strip()


def changes_from_git(base="origin/main", head=None, cwd=None):
    """(changes, meta) for base...head, or base...the working tree (with uncommitted and untracked files) when head
    is None."""
    root = repo_root(cwd)
    mb = git(root, "merge-base", base, head or "HEAD").strip()
    rev = [mb] + ([head] if head else [])
    status = git(root, "diff", "-M", "--name-status", "-z", *rev, "--")
    fields = status.split("\0")
    pairs, i = [], 0
    while i < len(fields) and fields[i]:
        st = fields[i]
        if st[0] in "RC":
            pairs.append((fields[i + 1], fields[i + 2]))
            i += 3
        else:
            p = fields[i + 1]
            pairs.append((None if st[0] == "A" else p, None if st[0] == "D" else p))
            i += 2
    if head is None:
        for p in git(root, "ls-files", "--others", "--exclude-standard", "-z").split("\0"):
            if p:
                pairs.append((None, p))
    hunks = parse_hunks(git(root, "diff", "-M", "-U0", "--no-color", "--no-ext-diff", *rev, "--"))

    def old_text(p):
        return git(root, "show", "%s:%s" % (mb, p), check=False) if p else None

    def new_text(p):
        if not p:
            return None
        if head:
            return git(root, "show", "%s:%s" % (head, p), check=False)
        try:
            with open(os.path.join(root, p), encoding="utf-8", errors="replace") as f:
                return f.read()
        except OSError:
            return None

    changes = []
    for old, new in pairs:
        path = new or old
        if path in hunks or old in hunks:
            ol, nl = hunks.get(path) or hunks[old]
        elif old is None or new is None:
            ol = nl = None   # an untracked file: all of it
        else:
            ol, nl = [], []  # a pure rename or a mode change: no line changed
        o, n = old_text(old), new_text(new)
        changes.append(Change(path, o, n, None if old is None else ol, None if new is None else nl))
    meta = {"base": base, "merge_base": mb[:12], "head": head or "working tree"}
    return changes, meta, root


def source_reader(root, rev=None):
    """(read, paths) for the launcher's Java and XML sources at a commit, or in the working tree (rev None)."""
    def read(p):
        if rev:
            return git(root, "show", "%s:%s" % (rev, p), check=False)
        try:
            with open(os.path.join(root, p), encoding="utf-8", errors="replace") as f:
                return f.read()
        except OSError:
            return None
    if rev:
        listing = git(root, "ls-tree", "-r", "--name-only", rev, "--", SOURCE).split("\n")
    else:
        listing = git(root, "ls-files", "--cached", "--others", "--exclude-standard", "--", SOURCE).split("\n")
    return read, [p for p in listing if p.endswith(".java") or p.endswith(".xml")]


def plan(base="origin/main", head=None, cwd=None):
    """The plan for base...head (head None: the working tree)."""
    changes, meta, root = changes_from_git(base, head, cwd)
    planner = Planner(old=source_reader(root, meta["merge_base"]), new=source_reader(root, head))
    return build_plan(changes, planner, **meta)


# ---- time and text -----------------------------------------------------------------------------------------------

def estimate_s(scenarios, runs=10, rounds=3, n_keys=16, settle=1.5, key_interval=0.35, dock_moves=8,
               dock_interval=1.2, key_cost=0.08, start_cost=0.6):
    """Rough seconds a run takes: the waits perf.py makes, plus about `key_cost` per `input keyevent` and
    `start_cost` per `am start -W` (0.04 and 0.15 s on an emulator; the defaults are a TV's)."""
    to_dock = 19 * (0.15 + key_cost)
    t = 3.0
    if "cold" in scenarios:
        t += runs * (settle * 4 + 2 * start_cost + 0.2)
    if "warm" in scenarios:
        t += start_cost + settle * 2 + runs * (settle * 2 + 2 * start_cost + 0.3)
    if "jank" in scenarios:
        t += start_cost + 2.5 + to_dock + 1.5 + rounds * (n_keys * (key_interval + key_cost) + 1.3)
    if "dock" in scenarios:
        t += start_cost + 2.5 + to_dock + 1.5 + 2 * (dock_interval + key_cost) + 2.0 + \
            rounds * (dock_moves * (dock_interval + key_cost) + 4.0)
    if "mem" in scenarios:
        t += start_cost + 3.0 + 3 * 1.5
    return t


def fmt_s(s):
    s = int(round(s))
    return "%d s" % s if s < 60 else "%d min %02d s" % (s // 60, s % 60)


def render(plan, n_keys=16, runs=10, rounds=3, verbose=False, command="python3 tools/perf.py"):
    """The plan as text for a person."""
    scen, files = plan["scenarios"], {f["path"] for f in plan["files"]}
    lines = ["Scope of %s...%s (merge base %s): %d file%s changed" % (
        plan.get("base", "?"), plan.get("head", "?"), plan.get("merge_base", "?"), len(files),
        "" if len(files) == 1 else "s"), ""]
    if not files:
        lines.append("Nothing changed: nothing to measure.")
        return "\n".join(lines)

    def groups(s):
        g = plan["reasons"].get(s, [])
        shown = g if verbose else g[:4]
        out = []
        for r in shown:
            where = r["where"] if verbose or len(r["where"]) <= 3 else r["where"][:3] + ["+%d" % (len(r["where"]) - 3)]
            out.append("%s: %s" % (", ".join(where), r["why"]))
        if len(g) > len(shown):
            out.append("... and %d more reasons (perf.py plan -v)" % (len(g) - len(shown)))
        return out

    if scen:
        keys = plan["keys"]
        what = ", ".join(s + (" (keys: %s)" % ", ".join(keys) if s == "jank" and keys != ["default"] else "")
                         for s in scen)
        lines.append("Measure: %s" % what)
        lines.append("  about %s a build on a TV; all five scenarios take about %s" % (
            fmt_s(estimate_s(scen, runs, rounds, n_keys=n_keys)), fmt_s(estimate_s(SCENARIOS, runs, rounds))))
        lines.append("Why:")
        for s in scen:
            for i, g in enumerate(groups(s)):
                lines.append("  %-6s %s" % (s if i == 0 else "", g))
    elif plan.get("variants"):
        lines.append("Measure: nothing as the launcher ships.")
    else:
        lines.append({"none": "Nothing to measure or run: nothing here changes what the launcher does.",
                      "local": "Nothing to measure: the build and the unit tests cover it.",
                      "emulator": "Nothing to measure: no perf.py scenario covers what changed. Check it on your "
                                  "emulator.",
                      "tv": "Nothing perf.py measures, but it needs the TV (below)."}[plan["check"]])
    for v in plan.get("variants", []):
        lines.append("Also with --variant '%s': %s" % (v["variant"], ", ".join(
            s + (" (keys: %s)" % ", ".join(v["keys"]) if s == "jank" and v["keys"] != ["default"] else "")
            for s in v["scenarios"])))
        where = [f for f in plan["files"] if any(x["variant"] == v["variant"] for x in f.get("variants", []))]
        lines.append("  for %s" % ", ".join(dict.fromkeys(label(Hit(f["path"], f["member"], None, "")) for f in where)))
    quiet = groups("")
    if quiet:
        lines += ["", "Not measured:"] + ["  " + g for g in quiet]
    lines.append("")
    if any(f.startswith("launcher/") for f in files) and plan["check"] != "none":
        lines.append("First: cd launcher && ./gradlew assembleDebug assembleRelease testDebugUnitTest")
    elif any(f.startswith("tools/") for f in files):
        lines.append("First: python3 -m unittest discover -s tools/tests")
    runs = ([("", scen, plan["keys"])] if scen else []) + [
        (v["variant"], v["scenarios"], v["keys"]) for v in plan.get("variants", [])]
    if runs:
        lines.append("Then on your emulator (--quick on both), and once it looks right, on the TV: main's numbers "
                     "(cached, or measured now), your build, then yours:")
    for variant, sc, keys in runs:
        cmd = "--scenarios " + ",".join(sc)
        if "jank" in sc and keys != ["default"]:
            cmd += " --keys " + ",".join(keys)
        var = " --variant '%s'" % variant if variant else ""
        lines.append("  %s baseline ensure --scenarios auto%s" % (command, var))
        lines.append("  %s run --label <name>%s --scenarios auto%s      # = %s"
                     % (command, "-art" if variant == ART else "-variant" if variant else "", var, cmd))
    if runs:
        lines.append("  %s compare --against main tools/perf-results/<name>.json" % command)
    traces = _trace_commands(plan, command)
    if traces:
        lines.append("If a number moves, or for what no scenario measures:")
        lines.extend("  " + t for t in traces)
    return "\n".join(lines)


def _trace_commands(plan, command):
    out = []
    for t in plan["traces"]:
        action, _, keys = t.partition(":")
        if action == "keys":
            out.append("%s trace --action keys%s" % (command, " --keys " + keys if keys else ""))
        elif action == "dream":
            out.append("%s trace --action none --seconds 70   # while the screensaver runs" % command)
        elif action == "launch":
            out.append("%s trace --action keys --keys CENTER --seconds 6   # opens the focused app" % command)
        elif action == "remote":
            out.append("%s trace --action none --seconds 30   # on the TV, while you press the remote's keys"
                       % command)
        else:
            out.append("%s trace --action %s" % (command, action))
    return list(dict.fromkeys(out))
