---
name: daily-improvement
description: Use for the launcher's daily improvement runs (launcher/, tvhome), started each day by an automation or by hand. /daily-improvement with no area coordinates the day, starting one session per area for all seven (performance, ease of testing, UI, UX, motion and polish, accessibility, reliability and code health). /daily-improvement <area> is one of those sessions. It finds the most impactful problem in that area from data, researches best practice, measures, fixes, and checks the result on an emulator slot from tools/emulator.py. It proves a TV walkthrough there and opens one draft pull request for /test-on-tv. It never touches the TV and runs without anyone watching.
---

# Daily improvement

Every day, one small, measured improvement in each of seven areas. Each area's run finds where the launcher
falls short the most in that area, measures it, fixes it, measures it again, and leaves a draft pull request that
Brian tests on the TV later with `/test-on-tv`. Everything is decided from data: numbers, screenshots, counts and
the sources that set the bar, not taste.

Two ways in:

- **`/daily-improvement`** (the daily automation): coordinate the day. Start one session per area and let them
  run: [Coordinating the day](#coordinating-the-day).
- **`/daily-improvement <area>`**, such as `/daily-improvement ux`: one area's run, sections 1 to 11. The
  coordinator starts these; Brian can too.

The runs are usually unattended (an automation in autopilot). **Nobody will answer questions**: don't use
`ask_user`, and don't wait for input. Decide, and write any assumption in the pull request or the backlog.

## Rules

- **`gh` on origin.** This checkout also has an `upstream` remote, someone else's repository, and `gh` picks
  it by default. Run every `gh` command with `GH_REPO=briansilah/tv-vibehack` (export it in each shell
  command: variables don't carry from one to the next).
- **Never the TV.** Use only your own emulator, and never connect to, request or lease the TV, even when a step
  says "on the TV". `tools/walkthrough.py` refuses any device that isn't an emulator unless given `--tv`; never
  pass it. Emulator numbers are relative: compare main and your build on the same emulator, and never present
  them as TV numbers.
- **Share the Mac.** Up to nine runs share it (seven areas, the upstream sync and contribution runs, Brian's own
  sessions). Start your emulator only with `tools/emulator.py start`, which waits for one of three slots, and
  **build only while you hold one**: a slot is room for an emulator and a Gradle build. Measure with `perf.py`
  only inside `tools/emulator.py exclusive`. Stop your emulator when you're done.
- **Every shell command starts fresh:** begin each with `. tools/devenv.sh &&`. It sets JDK 17, the SDK and
  `adb`, unsets `TV_HOST`, and sets `ANDROID_SERIAL` to your emulator. `tools/emulator.py start` also binds
  your worktree to that emulator, so `tv.py`, `perf.py`, `walkthrough.py` and `build-and-install.sh` in it
  can't reach anything else.
- **No number, no change.** Every candidate needs evidence you collected today or that's on record: a
  measurement, a count, a screenshot measured against a guideline, a crash log, or Brian's own words. Every pull
  request says the metric's value before and after.
- **One topic, one draft pull request, per area per day.** Small enough to review in ten minutes (about 300
  changed lines, tests, docs and skills not counted). If the best candidate is bigger, do its first useful slice and put the
  rest in the backlog.
- **Never merge, never mark ready.** `/test-on-tv` does that after the TV says yes.
- **Don't collide.** Leave alone files that open pull requests change, and files another area's run has
  claimed today (section 2).
- **Stay inside [CONTRIBUTING.md](../../../CONTRIBUTING.md):** no new launcher dependencies, no telemetry, no
  network beyond what the top shelf already does, TV-specific values in `config.xml`, deterministic D-pad
  navigation.
- **A day with no change is a good result** when the data says nothing is worth changing, or the fix didn't
  move the number. Record it in the backlog instead of forcing a pull request.

## The areas

| Area | What | Label | Playbook |
|---|---|---|---|
| `performance` | Start-up, smoothness, memory | `improve:performance` | [areas/performance.md](areas/performance.md) |
| `testing` | Ease of testing: faster, surer checks | `improve:testing` | [areas/testing.md](areas/testing.md) |
| `ui` | Layout, type, colour, consistency | `improve:ui` | [areas/ui.md](areas/ui.md) |
| `ux` | Tasks, navigation, words | `improve:ux` | [areas/ux.md](areas/ux.md) |
| `motion` | Motion and visual polish | `improve:motion` | [areas/motion.md](areas/motion.md) |
| `accessibility` | Accessibility | `improve:accessibility` | [areas/accessibility.md](areas/accessibility.md) |
| `reliability` | Reliability and code health | `improve:reliability` | [areas/reliability.md](areas/reliability.md) |

An area's run reads its playbook before it starts: where to look, what to measure, which sources set the bar,
and what has gone wrong before.

## Coordinating the day

`/daily-improvement` with no area. The coordinator only starts and follows the area runs: no emulator, no
builds, no changes to the repository.

1. **Which areas.** All seven, except an area whose backlog issue already has today's run marker (section 1)
   that isn't `Status: abandoned`: that one ran today, or is running.

   ```bash
   export GH_REPO=briansilah/tv-vibehack            # origin: gh would pick the upstream remote
   D=$(date +%F)
   for i in $(gh issue list --label improvement-backlog --state open --json number --jq '.[].number'); do
     gh api repos/{owner}/{repo}/issues/$i/comments --paginate \
       --jq ".[] | select(.body | contains(\"<!-- daily-improvement $D -->\")) | select(.body | test(\"Status: abandoned\") | not) | \"$i \(.id)\""
   done
   gh issue list --label improvement-backlog --state open --json number,labels --jq '.[] | "\(.number) \([.labels[].name] | join(","))"'
   ```

2. **Start one session per area**, all at once: `tools/emulator.py` makes them take turns on the Mac, three at a
   time, and each does its reading and research while it waits. With the app's `create_session` tool, for each
   area: name `Daily <Area>, <date>`, `kickoff.mode` `autopilot`, `notify_on_idle` `once`, no `base_branch`
   (they branch off `main`), and this kickoff prompt:

   ```text
   /daily-improvement <area>

   Run the daily-improvement skill (.github/skills/daily-improvement/SKILL.md) for the <area> area only:
   sections 1 to 11, unattended. Never touch the TV. Start your emulator with tools/emulator.py and build only
   while you hold its slot. End with one draft pull request labelled needs-tv-test, or a backlog note when
   nothing is worth changing. When done, reply in one line: the area, the pull request or "no change", and why.
   ```

3. **Follow them.** As each one finishes (its reply, or the idle notification), note its result. Once all have,
   or by the end of the day, finish with a table: area, pull request or "no change" or "blocked", and the
   number that moved. Don't redo an area's work, and don't message the runs unless one asks something.
4. **No `create_session`** (the tool isn't there, or it fails): run one area yourself, the one for today's
   weekday (Monday `performance`, Tuesday `testing`, Wednesday `ui`, Thursday `ux`, Friday `motion`, Saturday
   `accessibility`, Sunday `reliability`), as sections 1 to 11 say, and say that the others didn't run.

## 1. Set up

```bash
date +%F                                        # today's run marker
git fetch -q origin && git merge -q --no-edit origin/main
```

- **Labels and the backlog issue.** Each area has one open issue labelled `improvement-backlog` and its
  `improve:<area>` label, titled `Improvement backlog: <Area>`. Find it with
  `gh issue list --label improvement-backlog --label improve:<area> --state open`. If it's missing, create it,
  with the body in [The backlog issue](#the-backlog-issue). Create any missing label first (colours don't
  matter): `needs-tv-test`, `tv-tested`, `tv-failed`, `improvement-backlog`, `improve:<area>`.
- **Once a day.** Runs can overlap (a catch-up after the Mac wakes, a run started by hand), so claim the day:
  post your run comment with the marker `<!-- daily-improvement <YYYY-MM-DD> -->` and `Status: scouting`,
  keep its id, then list today's markers again. The comment with the lowest id that isn't abandoned runs; any
  other stops, deletes its own comment (`gh api -X DELETE repos/{owner}/{repo}/issues/comments/<id>`) and says
  so. A marker that isn't finished (a pull request, `no change` or `blocked`) and hasn't been updated for eight
  hours is a run that died: edit it to `Status: abandoned`, and it no longer counts. While `tools/emulator.py`
  makes you wait, say so: `Status: waiting for an emulator slot`.

  ```bash
  export GH_REPO=briansilah/tv-vibehack            # origin: gh would pick the upstream remote
  gh api repos/{owner}/{repo}/issues/<issue>/comments -F body=@run.md --jq .id
  gh api repos/{owner}/{repo}/issues/<issue>/comments --paginate \
    --jq '.[] | select(.body | contains("<!-- daily-improvement <YYYY-MM-DD> -->")) | "\(.id) \(.updated_at) \(.body | test("Status: abandoned"))"'
  ```

- **The queue.** With two or more open `needs-tv-test` pull requests of this area
  (`gh pr list --label needs-tv-test --label improve:<area> --state open`), Brian is behind on TV tests for it:
  scout and update the backlog, but don't open another pull request.
- **Build tools.** `. tools/devenv.sh` (JDK 17, the SDK, `adb`, `launcher/local.properties`), and the tools'
  venv: `python3 -m venv tools/venv && tools/venv/bin/pip install -q -r tools/requirements.txt` (which
  `launcher/build-and-install.sh` needs). The local `tv-lease` skill has this Mac's paths if one is missing; use
  none of its TV parts.

## 2. Read what's known

- **The backlog issue**: its ranked candidates, and what earlier runs measured, tried, and dropped as "tried,
  no gain".
- **Earlier pull requests of this area**: `gh pr list --label improve:<area> --state all --limit 20`. One
  labelled `tv-failed` comes first: read why in its comments. If the reason is fixable, today's work is that
  pull request, not a new one: see [Fixing a pull request that failed on the TV](#fixing-a-pull-request-that-failed-on-the-tv).
- **Open pull requests' files**, to stay off them:
  `gh pr list --state open --json number,files --jq '.[] | "\(.number) \(.files[].path)"'`.
- **Today's other areas.** The other runs today write the files they'll change in their run comment, on a line
  `Files: <path>, <path>` (section 3). Stay off those too; read them again just before you claim your own:

  ```bash
  export GH_REPO=briansilah/tv-vibehack
  for i in $(gh issue list --label improvement-backlog --state open --json number --jq '.[].number'); do
    gh api repos/{owner}/{repo}/issues/$i/comments --paginate \
      --jq ".[] | select(.body | contains(\"<!-- daily-improvement $(date +%F) -->\")) | .body" | grep '^Files:'
  done
  ```
- **What Brian has said.** His messages in earlier sessions on this repository are the best record of what
  bothers him. With the `session_store_sql` tool, if it's there: first the sessions, then their turns (never
  scan all turns):

  ```sql
  SELECT id FROM sessions WHERE repository = 'briansilah/tv-vibehack' AND created_at > now() - INTERVAL '30 days'
  ```
  ```sql
  SELECT session_id, timestamp, substr(user_message, 1, 300) AS said FROM turns
  WHERE session_id IN (<the ids>) AND timestamp > now() - INTERVAL '30 days'
    AND (user_message ILIKE '%slow%' OR user_message ILIKE '%annoying%' OR user_message ILIKE '%confus%'
         OR user_message ILIKE '%should%' OR user_message ILIKE '%wrong%')
  ORDER BY timestamp DESC LIMIT 50
  ```

  Swap in the area's words. Quote him in the evidence, with the date.
- **Repository docs that record findings**: [docs/performance.md](../../../docs/performance.md) (findings on
  a TV, suspected hotspots), [docs/transitions.md](../../../docs/transitions.md), the README's Troubleshooting
  and Questions, and open issues (`gh issue list --state open`).

## 3. Scout: where it hurts most

Take an emulator slot. `start` waits until one of the Mac's three is free, which can take an hour or two on a
busy day, so start it in an async shell, do the reading in section 2 and the research for known candidates
meanwhile, and read its output when it's done. It prints the emulator's serial once it has booted:

```bash
python3 tools/emulator.py start --owner daily-<area>     # prints e.g. emulator-5556, and binds this worktree to it
```

It stops your emulator after six hours unless you `tools/emulator.py renew <serial>`. Then, holding the slot,
build and install `main` as it is (your branch hasn't changed yet):

```bash
. tools/devenv.sh && (cd launcher && ./gradlew -q assembleDebug assembleRelease testDebugUnitTest) \
  && adb install -r launcher/app/build/outputs/apk/debug/app-debug.apk   # debug: walkthrough.py can check settings
```

Collect the signals the playbook lists, then write the candidates down as a table, with ICE scores
(Impact × Confidence × Ease, 1 to 5 each):

| # | Candidate | Evidence | I | C | E | Score |
|---|---|---|---|---|---|---|
| 1 | Coming back to Home redraws the whole grid | warm start 412 ms median on the TV (cache, 2026-10-21), 30 % in `tvhome:reload`; trace attached | 5 | 4 | 3 | 60 |

- **Impact**: 5, everyone, every time Home shows; 4, a daily task; 3, weekly, or some TVs; 2, rare; 1, a corner
  case.
- **Confidence**: 5, measured on the TV (the perf cache, `docs/performance.md`); 4, measured today on the
  emulator; 3, a screenshot measured against a published rule; 2, read from the code; 1, a hunch. Pick nothing
  below 3: measure it first.
- **Ease**: 5, a few lines; 3, one feature across a few files; 1, a redesign (split it).

Pick the highest score that fits one small pull request, stays off open pull requests' files and today's
claims, and isn't "tried, no gain" in the backlog unless there's new evidence. Everything else goes to the
backlog. **Claim it at once:** edit your run comment to `Status: fixing` with a line `Files: <path>, <path>`
naming the files you expect to change, then read today's claims again (section 2): when another run claimed
one of your files too, the claim with the lower comment id keeps it, and the other run picks something else.
If you later need a file another run has claimed, pick something else.

## 4. Research: what the best do

Before changing anything, find out what good looks like, from primary sources: Apple's Human Interface
Guidelines and WWDC sessions (the launcher follows Apple TV), Google's TV design and TV app quality guidelines,
Android's performance and accessibility docs, WCAG 2.2, Nielsen Norman Group. The playbook lists the ones for
the area. Use `web_search` and `web_fetch`; read the page, not a summary of it.

- **Check the repository's skills first.** [ux-writing](../ux-writing/SKILL.md) already holds Apple's UX
  writing rules and the launcher's word list. Follow what a skill says rather than researching it again.
- Write down the three to seven rules that decide today's change, each with its link, and what Apple TV or
  Google TV does in the same spot.
- **Keep what lasts.** When the area has no guide yet and the rules will matter again, distil them into a new
  skill in `.github/skills/<topic>/SKILL.md` in the same pull request, in the shape of `ux-writing`: the rules,
  the launcher's own values, before → after, sources. Otherwise the rules go in the backlog comment. Pull
  request descriptions don't carry research ([pull-requests](../pull-requests/SKILL.md)).

## 5. Measure before

Before editing, write down: the metric, how it's measured (commands, keys, screens), its value on `main` now,
the target the research sets, and the noise (the spread between repeats). Keep the raw data (JSON, screenshots,
dumps) in the session's files folder, not in the repository.

For screens, write the walkthrough plan now (step 8) and run it on `main`'s build: its screenshots are the
"before" pictures, and the same plan on your build gives the "after" ones, from the same steps.

## 6. Fix

One topic. Follow the code around it: plain Java, the launcher's names and comments, `config.xml` for anything
TV-specific. Add or extend a JVM test for any logic. Every changed string follows `ux-writing` and is translated
in `values-de`. Update the README and docs that describe what you changed.

## 7. Check it

```bash
tools/ci.sh                     # what ci.yml runs: GitHub Actions is off on this fork, so this is the CI
```

Then on your emulator:

- **The metric again**, the same way, with your build. It must reach the target, or at least beat `main` by
  more than the noise. If it doesn't, try once more; if it still doesn't, drop it: back out the change, record
  "tried, no gain" with the numbers in the backlog, and finish without a pull request.
- **Nothing else got slower.** `python3 tools/perf.py plan` says which scenarios. Then measure `main` and
  yours back to back, inside the Mac's measuring lock, so other runs' builds and measurements don't skew one of
  the two ([launcher-tv-testing](../launcher-tv-testing/SKILL.md), sections 2 and 3, explain each step):

  ```bash
  . tools/devenv.sh && python3 tools/emulator.py exclusive --owner daily-<area> -- sh -c '
    python3 tools/perf.py baseline refresh --scenarios auto --quick &&    # main, measured now
    launcher/build-and-install.sh &&                                     # your release build
    python3 tools/perf.py run --label <topic> --scenarios auto --quick'
  python3 tools/perf.py compare --against main tools/perf-results/<topic>.json
  ```

  A number that's worse beyond the noise is a problem to fix before shipping, not a footnote. Other runs'
  builds can still load the Mac while you measure: when a difference is small, measure both again.
- **Nothing else broke.** When you changed launcher code, the shared core tour must still pass on your debug
  build: `python3 tools/walkthrough.py run tools/walkthroughs/core-tour.json --out <files>/core-tour`. Look at
  its pictures as you look at your own.
- **It looks and moves right**: before and after screenshots of every screen you changed; D-pad from every edge
  of what you touched.

## 8. Prove the TV walkthrough

`/test-on-tv` replays a walkthrough plan on the TV, and judges it by what each screenshot should show. Write one
for your change with the format in [tools/walkthrough.py](../../../tools/walkthrough.py) (its docstring):

- **Test the feature, the way Brian will use it** (his rule, 2026-10-21): start from Home, go to it with the
  D-pad, open each screen and choice you changed, and take a screenshot where the result shows, with an
  `expect` that a stranger could check against the picture ("Netflix's row says App Colour, ticked", not
  "looks right"). Add a `cold_restart` and a screenshot when what you changed should survive the process
  ending. A `record` clip for anything that moves.
- **Put back what you change.** The TV is Brian's: undo every setting the plan changes, and never end an
  Arrange with `CENTER` (that saves the order).
- `"perf": {"scenarios": "auto"}` unless your change needs another set.

```bash
python3 tools/walkthrough.py check plan.json
. tools/devenv.sh && adb install -r launcher/app/build/outputs/apk/debug/app-debug.apk   # your debug build
python3 tools/walkthrough.py run plan.json --out <files>/walk-after
```

It must pass, with the debug build installed (a release build can't show its saved settings, so the check
would be skipped). Look at every screenshot yourself: each must show what its `expect` says.

## 9. Ship a draft pull request

- Commit on this session's branch (with the Co-authored-by trailer), push, and open a **draft** pull request
  with the app's `create_pull_request` tool (draft) or `gh pr create --draft`. Labels: `needs-tv-test` and
  `improve:<area>`.
- The description follows [pull-requests](../pull-requests/SKILL.md) and the template: **Why** with the
  evidence and the number before, and `Backlog: #<issue>`; **What**; **How tested** with the local checks, the
  emulator's numbers marked as the emulator's, before and after screenshots (upload them with the
  `github-pr-media` skill), and a last line `TV: pending, /test-on-tv pr <N>`.
- Post the walkthrough with the manifest of its passing emulator run. `comment` refuses a manifest from a
  failed run, from a device that isn't an emulator, or from a plan that changed since: then run it again.

  ```bash
  export GH_REPO=briansilah/tv-vibehack            # origin: gh would pick the upstream remote
  python3 tools/walkthrough.py comment plan.json --manifest <files>/walk-after/manifest.json --pr <N> > walk.md
  gh pr comment <N> --body-file walk.md
  ```

### Fixing a pull request that failed on the TV

Work on its branch, in this worktree: `git fetch -q origin && git checkout -B <your branch> origin/<branch>`,
then merge `origin/main`. Fix what the `/test-on-tv` comment says, check it as in sections 7 and 8, push with
`git push origin HEAD:<branch>`, post a new walkthrough comment if the plan changed (the newest one counts),
and update How tested. Then put it back in the queue:
`gh pr edit <N> --remove-label tv-failed --add-label needs-tv-test`. No new pull request; the backlog comment
links the same one.

## 10. Log it in the backlog

Edit your run comment (`gh api -X PATCH repos/{owner}/{repo}/issues/comments/<id> -F body=@run.md`):

```markdown
<!-- daily-improvement 2026-10-26 -->
**2026-10-26: performance.** Status: scouting | fixing | #41 (draft, needs TV) | no change | blocked: <why>
Files: launcher/app/src/main/java/com/gathin/tvhome/AppAdapter.java

Picked: <candidate>, <metric> <before> → <after> on the emulator (target <target>).

| # | Candidate | Evidence | I | C | E | Score |
|---|---|---|---|---|---|---|

Rules that set the bar: <rule> ([source](link)), ...

<details><summary>Data</summary>

Commands, raw numbers, what was tried and dropped.

</details>
```

Then rewrite the issue body's table: add today's new candidates, link the pull request on the one you picked,
and mark dropped ones "tried, no gain" with the date and the numbers.

## 11. Clean up

Stop your emulator (`python3 tools/emulator.py stop <serial>`, which also unbinds the worktree; never anyone
else's), delete temporary files outside the session's files folder, and leave the worktree as it is. Finish with two or three
sentences: the area, what changed and by how much (or why nothing did), and the pull request.

## When something goes wrong

- `main` doesn't build, the emulator won't boot, or `gh` fails: say so in the run comment (`Status: blocked`)
  and stop. Never fall back to the TV.
- No emulator slot came free (`start` gives up after six hours): record `Status: blocked: no emulator slot` and
  stop. Never stop someone else's emulator.
- Out of time or budget: ship what's measured and done, or record where you stopped in the backlog.

## The backlog issue

Title `Improvement backlog: <Area>`, labels `improvement-backlog` and `improve:<area>`. Its body:

```markdown
The daily improvement run's candidates for <area> (.github/skills/daily-improvement), best first.
Each run adds a comment with its data; this table is the current state.

| Candidate | Evidence | I | C | E | Score | Status |
|---|---|---|---|---|---|---|
| ... | ... | | | | | open / #41 / tried, no gain (2026-10-26: 412 → 405 ms) |
```
