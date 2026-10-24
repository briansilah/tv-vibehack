# Thursday: UX

**Goal:** the things people do most take the fewest presses, never strand them, and say clearly what will
happen. The person on the sofa just wants to watch something.

## Scout: the biggest user challenges first

1. **What people do, and what has hurt.** Brian's own words in earlier sessions (`session_store_sql`, see
   the skill's step 2: try "confus", "can't find", "where", "annoying", "expected", "should"), the README's
   Troubleshooting and Questions people ask, open issues, and the `Why` of recent pull requests (each was a
   problem someone hit).
2. **The top tasks, in presses.** Write one walkthrough plan per task, from Home with the first Dock app
   highlighted, and count the presses (and screens) each needs:
   - open an app in the Dock; open one in the grid's third row
   - switch to an HDMI input
   - change an app's Top Shelf
   - move an app into the Dock, and rearrange the Dock
   - change Liquid Glass
   - see the Wi-Fi network; open the TV's settings
   - come back to Home from an app, and find where you were

   Compare each with the fewest presses possible and with Apple TV's way of doing the same.
3. **Dead ends and traps.** From every edge of every screen, press each direction: where does focus go? Does
   Back always go back one level, and leave nothing half-done? CONTRIBUTING asks for this ("Test
   Up/Down/Left/Right from every edge"). Note every place focus disappears, jumps somewhere unexpected, or
   stops.
4. **Empty and error states.** No network (`adb shell svc wifi disable` and `svc data disable` on the
   emulator; enable them again after), no Top Shelf picture yet, an app without a banner, one app only, many
   apps. Does the screen say what's happening and what to do?
5. **The words.** Every string against [ux-writing](../../ux-writing/SKILL.md): its four questions, the rules
   and the word list. Count the strings that break a rule.

## Metrics and the bar

| Metric | How | The bar |
|---|---|---|
| Presses per top task | walkthrough plans | as few as Apple TV takes for the same task |
| Dead ends and focus traps | the edge walk | 0 |
| Back that doesn't go back one level | the edge walk | 0 |
| Empty and error states without a message | 4 | 0 |
| Strings breaking `ux-writing` | 5 | 0 |

## Research

- Apple HIG: [Focus and selection](https://developer.apple.com/design/human-interface-guidelines/focus-and-selection),
  [Remotes](https://developer.apple.com/design/human-interface-guidelines/remotes),
  [Designing for tvOS](https://developer.apple.com/design/human-interface-guidelines/designing-for-tvos),
  [Feedback](https://developer.apple.com/design/human-interface-guidelines/feedback)
- Google TV: [Design for TV](https://developer.android.com/design/ui/tv) and its navigation guides;
  [TV app quality](https://developer.android.com/docs/quality-guidelines/tv-app-quality) (D-pad navigation)
- Nielsen Norman Group, [10 usability heuristics](https://www.nngroup.com/articles/ten-usability-heuristics/)
- [ux-writing](../../ux-writing/SKILL.md) for every word

## Typical fixes

Focus that lands where people expect; a shorter path to a frequent task; Back that undoes one step; a message
for an empty or error state; a clearer label (per `ux-writing`); hiding what doesn't apply.

## Check it

The task plans again: presses before → after, and the edge walk with no dead ends. Screenshots of each changed
step. Every changed string in `values-de` too. Navigation changes get `perf.py plan`'s scenarios (often
`jank` with the right keys).

## Pitfalls

- Keep D-pad navigation deterministic: the same press from the same place always goes to the same place.
- On Google TV, Home goes through `HomeButtonService`; key injection skips it. The walkthrough can't press
  Home: say in the pull request what only the real remote can check.
- Don't trade a frequent task's speed for a rare one's.
