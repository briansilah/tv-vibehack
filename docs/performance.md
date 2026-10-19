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
| `tools/perf.py summarize` | The slowest slices on the launcher's main thread and RenderThread in a trace, its `tvhome:*` sections that span frames, its frames by jank type, and how long keys took to reach it. |
| Trace sections in the app | `tvhome:*` slices (below) show where the launcher's own code spends time. |
| Timing log lines | `TvHomePerf` log lines with app-side timings; `perf.py` switches them on while it runs. |

### Connecting

`perf.py` connects like the other tools, through `tools/tv.py` (see the README's
[Connecting](../README.md#connecting)): with `TV_HOST` set it uses `adb_shell` (no `adb` binary
needed). Without `TV_HOST`, or with `--adb`, it uses the `adb` binary, which you need for Android 11+
wireless debugging (TLS pairing). Pick the device with `--serial` or `ANDROID_SERIAL` when more than
one is attached.

```bash
TV_HOST=192.168.1.50 tools/venv/bin/python -I tools/perf.py run --label main
python3 tools/perf.py run --label main                                       # adb binary, the only device
ANDROID_SERIAL=192.168.1.50:37123 python3 tools/perf.py run --label main     # adb binary, one of several
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

**The remote's own keys.** `adb shell input` keys skip accessibility services, so on Google TV they also
skip `HomeButtonService`, which every key from the remote passes through. To time the remote, record
`tools/perf.py trace --action none --seconds 30` while someone presses keys on it. `summarize` then lists
the keys that reached the launcher by input source (`0x301` is the remote's D-pad), with the time from when
the key reached the TV until the launcher's main thread took it, and the time `HomeButtonService.onKeyEvent`
took in the `:home` process (Android 12+).

`run` takes about 4 minutes with the defaults. Options: `--scenarios cold,warm,jank,dock,mem`,
`--runs` (cold/warm repeats, default 10), `--rounds` (jank rounds, default 3), `--keys` (the D-pad
sequence), `--dock-moves` and `--dock-interval` (the dock scenario), `--via-home` (warm start through
the HOME intent, like pressing Home), `--other-app` (see below).

**Right after an install** the first warm (Home) numbers can come out up to about 10 ms slower; on the TCL TV a
second `run --scenarios warm --runs 15` of the same build matched the build before. Re-run when a Home
number moves by about that much.

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
| warm | Open another TV app, start the launcher again (or the HOME intent) | `TotalTime`; `focus_restored` = from `onResume` until the first frame with focus back on a tile, i.e. when Home is usable; `resume`; `reload` split into the package query and loading banners/icons/labels; frames drawn until Home settles (animations replayed on return; where the system fades Home in, as on Google TV, Home's 900 ms fall back into place draws about 55, see [transitions.md](transitions.md)) |
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
| `tvhome:reload` | `AppAdapter.reload()` on the main thread: waiting for the app list, then showing it |
| `tvhome:reload.query`, `tvhome:loadApp <package>` | loading the app list: the package query and each app's banner, icon and label, on the `tvhome-preload` and `tvhome-apps` threads (at start, while `onCreate` inflates the screen) |
| `tvhome:vividColor` | ambient colour sampling for a dock app, on its first focus |
| `tvhome:onTileFocused` | scrolling and the top shelf on each focus change |
| `tvhome:launch` | opening an app from a tile: starting it, then Home's push away; the `launch` log line splits it (`start_ms`, `check_ms`) |
| `tvhome:shelf` (async, Android 10+) | from a dock focus change until the top shelf shows that app; the `shelf` log line has the same time |
| `tvhome:updateWifiLabel`, `tvhome:showInputs` | network pill refresh, Inputs menu |
| `tvhome:DockDecoration.onDraw` | the dock panel |
| `tvhome:dream.setGlow` | screensaver glow colour, per animation frame |

RecyclerView adds its own (`RV OnLayout`, `RV OnBindView`, `RV CreateView`), and the system adds
`Choreographer#doFrame`, `inflate`, `DrawFrame` (RenderThread), binder calls and GC.

## Suspected hotspots

From reading the code, before anything was measured; [Findings on a TV](#findings-on-a-tv) says what a
TV showed for each. Measure a suspect before fixing it, and record the result here or in the fix's pull
request. "Reference" numbers come from upstream pull requests measured on a Xiaomi MiTV-MOOR2 (Android 11),
not from our TVs.

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

## Findings on a TV

A TCL Smart TV Pro: Google TV, Android 14, MediaTek, 4 cores, 2.3 GB RAM, a 1920×1080 UI at 60 Hz. Measured
with `perf.py` over wireless debugging, with `com.tcl.ui_mediaCenter` as the background app. Release builds
compiled with `speed`, as the install scripts leave them, unless the table says debug.

**How the launcher feels there**, with the fixes below: a dock move shows the new app's name and glow in
about 260 ms (the animations' length), frames take 7 ms (99th percentile 9 ms, none janky), a key from the
remote reaches the launcher in 3 ms, Home comes back in about 50 ms, and a cold start takes about 370 ms.
Moving between dock apps, holding Right along the dock, Home from an app and the top bar all felt snappy
with the real remote.

| # | Finding | Result |
|---|---|---|
| New | **Font weights cost 10–12 ms per view.** Every TextView with its own `android:fontVariationSettings` built a new typeface as it inflated, and with it a copy of every variable system fallback font; the same view without it took 0.4 ms. A cold start inflated 30 of them (230 ms in a trace), and a tile created while scrolling held the main thread for 13 ms, 63 ms once when the TV was short of memory. Native memory went too. | Views take the weight from a style: on Android 9+ one font family per weight (`res/font-v28`), loaded once and shared. Cold start 711 → 502 ms, native heap 33 → 25 MB, tile creation 13 → 2 ms. Screenshots are pixel-identical. |
| 2 | **The app list loaded on the main thread at every cold start**, one app after another, after the screen had inflated: about 165 ms for 21 apps. | It loads in the background from `onCreate`, the apps in parallel, while the screen inflates: `onResume` waits 2–4 ms for it. Cold start 502 → 368 ms. |
| 11 | `updateWifiLabel` made its binder calls in `onResume`, before Home's first frame: 4–5 ms (12 ms in a traced cold start). | It runs after the first frame, and an unchanged label no longer lays out the top bar: `onResume` 4.4 → 0.2 ms. Home's end-to-end time doesn't move beyond noise. |
| 5–7 | **Drawing is not a bottleneck here.** A full-screen frame takes 3.3–3.7 ms on the RenderThread (20 ms on the emulator), the main thread about 1 ms per frame, and no frame is janky while moving along the dock. | The glow, the focus springs and the shadows stay as they are. |
| – | **The remote's keys through `HomeButtonService`**: 2.9 ms median, 6.7 ms at most, from the key reaching the TV until the launcher's main thread takes it (163 keys); `onKeyEvent` itself 0.08 ms. Keys from `adb shell input`: 3–11 ms. | Nothing to fix. |
| 4 | Banners are 320×180 to 426×240 px, about the focused tile's size. | Nothing to fix. |
| 9 | `onTileFocused` takes 0.15 ms and a key 0.2 ms in all. | Nothing to fix. |
| 10 | Not this TV's screensaver; not measured. | – |
| – | Home's first frame after an app (about 35 ms) is mostly the system allocating the window's buffers (34 ms on the RenderThread); opening an app, the system's `startActivity` call takes 19 ms. Waking from standby, Google TV's home showed for about half a second before `HomeButtonService` covered it (seen once in the log, not traced; started by hand, the stock home is covered within 64 ms). | Outside the launcher, or not traced yet. |

[#9](https://github.com/briansilah/tv-vibehack/pull/9)'s fixes on this TV (its measurement commit 434d858
as the baseline, and `main` at 49271fe, which also has #8 and #10):

| Metric (median) | baseline, debug | `main`, debug | `main`, release |
|---|---|---|---|
| Dock move: top shelf shows the new app | 638 ms | 252 ms | 258 ms |
| Frames drawn per round of dock moves | 311 | 148 | 144 |
| Home: frames drawn until it settles | 38 | 2 | 2 |
| Home: `onResume` main-thread time | 50.6 ms | 10.7 ms | 4.5 ms |
| Home / warm start (TotalTime) | 131.5 ms | 118 ms | 55.5 ms |
| Cold start (TotalTime) | 1889 ms | 1966 ms | 717.5 ms |
| Frames while moving along the dock: 50th / 99th percentile | 7 / 9 ms | 8 / 12 ms | 7 / 8 ms |
| Memory (PSS) | 90.1 MB | 92.7 MB | 81.5 MB |
| APK size | 1638 KB | 1669 KB | 245 KB |

The shelf (−60 %) and Home (38 → 2 frames) changes match the emulator's. The TV differs in two ways: it
draws 6 times faster than the emulator's software GPU, so the emulator's frame times say little about it,
and its cold starts are 3 times slower (21 real apps to load instead of a few stubs, on a TV short of
memory), which is why they were the next thing to fix. In the debug build `main`'s D-pad frames were
slightly slower than the baseline's (50th percentile 7 → 9 ms, 1.4 % janky); the release build has no janky
frames.

## Earlier findings (Android TV emulator)

Measured with `perf.py` on an Android TV emulator (Android 14, 1920×1080 at 320 dpi, 2 cores, 2 GB,
software GPU), with stub apps standing in for the dock apps. Emulator numbers are relative only:
they rank causes and show which way a change moves, not what the TV does. Each change was measured on
its own; the before/after tables are in the pull request that made it.

| | Finding | What changed |
|---|---|---|
| New | **The top shelf waited on purpose**: 280 ms for focus to settle, then 100 + 220 ms of fades. The new app's name showed 674 ms after the move, and each move drew about 32 frames. This is most of "moving between two apps feels slow". | The shelf follows focus at once with short fades: 270 ms, about 16 frames per move. |
| 2 | Confirmed: every Home press reloaded the app list (8.8 ms of `onResume`, 7.5 ms of it the reload), then faded the shelf in again (27 frames). | Reload only when a TV app changes, and keep the shelf: 0.8 ms, 2 frames. |
| 1 | Confirmed: the debug build. A release build compiled with `speed` cuts cold start by 37 %, PSS by 18 % and the APK by 86 %. `adb install` leaves a release build at `verify` (no ahead-of-time code; an embedded Baseline Profile needs ProfileInstaller or Play), so the install scripts compile it. | The install scripts build and compile a release build. |
| 8 | Confirmed, small: 1.3–1.7 ms on the main thread per dock app, at its first focus. | Sampled on a background thread after loading. |
| 5 | Not measurable on the emulator: removing the root's black fill, `forceHasOverlappingRendering(false)` on the glow, an opaque glow. RenderThread time per frame stayed at about 20 ms. | Not applied. On the TV a whole frame draws in about 3.5 ms (above). |
| 6 | **The glow is the most expensive part of a frame**: 20.2 ms of RenderThread time per frame with it, 14.9 ms without. A hardware layer on it makes frames slower (23.3 ms). | Fewer animated frames (above) is what helped. Not worth more on the TV. |
| 7 | Tile shadows and rounded clipping cost at most 0.5 ms per frame. | Left as they are; the TV agrees. |
| – | Changing the shelf's name laid out the screen again on every swap (8 measure/layout passes per 8 moves). | The name view is full width: 1 pass. |
