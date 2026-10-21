"""Offline tests for tools/perf_scope.py (which perf.py scenarios a diff needs) and its wiring in perf.py.

    python3 -m unittest discover -s tools/tests -v
"""
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))
import perf  # noqa: E402
import perf_scope as ps  # noqa: E402

MAIN = ps.JAVA + "MainActivity.java"

FAKE_MAIN = """package com.gathin.tvhome;

import android.app.Activity;

/** Home. */
public class MainActivity extends Activity implements AppAdapter.TileListener {
    private static final long GLOW_BLEND_MS = 400L;
    private static final int SHELF_TEXT = 0, SHELF_GLOW = 1;
    private ProfileCorner corner;
    private TopShelf topShelf;
    private AppAdapter adapter;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        corner = findViewById(R.id.corner);
        topShelf = new TopShelf(this);
        adapter.setOnOrderSaved(() -> topShelf.setDock(adapter.dockApps()));
        View.OnKeyListener pillKeys = (v, keyCode, event) -> {
            if (keyCode == 19) return true;
            return false;
        };
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        // the network pill
        int x = 1;
    }

    private void showHero(int position) {
        topShelf.show(position);
        Fancy.paint();
        String s = "{ not a brace } " + '{';
    }
}
"""


def lines_of(text, needle):
    """1-based numbers of the lines containing `needle`."""
    found = [i + 1 for i, t in enumerate(text.split("\n")) if needle in t]
    assert found, needle
    return found


def change(path, old, new, needle=None, old_needle=None):
    """A Change to `path` whose changed lines are those containing the needles."""
    return ps.Change(path, old, new, lines_of(old, old_needle) if old_needle else ([] if old is not None else None),
                     lines_of(new, needle) if needle else ([] if new is not None else None))


def planner(new=None, old=None):
    new, old = new or {}, old or new or {}
    return ps.Planner(old=(old.get, sorted(old)), new=(new.get, sorted(new)))


def plan_for(*changes, sources=None):
    return ps.build_plan(list(changes), planner(sources or {}))


class ReadingJavaTests(unittest.TestCase):
    SRC = """package x;
/** A { brace in a comment. */
final class Thing<T> implements Runnable {
    private static final int A = 1, B = 2;   // two at once
    @SuppressWarnings("unused")
    private final Map<String, Integer> map = new HashMap<>();
    static {
        init();
    }
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            go("}");
        }
    };
    private final Runnable later = () -> {
        go('{');
    };

    interface Listener {
        void onThing(int a, View b);
    }

    @Override
    public void run() {
        grid.post(() -> {
            go();
        });
        View.OnKeyListener keys = (v, k, e) -> {
            return k == 1;
        };
        if (a) {
            other.call(new Thing.Listener() {
                public void onThing(int a, View b) { }
            });
        }
    }

    static final class Holder {
        float scale;

        void settle(boolean focused) {
            scale = 1f;
        }
    }
}
"""

    def test_mask_keeps_offsets(self):
        code, bare = ps.mask_java(self.SRC)
        self.assertEqual(len(code), len(self.SRC))
        self.assertEqual(code.count("\n"), self.SRC.count("\n"))
        self.assertNotIn("brace in a comment", code)
        self.assertIn('go("}")', code)          # comments only
        self.assertIn('go(" ")', bare)          # strings too
        self.assertIn("go(' ')", bare)

    def test_members(self):
        spans = {s.path: s for s in ps.java_spans(self.SRC)}
        for name in ("A", "B", "map", "static", "tick", "later", "Listener", "Listener.onThing", "run",
                     "run>grid.post", "run>keys", "Holder", "Holder.scale", "Holder.settle"):
            self.assertIn(name, spans)
        self.assertTrue(spans["A"].private and spans["tick"].private)
        self.assertFalse(spans["run"].private)
        self.assertEqual(spans["map"].start, 5)      # from its annotation
        self.assertEqual((spans["tick"].start, spans["tick"].end), (10, 15))
        self.assertEqual(ps.innermost(list(spans.values()), 13).path, "tick")
        self.assertEqual(ps.innermost(list(spans.values()), 27).path, "run>grid.post")
        self.assertEqual(ps.innermost(list(spans.values()), 30).path, "run>keys")
        self.assertEqual(ps.innermost(list(spans.values()), 33).path, "run")   # in an if block: its method's
        self.assertEqual(ps.innermost(list(spans.values()), 43).path, "Holder.settle")
        self.assertIsNone(ps.innermost(list(spans.values()), 3))              # the class's declaration

    def test_statements(self):
        src = ps.Source(MAIN, FAKE_MAIN)
        ln = lines_of(FAKE_MAIN, "adapter.setOnOrderSaved")[0]
        self.assertEqual(src.statement_idents(ln),
                         {("adapter", "setOnOrderSaved"), ("topShelf", "setDock"), ("adapter", "dockApps")})
        self.assertEqual(src.types["corner"], "ProfileCorner")
        self.assertEqual(src.statement_idents(lines_of(FAKE_MAIN, "        };")[0]), set())


class ReadingXmlTests(unittest.TestCase):
    def test_values(self):
        src = '<resources>\n  <!-- <string name="gone">x</string> -->\n  <string name="a">A</string>\n' \
              '  <style name="Text.Big" parent="Text">\n    <item name="android:textSize">20sp</item>\n' \
              '  </style>\n</resources>\n'
        self.assertEqual([(s.path, s.start, s.end) for s in ps.xml_spans(src)],
                         [("a", 3, 3), ("Text.Big", 4, 6)])

    def test_layout(self):
        src = """<FrameLayout android:id="@+id/root">
    <FrameLayout android:padding="4dp">
        <View android:id="@+id/grid" />
    </FrameLayout>
    <FrameLayout android:id="@+id/topArea">
        <TextView android:text="a" />
    </FrameLayout>
</FrameLayout>
"""
        spans = ps.xml_spans(src, "layout")
        self.assertEqual([s.path for s in spans], ["root", "root/grid", "root/grid/grid", "root/topArea",
                                                   "root/topArea"])
        self.assertEqual(ps.innermost(spans, 6).path, "root/topArea")

    def test_manifest(self):
        src = '<manifest>\n    <uses-permission android:name="android.permission.INTERNET" />\n</manifest>\n'
        self.assertEqual(ps.innermost(ps.xml_spans(src, "manifest"), 2).path,
                         "uses-permission:android.permission.INTERNET")


class GitDiffTests(unittest.TestCase):
    def test_parse_hunks(self):
        diff = """diff --git a/a.java b/a.java
index 1..2 100644
--- a/a.java
+++ b/a.java
@@ -3 +3,2 @@ class A {
-x
+y
+z
@@ -10,2 +11,0 @@
-p
-q
diff --git a/new.txt b/new.txt
new file mode 100644
--- /dev/null
+++ b/new.txt
@@ -0,0 +1 @@
+hello
diff --git a/gone.txt b/gone.txt
deleted file mode 100644
--- a/gone.txt
+++ /dev/null
@@ -1 +0,0 @@
-bye
diff --git a/font.ttf b/font.ttf
Binary files a/font.ttf and b/font.ttf differ
"""
        h = ps.parse_hunks(diff)
        self.assertEqual(h["a.java"], ([3, 10, 11], [3, 4]))
        self.assertEqual(h["new.txt"], ([], [1]))
        self.assertEqual(h["gone.txt"], ([1], []))
        self.assertEqual(h["font.ttf"], (None, None))


class PlanTests(unittest.TestCase):
    def assertPlan(self, plan, scenarios, keys=(), check=None):
        self.assertEqual(plan["scenarios"], list(scenarios), plan["files"])
        self.assertEqual(plan["keys"], list(keys), plan["files"])
        if check:
            self.assertEqual(plan["check"], check)

    def test_docs_only(self):
        p = plan_for(ps.Change("docs/performance.md", "a\n", "b\n", [1], [1]),
                     ps.Change("README.md", "a\n", "b\n", [1], [1]))
        self.assertPlan(p, [], check="none")
        self.assertFalse(p["tv"])
        self.assertIn("Nothing to measure or run", ps.render(p))

    def test_tools(self):
        self.assertPlan(plan_for(ps.Change("tools/tests/test_x.py", "a\n", "b\n", [1], [1])), [], check="local")
        self.assertPlan(plan_for(ps.Change("tools/tv.py", "a\n", "b\n", [1], [1])), [], check="emulator")

    def test_build_changes_everything(self):
        p = plan_for(ps.Change("launcher/app/build.gradle", "a\n", "b\n", [1], [1]))
        self.assertPlan(p, ps.SCENARIOS, ["default"], check="tv")

    def test_unknown_launcher_file_is_everything(self):
        self.assertPlan(plan_for(ps.Change("launcher/app/lint.xml", None, "x\n", None, None)), ps.SCENARIOS,
                        ["default"])

    def test_shelf_order_is_the_top_shelf(self):
        # What shows next and what stays decoded: the dock's moves and memory, not everything.
        p = plan_for(ps.Change(ps.JAVA + "ShelfOrder.java", None, "final class ShelfOrder {\n}\n", None, None))
        self.assertPlan(p, ["dock", "mem"])

    def test_home_settings(self):
        # What each app's top shelf shows: the dock's moves and memory.
        p = plan_for(ps.Change(ps.JAVA + "ShelfChoice.java", None, "final class ShelfChoice {\n}\n", None, None))
        self.assertPlan(p, ["dock", "mem"])
        # The settings themselves: on screen only while you use them, so no scenario.
        for path in (ps.JAVA + "SettingsActivity.java", ps.JAVA + "ShelfPreview.java",
                     ps.RES + "layout/settings_row.xml", ps.RES + "color/settings_text.xml"):
            self.assertPlan(plan_for(ps.Change(path, "a\n", "b\n", [1], [1])), [], check="emulator")
        # Its tile in the grid loads and binds with the other apps'.
        self.assertPlan(plan_for(ps.Change(ps.RES + "drawable/settings_banner.xml", "a\n", "b\n", [1], [1])),
                        ["cold", "jank", "mem"], ["grid"])

    def test_class_rule(self):
        old = "class ProfileCorner {\n    void open() {\n        a();\n    }\n}\n"
        new = old.replace("a();", "b();")
        self.assertPlan(plan_for(change(ps.JAVA + "ProfileCorner.java", old, new, "b();", "a();")), ["jank"],
                        ["corner"])

    def test_comments_only(self):
        old = "class ProfileCorner {\n    // old words\n    void open() {\n    }\n}\n"
        new = old.replace("old words", "new words")
        p = plan_for(change(ps.JAVA + "ProfileCorner.java", old, new, "new words", "old words"))
        self.assertPlan(p, [], check="local")
        p = plan_for(change(ps.RES + "layout/item_app.xml", "<a>\n<!-- x -->\n</a>\n", "<a>\n<!-- y -->\n</a>\n",
                            "y", "x"))
        self.assertPlan(p, [])

    def test_member_rules(self):
        new = FAKE_MAIN.replace("topShelf.show(position);", "topShelf.show(position + 1);")
        self.assertPlan(plan_for(change(MAIN, FAKE_MAIN, new, "position + 1", "topShelf.show(position)")), ["dock"])
        new = FAKE_MAIN.replace("return true;", "return false;")
        self.assertPlan(plan_for(change(MAIN, FAKE_MAIN, new, "== 19) return false", "== 19) return true")),
                        ["jank"], ["corner"])

    def test_private_member_by_its_users(self):
        new = FAKE_MAIN.replace("int x = 1;", "int x = 2;")
        p = plan_for(change(MAIN, FAKE_MAIN, new, "x = 2", "x = 1"))
        self.assertPlan(p, ["cold", "warm"])
        self.assertIn("used by MainActivity.onResume", p["files"][0]["why"])

    def test_on_create_by_statement(self):
        new = FAKE_MAIN.replace("corner = findViewById(R.id.corner);", "corner = findViewById(R.id.corner2);")
        self.assertPlan(plan_for(change(MAIN, FAKE_MAIN, new, "corner2", "R.id.corner)")),
                        ["cold", "jank", "mem"], ["corner"])
        new = FAKE_MAIN.replace("new TopShelf(this)", "new TopShelf(this, 2)")
        self.assertPlan(plan_for(change(MAIN, FAKE_MAIN, new, "this, 2", "TopShelf(this)")), ["cold", "dock", "mem"])
        # x.method() on a class with member rules: that method's rule
        new = FAKE_MAIN.replace("adapter.dockApps()", "adapter.dockApps(1)")
        self.assertPlan(plan_for(change(MAIN, FAKE_MAIN, new, "dockApps(1)", "dockApps()")),
                        ["cold", "warm", "jank", "dock", "mem"], ["arrange"])
        # a statement naming nothing it can place: everything
        new = FAKE_MAIN.replace("super.onCreate(b);", "super.onCreate(b);\n        mystery(42);")
        p = ps.build_plan([ps.Change(MAIN, FAKE_MAIN, new, [], lines_of(new, "mystery"))], planner())
        self.assertPlan(p, ps.SCENARIOS, ["default"])

    def test_class_declaration_and_imports(self):
        new = FAKE_MAIN.replace("implements AppAdapter.TileListener {", "implements AppAdapter.TileListener, X {")
        new = new.replace("import android.app.Activity;", "import android.app.Activity;\nimport a.X;")
        self.assertPlan(plan_for(ps.Change(MAIN, FAKE_MAIN, new, lines_of(FAKE_MAIN, "implements"),
                                           lines_of(new, "implements") + lines_of(new, "import a.X"))), [])
        # a new superclass is no small thing
        new = FAKE_MAIN.replace("extends Activity", "extends FragmentActivity")
        self.assertPlan(plan_for(change(MAIN, FAKE_MAIN, new, "extends", "extends")), ps.SCENARIOS, ["default"])

    def test_new_class_by_what_uses_it(self):
        fancy = "final class Fancy {\n    static void paint() {\n    }\n}\n"
        p = ps.build_plan([ps.Change(ps.JAVA + "Fancy.java", None, fancy, None, None)],
                          planner({MAIN: FAKE_MAIN, ps.JAVA + "Fancy.java": fancy}))
        self.assertPlan(p, ["dock"])
        self.assertIn("MainActivity.showHero", p["files"][0]["why"])
        lonely = ps.build_plan([ps.Change(ps.JAVA + "Lonely.java", None, fancy, None, None)], planner({}))
        self.assertPlan(lonely, ps.SCENARIOS, ["default"])

    def test_resources(self):
        top_bar = '<LinearLayout>\n    <ImageView android:src="@drawable/ic_new" />\n</LinearLayout>\n'
        icon = "<vector>\n</vector>\n"
        p = ps.build_plan([ps.Change(ps.RES + "drawable/ic_new.xml", None, icon, None, None)],
                          planner({ps.RES + "layout/top_bar.xml": top_bar, ps.RES + "drawable/ic_new.xml": icon}))
        self.assertPlan(p, ["cold", "jank"], ["corner"])
        config = '<resources>\n    <string name="top_bar_style">tvos</string>\n    <bool name="x">true</bool>\n' \
                 '</resources>\n'
        new = config.replace("tvos", "google")
        self.assertPlan(plan_for(change(ps.RES + "values/config.xml", config, new, "google", "tvos")),
                        ["cold", "jank"], ["corner"])
        # a style without a rule: what uses it
        styles = '<resources>\n    <style name="Text.Fancy">\n        <item name="a">1</item>\n    </style>\n' \
                 '</resources>\n'
        item_app = '<FrameLayout>\n    <TextView style="@style/Text.Fancy" />\n</FrameLayout>\n'
        new = styles.replace(">1<", ">2<")
        p = ps.build_plan([change(ps.RES + "values/styles.xml", styles, new, ">2<", ">1<")],
                          planner({ps.RES + "values/styles.xml": new, ps.RES + "layout/item_app.xml": item_app}))
        self.assertPlan(p, ["cold", "jank", "mem"], ["grid"])

    def test_layout_by_view(self):
        layout = """<FrameLayout android:id="@+id/root">
    <FrameLayout android:id="@+id/topArea">
        <com.gathin.tvhome.ProfileCorner android:id="@+id/corner"
            android:padding="4dp" />
    </FrameLayout>
    <TextView android:id="@+id/heroName" android:alpha="0" />
</FrameLayout>
"""
        path = ps.RES + "layout/activity_main.xml"
        new = layout.replace("4dp", "6dp")
        self.assertPlan(plan_for(change(path, layout, new, "6dp", "4dp")), ["cold", "jank"], ["corner"])
        new = layout.replace('android:alpha="0"', 'android:alpha="1"')
        self.assertPlan(plan_for(change(path, layout, new, 'alpha="1"', 'alpha="0"')), ["dock", "mem"])
        new = layout.replace('<FrameLayout android:id="@+id/root">', '<FrameLayout android:id="@+id/root" a="b">')
        self.assertPlan(plan_for(change(path, layout, new, 'a="b"', "root")), ps.SCENARIOS, ["default"])

    def test_manifest(self):
        old = '<manifest>\n    <application />\n</manifest>\n'
        new = old.replace("<application />", '<uses-permission android:name="android.permission.INTERNET" />\n'
                                             '    <application />')
        path = ps.SOURCE + "AndroidManifest.xml"
        self.assertPlan(plan_for(change(path, old, new, "INTERNET")), ["dock", "mem"])
        new = old.replace("<application />", '<uses-permission android:name="android.permission.DUMP" />\n'
                                             '    <application />')
        p = plan_for(change(path, old, new, "DUMP"))
        self.assertPlan(p, [], check="tv")
        self.assertTrue(p["tv"])
        new = old.replace("<application />", '<application android:largeHeap="true" />')
        self.assertPlan(plan_for(change(path, old, new, "largeHeap", "application")), ps.SCENARIOS, ["default"])

    def test_keys_and_traces(self):
        p = plan_for(ps.Change(ps.JAVA + "GridScroller.java", "a\n", "b\n", [1], [1]),
                     ps.Change(ps.JAVA + "ProfileCorner.java", "a\n", "b\n", [1], [1]))
        self.assertPlan(p, ["jank"], ["grid", "corner"])
        self.assertEqual(p["traces"], ["keys:grid", "keys:corner"])
        text = ps.render(p)
        self.assertIn("--scenarios jank --keys grid,corner", text)
        self.assertIn("trace --action keys --keys corner", text)

    def test_needs_the_tv_without_a_scenario(self):
        p = plan_for(ps.Change(ps.JAVA + "HomeButtonService.java", "a\n", "b\n", [1], [1]))
        self.assertPlan(p, [], check="tv")
        self.assertTrue(p["tv"])
        self.assertIn("--seconds 30", ps.render(p))
        p = plan_for(ps.Change(ps.JAVA + "VolumeHold.java", None, "a\n", None, None))
        self.assertPlan(p, [], check="tv")

    def test_variants(self):
        p = plan_for(ps.Change(ps.JAVA + "GlassTestArt.java", "a\n", "b\n", [1], [1]))
        self.assertPlan(p, [])
        self.assertTrue(p["tv"])
        self.assertEqual(p["variants"], [{"variant": ps.ART, "scenarios": ["dock"], "keys": []}])
        self.assertIn("--scenarios auto --variant '%s'" % ps.ART, ps.render(p))
        p = plan_for(ps.Change(ps.JAVA + "LiquidGlass.java", "a\n", "b\n", [1], [1]),
                     ps.Change(ps.JAVA + "CornerShade.java", "a\n", "b\n", [1], [1]))
        self.assertPlan(p, ["jank", "dock", "mem"], ["default", "corner"])
        self.assertEqual(p["variants"], [{"variant": ps.ART, "scenarios": ["jank", "dock"], "keys": ["corner"]}])

    def test_plan_is_json(self):
        p = plan_for(ps.Change(ps.JAVA + "TopShelfView.java", "a\n", "b\n", [1], [1]))
        self.assertEqual(json.loads(json.dumps(p))["reasons"]["dock"][0]["where"], ["TopShelfView"])


class MapTests(unittest.TestCase):
    def test_presets_exist(self):
        self.assertEqual(set(ps.PRESETS), set(perf.KEY_PRESETS))
        effects = [e for _, e in ps.FILE_RULES] + [e for r in ps.MEMBER_RULES.values() for _, e in r]
        for e in effects:
            self.assertTrue(set(e.scenarios) <= set(ps.SCENARIOS), e)
            self.assertTrue(set(e.keys) <= set(ps.PRESETS), e)
            self.assertIn(e.check, ps.CHECKS)
            if e.keys:
                self.assertIn("jank", e.scenarios, e)
        self.assertEqual(ps.SCENARIOS, perf.ALL_SCENARIOS)

    def test_presets_are_round_trips_from_the_first_dock_app(self):
        for name, keys in perf.KEY_PRESETS.items():
            k = keys.split()
            self.assertTrue(all(x in perf.KEYCODES for x in k), name)
            if name in ("default", "grid", "dock"):
                self.assertEqual(k.count("UP"), k.count("DOWN"), name)
                self.assertEqual(k.count("LEFT"), k.count("RIGHT"), name)
        self.assertEqual(perf.KEY_PRESETS["corner"].split()[-1], "BACK")   # Back from the top right: the dock app
        arrange = perf.KEY_PRESETS["arrange"].split()
        # on a shared TV, never put an app down (CENTER while holding it saves the order): end on Back, Back
        self.assertEqual(arrange[-2:], ["BACK", "BACK"])
        self.assertEqual(arrange.count("CENTER"), 2)
        self.assertLess(max(i for i, x in enumerate(arrange) if x == "CENTER"), arrange.index("BACK"))

    def test_the_real_launcher_resolves(self):
        """Every file of the launcher as it is, changed as a whole, resolves without an error."""
        root = os.path.dirname(os.path.dirname(HERE))
        src = os.path.join(root, ps.SOURCE)
        if not os.path.isdir(src):
            self.skipTest("no launcher sources")
        files = {}
        for d, _, names in os.walk(src):
            for n in names:
                p = os.path.relpath(os.path.join(d, n), root).replace(os.sep, "/")
                if p.endswith((".java", ".xml")):
                    with open(os.path.join(root, p), encoding="utf-8") as f:
                        files[p] = f.read()
        pl = planner(files)
        for p, text in files.items():
            hits = pl.hits(ps.Change(p, text, text, None, None))
            self.assertTrue(hits, p)

    def test_estimate(self):
        full = ps.estimate_s(ps.SCENARIOS)
        self.assertTrue(180 < full < 360, full)
        self.assertLess(ps.estimate_s(["jank"]), ps.estimate_s(["jank", "dock"]))
        self.assertLess(ps.estimate_s(["cold"], runs=5), ps.estimate_s(["cold"], runs=10))
        self.assertEqual(ps.fmt_s(75), "1 min 15 s")


@unittest.skipUnless(shutil.which("git"), "needs git")
class GitTests(unittest.TestCase):
    def setUp(self):
        self.repo = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.repo)
        self.git("init", "-q", "-b", "main")
        self.git("config", "user.email", "t@example.com")
        self.git("config", "user.name", "t")
        self.write(MAIN, FAKE_MAIN)
        self.write("README.md", "hi\n")
        self.git("add", "-A")
        self.git("commit", "-q", "-m", "base")
        self.git("checkout", "-q", "-b", "fix")

    def git(self, *args):
        return subprocess.run(("git",) + args, cwd=self.repo, check=True, capture_output=True, text=True).stdout

    def write(self, path, text):
        full = os.path.join(self.repo, path)
        os.makedirs(os.path.dirname(full), exist_ok=True)
        with open(full, "w") as f:
            f.write(text)

    def test_committed_and_working_tree(self):
        self.write(MAIN, FAKE_MAIN.replace("topShelf.show(position);", "topShelf.show(position, 1);"))
        self.git("commit", "-qam", "shelf")
        p = ps.plan("main", "HEAD", self.repo)
        self.assertEqual(p["scenarios"], ["dock"])
        self.assertEqual(p["head"], "HEAD")
        # the working tree: uncommitted and new files count too
        self.write("docs/new.md", "notes\n")
        self.write(ps.JAVA + "ProfileCorner.java", "class ProfileCorner {\n}\n")
        p = ps.plan("main", None, self.repo)
        self.assertEqual(p["scenarios"], ["jank", "dock"])
        self.assertEqual(p["keys"], ["corner"])
        self.assertIn("docs/new.md", [f["path"] for f in p["files"]])

    def test_nothing_changed(self):
        p = ps.plan("main", None, self.repo)
        self.assertEqual((p["scenarios"], p["files"]), ([], []))
        self.assertIn("Nothing changed", ps.render(p))

    def test_bad_base(self):
        with self.assertRaises(RuntimeError):
            ps.plan("no-such-branch", None, self.repo)


class PerfWiringTests(unittest.TestCase):
    def args(self, *argv):
        return perf.build_parser().parse_args(["run", "--label", "x"] + list(argv))

    def test_resolve_keys(self):
        self.assertEqual(perf.resolve_keys(None), perf.DEFAULT_KEYS)
        self.assertEqual(perf.resolve_keys("corner"), perf.KEY_PRESETS["corner"])
        self.assertEqual(perf.resolve_keys("grid,corner"), perf.KEY_PRESETS["grid"] + " " + perf.KEY_PRESETS["corner"])
        self.assertEqual(perf.resolve_keys("UP down"), "UP down")
        for bad in ("sideways", "UP sideways", "corner,UP", ""):
            with self.assertRaises(SystemExit):
                perf.resolve_keys(bad)

    def test_defaults_and_quick(self):
        a = self.args()
        self.assertIsNone(perf.resolve_run_args(a, log=lambda m: None))
        self.assertEqual((a.scenarios, a.runs, a.rounds, a.keys), ("cold,warm,jank,dock,mem", 10, 3, perf.DEFAULT_KEYS))
        a = self.args("--quick", "--rounds", "4")
        perf.resolve_run_args(a, log=lambda m: None)
        self.assertEqual((a.runs, a.rounds), (5, 4))

    def test_scenarios_auto(self):
        fake = {"base": "origin/main", "head": "working tree", "merge_base": "abc", "scenarios": ["jank", "mem"],
                "keys": ["corner"]}
        logs = []
        with mock.patch.object(perf.perf_scope, "plan", return_value=fake) as plan:
            a = self.args("--scenarios", "auto", "--base", "main")
            self.assertIs(perf.resolve_run_args(a, log=logs.append), fake)
        self.assertEqual(plan.call_args[0][0], "main")
        self.assertEqual((a.scenarios, a.keys), ("jank,mem", perf.KEY_PRESETS["corner"]))
        self.assertIn("jank, mem (keys: corner)", logs[0])
        # an explicit --keys wins
        with mock.patch.object(perf.perf_scope, "plan", return_value=fake):
            a = self.args("--scenarios", "auto", "--keys", "grid")
            perf.resolve_run_args(a, log=logs.append)
        self.assertEqual(a.keys, perf.KEY_PRESETS["grid"])

    def test_scenarios_auto_with_a_variant(self):
        fake = {"base": "origin/main", "head": "working tree", "merge_base": "abc", "scenarios": ["jank", "mem"],
                "keys": ["corner"], "variants": [{"variant": ps.ART, "scenarios": ["dock"], "keys": []}]}
        logs = []
        with mock.patch.object(perf.perf_scope, "plan", return_value=fake):
            a = self.args("--scenarios", "auto", "--variant", " --ez  glass_test_art true")
            perf.resolve_run_args(a, log=logs.append)
            self.assertEqual((a.scenarios, a.keys), ("dock", perf.DEFAULT_KEYS))
            self.assertIn("also as it ships: jank,mem", logs[-1])
            a = self.args("--scenarios", "auto")
            perf.resolve_run_args(a, log=logs.append)
            self.assertEqual(a.scenarios, "jank,mem")
            self.assertIn("also --variant '%s': dock" % ps.ART, logs[-1])
            a = self.args("--scenarios", "auto", "--variant", "--ei liquid_glass 50")
            perf.resolve_run_args(a, log=logs.append)
            self.assertEqual(a.scenarios, "jank,mem")
            self.assertIn("no runs with --variant", logs[-2])

    def test_run_without_scenarios_does_not_connect(self):
        fake = {"base": "origin/main", "head": "working tree", "merge_base": "abc", "scenarios": [], "keys": []}
        logs = []
        with mock.patch.object(perf.perf_scope, "plan", return_value=fake), \
                mock.patch.object(perf, "connect", side_effect=AssertionError("connected")):
            self.assertIsNone(perf.main(["run", "--label", "x", "--scenarios", "auto"], log=logs.append))
        self.assertIn("nothing to measure on a TV", logs[0])

    def test_plan_command(self):
        fake = ps.build_plan([ps.Change(ps.JAVA + "TopShelfView.java", "a\n", "b\n", [1], [1])], planner(),
                             base="origin/main", head="working tree", merge_base="abc")
        with mock.patch.object(perf.perf_scope, "plan", return_value=fake), mock.patch("builtins.print") as out:
            perf.main(["plan"])
        self.assertIn("Measure: dock, mem", out.call_args[0][0])
        with mock.patch.object(perf.perf_scope, "plan", return_value=fake), mock.patch("builtins.print") as out:
            perf.main(["plan", "--json"])
        self.assertEqual(json.loads(out.call_args[0][0])["scenarios"], ["dock", "mem"])


from test_perf_baseline import Base, FakeDevice  # noqa: E402


class BaselineTests(Base):
    """baseline ensure --scenarios auto measures main for exactly what run --scenarios auto measures."""

    def test_ensure_and_run_follow_the_same_plan(self):
        fake = {"base": "origin/main", "head": "working tree", "merge_base": "abc", "scenarios": ["jank", "mem"],
                "keys": ["corner"], "variants": [{"variant": ps.ART, "scenarios": ["dock"], "keys": []}]}
        with mock.patch.object(perf.perf_scope, "plan", return_value=fake):
            main, logs, _ = self.ensure(FakeDevice(), "--scenarios", "auto")
            self.assertEqual(sorted(main["baseline"]["scenarios"]), ["jank", "mem"])
            self.assertEqual(main["params"]["keys"], perf.KEY_PRESETS["corner"])
            self.assertTrue(any("also --variant '%s': dock" % ps.ART in m for m in logs))
            mine = self.run_mine(FakeDevice(), "mine", "--scenarios", "auto")
            _, _, table = self.perf(None, "compare", "--against", "main", mine)
            self.assertIn("| Janky frames (D-pad moves) |", table)
            art, _, _ = self.ensure(FakeDevice(), "--scenarios", "auto", "--variant", ps.ART)
            self.assertEqual((sorted(art["baseline"]["scenarios"]), art["params"]["variant"]), (["dock"], ps.ART))


if __name__ == "__main__":
    unittest.main()
