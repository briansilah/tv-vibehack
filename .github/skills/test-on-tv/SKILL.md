---
name: test-on-tv
description: Use when Brian runs /test-on-tv (pr <N>, <N> or #N, or nothing) or asks to test a pull request on the real TV. It tests launcher pull requests, usually the daily improvement runs' drafts and the upstream sync's, in one TV hold. It builds everything off the TV first, then on the TV replays each pull request's proven walkthrough and runs the perf scenarios its diff needs against main. It judges each one, posts screenshots and numbers to the pull request, merges the ones that pass, and leaves the TV on main with the tv-lease checks. With no number it tests every open needs-tv-test pull request, oldest first.
---

# Testing pull requests on the TV

Brian runs this when he's back. The daily improvement runs ([daily-improvement](../daily-improvement/SKILL.md))
have already checked their change on an emulator and proved a walkthrough there; this skill does what only the
TV can: the real look and feel, the real numbers, and the decision to merge.

**Brian running `/test-on-tv` is the TV grant**: no coordinator session, no lease request. He's here, so ask him
(`ask_user`) when you need him. Before anything else, read the local **`tv-lease`** skill for the TV's serial,
this Mac's tools (adb isn't on PATH; JDK 17; ffmpeg) and the hard rules, and
[launcher-tv-testing](../launcher-tv-testing/SKILL.md) for how to measure.

## Rules

- **Everything that can happen off the TV happens first**: builds, merges of `main`, plans, scripts. The hold
  is only installs, the walkthrough, perf runs and putting `main` back. Tell Brian how long it will take
  before you start.
- **The hard rules in `tv-lease` always apply.** Never `am force-stop com.gathin.tvhome`, never uninstall it
  (`install -r` only), never reboot or sleep the TV, never disable `com.google.android.apps.tv.launcherx`,
  never `--ei liquid_glass`, bring Home up with `am start -n com.gathin.tvhome/.MainActivity`.
- **`gh` on origin.** This checkout also has an `upstream` remote, someone else's repository, and `gh` picks
  it by default. Run every `gh` command with `GH_REPO=briansilah/tv-vibehack` (export it in each shell command).
- **`walkthrough.py run --tv` only with the TV's serial in `ANDROID_SERIAL`**, `TV_HOST` unset.
- **Merge only what passes every check** below. A failure leaves the pull request a draft, with the reason.
- **If a step fails on the TV, don't debug there.** Note it, finish or skip that pull request, and fix it off
  the TV.

## 1. Which pull requests

- `/test-on-tv pr 41`, `/test-on-tv 41` or `/test-on-tv #41`: that one, labelled or not.
- `/test-on-tv`: every open `needs-tv-test` pull request, oldest first:

  ```bash
  export GH_REPO=briansilah/tv-vibehack            # origin: gh would pick the upstream remote
  gh pr list --label needs-tv-test --state open --json number,title,createdAt --jq 'sort_by(.createdAt)[] | "\(.number) \(.title)"'
  ```

  None: say so and stop. The `upstream-sync` pull request goes first when it's there: the small daily ones
  merge on top of it more easily than it merges on top of them.
- **Seven areas a day** can queue up a dozen or more. With more than about five, tell Brian how long all of
  them would hold the TV (section 2's estimate) and ask with `ask_user` whether to test them all now or only
  the first few.
- **Upstream proposals** (issues labelled `upstream-proposal` and `needs-tv-test`, from
  [upstream-contribute](../upstream-contribute/SKILL.md)) are tested too, after the pull requests: section 9.
  `gh issue list --label upstream-proposal --label needs-tv-test --state open`.
- **No TV needed:** a pull request that changes nothing in `launcher/` and none of the tools that talk to a TV
  (`tools/tv.py`, `b64push.py`, `perf*.py`, `walkthrough.py`, `launcher/build-and-install.*`). Prepare and
  check it as in section 2, read the diff, and judge it without the TV (section 5's points 1 and 5); then
  report and merge it as in sections 6 and 7, saying the TV wasn't needed.

## 2. Prepare, off the TV

Prepare every pull request before you take the TV, each in a temporary worktree of its own (never in another
session's), at the exact commit you'll test:

```bash
export GH_REPO=briansilah/tv-vibehack            # origin: gh would pick the upstream remote
gh pr view <N> --json state,isDraft,headRefName,headRefOid,isCrossRepository,mergeable,title,url
gh pr checks <N>                                            # GitHub's checks, if any (Actions is off on this fork)
git fetch -q --prune origin && git fetch -q origin "pull/<N>/head" && W=$(mktemp -d)/pr-<N>
git worktree add --detach "$W" <headRefOid>
cd "$W" && git merge --no-edit origin/main                  # when it's behind main
git push origin HEAD:<headRefName>                          # only if the merge made a commit, and not from a fork
TESTED=$(git rev-parse HEAD)                                # the commit you test, and later merge
cp <this checkout>/launcher/local.properties launcher/      # sdk.dir
python3 -m venv tools/venv && tools/venv/bin/pip install -q -r tools/requirements.txt
tools/ci.sh                                                 # what ci.yml runs, at $TESTED: this is the CI that counts
python3 tools/walkthrough.py fetch <N> -o plan.json
python3 tools/walkthrough.py check plan.json                # its last line: the perf options to use
```

- **Skip a pull request**, with a comment saying why and the `tv-failed` label in place of `needs-tv-test`,
  when: it isn't open, CI fails, or `main` doesn't merge cleanly (`git merge --abort`). Its area's next daily
  run fixes it (the sync's next run, for the sync pull request).
- **From a fork**: don't push. Test the local merge, and merge it later only if `origin/main` hasn't moved.
- **No walkthrough comment for this pull request** (one from outside the daily runs, or an old plan):
  write a plan from its diff as [daily-improvement](../daily-improvement/SKILL.md) section 8 says, prove it on
  your own emulator, and post it with `walkthrough.py comment --pr <N>`.
- **`main` brought launcher changes into it**, or you wrote the plan yourself: run the plan on your own
  emulator with the debug build first (`tools/emulator.py start --owner test-on-tv` in `$W`). It must pass
  there before it runs on the TV. Then `tools/emulator.py stop <serial>`: `start` binds `$W` to the emulator,
  and a bound worktree won't talk to the TV.
- **`main`'s side:** `python3 tools/perf.py baseline apk` (cached), and `baseline show` to see which scenarios
  the TV has cached for this `main`: those cost no TV time.
- Estimate the hold from the scenarios (`cold` about 70 s, `warm` 40 s, `jank` 30 s, `dock` a minute, `mem`
  10 s), two installs and a minute's settle per pull request, and the walkthrough's length, and tell Brian.
  With several pull requests, also tell him the TV stays held for a few minutes between them after each
  merge (section 7).

## 3. Take the TV

Use the serial `tv-lease` gives (`adb connect` it if it's missing; the skill says what to do when it won't
connect), then:

```bash
unset TV_HOST; export ANDROID_SERIAL='<tv serial>'
adb shell dumpsys media_session | grep -m1 -E 'state=PlaybackState \{state=(PLAYING|3[,(])'
adb shell dumpsys package com.gathin.tvhome | grep -E 'versionName|lastUpdateTime'
```

If the `media_session` line prints anything, something is playing: ask Brian to pause it, and wait for his answer.
Note the installed build, to report what you leave.

## 4. Each pull request, on the TV

The walkthrough runs on the pull request's **debug** build: a debuggable build lets `walkthrough.py` read the
launcher's saved settings before it starts and put them back exactly if a step fails before the plan undoes
what it changed. The numbers come from the **release** build, as it ships. From its worktree (`perf.py plan` and
`--scenarios auto` read the diff from there), with `<perf>` the options `walkthrough.py check` printed
(`--scenarios auto`, `--scenarios dock,mem`, maybe a `--variant`):

```bash
cd "$W"
python3 tools/perf.py baseline ensure <perf> --other-app com.tcl.ui_mediaCenter     # first: it installs main on a miss
adb install -r launcher/app/build/outputs/apk/debug/app-debug.apk
python3 tools/walkthrough.py run plan.json --tv --other-app com.tcl.ui_mediaCenter --out <files>/pr-<N>-tv
launcher/build-and-install.sh                                # the release build, compiled speed
sleep 60                                                     # settle
python3 tools/perf.py run --label pr-<N> <perf> --other-app com.tcl.ui_mediaCenter
python3 tools/perf.py compare --against main tools/perf-results/pr-<N>.json
```

- `--scenarios none` skips the three perf lines (and the release install can wait for section 8).
- `com.tcl.ui_mediaCenter` is the TCL TV's full-screen app that opens without video (docs/performance.md); use
  the same one everywhere, for `main` and the pull request.
- If the walkthrough reports `"restored": false` for the settings, stop and tell Brian which settings
  (`manifest.json`) before anything else.
- The TV's Wi-Fi drops now and then (`tv-lease`). `walkthrough.py` reconnects by itself, but never presses keys
  again: a step that lost the connection fails, so run the walkthrough once more from the start. For `perf.py`
  or an install, `adb connect` the serial, wait for `device`, and run that step once more.
- The debug build's clips show what moves, not how smoothly: the release build's `jank` and `dock` numbers
  say that.

## 5. Judge

A pull request **passes** when all of these hold:

1. `tools/ci.sh` passed at `$TESTED` (again, after any merge of `main` you pushed), and so did GitHub's checks
   if `gh pr checks` lists any. The pull request's head is still `$TESTED` (`gh pr view <N> --json
   headRefOid`): if someone pushed since, it's untested, so prepare it again.
2. The walkthrough passed every step (`manifest.json`'s `ok`).
3. **Every screenshot shows what its `expect` says.** Look at each one yourself (`sips -Z 960` makes a smaller
   copy to view), and at each clip as a contact sheet
   (`ffmpeg -i clip.mp4 -vf "fps=8,scale=480:-1,tile=8x4" -frames:v 1 sheet.png`). A picture that doesn't
   match is a failure, even with every step "ok".
4. **No perf number got worse beyond the noise:** a median more than about 10 % worse than `main` and outside
   the min–max spread in the JSON. When one is, measure that scenario again for both
   (`baseline refresh --scenarios <it>`, then `run` again). Only a regression that holds fails.
5. Nothing the pull request claims is contradicted: if it says a number improved, the TV agrees (a smaller gain
   than the emulator's is fine; none at all is worth saying, not failing).

Anything else is a failure, with the evidence: the step, the picture, the numbers.

## 6. Report on the pull request

- Upload the screenshots and clips with the `github-pr-media` skill, then comment:

  ```markdown
  **TV test: passed** (or **failed: <the reason in a few words>**), TCL Smart TV Pro, Google TV 14, build `<versionName>`, <date>.

  Walkthrough: 12 of 12 steps; each picture matches what it should show.

  | Metric (median) | main | this PR | change |
  |---|---|---|---|
  | only the rows that moved or that the PR is about | | | |

  <details><summary>Full perf.py compare table</summary> ... </details>

  <screenshots, each under its expect>
  ```

- In the description, replace the line that starts with `TV: pending` with one line, for example
  `TV (TCL Smart TV Pro): passed; warm start 58 → 51 ms. Details in the comment.`:
  `gh pr view <N> --json body --jq .body > body.md`, edit it, `gh pr edit <N> --body-file body.md`. Keep the
  rest as it is ([pull-requests](../pull-requests/SKILL.md)).
- Labels, exactly one result: `gh pr edit <N> --remove-label needs-tv-test --remove-label tv-failed
  --add-label tv-tested` when it passed; `--remove-label needs-tv-test --remove-label tv-tested --add-label
  tv-failed` when it didn't.

## 7. Merge what passed

```bash
export GH_REPO=briansilah/tv-vibehack            # origin: gh would pick the upstream remote
gh pr ready <N>
gh pr checks <N> 2>&1 | grep -q 'no checks reported' || gh pr checks <N> --watch --fail-fast   # only when GitHub runs any
gh pr merge <N> --merge --match-head-commit "$TESTED"     # merge commits, as the repository does; only what you tested
git push origin --delete <headRefName>                    # not for a fork
```

If `--match-head-commit` refuses, someone pushed after you tested: don't merge, label it `needs-tv-test` again,
and say so.

**Several pull requests:** each one after a merge needs the new `main`. Keep the TV held while you `git fetch`,
merge `main` into the next one's worktree (a conflict skips it, as in section 2), push, set `$TESTED`
again, rebuild both builds, and run its walkthrough on your emulator once more when the merge brought launcher
changes. Then test it as in section 4: its `baseline ensure` measures the new `main` (correct, and it costs TV
time). If the daily run's backlog issue is linked in the description, leave it: the next run reads the pull
request's state.

## 8. Give the TV back

```bash
git fetch -q origin && python3 tools/perf.py baseline install      # the latest main, with what you merged
adb shell am start -n com.gathin.tvhome/.MainActivity
```

Then the checks on release from `tv-lease` (the Home button service still enabled, our `MainActivity` on top,
none of your files left in `/sdcard` or `/data/local/tmp`, the Perfetto trace deleted). Remove the temporary
worktrees (`git worktree remove --force "$W"`).

Finish with a short table for Brian: each pull request, passed or failed and why, the numbers that matter, and
merged or not; and what's installed on the TV now.

## 9. Upstream proposals: upstream's build on the TV

An upstream proposal is a change for the original project, built on its code, so the TV must run upstream's
launcher for a few minutes. Upstream's build has the same package but its own Home-key service
(`HomeKeyService`, not enabled), so while it's installed the remote's Home button opens Google TV's own home
(bring ours up with `am start`), and it may rewrite the saved settings. So: back them up first, and put
everything back after.

Off the TV, build three APKs: the fork's `main` as debug and as release (a worktree of `origin/main`, as
section 2), and the proposal's debug build (a worktree of its `contrib/<topic>` branch, `./gradlew -q
assembleDebug`). Fetch its walkthrough: `python3 tools/walkthrough.py fetch <issue> -o plan.json` (the issue
number works like a pull request's).

On the TV, `tools/try-upstream-build.sh` does it all, and puts the fork back whatever happens in between: it
installs the fork's debug build and backs up every saved settings file; installs upstream's build and runs the
walkthrough; then goes back down to the fork's debug build (`install -r -d`, allowed because upstream's build is
debuggable), removes the settings files upstream added, and writes the owner's back byte for byte. Then the
fork's release build:

```bash
. tools/devenv.sh && export ANDROID_SERIAL='<tv serial>'
tools/try-upstream-build.sh <fork main>/app-debug.apk <contrib>/app-debug.apk plan.json <files>/proposal-<issue>-tv \
  --tv --other-app com.tcl.ui_mediaCenter                     # must print settings-restored
(cd <fork main worktree> && launcher/build-and-install.sh)   # the fork's release build, compiled speed, HomeButtonService on
```

- If it doesn't print `settings-restored`, stop and tell Brian which file (it says) before anything else.
- The script was proved on an emulator on 2026-10-25 with upstream's real build (`versionCode` 8): the
  walkthrough on upstream's launcher, then the fork back with its settings byte for byte, and the same when
  upstream's APK failed to install.
- Judge it as section 5 (the walkthrough and its pictures; upstream's checks the proposal lists) and report on
  the proposal issue as section 6 (screenshots, `tv-tested` or `tv-failed`). Don't merge anything: the next
  upstream-contribute run sends it once Brian approves.
- Then section 8's checks, including that `enabled_accessibility_services` still lists
  `com.gathin.tvhome/.HomeButtonService`.
