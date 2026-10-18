# Performance: measure first, then fix

The launcher runs on TVs with 1 GB of RAM and slow CPUs, so performance work here is data-driven:
measure on a real TV, find where the time goes, change one thing, measure again. Each fix's pull
request should include a before/after table from the same TV.

Everything stays local. The launcher only writes to the TV's own log when you ask it to, and
nothing is sent anywhere (no telemetry; see CONTRIBUTING.md).

## Tools

| | What it gives you |
|---|---|
| `tools/perf.py run` | Cold start, Home (warm start), D-pad frame jank, dock moves (top shelf latency) and memory, each repeated, saved as JSON with medians. |
| `tools/perf.py compare` | A Markdown before/after table for a pull request. |
| `tools/perf.py trace` | A [Perfetto](https://perfetto.dev) trace of a cold start, a Home press, D-pad moves, or moves between two dock apps. |
| `tools/perf.py summarize` | The slowest slices on the launcher's main thread and RenderThread in a trace, its `tvhome:*` sections that span frames, and its frames by jank type. |
| Trace sections in the app | `tvhome:*` slices (below) show where the launcher's own code spends time. |
| Timing log lines | `TvHomePerf` log lines with app-side timings; `perf.py` switches them on while it runs. |

### Connecting

`perf.py` connects like the other tools: with `TV_HOST` set it uses `tools/tv.py` (no `adb` binary
needed). Without `TV_HOST`, or with `--adb`, it uses the `adb` binary, which you need for Android 11+
wireless debugging (TLS pairing). Pick the device with `--serial` or `ANDROID_SERIAL`.

```bash
TV_HOST=192.168.1.50 tools/venv/bin/python -I tools/perf.py run --label main
ANDROID_SERIAL=192.168.1.50:37123 python3 tools/perf.py run --label main   # adb binary
```

The launcher must be installed (`launcher/build-and-install.sh`). It doesn't need to be the default
home app, except for `run --via-home` and `trace --action home`. On Google TV the HOME intent always
resolves to the stock launcher, and `HomeButtonService` brings ours back (see the README), so those
two measure that hand-over rather than the launcher alone. Use the default warm start there.

## Workflow

1. **Same conditions for A and B.** Same TV, same installed apps, same build type (a debug build is
   much slower than a release build; `perf.py` records which one it measured and warns), screensaver
   off, nothing downloading. After installing, wait a minute for the TV to settle.
2. **Baseline.** Install `main` and run `tools/perf.py run --label main`.
3. **Change one thing**, install that build and run `tools/perf.py run --label my-fix`.
4. **Compare:** `tools/perf.py compare tools/perf-results/main.json tools/perf-results/my-fix.json`
   and paste the table into the pull request. Medians of 10 runs; treat changes smaller than about
   10 %, or within the min-max spread in the JSON, as noise and re-run.
5. **Find the cause** of a slow number with a trace:

   ```bash
   tools/perf.py trace --action home --seconds 10     # or: --action cold | keys | dock | none
   pip install perfetto && tools/perf.py summarize tvhome-<time>.perfetto-trace
   ```

   Or open the trace at <https://ui.perfetto.dev> and look at the `com.gathin.tvhome` main thread.
   `trace` uses `perfetto --txt`, so it needs Android 10 or newer.

`run` takes about 4 minutes with the defaults. Options: `--scenarios cold,warm,jank,dock,mem`,
`--runs` (cold/warm repeats, default 10), `--rounds` (jank rounds, default 3), `--keys` (the D-pad
sequence), `--dock-moves` and `--dock-interval` (the dock scenario), `--via-home` (warm start through
the HOME intent, like pressing Home), `--other-app` (see below).

**Cold starts use `am kill`, never `am force-stop`.** Force-stopping a package also turns its
accessibility services off for good (Android removes them from `enabled_accessibility_services`), which
would switch off `HomeButtonService` on Google TV. `am kill` ends only the launcher's background process;
the service's own `:home` process stays bound. `perf.py` warns when the launcher's process survives.

**The background app.** Cold and warm starts first open another app so the launcher really goes to
the background. It must cover the whole screen: TV Settings is see-through, so the launcher would
stay visible behind it and the system wouldn't time the start (`perf.py` warns when that happens).
By default `perf.py` uses the first installed TV app by package name and records it; choose one
with `--other-app <package>` (one that opens without playing video is best) and use the same one
for A and B.

## What is measured

| Scenario | How | Numbers |
|---|---|---|
| cold | Open another TV app, `am kill` the launcher, `am start -W` it | `TotalTime` (system: until the first frame); fully drawn (until focus is on a tile, via `reportFullyDrawn`); app-side `create`, `resume`, `reload` |
| warm | Open another TV app, start the launcher again (or the HOME intent) | `TotalTime`; `focus_restored` = from `onResume` until the first frame with focus back on a tile, i.e. when Home is usable; `resume`; `reload` split into the package query and loading banners/icons/labels |
| jank | `dumpsys gfxinfo reset`, 16 D-pad moves 0.35 s apart, `dumpsys gfxinfo` | janky frames %, frame-time percentiles, slow UI thread and high input latency counts |
| dock | Focus on the first dock app, then Right/Left between the first two dock apps, one press every 1.2 s, 8 per round | `shelf`: from the focus change until the top shelf shows the new app, split into the name (`text`) and the glow colour (`glow`); janky frames and frame percentiles of those moves |
| mem | `dumpsys meminfo` on Home after the other scenarios | PSS total, Java heap, native heap (bitmaps live here), graphics, view count |

The JSON also records the device, the Android version, the APK size on the TV, whether the build is
debuggable, and how ART compiled it (`verify`, `speed-profile`, `speed`), because each of these
changes every number.

To read the app's timings yourself:

```bash
tools/tv.py 'setprop log.tag.TvHomePerf DEBUG'
tools/tv.py 'logcat -d -s TvHomePerf'
```

### Trace sections

| Section | Where |
|---|---|
| `tvhome:onCreate`, `tvhome:onResume` | `MainActivity` lifecycle |
| `tvhome:reload`, `tvhome:reload.query`, `tvhome:loadApp <package>` | `AppAdapter.reload()`: the package query and each app's banner, icon and label |
| `tvhome:vividColor` | ambient colour sampling for a dock app, on its first focus |
| `tvhome:onTileFocused` | scrolling and the top shelf on each focus change |
| `tvhome:shelf` (async, Android 10+) | from a dock focus change until the top shelf shows that app; the `shelf` log line has the same time |
| `tvhome:updateWifiLabel`, `tvhome:showInputs` | network pill refresh, Inputs menu |
| `tvhome:DockDecoration.onDraw` | the dock panel |
| `tvhome:dream.setGlow` | screensaver glow colour, per animation frame |

RecyclerView adds its own (`RV OnLayout`, `RV OnBindView`, `RV CreateView`), and the system adds
`Choreographer#doFrame`, `inflate`, `DrawFrame` (RenderThread), binder calls and GC.

## Suspected hotspots

From reading the code; none is confirmed on a TV yet. Measure each before fixing it, and record the
result here or in the fix's pull request. "Reference" numbers come from upstream pull requests
measured on a Xiaomi MiTV-MOOR2 (Android 11), not from our TVs.

| # | Suspect | Where | Should show up in | Reference |
|---|---|---|---|---|
| 1 | A **debug build** is installed: debuggable, not shrunk by R8, and ART runs debuggable apps without ahead-of-time compiled code. | `launcher/build-and-install.sh` (`assembleDebug`) | everything, cold start most; `build.debuggable` in the results | Gathin23/tv-vibehack#40: APK 1.66 MB → 222 KB; with Gathin23/tv-vibehack#39, cold start ~1.6 s → 0.42 s |
| 2 | **Every Home press reloads all apps on the main thread**: package query, `loadBanner`/`loadIcon`/`loadLabel` for every app, then `notifyDataSetChanged` rebinds every tile. | `MainActivity.onResume` → `AppAdapter.reload()` | warm `focus_restored`, `resume`, `reload_load`; `tvhome:loadApp` slices | Gathin23/tv-vibehack#39: Home ~1.0 s → 0.41 s |
| 3 | ~~The tile entrance animation replays on every Home press.~~ Plays once since #2; confirm with a warm run. | `grid.scheduleLayoutAnimation()` in `onResume` | warm: frames after Home | Gathin23/tv-vibehack#39 also plays it only at start |
| 4 | **Banners at full source size** for every app, kept for the life of the activity and scaled at draw time. | `AppAdapter.reload()`, `item_app.xml` (`centerCrop`) | mem: native heap, graphics, PSS; `Slow bitmap uploads` | Gathin23/tv-vibehack#39: PSS 111 MB → 54 MB |
| 5 | **Full-screen overdraw**: black window background plus a black root view, plus a full-screen ambient glow view. | `styles.xml`, `activity_main.xml` | GPU percentiles in jank; `setprop debug.hwui.overdraw show` | Gathin23/tv-vibehack#39 removed the second fill |
| 6 | **Ambient glow**: a full-screen radial gradient whose colours change and whose alpha animates (500–600 ms) on dock focus changes. | `MainActivity.showHero`/`setAmbient` | jank and RenderThread `DrawFrame` while moving along the dock | – |
| 7 | **Focus lift** (since #2): each focus change runs scale springs on two tiles and animates `translationZ`, so the shadow is re-rendered every frame of the move. The floating glass highlight it replaced is gone. | `AppAdapter.Holder` | `DrawFrame` and GPU percentiles in jank; compare with #2's 60 fps on the TCL | – |
| 8 | **Colour sampling on the main thread** at the first focus of each dock app: a drawable copy drawn into a 64×36 bitmap, then a hue histogram over 2,304 pixels (since #1). | `AppAdapter.vividColor` | `tvhome:vividColor`, `ambient` log line | Gathin23/tv-vibehack#39 does it during loading |
| 9 | **Allocation per focus/bind**: `Rect`s in `onTileFocused`, new click listeners per bind, `getItemId` builds a string per call, `positionOf` is O(n) with strings. | `AppAdapter`, `MainActivity` | GC slices in a `keys` trace; Java heap | Gathin23/tv-vibehack#39 |
| 10 | **Screensaver redraws a full-screen gradient every frame** during the 8 s colour shift each minute: heat and power on an idle TV, not latency. | `ClockDream.shiftGlow` | `tvhome:dream.setGlow` count (`trace --action none` while dreaming) | Gathin23/tv-vibehack#39: 5 fps steps |
| 11 | Small main-thread work: `DockDecoration.onDraw` calls `findViewById` per tile per frame; `updateWifiLabel` makes binder calls in `onResume`; the Inputs menu sorts with `loadLabel` in the comparator; the grid's padding is set from a layout listener (second layout at start). | `DockDecoration`, `MainActivity` | their `tvhome:*` sections; `RV OnLayout` count at cold start | – |

Rough expectation: 1–4 decide how fast Home feels and how much memory the launcher takes; 5–7 decide
smoothness on weak GPUs; 8–11 are probably small, so fix them only if a trace says otherwise.
