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
| 2026-10-25 | [Spanish translation (cleanyourtv#46)](https://github.com/Gathin23/cleanyourtv/pull/46) | Kept every Spanish key and restored the five corresponding English greeting/date resources, without restoring the old header. | The fork replaced the greeting header with its Top Shelf. Default resources keep the complete translation valid without changing the fork's layout. Spanish falls back to English for the fork-only strings. |
| 2026-10-25 | [Windows HOME component (cleanyourtv#41)](https://github.com/Gathin23/cleanyourtv/pull/41) | Ported the Android 9 component-name fix into the fork's install and restore branches. | Kept release builds, both transports, environment restoration and the Google TV HomeButtonService path; no duplicate upstream install flow. |
| 2026-10-25 | [Inputs visibility (cleanyourtv#42)](https://github.com/Gathin23/cleanyourtv/pull/42) | Ported input callbacks, visibility and the left-edge focus guard into the fork's expanding top corner. Move focus to the network button if the last input disappears. | Kept the fork's top corner and deferred service queries until after the first frame. |
| 2026-10-25 | [Glass scrolling (cleanyourtv#47)](https://github.com/Gathin23/cleanyourtv/pull/47) | Did not restore upstream's moving glass rectangle or its spring properties. | The fork's LiquidGlass dock and tile highlights replaced that rectangle; its GridScroller/followPage already moves the page and backdrop together. |
| 2026-10-25 | [Row-edge focus (cleanyourtv#55)](https://github.com/Gathin23/cleanyourtv/pull/55) | Kept AppOrder.step and onTileDpadKey instead of restoring onTileVerticalKey. | The fork already handles all four directions and clamps both row edges, including while arranging apps; retaining its path preserves Top Shelf and corner navigation. |
