"""What a tools/perf.py result measured, and whether two results can be compared fairly.

See docs/performance.md, "Measure the main that ships". The launcher's Gradle build stamps every APK with what it was
built from, in its versionName, which `dumpsys package` shows:

    2.0+commit-<sha>.launcher-<tree>[.dirty]

<sha> is the commit checked out, <tree> the git tree of launcher/ as it was on disk (uncommitted changes included, so
the same files always give the same hash), and ".dirty" says it differs from the commit. A build without git (a
source zip) has no stamp. perf.py records it as result["build"]["provenance"]; problems() checks a baseline and a
candidate against origin/main with the local git clone, as of its last fetch.
"""
import os
import re
import subprocess
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LAUNCHER = "launcher"
MAIN_REF = "origin/main"
# problems() levels: compare refuses a mismatch without --force; a note is printed under the table.
MISMATCH, NOTE = "mismatch", "note"

_STAMP = re.compile(r"\+commit-([0-9a-f]{40})\.launcher-([0-9a-f]{40})(\.dirty)?$")
_SHA = re.compile(r"^[0-9a-f]{40}$")


def parse_stamp(version_name):
    """{"commit", "launcher_tree", "dirty"} from a stamped versionName, or None."""
    m = _STAMP.search(version_name or "")
    if not m:
        return None
    return {"commit": m.group(1), "launcher_tree": m.group(2), "dirty": bool(m.group(3))}


# ---- git -----------------------------------------------------------------------------------------

def _run(args, root=None, env=None):
    try:
        return subprocess.run(["git", "-C", root or ROOT] + args, capture_output=True, text=True, timeout=60,
                              env=env)
    except (OSError, subprocess.SubprocessError):
        return None


def _git(args, root=None, env=None):
    p = _run(args, root, env)
    return p.stdout.strip() if p is not None and p.returncode == 0 else None


def _sha(out):
    return out if out and _SHA.match(out) else None


def launcher_tree(ref="HEAD", root=None):
    """The git tree hash of launcher/ at `ref` (40 hex), or None without git or for an unknown ref."""
    return _sha(_git(["rev-parse", "--verify", "--quiet", "%s:%s" % (ref, LAUNCHER)], root))


def launcher_dirty(root=None):
    """Whether launcher/ differs from HEAD on disk (untracked files too); None without git."""
    out = _git(["status", "--porcelain", "--untracked-files=all", "--", LAUNCHER], root)
    return None if out is None else bool(out)


def worktree_launcher_tree(root=None):
    """The tree of launcher/ as it is on disk, as the Gradle stamp computes it: HEAD's when it is clean, else
    through a scratch index (the real one is left alone). None without git."""
    dirty = launcher_dirty(root)
    if dirty is None:
        return None
    if not dirty:
        return launcher_tree("HEAD", root)
    with tempfile.TemporaryDirectory() as d:
        env = dict(os.environ, GIT_INDEX_FILE=os.path.join(d, "index"))
        if _git(["read-tree", "HEAD"], root, env) is None or _git(["add", "-A", "--", LAUNCHER], root, env) is None:
            return None
        return _sha(_git(["write-tree", "--prefix=%s/" % LAUNCHER], root, env))


def has_commit(sha, root=None):
    return bool(sha) and _git(["cat-file", "-e", sha + "^{commit}"], root) is not None


def contains(commit, base, main_ref=MAIN_REF, root=None):
    """Whether a build from `commit` has all of the baseline's main in it: the baseline's commit is in its history,
    or the newest main commit in it has the baseline's launcher/ (main only moved outside launcher/ since). None when
    git can't tell (the commit isn't in this clone: git fetch)."""
    if not has_commit(commit, root):
        return None
    if has_commit(base["commit"], root):
        p = _run(["merge-base", "--is-ancestor", base["commit"], commit], root)
        if p is not None and p.returncode == 0:
            return True
    mb = _sha(_git(["merge-base", commit, main_ref], root))
    return bool(mb) and launcher_tree(mb, root) == base["launcher_tree"]


# ---- what a result says it measured -------------------------------------------------------------

def stamp(result):
    return ((result or {}).get("build") or {}).get("provenance")


def label(result):
    return (result or {}).get("label") or "?"


def scales(result):
    """The system animation scales during the run, {"window", "transition", "animator"}, or None."""
    return ((result or {}).get("config") or {}).get("animation_scales")


def variant(result):
    """How the launcher's configuration differed from the one it ships with: a list of phrases, [] when it was the
    shipped one, None when the build didn't report it."""
    lc = ((result or {}).get("config") or {}).get("launcher")
    if not lc:
        return None
    out = []
    if lc.get("glass") != lc.get("glass_default"):
        out.append("Liquid Glass at %s (ships at %s)" % (lc.get("glass"), lc.get("glass_default")))
    if lc.get("test_art"):
        out.append("stand-in top shelf art (glass_test_art)")
    return out


def _scales_text(s):
    return "window %g, transition %g, animator %g" % (s.get("window"), s.get("transition"), s.get("animator"))


def _short(h):
    return h[:10] if h else "?"


def describe(result):
    """One line: what was measured, for the notes under a table."""
    b, d = result.get("build") or {}, result.get("device") or {}
    parts = ["%s %s (Android %s)" % (d.get("manufacturer"), d.get("model"), d.get("android")),
             "%s build compiled %s" % ("debug" if b.get("debuggable") else "release", b.get("compiler_filter"))]
    p = stamp(result)
    if p:
        parts.append("commit %s, launcher/ %s%s" % (_short(p["commit"]), _short(p["launcher_tree"]),
                                                   " with uncommitted changes" if p["dirty"] else ""))
    else:
        parts.append("unstamped build (versionName %s)" % b.get("version_name"))
    v = variant(result)
    parts.append("configuration not reported" if v is None else ", ".join(v) if v else "shipped configuration")
    s = scales(result)
    if s and any(x != 1 for x in s.values()):
        parts.append("animation scales " + _scales_text(s))
    return "%s: %s" % (label(result), ", ".join(parts))


# ---- checks --------------------------------------------------------------------------------------

def _baseline(base, cand, main_ref, root, add):
    """Is `base` main as it ships: the current main's launcher/, a release build compiled with speed, its shipped
    configuration, the TV's animations at 1."""
    n, p, b = label(base), stamp(base), base.get("build") or {}
    if p:
        if p["dirty"]:
            add(MISMATCH, "%s: built with uncommitted changes in launcher/, so it isn't main" % n)
        main_tree = launcher_tree(main_ref, root)
        if main_tree is None:
            add(NOTE, "couldn't check %s against %s (no git here, or no such ref: git fetch origin)" % (n, main_ref))
        elif p["launcher_tree"] != main_tree:
            add(MISMATCH, "%s: its launcher/ (%s) isn't %s's (%s, commit %s): main has moved since it was measured, "
                          "or it isn't main. Measure the current main" % (
                              n, _short(p["launcher_tree"]), main_ref, _short(main_tree),
                              _short(_git(["rev-parse", main_ref], root))))
    if b.get("debuggable"):
        add(MISMATCH, "%s: a debug build; main ships as a release build" % n)
    elif b.get("compiler_filter") not in (None, "speed"):
        add(MISMATCH, "%s: compiled `%s`; main ships compiled `speed` (launcher/build-and-install.sh)"
            % (n, b.get("compiler_filter")))
    s = scales(base)
    if s and any(x != 1 for x in s.values()):
        add(MISMATCH, "%s: animation scales %s; main's transitions run at 1" % (n, _scales_text(s)))
    v = variant(base)
    pc = stamp(cand)
    if v is None:
        if p:
            add(NOTE, "%s: the build didn't report its configuration; a saved Liquid Glass choice or stand-in art may "
                      "have been in effect" % n)
    elif v and cand is not None and variant(cand) == v:
        add(NOTE, "both measured with %s: a variant. Measure one only when your change adds or changes it"
            % ", ".join(v))
    elif v and not (p and pc and p["launcher_tree"] == pc["launcher_tree"]):   # same launcher/: see _pair
        add(MISMATCH, "%s: not the configuration main ships with: %s" % (n, ", ".join(v)))


def _pair(a, b, main_ref, root, add):
    na, nb = label(a), label(b)
    da, db = a.get("device") or {}, b.get("device") or {}
    if (da.get("model"), da.get("android")) != (db.get("model"), db.get("android")):
        add(MISMATCH, "Different devices (%s, Android %s vs %s, Android %s): the numbers are not comparable"
            % (da.get("model"), da.get("android"), db.get("model"), db.get("android")))
    ba, bb = a.get("build") or {}, b.get("build") or {}
    if ba.get("debuggable") != bb.get("debuggable"):
        add(MISMATCH, "Debug vs release build: part of the change comes from the build type alone")
    elif ba.get("compiler_filter") != bb.get("compiler_filter"):
        add(MISMATCH, "Compiled `%s` vs `%s`: part of the change comes from the compiler alone"
            % (ba.get("compiler_filter"), bb.get("compiler_filter")))
    started = [any(s in r for s in ("cold", "warm")) for r in (a, b)]
    oa, ob = ((r.get("params") or {}).get("other_app") for r in (a, b))
    if all(started) and oa != ob:
        add(MISMATCH, "Different background apps for cold/warm starts (%s vs %s): pass the same --other-app"
            % (oa, ob))
    sa, sb = scales(a), scales(b)
    if sa and sb and sa != sb:
        add(MISMATCH, "Different animation scales (%s vs %s)" % (_scales_text(sa), _scales_text(sb)))
    for scenario in ("jank", "dock"):
        if scenario in a and scenario in b and any(a[scenario].get(k) != b[scenario].get(k)
                                                   for k in ("keys", "interval_s")):
            add(MISMATCH, "Different %s keys or key intervals" % scenario)
    pa, pb = a.get("params") or {}, b.get("params") or {}
    if pa.get("settle_s") != pb.get("settle_s"):
        add(MISMATCH, "Different --settle (%s vs %s s)" % (pa.get("settle_s"), pb.get("settle_s")))
    va, vb = variant(a), variant(b)
    sta, stb = stamp(a), stamp(b)
    same_tree = sta and stb and sta["launcher_tree"] == stb["launcher_tree"]
    if va is not None and vb is not None and va != vb:
        what = "%s: %s; %s: %s" % (na, ", ".join(va) or "shipped", nb, ", ".join(vb) or "shipped")
        if same_tree:
            add(NOTE, "The same launcher/ in two configurations (%s): a variant comparison" % what)
        else:
            add(MISMATCH, "Different configurations (%s): the change column mixes the configuration into the change"
                % what)
    if sta and stb and not same_tree:
        has = contains(stb["commit"], sta, main_ref, root)
        if has is None:
            add(NOTE, "couldn't check that %s contains the baseline (%s): commit %s isn't in this clone (git fetch)"
                % (nb, na, _short(stb["commit"])))
        elif not has:
            add(MISMATCH, "%s (commit %s) doesn't contain the baseline (%s, commit %s): merge main, rebuild and "
                          "measure again, or the change column mixes main's own changes into yours"
                % (nb, _short(stb["commit"]), na, _short(sta["commit"])))
    if stb and stb["dirty"]:
        add(NOTE, "%s: built with uncommitted changes in launcher/ (tree %s): commit what you measured"
            % (nb, _short(stb["launcher_tree"])))


def problems(baseline, candidate=None, main_ref=MAIN_REF, root=None):
    """What makes `baseline` not main as it ships, and comparing `candidate` with it misleading: a list of
    (MISMATCH or NOTE, text). Uses the local git clone at `root` (default: this repository)."""
    out = []

    def add(level, text):
        if (level, text) not in out:
            out.append((level, text))

    for r in [baseline] + ([candidate] if candidate is not None else []):
        if not stamp(r):
            add(MISMATCH, "%s: the build has no provenance stamp (built before stamping, or without git), so nothing "
                          "says what was measured. Rebuild it and measure again" % label(r))
    _baseline(baseline, candidate, main_ref, root, add)
    if candidate is not None:
        _pair(baseline, candidate, main_ref, root, add)
    return out


def compare_problems(results, main_ref=MAIN_REF, root=None):
    """problems() of each result after the first against the first, without repeats."""
    out = []
    for cand in results[1:] or [None]:
        for p in problems(results[0], cand, main_ref, root):
            if p not in out:
                out.append(p)
    return out
