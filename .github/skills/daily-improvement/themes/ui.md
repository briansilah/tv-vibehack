# Wednesday: UI

**Goal:** every screen looks like one considered design from three metres away: aligned, legible, consistent
spacing, type and colour, inside the TV's safe area, at the standard of Apple TV's own screens.

## Scout

1. **A tour of every screen.** Write a walkthrough plan (`tools/walkthrough.py`) that screenshots each:
   Home with the Dock highlighted, the grid scrolled, the top right and Quick Settings, the Inputs menu, an
   app's actions (hold OK), Home Settings (its top level, an app's Top Shelf page, Custom), the Liquid Glass
   slider, Arrange mode, and the screensaver (README, "The launcher"). Run it with
   `"default_config": true` and stand-in art (`{"launcher": true, "glass_test_art": true}`) so pictures repeat
   from run to run. Keep the plan: it's the before and after for the day's change.
2. **Measure the pictures, not only look.** `adb shell uiautomator dump /data/local/tmp/ui.xml` on each
   screen gives every view's bounds (pull it, then delete it from the device). At 1920×1080 the launcher draws
   at 320 dpi: 2 px per dp. From the bounds and the pixels:
   - **Safe area**: distance of every element from the screen's edges.
   - **Alignment**: left edges and baselines that should line up but are a few pixels apart.
   - **Spacing**: gaps between neighbours that should match but don't.
   - **Contrast**: text and the colour behind it, sampled from the PNG (Pillow is in `tools/requirements.txt`),
     as a WCAG ratio.
3. **The values in the code.** Inventories show drift: many near-identical values where a few would do.

   ```bash
   grep -rhoE '[0-9.]+dp' launcher/app/src/main/res | sort | uniq -c | sort -rn
   grep -rhoE 'textSize="[^"]+"|[0-9.]+sp' launcher/app/src/main/res launcher/app/src/main/java | sort | uniq -c | sort -rn
   grep -rhoE '#[0-9A-Fa-f]{6,8}' launcher/app/src/main/res launcher/app/src/main/java | sort | uniq -c | sort -rn
   grep -rhoE 'setTextSize\([^)]*\)|dp\([0-9.]+\)' launcher/app/src/main/java | sort | uniq -c | sort -rn
   ```

4. **Other sizes and languages.** The `Television_4K` AVD (3840×2160) for scaling; German for longer words:
   `adb shell cmd locale set-app-locales com.gathin.tvhome --locales de-DE` (and `--locales ""` to undo).
   Truncated or wrapped labels are candidates.
5. **Compare with the reference.** Put Apple TV's equivalent screen (HIG pictures, WWDC) next to ours and name
   the differences you can measure.

## Metrics and the bar

| Metric | How | The bar |
|---|---|---|
| Elements outside the safe area | bounds from `uiautomator dump` | Google TV: keep 48 dp at the sides and 27 dp at the top and bottom (5 % overscan); check the HIG's tvOS safe area too |
| Misaligned edges | bounds that should match, in px | 0 |
| Distinct spacing values | the dp inventory | a short scale, such as multiples of 4 or 8 dp |
| Distinct text sizes | the sp inventory | a deliberate type scale, as the HIG and Google TV's typography set |
| Text contrast | WCAG ratio from pixels | 4.5:1 for body text, 3:1 for large text and focus indicators (WCAG 2.2) |
| Truncated labels | German and 4K screenshots | 0 |

## Research

- Apple HIG: [Designing for tvOS](https://developer.apple.com/design/human-interface-guidelines/designing-for-tvos),
  [Layout](https://developer.apple.com/design/human-interface-guidelines/layout),
  [Typography](https://developer.apple.com/design/human-interface-guidelines/typography),
  [Color](https://developer.apple.com/design/human-interface-guidelines/color),
  [Materials](https://developer.apple.com/design/human-interface-guidelines/materials),
  [Focus and selection](https://developer.apple.com/design/human-interface-guidelines/focus-and-selection)
- Google TV: [Design for TV](https://developer.android.com/design/ui/tv), its layouts, typography and color guides
- [WCAG 2.2](https://www.w3.org/TR/WCAG22/): 1.4.3 Contrast (Minimum), 1.4.11 Non-text Contrast

## Typical fixes

Snap spacing to the scale; one corner radius per kind of element; align edges that almost line up; type sizes
from a few styles in `styles.xml`; raise contrast on dim text; move content into the safe area; give German
room.

## Check it

The same tour plan before and after, side by side in How tested, with the measured numbers (for example
"distinct spacing values 23 → 9; contrast of the Settings explanation 3.1 → 4.7:1"). Layout and style changes
also get `perf.py plan`'s scenarios (often `cold` and `mem`).

## Pitfalls

- Change the look a little, with evidence. A redesign is for Brian to decide: put it in the backlog with
  pictures.
- The Top Shelf's art and Liquid Glass change per app; use stand-in art for comparable pictures.
- `uiautomator dump` can fail while something animates: wait, or dump again.
- Undo the locale (`--locales ""`) before you leave the emulator.
