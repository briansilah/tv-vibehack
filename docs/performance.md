# Performance: measure first, then fix

The launcher runs on TVs with 1 GB of RAM and slow CPUs, so performance work here is data-driven:
measure on a real TV, find where the time goes, change one thing, measure again. Each fix's pull
request should include a before/after table from the same TV.

The measurements stay local. The launcher only writes to the TV's own log when you ask it to, and it
sends no telemetry (CONTRIBUTING.md); its only network use is the top shelf's pictures.

## Tools

| | What it gives you |
|---|---|
| `tools/perf.py plan` | Which scenarios, D-pad keys and traces your branch's diff needs, and why ([below](#measure-only-what-you-changed)). No TV needed. |
| `tools/perf.py run` | Cold start, Home (warm start), D-pad frame jank, dock moves (top shelf latency) and memory, each repeated, saved as JSON with medians. `--scenarios auto` runs only what `plan` picks. |
| `tools/perf.py compare` | A Markdown before/after table for a pull request; `--against main` takes `main`'s side from the shared cache. |
| `tools/perf.py baseline` | `main`'s numbers, measured once per change to what affects them and shared by every worktree ([below](#reusing-mains-baseline)). |
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
2. **Baseline.** `tools/perf.py baseline ensure` with the options you'll measure yours with: it takes `main`'s
   numbers on this TV from the cache, or measures them when nothing that changes them has been measured yet
   (see [Reusing main's baseline](#reusing-mains-baseline)).
3. **Change one thing**, install that build and run `tools/perf.py run --label my-fix`.
4. **Compare:** `tools/perf.py compare --against main tools/perf-results/my-fix.json` (or two result files)
   and paste the table into the pull request. It refuses results that would mislead
   ([Measure the main that ships](#measure-the-main-that-ships)). Medians of 10 runs; treat changes smaller than about
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

`run` takes about 4 minutes with the defaults. Options: `--scenarios cold,warm,jank,dock,mem` (or `auto`, see
[Measure only what you changed](#measure-only-what-you-changed)), `--runs` (cold/warm repeats, default 10),
`--rounds` (jank rounds, default 3), `--quick` (5 and 2), `--keys` (the D-pad sequence, or presets), `--dock-moves` and `--dock-interval` (the dock scenario),
`--via-home` (warm start through the HOME intent, like pressing Home), `--other-app` (see below), `--variant` (see
[Measure the main that ships](#measure-the-main-that-ships)).

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
for A and B: `compare` refuses different ones. On the TCL TV, use `--other-app com.tcl.ui_mediaCenter`.

### Measure the main that ships

A before/after table is only as good as its baseline, and the baseline is the current `main`'s launcher
as it ships: a release build compiled with `speed`, in the configuration `config.xml` gives it, with the
TV's animations at their normal speed. Not an older `main`, a retired variant (like the transitions
before #12), or your branch from before it had `main`'s latest changes.

- **Merge the latest `main` into your branch, then measure once, at the end.** Numbers taken before the
  merge compare against a `main` that no longer exists: on 2026-10-19 two branches measured `main` with
  the old transitions in the morning, merged the new ones at noon, and had to measure everything again.
- **Measure a variant only when your change adds or changes it**: another Liquid Glass look or stand-in
  art for changes to the glass or the top shelf's art, with the same `--variant` for `main` and yours:
  `run --variant '--ez glass_test_art true'` or `--variant '--ei liquid_glass 50'`. A build with
  `liquid_glass_blur=false` is a variant too.

**Every APK says what it was built from.** The Gradle build adds a stamp to `versionName`:
`2.0+commit-<sha>.launcher-<tree>[.dirty]`, where `<tree>` is the git tree hash of `launcher/` as it
was on disk, uncommitted changes included, and `.dirty` says there were some. A clean build's tree is
`git rev-parse HEAD:launcher`; `tools/provenance.py` computes both. It works in a shallow clone; without
git (a source zip) the version is a plain `2.0`. See what a TV has installed with
`adb shell dumpsys package com.gathin.tvhome | grep -E 'versionName|lastUpdateTime'`.

**Every result says what it measured.** `run` records the stamp in `build.provenance` (`commit`,
`launcher_tree`, `dirty`) and the install time in `build.installed`. Under `config` it records the
system animation scales (`window`, `transition`, `animator`), which decide which transitions run, and
what the launcher reports about itself: the Liquid Glass look in effect and `config.xml`'s, the saved
choice, the glass tier and stand-in art. It warns when the build has no stamp, when a scale isn't 1,
and when a result labelled `main` isn't the current `origin/main` as it ships.

**The shipped configuration, without losing the TV owner's.** `run` and `trace` start the launcher
with `--ez default_config true`: for that process only, it shows `config.xml`'s Liquid Glass instead
of the slider's saved choice, which stays saved, and drops stand-in art. Meanwhile `--ei liquid_glass`
isn't saved either, which is how `--variant` measures another look. Every start passes it (a cold
start is a new process), and the run ends with `--ez default_config false`, back to the saved choice
without stand-in art. Don't reset a TV with `--ei liquid_glass` on its own: it saves the value as the
owner's choice, and `-1` forgets it.

**`compare` refuses results that would mislead.** The first file is the baseline. It checks the
results against each other, and with git against `origin/main` as of your last `git fetch` (`--main`
picks another ref):

| Refused | Because |
|---|---|
| A build without a stamp | nothing says what was measured |
| A baseline whose `launcher/` isn't `origin/main`'s, or with uncommitted changes | `main` has moved since it was measured, or it isn't `main`: measure `main` again. `main` moving only outside `launcher/` is fine |
| A baseline that's a debug build, not compiled `speed`, not in its shipped configuration, or measured with an animation scale other than 1 | it isn't `main` as it ships. Compiled otherwise with the other build compiled the same is only a note: ART compiles the default Home app its own way (an emulator, TVs other than Google TV) |
| A build that doesn't contain the baseline's `main` | merge `main`, rebuild and measure again; otherwise the change column mixes `main`'s own changes (say, new transitions) into yours |
| Different devices, build types, compiler filters, background apps, animation scales, `--settle`, D-pad keys, or configurations | the change would come from the difference |

`--force` prints the table anyway, with the reasons in bold under it. Some things are only noted under
the table: a build with uncommitted changes (commit what you measured), the same `launcher/` in two
configurations (a variant comparison), and what git couldn't check (a commit that isn't in your
clone: `git fetch`).

### Reusing main's baseline

`main`'s numbers on a device only change when something that changes them does, so `perf.py` keeps them in a cache
that every worktree on the computer shares (`$(git rev-parse --git-common-dir)/tvhome-perf`, never committed;
`TVHOME_PERF_CACHE` moves it), and measures `main` only for what isn't in it:

```bash
tools/perf.py baseline ensure --scenarios dock,mem   # main on this device: from the cache, or measured now
launcher/build-and-install.sh                        # then your build
tools/perf.py run --label my-fix --scenarios dock,mem
tools/perf.py compare --against main tools/perf-results/my-fix.json   # no device needed
tools/perf.py baseline install                       # main's release build back on the device
```

Run `ensure` before you install your build: when something isn't cached, it installs `main`. Each scenario's
numbers are cached under:

| | |
|---|---|
| `main`'s `launcher/` | its tree, `git rev-parse origin/main:launcher`: merges of docs or tools keep the baseline, any change in `launcher/` measures it again |
| The device | its model, build fingerprint (an update measures again) and unit: the serial number, kept hashed, or an emulator's AVD, so emulators started from one AVD share a baseline. Also its animation scales and its set of TV apps, which the launcher loads at every start |
| The scenario's code | a hash of its function in `perf.py` and every function and constant it uses, comments and docstrings left out: a change to how `jank` measures measures `jank` again, not `cold` |
| Its options | `cold`: `--runs`, `--settle`, the background app; `warm`: the same and `--via-home`; `jank`: `--rounds`, `--keys`, `--key-interval`; `dock`: `--rounds`, `--dock-moves`, `--dock-interval`; `mem`: the scenarios that ran before it, which leave pictures in memory. `--variant` for all of them: a variant never comes from the plain baseline |
| The build | `main`'s release build, built once per `launcher/` tree in a temporary worktree and cached too (`baseline apk`) |

`ensure` takes `run`'s options. A hit installs and measures nothing. For what's missing (with `mem`, also the
scenarios before it), it builds `main`'s APK unless a session already has, installs it over the launcher
(`install -r`, compiled like the install scripts do), waits `--install-wait` seconds (60), measures with `run`, and
caches what `run` recorded, stamp and configuration included. It doesn't cache a measurement of another APK than
main's (by its sha256 and stamp) or of the launcher outside its shipped configuration. It also measures again what
is older than `--max-age` days (7). `baseline show` lists what's cached for `main`'s
`launcher/` on each device, `baseline list` the whole cache.

`compare --against main` looks the baseline up from your result's device, options and scenario code. When it isn't
cached, it says what differs and prints the `ensure` command. Then it makes the same checks as `compare` (above),
with `--force` the same way, and also refuses when ART compiled `main` and yours differently: install yours again
with `launcher/build-and-install.sh` and measure it again, which compiles it the way the device now compiles `main`. ART compiles the device's default Home app with `speed-profile`, which is `verify` until there's a
profile, whatever the install asks for. On TVs other than Google TV the launcher is the default Home, so both come
out `verify` there; on Google TV the stock home stays the default, and both are `speed`. **On an emulator** `run`
and `baseline` give Home back to the emulator's stock launcher (from the HOME role, which
`launcher/build-and-install.sh` gives the launcher there) and compile it `speed` first, so every emulator measures
the same build and their shared baseline doesn't flip between `verify` and `speed`; `--via-home` keeps the launcher
as Home and only notes it.

**When to measure `main` again.** A TV drifts over days: apps update, memory fills up. `compare` says how old the
baseline is. When a difference is within the noise, measure `main` again right before or after yours with
`tools/perf.py baseline refresh` and the same options: it reinstalls `main` and measures those scenarios again,
cached or not. Do the same on an emulator, whose numbers move with whatever else the computer is running.

### Measure only what you changed

Each scenario costs TV time: with the defaults about 70 s for `cold`, 40 s for `warm`, 30 s for `jank`, a minute
for `dock` and 10 s for `mem`, for each build. `perf.py plan` reads your branch's diff against `origin/main`
(commits, uncommitted changes and new files) and says which scenarios, D-pad keys and traces it needs, and why.
`run --scenarios auto` runs just those. `plan` itself needs no TV.

```bash
tools/perf.py plan                                     # -v: every reason; --json: for scripts
tools/perf.py plan --base 9a18332 --head 826f811       # any two commits
tools/perf.py baseline ensure --scenarios auto         # main, for the same scenarios, keys and options
launcher/build-and-install.sh                          # then your build
tools/perf.py run --label fix --scenarios auto         # add --quick on an emulator, to both
tools/perf.py compare --against main tools/perf-results/fix.json
```

How it decides. The map is `tools/perf_scope.py`, tested in `tools/tests/test_perf_scope.py`:

- **By file**: docs need nothing, the tools need their tests, and the build, ProGuard rules and dependencies need
  everything. Each class and resource gets the scenarios that run its code: `ProfileCorner` gets `jank` with the
  corner keys, `TopShelfView` gets `dock` and `mem`.
- **By member**, in the files that touch most of the launcher (`MainActivity`, `AppAdapter`, `TopShelf`,
  `activity_main.xml`, `styles.xml`, `config.xml`, the manifest): the method, field, view, style or resource the
  changed lines are in. `showHero` gets `dock`; `onResume` gets `cold` and `warm`. `onCreate` sets up everything,
  so it goes statement by statement, by what each one names: `corner.setBar(topBar)` gets `cold` and `mem`, plus
  the corner's `jank`.
- Comments, blank lines and imports count for nothing.
- **Code without a rule** (a new class, a private helper, a new drawable or style) gets what the code that uses it
  gets. Where nothing uses it, or a statement names nothing it knows, the plan falls back to everything: a missed
  regression costs more than an extra minute.
- **Variants**: changes to the glass or its stand-in art also get runs over stand-in art, `--variant '--ez
  glass_test_art true'`. `run --scenarios auto --variant '...'` runs the plan's scenarios for that variant.
- **What no scenario measures** gets a note instead: the screensaver (a trace while it runs), opening an app (a
  trace of Select on a tile), `HomeButtonService` (the TV and its remote: `warm` doesn't go through it, and it's
  off on an emulator), menus and sheets (look at them on your emulator).

An explicit `--scenarios` or `--keys` overrides the plan; `plan` prints the command, ready to edit. When the map is
wrong, fix `tools/perf_scope.py` and its tests.

**D-pad key presets**, for `jank` and `trace --action keys`: `--keys corner`, or several in turn,
`--keys grid,corner`. Each starts and ends on the first dock app, and `jank` moves focus there before it starts.

| Preset | Keys |
|---|---|
| `default` | along the dock and into the first two grid rows (16 keys) |
| `grid` | three rows down the grid and back, scrolling the page (needs 16 apps) |
| `dock` | along the dock and back, twice, at D-pad speed |
| `corner` | up into the top right (through the top shelf's button when it shows one), along its buttons, Back to the dock; twice |
| `arrange` | down to the Arrange apps tile (32 apps at most), pick up the first app and move it, then Back, Back, which puts it back without saving. On a shared TV never end with Select: putting an app down saves the order. |

On an emulator `--quick` (5 cold and warm starts, 2 rounds) is enough to see which way a number moves. Use the
defaults on the TV, for the pull request's table.

## What is measured

| Scenario | How | Numbers |
|---|---|---|
| cold | Open another TV app, `am kill` the launcher, `am start -W` it | `TotalTime` (system: until the first frame); fully drawn (until focus is on a tile, via `reportFullyDrawn`); app-side `create`, `resume`, `reload` |
| warm | Open another TV app, start the launcher again (or the HOME intent) | `TotalTime`; `focus_restored` = from `onResume` until the first frame with focus back on a tile, i.e. when Home is usable; `resume`; `reload` split into the package query and loading banners/icons/labels; frames drawn until Home settles (animations replayed on return; where the system fades Home in, as on Google TV, Home's 900 ms fall back into place draws about 55, see [transitions.md](transitions.md)) |
| jank | Focus to the first dock app, `dumpsys gfxinfo reset`, 16 D-pad moves 0.35 s apart (or a preset's, `--keys`), `dumpsys gfxinfo` | janky frames %, frame-time percentiles, slow UI thread and high input latency counts |
| dock | Focus on the first dock app, then Right/Left between the first two dock apps, one press every 1.2 s, 8 per round | `shelf`: from the focus change until the top shelf shows the new app, split into the name (`text`; when the art shows its own title, until the name has gone), the glow colour (`glow`) and the art (`art`: its cross-fade, or 0 when there's none ready); janky frames and frame percentiles of those moves |
| mem | `dumpsys meminfo` on Home after the other scenarios | PSS total, Java heap, native heap (bitmaps live here), graphics, view count |

The JSON also records the device, the Android version, the APK size on the TV, whether the build is
debuggable, and how ART compiled it (`verify`, `speed-profile`, `speed`), because each of these
changes every number; and what the build was built from and how it was configured
([above](#measure-the-main-that-ships)).

To read the app's timings yourself:

```bash
tools/tv.py 'setprop log.tag.TvHomePerf DEBUG'
tools/tv.py 'logcat -d -s TvHomePerf'
```

Besides the timings above, the top shelf logs `shelf_fallback` when focus comes to a dock app whose title isn't
decoded yet, so its cover art or the glow shows first, and `shelf_step`, `shelf_carousel` and `shelf_arrived` when a
title comes on screen after Left or Right, the carousel, or a fallback: how long after the move (waiting for its
picture), its place in the app's list (`i`, `n`) and its name.

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
| (threads) `tvhome-shelf`, `tvhome-shelf-net` | the top shelf's art: decoding pictures, cutting cover art, reading TvProvider; and fetching catalogues and pictures. Both at background priority |
| `tvhome:updateWifiLabel`, `tvhome:showInputs` | network pill refresh, Inputs menu |
| `tvhome:DockDecoration.onDraw` | the dock panel |
| `tvhome:glass.record` | Liquid Glass (`LiquidGlass`): recording its copy of the top shelf behind the dock or the top right capsule, on frames where the shelf or the glass moved or changed; the GPU then blurs it during the RenderThread's `DrawFrame` |
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
| 12 | **Liquid Glass** on the dock and the top right capsule: each records the top shelf into a half-resolution RenderNode and blurs it (plus saturation, and an AGSL lens on Android 13+) whenever the shelf changes (every frame of a glow or art cross-fade) or the glass moves (every frame of a scroll). | `LiquidGlass`, `DockDecoration`, `ProfileCorner` | `tvhome:glass.record` count and length; RenderThread `DrawFrame` and jank while moving along the dock | – |
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
| New | **Font weights cost 10–12 ms per view.** Every TextView with its own `android:fontVariationSettings` built a new typeface as it inflated, and with it a copy of every variable system fallback font; the same view without it took 0.4 ms. A cold start inflated 30 of them (230 ms in a trace), and a tile created while scrolling held the main thread for 13 ms, 63 ms once when the TV was short of memory. Native memory went too. | Views take the weight from a style: on Android 9+ one font family per weight (`res/font-v28`), loaded once and shared. Cold start 711 → 502 ms, native heap 33 → 25 MB, tile creation 13 → 2 ms. Screenshots are pixel-identical. Since then the launcher is in Inter, as one small static font per weight (`res/font`, built by `tools/fonts.py`): no variation at all, on any Android version. |
| 2 | **The app list loaded on the main thread at every cold start**, one app after another, after the screen had inflated: about 165 ms for 21 apps. | It loads in the background from `onCreate`, the apps in parallel, while the screen inflates: `onResume` waits 2–4 ms for it. Cold start 502 → 368 ms. |
| 11 | `updateWifiLabel` made its binder calls in `onResume`, before Home's first frame: 4–5 ms (12 ms in a traced cold start). | It runs after the first frame, and an unchanged label no longer lays out the top bar: `onResume` 4.4 → 0.2 ms. Home's end-to-end time doesn't move beyond noise. |
| 5–7 | **Drawing is not a bottleneck here.** A full-screen frame takes 3.3–3.7 ms on the RenderThread (20 ms on the emulator), the main thread about 1 ms per frame, and no frame is janky while moving along the dock. | The glow, the focus springs and the shadows stay as they are. |
| – | **The remote's keys through `HomeButtonService`**: 2.9 ms median, 6.7 ms at most, from the key reaching the TV until the launcher's main thread takes it (163 keys); `onKeyEvent` itself 0.08 ms. Keys from `adb shell input`: 3–11 ms. | Nothing to fix. |
| 4 | Banners are 320×180 to 426×240 px, about the focused tile's size. | Nothing to fix. |
| 9 | `onTileFocused` takes 0.15 ms and a key 0.2 ms in all. | Nothing to fix. |
| 10 | Not this TV's screensaver; not measured. | – |
| – | Home's first frame after an app (about 35 ms) is mostly the system allocating the window's buffers (34 ms on the RenderThread); opening an app, the system's `startActivity` call takes 19 ms. Waking from standby, Google TV's home showed for about half a second before `HomeButtonService` covered it (seen once in the log, not traced; started by hand, the stock home is covered within 64 ms). | Outside the launcher, or not traced yet. |
| 12 | **Liquid Glass** (the dock and the top right). The TV's GPU (Mali-G57) pays about 0.4 ms for each pass over a texture, whatever its size, and the first version took five per piece of glass (copy, colour, blur twice, lens) on every frame the top shelf changed (104 of a dock round's 144): RenderThread 3.5 → 6.9 ms a frame, frames 7 → 10 ms. Window blur isn't an option: this TV has cross-window blur off (`mBlurEnabled=false`). | Two passes per glass: the copy, then one AGSL shader that bends, blurs (9 filtered taps) and saturates; the copy at half resolution, a quarter for stronger looks. Frames 9 ms (main 7), 0 % janky over 9 rounds of D-pad moves, top shelf latency unchanged (257 ms), PSS +8 MB. Over a full-screen picture that cross-fades on every dock move (a bitmap stand-in for cover art, `GlassTestArt`), the same build with and without the blur: frames 12 vs 10 ms, GPU 90th percentile 8 vs 8 ms, D-pad moves 1.0 vs 0.7 % janky (full-screen pictures cross-fading jank a little on their own: worth watching when the top shelf shows art), top shelf 257 vs 259 ms. Pre-Android 12 and low-RAM TVs, or `liquid_glass_blur=false`, skip it. |

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
