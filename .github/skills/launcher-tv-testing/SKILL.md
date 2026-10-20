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

1. **Local.** From the repository root: `(cd launcher && ./gradlew assembleDebug assembleRelease testDebugUnitTest)`
   and `python3 -m unittest discover -s tools/tests`. Gradle needs JDK 17 and the Android SDK: in a fresh worktree
   set `JAVA_HOME` and `ANDROID_HOME` first, unless `java -version` says 17 and `launcher/local.properties` exists.
   For docs or tools that don't talk to a TV, stop here.
2. **Your own emulator** (below). Check how the change looks, moves and behaves, with D-pad keys and
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
(API 34, 1920×1080, about 2 GB of RAM, like a real TV). Start yours headless on a free even port from 5554 to 5584,
the emulator's own range (adb looks for emulators only there when it starts). Check the port is free first:
`adb devices` doesn't list `emulator-<port>`, and `lsof -nP -iTCP:<port> -iTCP:<port+1> -sTCP:LISTEN` prints
nothing. `-read-only` lets several emulators run from the same AVD. `emulator` and `adb` are in the SDK's
`emulator/` and `platform-tools/` if they aren't on your PATH:

```bash
emulator -avd <tv-avd> -read-only -port 5570 -no-window -no-audio -no-boot-anim &
unset TV_HOST                         # tools/tv.py goes to TV_HOST whenever it's set, whatever ANDROID_SERIAL says
export ANDROID_SERIAL=emulator-5570   # always: other emulators and the TV are attached too
adb wait-for-device; until [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = 1 ]; do sleep 3; done
```

A taken port doesn't stop the emulator: it boots on random ports, and `emulator-<port>` then leads to whatever
holds that port, maybe someone else's emulator. Run one emulator per agent, and stop it when you're done: `kill`
the PID that `pgrep -f -- '[q]emu.*-port 5570'` prints, or `adb -s emulator-5570 emu kill`. Never stop someone
else's.

Its numbers are only relative. Its GPU is software, so its frame times say little about a TV's, and other
emulators share the CPU with it. Compare `main` and your build measured back to back on the same emulator, and
never put emulator numbers in a pull request as TV numbers.

## 2. Before/after: compare against the main that ships

- **Merge the latest `main` into your branch first, and measure once, at the end.** Numbers taken before a merge
  compare against a `main` that no longer exists: baselines taken before a big merge (the new transitions, #12)
  had to be thrown away.
- **The baseline is `main` as it ships:** a release build compiled with `speed`, in its default configuration,
  with the TV's animation scales at 1. Don't measure retired variants (an older `main`, the old transitions, a
  branch build from before the merge).
- **Measure a variant only when your change adds or changes it,** with the same variant for `main` and yours: for
  changes to the glass or the top shelf's art, `perf.py run --variant '--ez glass_test_art true'` (stand-in art) or
  `--variant '--ei liquid_glass 50'`, or a build with `liquid_glass_blur=false`.
- **Every build says what it is.** Gradle stamps each APK's `versionName` with its commit and `launcher/` tree
  (`2.0+commit-<sha>.launcher-<tree>[.dirty]`). `perf.py run` records that, the animation scales and the
  launcher's own configuration, and measures with `--ez default_config true`: `config.xml`'s Liquid Glass for that
  process only, the TV owner's saved choice untouched. Never reset a TV with `--ei liquid_glass`: it overwrites
  that choice.
- **`perf.py compare` refuses comparisons that would mislead:** an unstamped build; a baseline whose `launcher/`
  isn't `origin/main`'s (`git fetch` first), or that isn't release, `speed` and the shipped configuration; a branch
  build without the baseline's `main` merged; A and B measured differently (device, build type, compiler filter,
  background app, animation scales, options, configuration). Fix the cause and measure again; `--force` is only for
  tables you won't present as a before/after. [docs/performance.md](../../../docs/performance.md#measure-the-main-that-ships)
  has the full list.
- **Use the same conditions for A and B:** the same TV, the same background app (`--other-app`), the same
  `perf.py` options, and about a minute's wait after each install. To install an APK you built earlier (for
  example `main`'s, from a second worktree): `adb install -r <apk>`, then
  `adb shell cmd package compile -m speed -f com.gathin.tvhome`, as `launcher/build-and-install.sh` does.

## 3. Measure only what you changed

Each scenario costs TV time. With the defaults, `cold` takes about 70 s, `warm` about 40 s, `jank` about 30 s,
`dock` about a minute and `mem` about 10 s. A full `run` takes about 4 minutes, plus installs and settling.
Pick the scenarios from what your diff touches:

| You changed | Scenarios | Trace if a number moves |
|---|---|---|
| Start-up: `MainActivity.onCreate`, app loading in `AppAdapter`, layouts, fonts, styles | `cold`, `mem` | `trace --action cold` |
| Coming back to Home: `onResume`, `comeBackFromApp`, `HomeMotion.fallBack`, `Transitions`' Home options | `warm` | `trace --action home` (on Google TV it goes through the stock home first) |
| Opening an app: `onLaunchApp`, `HomeMotion.push`, `AppAdapter.Holder.press`, `Transitions`' launch options | none of them | `trace --action keys --keys CENTER --seconds 6` (Select on the focused tile; the `tvhome:launch` section) |
| The Home button on Google TV: `HomeButtonService` | none: `warm` starts the launcher directly, and `adb shell input` keys skip the service. It's off on an Android TV emulator, so this needs the TV | `trace --action home` (the hand-over from the stock home); for its key handling, `trace --action none --seconds 30` while someone presses the real remote ([docs/performance.md](../../../docs/performance.md)) |
| Focus and scrolling: `GridScroller`, `AppAdapter.Holder`, `DockDecoration` | `jank` | `trace --action keys` |
| Arranging apps: `Rearranger`, `MoveMarks`, `AppOrder` (JVM tests) | `jank` with `--keys` that reach the Arrange apps tile, pick an app up with `CENTER`, move it and end with `BACK BACK`, which puts it back unsaved. Never end with `CENTER` on a shared TV: putting the app down saves the order | `trace --action keys` with the same keys |
| The top shelf and the dock: `TopShelf*`, `Shelf*`, `CoverArt`, `AmbientColor`, `LiquidGlass`, `GlassSlider` | `dock`, `mem`; add `jank` for `LiquidGlass`, which redraws on every frame of a scroll | `trace --action dock` |
| The top right: `ProfileCorner`, `TopBarButton`, `QuickSettings`, `top_bar.xml` | `jank` with `--keys` that go up into the corner and along it | `trace --action keys` |
| The screensaver: `ClockDream` | none of them | `trace --action none` while it runs |
| `build.gradle`, ProGuard rules, the manifest, dependencies | all of them | – |

For example: `python3 tools/perf.py run --label my-fix --scenarios dock,mem`. On the emulator, fewer repeats
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
- Don't change the glass setting on a shared TV: `--ei liquid_glass N` and the glass slider save N over the
  owner's own choice, and a release build can't read the old value back. `--ez glass_test_art true` lasts only
  until `--ez glass_test_art false` or the process ends.
- When you're done, delete your files from `/sdcard` and `/data/local/tmp`, and
  `/data/misc/perfetto-traces/tvhome.perfetto-trace` if you traced. Check that
  `settings get secure enabled_accessibility_services` still lists `com.gathin.tvhome/.HomeButtonService`. Leave
  the launcher on screen, with `main`'s release build installed unless yours is the one to keep.
