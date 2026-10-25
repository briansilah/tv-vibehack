---
name: upstream-sync
description: Use for the daily upstream sync (/upstream-sync), started by an automation or by hand. It brings the original project's changes (Gathin23/cleanyourtv, the upstream remote) into this fork without losing the fork's own. It merges upstream, in steps, into one long-lived sync pull request, resolving conflicts so both sides' intent survives, and records in docs/upstream-sync.md what it deliberately leaves out. It checks the result the way both projects' CI and an emulator would, then merges it itself when the launcher is untouched or leaves it for /test-on-tv. It never touches the TV and runs without anyone watching.
---

# Upstream sync

This repository is a fork of [Gathin23/cleanyourtv](https://github.com/Gathin23/cleanyourtv) (it was called
tv-vibehack). The original moves fast, often hundreds of commits a day: TV lists in `examples/`, add-ons,
themes, docs, translations, tools, and launcher fixes and options. The fork has its own launcher design (Liquid
Glass, the Top Shelf, Home Settings, Apple TV-like motion and navigation), its own tools (`perf*.py`,
`walkthrough.py`, `emulator.py`, `provenance.py`) and its own skills. The sync brings theirs in and keeps ours.

The run is usually unattended (an automation in autopilot). **Nobody will answer questions**: don't use
`ask_user`. Decide, and write what you decided in the pull request and the ledger.

## Rules

- **History stays.** Merge, never rebase or squash upstream, never force-push, never push to `main`. Merge
  commits keep both histories, so each sync only deals with what's new since the last.
- **Keep the fork's look and behaviour; take upstream's fixes, device support, options, content and
  translations.** Section 4 says how, file by file.
- **Nothing upstream is lost without a reason.** Once a merge is committed, git counts every upstream change in
  it as merged for good: a change the resolution leaves out never comes back by itself. Everything you don't
  adopt, or adopt differently, gets a row in [docs/upstream-sync.md](../../../docs/upstream-sync.md).
- **One sync pull request at a time**, from the branch `upstream-sync`, labelled `upstream-sync`. Each day adds
  to it until it's merged.
- **Never the TV.** Your own emulator, only through `tools/emulator.py start --owner upstream-sync --hours 10`
  (renew it if you need longer); build only while you hold its slot; measure only inside
  `tools/emulator.py exclusive` ([daily-improvement](../daily-improvement/SKILL.md) explains the slots).
  `start` binds your worktree to the emulator. Begin every shell command with `. tools/devenv.sh &&`: each
  starts fresh.
- **`gh` on origin.** Run every `gh` command with `GH_REPO=briansilah/tv-vibehack`; name upstream with
  `--repo Gathin23/cleanyourtv` when you read its pull requests.

## 1. Set up

```bash
export GH_REPO=briansilah/tv-vibehack
git remote get-url upstream >/dev/null 2>&1 || git remote add upstream https://github.com/Gathin23/cleanyourtv.git
git remote set-url upstream https://github.com/Gathin23/cleanyourtv.git     # it was renamed from tv-vibehack
git fetch -q origin && git fetch -q upstream
gh label create upstream-sync --color 5319E7 --description "Changes from the original project" 2>/dev/null
gh pr list --label upstream-sync --state open --json number,headRefName,isDraft
```

- **The branch.** If a sync pull request is open, continue it: `git checkout -B <this session's branch>
  origin/upstream-sync`, then `git merge --no-edit origin/main` so it has the fork's latest. Otherwise start
  from `main`: `git checkout -B <this session's branch> origin/main`. Either way you push with
  `git push origin HEAD:upstream-sync`.
- **Nothing new?** `git rev-list --count HEAD..upstream/main` is 0: say so and stop.
- **Two runs at once** (a catch-up and a run by hand): when your push is refused because `upstream-sync` moved,
  the other run got there first. Stop, and say so.
- **Build tools.** `. tools/devenv.sh` (JDK 17, the SDK, `adb`, `launcher/local.properties`; the local
  `tv-lease` skill has this Mac's paths if one is missing, use none of its TV parts), and
  `python3 -m venv tools/venv && tools/venv/bin/pip install -q -r tools/requirements.txt`.
- **Take your emulator slot now**, before the first build: `python3 tools/emulator.py start --owner
  upstream-sync --hours 10` (in an async shell: it may wait for a slot). Read section 2 meanwhile.

## 2. See what's new

```bash
git rev-list --count --first-parent HEAD..upstream/main                 # upstream's merges and commits on main
git log --first-parent --date=short --format='%h %ad %s' HEAD..upstream/main | head -60
git diff --name-only HEAD...upstream/main | awk -F/ '{print $1}' | sort | uniq -c | sort -rn   # by folder
```

Upstream's merge commits read `Merge PR #<n>: <title>`. When a conflict needs to know why upstream changed
something, read that pull request: `gh pr view <n> --repo Gathin23/cleanyourtv`.

## 3. Merge in steps

A big merge hides mistakes in its size. When upstream is more than about 150 first-parent commits ahead, or a
trial merge (`git merge --no-commit --no-ff upstream/main`, then `git merge --abort`) conflicts in more than
about ten files, merge in steps along upstream's main line: each step one merge commit that builds and passes
the tests.

```bash
git rev-list --first-parent --reverse HEAD..upstream/main | awk 'NR % 150 == 0' > steps.txt
git rev-parse upstream/main >> steps.txt
# for each <step> in steps.txt, in order:
git merge --no-ff --no-edit -m "Merge upstream (Gathin23/cleanyourtv) up to $(git log -1 --format='%h, %ad' --date=short <step>)" <step>
```

Make each step smaller (every 50 commits, or one day of upstream's) when its conflicts are deep. Each step, in
this order:

1. Resolve the conflicts (section 4) and `git add` them.
2. **Commit the merge** (`git commit --no-edit`), so what you check is the exact commit you'll push.
3. Read what landed (section 4, point 3: `git diff HEAD^1 HEAD`), and check it (section 5).
4. Fix what the checks find, then `git commit --amend --no-edit` (the merge is still only yours) and check again.
5. Push: `git push origin HEAD:upstream-sync`, so the work survives the run.

Out of time: stop after a pushed step; the next run goes on from there.

## 4. Resolve: keep both sides' intent

For each conflicted file (`git diff --name-only --diff-filter=U`):

1. **Understand both sides first.** `git checkout --conflict=diff3 <file>` shows the common base between the
   two. `git log --oneline --merge -- <file>` lists the commits on both sides that touched it; read their
   messages, and upstream's pull request for anything unclear. Know what each side wanted before you choose.
2. **Then, by kind:**

| What conflicts | Resolve |
|---|---|
| A bug, crash or device fix; a firmware quirk; how the stock launcher, the Home key or HDMI inputs are handled | Upstream's fix, adapted to the fork's code. Keep the fork's own fixes too: both usually hold. |
| A new option or feature that leaves the fork's look and navigation alone (`power_on_home`, `date_below_clock`, a new `config.xml` key) | Take it, with its `config.xml` default; off when on would change the fork's look or navigation. |
| The look and navigation of a screen both redesigned (the Dock, the glass, the top right, the grid, motion, focus) | The fork's. Ledger row for upstream's. |
| Both sides built the same feature their own way: above all the Home key's accessibility service, the fork's `HomeButtonService` and upstream's `HomeKeyService` | One design (upstream's own rule too). Keep the fork's component and its name: Brian's TV has `com.gathin.tvhome/.HomeButtonService` enabled by name, and a rename or a second service switches the Home button off or makes two services fight over it. Port upstream's features into it (as `config.xml` options where they're TV-specific), keep `launcher/build-and-install.*` enabling the fork's, and write a ledger row. |
| `launcher/app/build.gradle` | Keep the fork's build stamp (`versionName` + `buildStamp()`, which `perf.py` and `provenance.py` read) and its release settings. Take upstream's `versionCode` and version when they're higher, so a TV takes the update, and upstream's `launcherApplicationId` property. |
| `strings.xml` and translations | Every key from both sides. A key both changed: the fork's English (it follows [ux-writing](../ux-writing/SKILL.md)); upstream's other languages, unless the fork changed what the string means. Upstream's new languages come in whole; list the fork's strings they lack in the pull request. |
| `config.xml`, `AndroidManifest.xml`, `build.gradle`, `styles.xml`, layouts | Both sides' entries. One entry changed both ways: by the rows above. |
| A file the fork deleted and upstream changed (`git status` says `DU`), such as `GlassView.java`, which `LiquidGlass` replaced | It stays deleted; port upstream's fix into the fork's replacement if it applies, else a ledger row. |
| Tools both changed (`tv.py`, `b64push.py`, `disable.*`, `restore.*`, `requirements.txt`) | Function by function, both. Both sides' tests must pass. |
| `.github/workflows/ci.yml` and `tools/ci.sh` | Both jobs: the fork's tools job, and upstream's lists, docs and launcher jobs (lint, the APK size limit). Then make `tools/ci.sh` run exactly what the merged `ci.yml` runs: with GitHub Actions off on the fork, it is the CI every session runs. |
| Upstream's maintainer bot (`.github/workflows/maintainer-bot.yml`, `.github/maintainer/`), `.github/FUNDING.yml`, its `.github/pull_request_template.md` | Not adopted, for good (the ledger's standing rows): its bot would act on the fork's pull requests. Remove them in the merge (`git rm`; for the template, keep the fork's). |
| README, CONTRIBUTING, PROMPT.md | The fork's for the launcher, its tools and how to contribute here; upstream's for everything else (supported TVs, examples, add-ons, themes, languages). Generated tables: run upstream's generator (`python3 tools/gen_tables.py`), don't merge them by hand. |
| The fork's own skills (`.github/skills/`), `perf*.py`, `walkthrough.py`, `emulator.py`, `provenance.py`, `docs/performance.md` | The fork's: upstream has none of them. |

3. **Check what merged without conflict too.** A clean text merge can still go against the fork's design (a
   second Home handler, a new default that changes the look). After the merge, read
   `git diff HEAD^1 HEAD -- launcher/` (upstream's changes as they landed on the fork) and resolve anything like
   that by the table above, with a ledger row.
4. **Nothing left half done:** `git diff --check` is clean and `git grep -n -E '^(<<<<<<<|>>>>>>>)'` finds
   nothing.
5. **Can't resolve one with confidence** (upstream rewrote what the fork rewrote, and neither side's intent
   fits): stop at the last good step, push it, and write in the pull request where it stopped and why. Brian
   decides.

## 5. Check it, as both projects' CI would

```bash
. tools/devenv.sh && tools/ci.sh        # what the merged ci.yml runs (section 4 keeps them in step), at HEAD
```

Until `tools/ci.sh` runs upstream's checks too, also run them yourself:
`(cd launcher && ./gradlew testReleaseUnitTest lintRelease)`, `tools/venv/bin/python -m unittest discover -s
tests` (upstream's tests), and the release APK under upstream's 589,824-byte limit
(`stat -f %z launcher/app/build/outputs/apk/release/app-release.apk`). Both projects' checks are now the
fork's: a failure from either side's code is the merge's to fix (for example `lintRelease`, which the fork's CI
didn't run before).

Then on your emulator (`tools/emulator.py start --owner upstream-sync`), when the launcher changed:

- **The core tour** on the debug build:
  `. tools/devenv.sh && adb install -r launcher/app/build/outputs/apk/debug/app-debug.apk && python3
  tools/walkthrough.py run tools/walkthroughs/core-tour.json --out <files>/core-tour`. It must pass,
  and every picture must show what its `expect` says. For upstream changes you can see with the defaults, copy
  the core tour and add steps to the copy.
- **The numbers:** `python3 tools/perf.py plan`, then inside
  `python3 tools/emulator.py exclusive --owner upstream-sync -- sh -c '...'`: `perf.py baseline refresh
  --scenarios auto --quick`, `launcher/build-and-install.sh`, `perf.py run --label upstream-sync --scenarios
  auto --quick`; then `perf.py compare --against main`. A regression beyond the noise is the merge's to find
  and fix, or a ledger row when it comes with an upstream fix the fork wants.

## 6. The pull request

- **Open it** if none is: `gh pr create --head upstream-sync --base main --draft --label upstream-sync --title
  "Upstream: <n> changes from cleanyourtv, up to <date>" --body-file body.md`. Later runs rewrite the title and
  description with the new totals (`gh pr edit`) and add a comment for the day.
- **The description**, in the [pull-requests](../pull-requests/SKILL.md) skill's three parts: **Why**, how far
  behind the fork was and which upstream commit it now reaches; **What**, upstream's changes in a few bullets
  by folder (launcher, tools, docs, examples, add-ons), how the conflicts went (a line per group of files), and
  `Not adopted: <n> changes, see docs/upstream-sync.md`; **How tested**, the checks, the core tour and the
  emulator's numbers marked as the emulator's, and `TV: pending, /test-on-tv pr <N>` when the TV is needed.
- **Without the TV:** when the pull request changes nothing in `launcher/` and none of the tools that talk to a
  TV (`tv.py`, `b64push.py`, `perf*.py`, `walkthrough.py`, `launcher/build-and-install.*`), and every check
  passed, merge it yourself: `gh pr ready <N>`, then
  `gh pr merge <N> --merge --match-head-commit <the commit you checked>`.
- **With the TV:** otherwise it stays a draft, labelled `needs-tv-test`, with the walkthrough (the core tour
  plus your steps) posted for `/test-on-tv`:
  `python3 tools/walkthrough.py comment plan.json --manifest <files>/core-tour/manifest.json --pr <N> > walk.md && gh pr comment <N> --body-file walk.md`.
  `/test-on-tv` tests it before the daily improvement pull requests.

## 7. The ledger, and clean up

Add today's rows to [docs/upstream-sync.md](../../../docs/upstream-sync.md) in the same pull request (date,
upstream's pull request or commit as a link, what the fork did, why). Stop your emulator
(`python3 tools/emulator.py stop <serial>`), delete temporary files outside the session's files folder, and
finish with three or four sentences: how many upstream changes came in, the conflicts and how they went, what
wasn't adopted, and the pull request's state.
