---
name: launcher-tv-testing
description: Use when changing, testing or measuring the Android TV launcher in launcher/ (tvhome), on a real TV or an emulator. Says which checks a change needs (unit tests, emulator, TV screenshots, which tools/perf.py scenarios), how to get a fair before/after against main, how to spend as little TV time as possible, and the rules that keep the TV working. Use it before installing anything on a TV or running tools/perf.py.
---

# Testing the launcher on a TV

The details live in [docs/performance.md](../../../docs/performance.md) (measuring) and
[CONTRIBUTING.md](../../../CONTRIBUTING.md) (what a pull request needs). This skill tells you how to use them
without wasting TV time. A TV is often shared, and it's often someone's daily TV, so every minute on it counts.

## 1. Pick the cheapest check that covers the change

Work up this ladder only as far as the change needs:

| The change | What to run |
|---|---|
| Docs, README, `examples/`, `PROMPT.md` | Nothing on a TV. |
| `tools/` | `python3 -m unittest discover -s tools/tests`. A TV only if the change is about talking to one. |
| Logic with JVM tests (`AmbientColor`, `CoverArt`, `ShelfFeeds`, `GridScroller`, `LiquidGlass`, ...) | `cd launcher && ./gradlew testDebugUnitTest`. |
| How something looks or moves | Check it on the emulator while you work. Take TV screenshots or a clip once, at the end, for the pull request. |
| Anything that could change speed, smoothness or memory | `tools/perf.py`, but only the scenarios for what you changed (section 3). |

## 2. Before/after: compare against the main that ships

- **Merge the latest `main` into your branch first, and measure once, at the end.** Numbers taken before a merge
  compare against a `main` that no longer exists. On 2026-10-19 baselines were taken before and after the new
  transitions merged, and the first set had to be thrown away.
- **The baseline is `main` as it ships:** a release build compiled with `speed`, in its default configuration.
  Don't measure retired variants (an older `main`, the old transitions, a branch build from before the merge).
- **Measure a variant only when your change adds or changes it.** For example, `liquid_glass_blur=false` or
  `--ez glass_test_art true` are for changes to the glass or the top shelf's art.
- **Use the same conditions for A and B:** the same TV, the same background app (`--other-app`), the same
  `perf.py` options, and about a minute's wait after each install. `perf.py compare` warns when the devices,
  background apps or build types differ.

## 3. Measure only what you changed

Each scenario costs TV time. With the defaults, `cold` takes about 70 s, `warm` about 40 s, `jank` about 30 s,
`dock` about a minute and `mem` a few seconds. A full `run` takes about 4 minutes, plus installs and settling.
Pick the scenarios from what your diff touches:

| You changed | Scenarios | Trace if a number moves |
|---|---|---|
| Start-up: `MainActivity.onCreate`, app loading in `AppAdapter`, layouts, fonts, styles | `cold`, `mem` | `trace --action cold` |
| Coming back to Home: `onResume`, `Transitions`, `HomeMotion`, `HomeButtonService` | `warm` | `trace --action home` |
| Focus and scrolling: `GridScroller`, `AppAdapter.Holder`, `DockDecoration`, `Rearranger` | `jank` | `trace --action keys` |
| The top shelf and the dock: `TopShelf*`, `ShelfFeeds`, `CoverArt`, `AmbientColor`, `LiquidGlass` | `dock`, `mem` | `trace --action dock` |
| The top right: `ProfileCorner`, `TopBarButton`, `QuickSettings`, `top_bar.xml` | `jank` with `--keys` that go up into the corner and along it | `trace --action keys` |
| The screensaver: `ClockDream` | none of them | `trace --action none` while it runs |
| `build.gradle`, ProGuard rules, the manifest, dependencies | all of them | – |

For example: `tools/perf.py run --label my-fix --scenarios dock,mem`. While you iterate, fewer repeats
(`--runs 5 --rounds 2`) are enough to see a direction. Use the defaults for the table in the pull request.

## 4. Spend the TV time in one hold

- Build every APK you need (`main`'s and yours) before you take the TV.
- Write one script with every step: install, settle, screenshots, clips, perf runs, traces, and putting the
  previous build back. Dry-run it on the emulator first.
- If a step fails on the TV, stop and give the TV back. Fix the problem off the TV.
- The emulator is for iterating. Its numbers never stand in for the TV's: when a result needs the TV and the TV is
  in use, ask whoever is using it for a slot.

## 5. Keep the TV working

- **Never `am force-stop` the launcher.** On Google TV it switches the Home button's accessibility service
  off for good. Use `am kill`, as `perf.py` does.
- Install with `install -r`, never uninstall first: that drops the Home button service's settings.
- On Google TV, never disable the stock launcher (`com.google.android.apps.tv.launcherx`); `README.md` explains
  why.
- Bring the launcher up with `am start -n com.gathin.tvhome/.MainActivity`. An injected Home key skips
  accessibility services and can land on the stock home.
- When you're done, delete your files from `/sdcard` and `/data/local/tmp`. Check that
  `settings get secure enabled_accessibility_services` still lists `com.gathin.tvhome/.HomeButtonService`. Leave
  the launcher on screen, with `main`'s release build installed unless yours is the one to keep.
