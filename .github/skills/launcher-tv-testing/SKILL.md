---
name: launcher-tv-testing
description: Use when changing, testing or measuring the Android TV launcher in launcher/ (tvhome). Says the order to test in (local checks, then your own emulator, then a real TV only once you're confident), which checks and tools/perf.py scenarios a change needs, how to run several emulators side by side, how to get a fair before/after against main, how to spend as little TV time as possible, and the rules that keep the TV working. Use it before starting an emulator, installing anything on a TV or running tools/perf.py.
---

# Testing the launcher on a TV

The details live in [docs/performance.md](../../../docs/performance.md) (measuring) and
[CONTRIBUTING.md](../../../CONTRIBUTING.md) (what a pull request needs). This skill tells you how to use them
without wasting TV time. A TV is often shared, and it's often someone's daily TV, so every minute on it counts.

## 1. Local first, then the emulator, then the TV

Go up a step only once the step below says the change is good:

1. **Local.** `cd launcher && ./gradlew assembleDebug assembleRelease testDebugUnitTest` and
   `python3 -m unittest discover -s tools/tests`. For docs or tools that don't talk to a TV, stop here.
2. **The emulator: your own (below).** Check how the change looks, moves and behaves, with D-pad keys and
   screenshots. Run the `perf.py` scenarios for it there too, `main` and yours back to back, to see which way the
   numbers move. Fix everything you can here.
3. **The TV, once you're confident.** One hold for what only a TV can show: the numbers for the pull request, its
   screenshots or clip, how it feels with the real remote, and anything specific to that TV.

| The change | Up to |
|---|---|
| Docs, README, `examples/`, `PROMPT.md` | Nothing to run. |
| `tools/` | Local: `python3 -m unittest discover -s tools/tests`. The emulator and the TV only if the change is about talking to one. |
| Logic with JVM tests (`AmbientColor`, `CoverArt`, `ShelfFeeds`, `GridScroller`, `LiquidGlass`, ...) and no change on screen | Local. |
| How something looks or moves | The emulator while you work; the TV once, at the end, for screenshots or a clip. |
| Anything that could change speed, smoothness or memory | `tools/perf.py` on the emulator, then on the TV, only the scenarios for what you changed (section 3). |

### Your own emulator

Several agents can each run an emulator at once, so nobody waits for anyone else's. Use an Android TV system image
(API 34, 1920×1080, about 2 GB of RAM, like a real TV). Start yours headless on a free even port from 5554 to 5682
(`adb devices` shows the ports in use). `-read-only` lets several emulators run from the same AVD:

```bash
emulator -avd <tv-avd> -read-only -port 5590 -no-window -no-audio -no-boot-anim &
export ANDROID_SERIAL=emulator-5590   # always: other emulators and the TV are attached too
adb wait-for-device; until [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = 1 ]; do sleep 3; done
```

If the port is taken, the emulator exits; try the next one. Run one emulator per agent, and stop it when you're
done with `adb -s emulator-5590 emu kill` (or `kill` the PID that `pgrep -f -- '-port 5590'` prints). Never stop
someone else's.

Its numbers are only relative. Its GPU is software, so its frame times say little about a TV's, and other
emulators share the CPU with it. Compare `main` and your build measured back to back on the same emulator, and
never put emulator numbers in a pull request as TV numbers.

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

For example: `tools/perf.py run --label my-fix --scenarios dock,mem`. On the emulator, fewer repeats
(`--runs 5 --rounds 2`) are enough to see a direction. Use the defaults on the TV, for the table in the pull
request.

## 4. On the TV: one hold, once you're confident

- Build every APK you need (`main`'s and yours) before you take the TV.
- Write one script with every step: install, settle, screenshots, clips, perf runs, traces, and putting the
  previous build back. Dry-run it on your emulator first.
- If a step fails on the TV, stop and give the TV back. Fix the problem off the TV.
- The emulator is where you iterate, but its numbers never stand in for the TV's. When a result needs the TV and
  the TV is in use, ask whoever is using it for a slot.

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
