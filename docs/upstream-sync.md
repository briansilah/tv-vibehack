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
