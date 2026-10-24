# Friday: motion and visual polish

**Goal:** everything that moves feels fluid, consistent and quick, like Apple TV's focus, parallax and
transitions, and every picture is sharp, without costing frames on a slow TV.

## Scout

1. **Film it.** A walkthrough plan with a `record` step for each motion: focus moving along the Dock and down
   the grid, the Top Shelf changing, the top right opening, an app's actions, Home Settings opening, coming
   back to Home, the screensaver. Then look at it frame by frame:

   ```bash
   ffmpeg -i 03-dock-moves.mp4 -vf "fps=8,scale=480:-1,tile=8x4" -frames:v 1 contact.png      # 4 s on one sheet
   ffmpeg -ss 1.0 -t 0.5 -i 03-dock-moves.mp4 -vf "fps=60,scale=480:-1,tile=6x5" -frames:v 1 move.png   # one move, every frame
   ffprobe -v error -select_streams v -show_entries frame=pts_time -of csv=p=0 03-dock-moves.mp4 | head
   ```

   (On this Mac, `/opt/homebrew/bin/ffmpeg`; the one first on PATH may be broken.) Look for jumps, a frame
   where two states overlap, things that pop instead of moving, and motions that finish at different times.
2. **The numbers in the code.** Durations and curves should come from a few shared values
   ([docs/transitions.md](../../../../docs/transitions.md) and `Transitions`, `HomeMotion`):

   ```bash
   grep -rnE 'setDuration\(|duration="|Interpolator|SpringForce|setStiffness|setDampingRatio' launcher/app/src/main | sort
   ```

   One-off durations and curves are candidates.
3. **Frames.** `perf.py run --scenarios jank,dock --quick` and `trace --action dock` on your emulator: dropped
   frames during a motion are worse than a plain cut.
4. **Pictures.** Top Shelf art, banners and icons: their pixel size against the size they're shown at (blurry
   when scaled up, wasted memory when far bigger); visible banding in glows and gradients; edges of rounded
   corners.

## Metrics and the bar

| Metric | How | The bar |
|---|---|---|
| Distinct durations and curves | the grep in 2 | a few shared ones, as in `docs/transitions.md` |
| Glitches per motion | frame-by-frame | 0 |
| Dropped frames during motion | `jank`, `dock` | no worse than `main`; aim for none |
| Scaled-up pictures | pixel size vs shown size | 0 shown above their own size |

## Research

- Apple HIG: [Motion](https://developer.apple.com/design/human-interface-guidelines/motion),
  [Focus and selection](https://developer.apple.com/design/human-interface-guidelines/focus-and-selection)
  (tvOS focus and parallax), [Images](https://developer.apple.com/design/human-interface-guidelines/images)
- WWDC18 [Designing Fluid Interfaces](https://developer.apple.com/videos/play/wwdc2018/803/)
- Android: [Physics-based motion](https://developer.android.com/develop/ui/views/animations/spring-animation)
  (the launcher's DynamicAnimation), [Material motion](https://m3.material.io/styles/motion/overview)

## Typical fixes

Shared durations and springs instead of one-offs; motions that start from where the last one left off
(interruptible); a cross-fade where something pops; art decoded at the size it shows; dithering for banded
glows.

## Check it

The same clips before and after, with a contact sheet of the key frames in How tested; `jank` and `dock` no
worse than `main`. Motion feels different on the TV, so say what the TV clip from `/test-on-tv` should show.

## Pitfalls

- The emulator's software GPU drops frames a TV wouldn't, and the other way round: compare `main` and your build
  on the same emulator, never against TV numbers.
- `screenrecord` writes frames only when the screen changes: a clip can be shorter than asked.
- Animation scales must be 1 (`settings get global animator_duration_scale`); `perf.py` checks this.
- Reduced motion (animator scale 0) must still work: check it once.
