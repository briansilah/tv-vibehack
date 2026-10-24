# Monday: performance

**Goal:** Home shows sooner, moves more smoothly and uses less memory, on TVs with 1 to 2 GB of RAM and slow
CPUs. What people feel: pressing Home, moving along the Dock, the Top Shelf catching up, scrolling the grid.

## Scout

1. **The TV's numbers on record.** Earlier TV runs are in the shared perf cache, so you can read real TV
   numbers without the TV. The newest ones for `main`:

   ```bash
   RUNS="$(git rev-parse --git-common-dir)/tvhome-perf/runs"
   for f in $(ls -t "$RUNS"/*.json | head -40); do
     python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); sys.exit(d["label"] != "main" or bool(d["device"].get("emulator_avd")))' "$f" && echo "$f"
   done | head -3 | xargs python3 tools/perf.py compare --force
   ```

   Also read [docs/performance.md](../../../../docs/performance.md): "Findings on a TV" and "Suspected
   hotspots" list what's known to be slow and what was already tried.
2. **Your emulator, all scenarios, for `main`:** `python3 tools/perf.py baseline ensure --quick` (from the cache
   when another session already measured this `main`). Relative only, but it shows which scenario moves.
3. **Where the time goes.** Trace what the numbers point at, then list the slowest sections:

   ```bash
   python3 -m venv tools/venv && tools/venv/bin/pip install -q -r tools/requirements.txt perfetto
   tools/venv/bin/python tools/perf.py trace --action cold      # or home, dock, keys --keys grid
   tools/venv/bin/python tools/perf.py summarize tvhome-<time>.perfetto-trace
   ```

   Look at the `tvhome:*` sections on the main thread, long `DrawFrame`s on the RenderThread, and frames by
   jank type.
4. **The code.** Work in `onCreate`, `onResume` or `onTileFocused` that could come after the first frame or
   off the main thread; bitmaps decoded bigger than they're shown; allocation or work in `onDraw`; whole-list
   `notifyDataSetChanged`; views that stay invisible but are laid out; view count (`mem`'s Views).

## Metrics and the bar

| Metric | How | The bar |
|---|---|---|
| Cold start | `cold`: `am start` TotalTime | Android vitals counts 5 s and over as excessive; ours should stay well under 1 s on the TV |
| Coming back to Home | `warm` | vitals: 2 s excessive; people notice anything over about 100 ms (RAIL's response budget) |
| Smoothness | `jank`: frames over the 16.7 ms budget at 60 Hz, and frozen frames (over 700 ms) | vitals: more than half the frames slow is "slow rendering"; aim for none slow on D-pad moves |
| Top Shelf latency | `dock`: focus change to shelf shown | under 100 ms feels instant |
| Memory | `mem`: PSS, native heap (bitmaps), graphics | as low as possible: 1 GB TVs kill background apps first |
| APK size | `mem`'s APK size | smaller loads faster; no new dependencies |

## Research

- Android: [App startup time](https://developer.android.com/topic/performance/vitals/launch-time),
  [Slow rendering](https://developer.android.com/topic/performance/vitals/render),
  [Memory](https://developer.android.com/topic/performance/memory),
  [TV app quality](https://developer.android.com/docs/quality-guidelines/tv-app-quality)
- [Perfetto](https://perfetto.dev/docs/) for reading traces; [RAIL](https://web.dev/articles/rail) for what
  people perceive
- Apple, for the feel to match: WWDC19 [Optimizing App Launch](https://developer.apple.com/videos/play/wwdc2019/423/)

## Typical fixes

Defer what isn't on screen until after the first frame; move work to the launcher's background threads; decode
bitmaps at the size they're shown and cache them; update one item instead of the whole list; precompute
colours and paths; skip redraws when nothing changed.

## Check it

`perf.py compare --against main` on your emulator for the scenarios `perf.py plan` picks, and a second trace
showing the section that shrank. Both go in How tested, marked as the emulator's. The TV numbers come from
`/test-on-tv`.

## Pitfalls

- The emulator's GPU is software: its frame times say little about a TV's. Trust it for which way start-up and
  main-thread time move, not for jank in absolute terms.
- The first warm run after an install can be about 10 ms slower: re-run before believing a small warm change.
- Measure release builds only (`perf.py` records which); debug builds are much slower.
- Baseline Profiles need a new dependency (`profileinstaller`): open an issue instead.
