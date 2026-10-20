"""Offline tests for tools/provenance.py: the build stamp, launcher/ tree hashes in a scratch git repository, and the
checks that keep a comparison against the main that ships.

    python3 -m unittest discover -s tools/tests -v
"""
import os
import shutil
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))
import provenance  # noqa: E402

M, N = provenance.MISMATCH, provenance.NOTE
HAS_GIT = shutil.which("git") is not None


class Repo:
    """A scratch git repository with launcher/ and docs/, and origin/main as a plain ref."""

    def __init__(self, test):
        self.root = tempfile.mkdtemp()
        test.addCleanup(shutil.rmtree, self.root, True)
        self.git("init", "-q", "-b", "main")
        for k, v in (("user.email", "t@example.com"), ("user.name", "t"), ("commit.gpgsign", "false")):
            self.git("config", k, v)

    def git(self, *args):
        return subprocess.run(["git", "-C", self.root] + list(args), check=True, capture_output=True,
                              text=True).stdout.strip()

    def write(self, path, text):
        full = os.path.join(self.root, path)
        os.makedirs(os.path.dirname(full), exist_ok=True)
        with open(full, "w") as f:
            f.write(text)

    def commit(self, files, msg="c"):
        for path, text in files.items():
            self.write(path, text)
        self.git("add", "-A")
        self.git("commit", "-q", "-m", msg)
        return self.git("rev-parse", "HEAD")

    def tree(self, ref):
        return self.git("rev-parse", ref + ":launcher")

    def main_at(self, ref):
        self.git("update-ref", "refs/remotes/origin/main", ref)

    def result(self, lbl, ref, dirty=False, **kw):
        return make_result(lbl, self.git("rev-parse", ref), self.tree(ref), dirty, **kw)


SHIPPED = {"default_config": 1, "glass": 0, "glass_default": 0, "glass_saved": 70, "glass_tier": "lens", "test_art": 0}
ONE = {"window": 1.0, "transition": 1.0, "animator": 1.0}


def make_result(lbl, commit, tree, dirty=False, debuggable=False, compiler="speed", scales=None, launcher=None,
                other_app="com.tcl.ui_mediaCenter/.Main", model="Smart TV Pro", scenarios=("cold", "warm", "jank")):
    stamp = {"commit": commit, "launcher_tree": tree, "dirty": dirty} if commit else None
    r = {"label": lbl,
         "device": {"manufacturer": "TCL", "model": model, "android": "14"},
         "build": {"version_name": "2.0", "provenance": stamp, "debuggable": debuggable,
                   "compiler_filter": compiler},
         "params": {"runs": 10, "rounds": 3, "settle_s": 1.5, "other_app": other_app},
         "config": {"animation_scales": dict(scales or ONE), "launcher": dict(launcher or SHIPPED)}}
    for s in scenarios:
        r[s] = {"summary": {}, "keys": "RIGHT LEFT", "interval_s": 0.35} if s == "jank" else {"summary": {}}
    return r


def levels(problems, level):
    return [t for lv, t in problems if lv == level]


class StampTests(unittest.TestCase):
    def test_parse(self):
        c, t = "3fac1fb61c1e53c7db0c9adea4c6fee546e41b90", "e9fb9311e5b786a1670d47e8a7782c932cd82bcd"
        self.assertEqual(provenance.parse_stamp("2.0+commit-%s.launcher-%s" % (c, t)),
                         {"commit": c, "launcher_tree": t, "dirty": False})
        self.assertTrue(provenance.parse_stamp("2.0+commit-%s.launcher-%s.dirty" % (c, t))["dirty"])
        for unstamped in ("2.0", "", None, "2.0+commit-3fac1fb.launcher-e9fb931"):
            self.assertIsNone(provenance.parse_stamp(unstamped))

    def test_gradle_writes_what_perf_reads(self):
        with open(os.path.join(provenance.ROOT, "launcher", "app", "build.gradle")) as f:
            gradle = f.read()
        self.assertIn("versionName '2.0' + buildStamp()", gradle)
        self.assertIn('"+commit-${commit}.launcher-${tree}${dirty ? \'.dirty\' : \'\'}"', gradle)


@unittest.skipUnless(HAS_GIT, "needs git")
class GitTests(unittest.TestCase):
    def test_trees_and_dirty(self):
        repo = Repo(self)
        repo.commit({"launcher/a.txt": "a", "launcher/.gitignore": "build/\n", "docs/x.md": "x"})
        tree = repo.tree("HEAD")
        self.assertEqual(provenance.launcher_tree("HEAD", repo.root), tree)
        self.assertIsNone(provenance.launcher_tree("no-such-ref", repo.root))
        self.assertFalse(provenance.launcher_dirty(repo.root))
        self.assertEqual(provenance.worktree_launcher_tree(repo.root), tree)
        repo.write("launcher/build/out.apk", "ignored")
        repo.write("docs/x.md", "outside launcher/")
        self.assertFalse(provenance.launcher_dirty(repo.root))
        # uncommitted changes: the tree they'd commit to, without touching the real index
        repo.write("launcher/a.txt", "changed")
        repo.write("launcher/new.txt", "untracked")
        self.assertTrue(provenance.launcher_dirty(repo.root))
        on_disk = provenance.worktree_launcher_tree(repo.root)
        self.assertNotEqual(on_disk, tree)
        self.assertEqual(repo.git("status", "--porcelain", "--", "launcher"), "M launcher/a.txt\n?? launcher/new.txt")
        repo.commit({})
        self.assertEqual(repo.tree("HEAD"), on_disk)

    def test_without_git(self):
        d = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, d, True)
        self.assertIsNone(provenance.launcher_tree("HEAD", d))
        self.assertIsNone(provenance.launcher_dirty(d))
        self.assertIsNone(provenance.worktree_launcher_tree(d))


@unittest.skipUnless(HAS_GIT, "needs git")
class ProblemTests(unittest.TestCase):
    def setUp(self):
        self.repo = Repo(self)
        self.c0 = self.repo.commit({"launcher/a.txt": "old transitions", "docs/x.md": "x"})
        self.c1 = self.repo.commit({"launcher/a.txt": "new transitions"})
        self.repo.main_at(self.c1)
        self.repo.git("checkout", "-q", "-b", "fix")
        self.fix = self.repo.commit({"launcher/b.txt": "my fix"})
        self.repo.git("checkout", "-q", "main")

    def problems(self, a, b=None):
        return provenance.problems(a, b, root=self.repo.root)

    def test_current_main_and_a_branch_on_it(self):
        main, fix = self.repo.result("main", self.c1), self.repo.result("fix", self.fix)
        self.assertEqual(levels(self.problems(main, fix), M), [])
        self.assertEqual(self.problems(main), [])

    def test_main_moved_since_the_baseline(self):
        self.repo.main_at(self.repo.commit({"launcher/a.txt": "newer"}))
        m = levels(self.problems(self.repo.result("main", self.c1)), M)
        self.assertEqual(len(m), 1)
        self.assertIn("isn't origin/main's", m[0])

    def test_main_moved_outside_launcher(self):
        docs = self.repo.commit({"docs/x.md": "docs only"})
        self.repo.main_at(docs)
        # the baseline measured before the docs change is still main's launcher/, and a branch from before it has it
        self.assertEqual(levels(self.problems(self.repo.result("main", self.c1)), M), [])
        self.assertEqual(levels(self.problems(self.repo.result("main", docs), self.repo.result("fix", self.fix)), M),
                         [])

    def test_old_main_as_the_baseline(self):
        # measured with the old transitions: main has moved since
        m = levels(self.problems(self.repo.result("main", self.c0), self.repo.result("fix", self.fix)), M)
        self.assertTrue(any("isn't origin/main's" in t for t in m))

    def test_branch_without_main_merged(self):
        self.repo.git("checkout", "-q", "-b", "stale", self.c0)
        stale = self.repo.commit({"launcher/b.txt": "my fix, before main's new transitions"})
        m = levels(self.problems(self.repo.result("main", self.c1), self.repo.result("stale", stale)), M)
        self.assertEqual(len(m), 1)
        self.assertIn("doesn't contain the baseline", m[0])
        self.repo.git("merge", "-q", "--no-edit", "main")
        merged = self.repo.git("rev-parse", "HEAD")
        self.assertEqual(levels(self.problems(self.repo.result("main", self.c1), self.repo.result("stale", merged)),
                                M), [])

    def test_commit_not_in_this_clone(self):
        fix = make_result("fix", "1" * 40, "2" * 40)
        p = self.problems(self.repo.result("main", self.c1), fix)
        self.assertEqual(levels(p, M), [])
        self.assertTrue(any("isn't in this clone" in t for t in levels(p, N)))

    def test_no_main_ref(self):
        self.repo.git("update-ref", "-d", "refs/remotes/origin/main")
        p = self.problems(self.repo.result("main", self.c1))
        self.assertEqual(levels(p, M), [])
        self.assertTrue(any("couldn't check main" in t for t in levels(p, N)))

    def test_unstamped(self):
        m = levels(self.problems(make_result("main", None, None), self.repo.result("fix", self.fix)), M)
        self.assertEqual(len(m), 1)
        self.assertIn("main: the build has no provenance stamp", m[0])

    def test_not_as_it_ships(self):
        for kw, words in ((dict(debuggable=True), "debug build"), (dict(compiler="verify"), "compiled `verify`"),
                          (dict(dirty=True), "uncommitted changes"),
                          (dict(scales={"window": 1, "transition": 1, "animator": 0.5}), "animator 0.5"),
                          (dict(launcher=dict(SHIPPED, glass=50)), "Liquid Glass at 50 (ships at 0)"),
                          (dict(launcher=dict(SHIPPED, test_art=1)), "stand-in top shelf art")):
            m = levels(self.problems(self.repo.result("main", self.c1, **kw)), M)
            self.assertTrue(any(words in t for t in m), (kw, m))

    def test_both_compiled_by_art_as_the_default_home_app(self):
        """An emulator (or a TV where the launcher is the default Home) gives both builds `verify`: a fair pair."""
        p = self.problems(self.repo.result("main", self.c1, compiler="verify"),
                          self.repo.result("fix", self.fix, compiler="verify"))
        self.assertEqual(levels(p, M), [])
        self.assertTrue(any("so was the other" in t for t in levels(p, N)))

    def test_pairs_that_differ(self):
        main = self.repo.result("main", self.c1)
        for kw, words in ((dict(model="Android TV emulator"), "Different devices"),
                          (dict(debuggable=True), "Debug vs release"),
                          (dict(compiler="speed-profile"), "Compiled `speed` vs `speed-profile`"),
                          (dict(other_app="com.android.tv.settings/.MainSettings"), "Different background apps"),
                          (dict(scales={"window": 0.5, "transition": 1, "animator": 1}), "Different animation scales"),
                          (dict(launcher=dict(SHIPPED, test_art=1)), "Different configurations")):
            m = levels(self.problems(main, self.repo.result("fix", self.fix, **kw)), M)
            self.assertTrue(any(words in t for t in m), (kw, m))
        # background apps only matter when both started the launcher
        only_jank = self.repo.result("fix", self.fix, other_app=None, scenarios=("jank",))
        self.assertEqual(levels(self.problems(main, only_jank), M), [])

    def test_variants(self):
        glass = dict(SHIPPED, glass=50)
        # both with the same variant: allowed, with a note
        p = self.problems(self.repo.result("main", self.c1, launcher=glass),
                          self.repo.result("fix", self.fix, launcher=glass))
        self.assertEqual(levels(p, M), [])
        self.assertTrue(any("a variant" in t for t in levels(p, N)))
        # one build in two configurations, either way round
        for a, b in ((SHIPPED, glass), (glass, SHIPPED)):
            p = self.problems(self.repo.result("main", self.c1, launcher=a),
                              self.repo.result("main-glass", self.c1, launcher=b))
            self.assertEqual(levels(p, M), [], (a, b))
            self.assertTrue(any("two configurations" in t for t in levels(p, N)))

    def test_dirty_candidate_is_a_note(self):
        p = self.problems(self.repo.result("main", self.c1), self.repo.result("fix", self.fix, dirty=True))
        self.assertEqual(levels(p, M), [])
        self.assertTrue(any("commit what you measured" in t for t in levels(p, N)))

    def test_compare_problems_once_each(self):
        main = make_result("main", None, None)
        p = provenance.compare_problems([main, self.repo.result("a", self.fix), self.repo.result("b", self.fix)],
                                        root=self.repo.root)
        self.assertEqual(sum("main: the build has no provenance stamp" in t for _, t in p), 1)

    def test_describe(self):
        d = provenance.describe(self.repo.result("fix", self.fix, dirty=True, launcher=dict(SHIPPED, glass=50)))
        self.assertIn("fix: TCL Smart TV Pro (Android 14), release build compiled speed, commit %s" % self.fix[:10], d)
        self.assertIn("with uncommitted changes, Liquid Glass at 50 (ships at 0)", d)
        self.assertIn("shipped configuration", provenance.describe(self.repo.result("main", self.c1)))
        old = make_result("old", None, None)
        old["config"]["launcher"] = None
        self.assertIn("unstamped build (versionName 2.0), configuration not reported", provenance.describe(old))


if __name__ == "__main__":
    unittest.main()
