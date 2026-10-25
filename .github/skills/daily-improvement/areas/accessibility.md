# Accessibility

**Goal:** everyone can use Home: with TalkBack, with larger text, with less motion, from far away, and without
telling colours apart. Many contributors set this up for their parents (CONTRIBUTING).

## Scout

1. **What a screen reader gets.** On each screen of a tour plan (see `ui.md`), dump the views and list every
   focusable one without a name:

   ```bash
   adb shell uiautomator dump /data/local/tmp/ui.xml && adb pull /data/local/tmp/ui.xml && adb shell rm /data/local/tmp/ui.xml
   python3 -c 'import re,sys; x=open("ui.xml").read(); [print(m.group(0)[:160]) for m in re.finditer(r"<node [^>]*focusable=\"true\"[^>]*>", x) if "text=\"\"" in m.group(0) and "content-desc=\"\"" in m.group(0)]'
   ```

   Then listen: turn TalkBack on in the emulator if its image has it, and walk the screen. Is the reading
   order the visual order? Does a row read "label, value"? Do state changes (ticked, selected) get spoken?
2. **Contrast.** Text and focus indicators against what's behind them, from screenshot pixels (Pillow), on
   the darkest and the brightest Top Shelf art.
3. **Larger text.** `adb shell settings put system font_scale 1.3`, the tour again, then
   `settings put system font_scale 1.0`. Truncated, overlapping or clipped text is a candidate.
4. **Less motion.** `adb shell settings put global animator_duration_scale 0` (and `transition_animation_scale`,
   `window_animation_scale`), the tour again, then back to 1. Nothing should stall, flash or need an
   animation to make sense.
5. **Colour alone.** Anything that says something only by colour (a state, a selection).
6. **Size at three metres.** Text smaller than the type scale's smallest body size, and focus that's hard to
   see from the sofa.

## Metrics and the bar

| Metric | How | The bar |
|---|---|---|
| Focusable views without a name | `uiautomator dump` per screen | 0 |
| Text below contrast | WCAG ratio from pixels | 4.5:1 body, 3:1 large text |
| Focus indicator contrast | pixels, focused vs not | 3:1 (WCAG 2.2, 1.4.11) |
| Clipped text at font scale 1.3 | screenshots | 0 |
| Broken states with animations off | the tour | 0 |

## Research

- Android: [Make apps more accessible](https://developer.android.com/guide/topics/ui/accessibility/apps),
  [TV accessibility](https://developer.android.com/training/tv/accessibility),
  [Accessibility Scanner's checks](https://support.google.com/accessibility/android/answer/6376570)
- Apple HIG: [Accessibility](https://developer.apple.com/design/human-interface-guidelines/accessibility);
  WWDC19 [Writing great accessibility labels](https://developer.apple.com/videos/play/wwdc2019/254/)
- [WCAG 2.2](https://www.w3.org/TR/WCAG22/) and [Understanding WCAG 2.2](https://www.w3.org/WAI/WCAG22/Understanding/)
- [ux-writing](../../ux-writing/SKILL.md): labels name what a thing is, not its type

## Typical fixes

`contentDescription` on views without text (from `strings.xml`, translated); rows announced as "label, value";
state announced (`setSelected`, `stateDescription`); contrast raised; text that wraps or scales instead of
clipping; a non-animated path when animations are off.

## Check it

The counts before and after, the dumps' lines that changed, and screenshots at font scale 1.3. New strings in
`values-de`.

## Pitfalls

- Put every setting back (font scale 1.0, animation scales 1, TalkBack off) before you stop: the emulator is
  read-only, but other steps today run on it.
- Labels go in `strings.xml`, never hard-coded.
- An accessibility fix must not change how it looks or moves for everyone else unless that's the point.
