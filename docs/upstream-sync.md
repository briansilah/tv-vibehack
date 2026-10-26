# Keeping up with upstream

This repository is a fork of [Gathin23/cleanyourtv](https://github.com/Gathin23/cleanyourtv) (it was called
tv-vibehack). A daily sync ([.github/skills/upstream-sync](../.github/skills/upstream-sync/SKILL.md)) merges
upstream's changes into the fork, keeping both histories. Once git merges a change, it counts it as merged for
good, even when the merge left part of it out. So this file lists every upstream change the fork didn't adopt,
or adopted differently, and why: the only record of it.

## Never adopted

| Upstream | Why |
|---|---|
| `.github/workflows/maintainer-bot.yml`, `.github/maintainer/` | Upstream's own maintainer bot: it would review and merge the fork's pull requests. |
| `.github/FUNDING.yml` | Upstream's sponsorship. |
| `.github/pull_request_template.md` (upstream's) | The fork keeps its own (Why, What, How tested). |

## Adopted differently, or not adopted

| Date | Upstream | What the fork did | Why |
|---|---|---|---|
| 2026-10-26 | [Solar Hijri date (cleanyourtv#70)](https://github.com/Gathin23/cleanyourtv/pull/70) | Took the conversion and off-by-default option. When enabled, the Shamsi and Gregorian dates appear at the top left using the fork's text styles; no greeting header or old glass rectangle returns. The block is inflated only when enabled and follows the page with the existing top area. | The fork's expanding clock/profile corner and Top Shelf remain unchanged by default. A separate date block avoids overlapping the corner's animated buttons and captions. |
| 2026-10-25 | [Spanish translation (cleanyourtv#46)](https://github.com/Gathin23/cleanyourtv/pull/46) | Kept every Spanish key and restored the five corresponding English greeting/date resources, without restoring the old header. | The fork replaced the greeting header with its Top Shelf. Default resources keep the complete translation valid without changing the fork's layout. Spanish falls back to English for the fork-only strings. |
| 2026-10-25 | [Windows HOME component (cleanyourtv#41)](https://github.com/Gathin23/cleanyourtv/pull/41) | Ported the Android 9 component-name fix into the fork's install and restore branches. | Kept release builds, both transports, environment restoration and the Google TV HomeButtonService path; no duplicate upstream install flow. |
| 2026-10-25 | [Inputs visibility (cleanyourtv#42)](https://github.com/Gathin23/cleanyourtv/pull/42) | Ported input callbacks, visibility and the left-edge focus guard into the fork's expanding top corner. Move focus to the network button if the last input disappears. | Kept the fork's top corner and deferred service queries until after the first frame. |
| 2026-10-25 | [Glass scrolling (cleanyourtv#47)](https://github.com/Gathin23/cleanyourtv/pull/47) | Did not restore upstream's moving glass rectangle or its spring properties. | The fork's LiquidGlass dock and tile highlights replaced that rectangle; its GridScroller/followPage already moves the page and backdrop together. |
| 2026-10-25 | [Row-edge focus (cleanyourtv#55)](https://github.com/Gathin23/cleanyourtv/pull/55) | Kept AppOrder.step and onTileDpadKey instead of restoring onTileVerticalKey. | The fork already handles all four directions and clamps both row edges, including while arranging apps; retaining its path preserves Top Shelf and corner navigation. |
| 2026-10-25 | [Xiaomi MOOR2 and stock-home detection (cleanyourtv#65)](https://github.com/Gathin23/cleanyourtv/pull/65) | Took the TV list and HOME-priority findings, but commented out its setupwraith disable entry and described it as an upstream-only workaround. Kept the fork's package-presence detection of Google TV, rather than resolving an arbitrary HOME owner to disable. | The fork already recognises launcherx and keeps it enabled with HomeButtonService. Disabling a setup wizard or an unknown HOME candidate is not needed for that design; the known Android TV default and explicit STOCK_LAUNCHER remain supported. |
