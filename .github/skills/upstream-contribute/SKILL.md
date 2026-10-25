---
name: upstream-contribute
description: Use for the twice-weekly upstream contribution run (/upstream-contribute), started by an automation or by hand. It finds what this fork has that the original project (Gathin23/cleanyourtv) would want, judged by upstream's own review rules, and offers only contributions that are clearly worth a maintainer's time. Usually that is none or one. It rebuilds each on upstream's code with upstream's checks and proposes it to Brian on the fork. Only after he approves does it open the pull request upstream, then it follows its review through. It never touches the TV and runs without anyone watching.
---

# Giving back upstream

This fork takes a lot from [Gathin23/cleanyourtv](https://github.com/Gathin23/cleanyourtv) every day
([upstream-sync](../upstream-sync/SKILL.md)). This run gives back the few things that would make upstream better
for its users. Upstream is run by a busy maintainer and a review bot that work through hundreds of pull requests a
week, so **a contribution that isn't clearly worth reviewing costs them more than it gives. No contribution beats
a weak one**: most runs should propose nothing, and that's a good result.

Unattended: no `ask_user`. Brian approves every contribution before it leaves the fork (section 4); that's the
only gate, and it's his.

## The bar: a contribution must pass all of these

Upstream's own rules are the yardstick: [their CONTRIBUTING](https://github.com/Gathin23/cleanyourtv/blob/main/CONTRIBUTING.md),
[AGENTS.md](https://github.com/Gathin23/cleanyourtv/blob/main/AGENTS.md) and above all the review rules in
[.github/maintainer/MAINTAINER.md](https://github.com/Gathin23/cleanyourtv/blob/main/.github/maintainer/MAINTAINER.md).
Read them from `upstream/main` at the start of every run (`git show upstream/main:<path>`): they change.

1. **Upstream's verdict would be `merge`.** Not `merge-with-fix`, `rework`, `hold`, `showcase`, `addon` or
   `theme`. In practice: a bug fix, a crash or safety fix, a missing translation or accessibility label, a wrong
   doc, a tool fix, or a small launcher switch that is off by default, has its strings in every locale and costs
   at most about 1 KB of APK. Never: a changed default for every TV, a restyle or a new look (the fork's Liquid
   Glass, Top Shelf and motion are a showcase, not a contribution), network or new permissions, CI and
   workflows, PROMPT.md's safety rules, the APK size gate.
2. **Upstream's users gain something real, with evidence:** an upstream issue that asks for it, the bug
   reproduced on upstream's own build (on an emulator), or a measured gain on upstream's code. "The fork has it"
   is not evidence.
3. **Upstream doesn't already have it, or have it coming.** Search upstream's code on `upstream/main`, its open
   pull requests, the ones merged or closed in the last 30 days, and its issues
   (`gh search prs --repo Gathin23/cleanyourtv <words>`, `gh search issues --repo Gathin23/cleanyourtv <words>`).
   A near-duplicate is a no.
4. **It's built for upstream, not copied from the fork:** made on `upstream/main`, in upstream's code, words and
   style (its `docs/workflows/` for switches, translations and perf), one topic, small (about 150 changed lines
   at most, tests not counted), with a test where upstream has tests for that code.
5. **It passes upstream's checks**, run here as upstream's CI (`.github/workflows/ci.yml`) runs them:
   `python3 -m unittest discover -s tests`, `(cd launcher && ./gradlew --no-daemon testReleaseUnitTest
   lintRelease assembleRelease)`, and the release APK under upstream's size gate.
6. **It's tested the way upstream asks:** on an emulator with upstream's build, before and after; and anything in
   `launcher/` or on the Home and launch path also on a real TV with upstream's build, with a screenshot
   ([test-on-tv](../test-on-tv/SKILL.md), "Upstream proposals").
7. **Upstream isn't busy with us already:** at most two of the fork's pull requests open upstream at once, and
   at most one new proposal per run. A kind of change upstream has turned down before isn't proposed again.

## 1. Set up

```bash
. tools/devenv.sh                                 # every shell command starts fresh: begin each with this
export GH_REPO=briansilah/tv-vibehack
U=Gathin23/cleanyourtv
git remote get-url upstream >/dev/null 2>&1 || git remote add upstream https://github.com/$U.git
git fetch -q origin && git fetch -q upstream
for l in upstream-proposal upstream-approved upstream-sent; do gh label create $l --color 0E8A16 2>/dev/null; done
gh pr list --repo $U --author @me --state all --limit 20 --json number,title,state,url    # ours, upstream
gh issue list --label upstream-proposal --state all --limit 30 --json number,title,state,labels,updatedAt
```

Read upstream's CONTRIBUTING.md, AGENTS.md and `.github/maintainer/MAINTAINER.md` from `upstream/main` now.

## 2. Follow up what's already upstream

For each of our open pull requests upstream:

- **Review comments** (the maintainer or its bot): when the ask is clear and small, do it on the
  `contrib/<topic>` branch, check again (bar points 5 and 6), push, and reply in one or two lines saying what
  changed. When upstream asks for a rebase because it conflicts, rebase the `contrib/` branch on
  `upstream/main`: it's ours alone, so `git push --force-with-lease origin contrib/<topic>` is fine there, and
  only there. An ask you can't settle with confidence, or a disagreement: don't argue upstream; note it on the
  proposal issue for Brian.
- **Merged:** close the proposal issue with the link. **Closed without merging:** close the proposal issue too,
  with upstream's reason in it: that kind of change isn't proposed again.

## 3. Send what Brian approved

A proposal issue labelled `upstream-approved` (Brian adds it) and, when it touches the launcher, `tv-tested`:

1. Rebase its `contrib/<topic>` branch on the latest `upstream/main` (in a worktree, with an emulator slot for
   the build); run bar points 3 and 5 again (something may have landed upstream meanwhile; then close the
   proposal and say so).
2. Open the pull request upstream, with upstream's template filled in honestly and nothing else: one or two
   sentences on what changes and why, **Tested on:** with the TV and the emulator, the checklist ticked only
   where true, and the TV screenshot from the proposal.

   ```bash
   git push --force-with-lease origin contrib/<topic>
   gh pr create --repo Gathin23/cleanyourtv --base main --head briansilah:contrib/<topic> --title "<area>: <what changes>" --body-file upstream-pr.md
   ```

3. Comment the link on the proposal issue and label it `upstream-sent`.

## 4. Find at most one new contribution

Look for candidates, best first:

- **Fixes the fork made to code upstream shares:** `git log --first-parent --format='%h %s' upstream/main..origin/main`,
  and the fork's merged pull requests. For each fix, find the same code on `upstream/main` and check whether the
  bug is still there (reproduce it on upstream's build when you can). For example, the fork's tiles name their
  app for screen readers (#36) and upstream's `AppAdapter` sets no names.
- **Upstream's open issues the fork has already solved**, or could in a small change:
  `gh issue list --repo Gathin23/cleanyourtv --state open --limit 100` (bugs, `help wanted`, `good first issue`).
- **Gaps the daily improvement runs measured** that hold for upstream's code too (accessibility labels,
  translations, wrong docs), from the fork's backlog issues (label `improvement-backlog`).
- **Once:** a row for this fork in upstream's `SHOWCASE.md`, which upstream invites for a heavy restyle like the
  fork's launcher, with a real-TV screenshot. Propose it only when the fork's launcher is stable and Brian
  hasn't turned it down.

Hold each candidate to the bar. When a proposal was already opened today (another run got there), stop. With
the one that clears it best (or none):

1. **Take an emulator slot first** (`python3 tools/emulator.py start --owner upstream-contribute`, in this,
   the fork's checkout; it binds it to the emulator), and build only while you hold it.
2. **Build it for upstream,** in a worktree of its own so the fork's checkout keeps its tools:
   `git worktree add -b contrib/<topic> <dir> upstream/main`. Make the change in upstream's code and style, add
   its test, and update upstream's docs it touches.
3. **Check it:** bar point 5 in that worktree, then on your emulator with upstream's build: before on
   `upstream/main`, after on your branch, with screenshots or numbers. Run the fork's `walkthrough.py` and
   `perf.py` from the fork's checkout (bound to your emulator), and install upstream's APKs with
   `. tools/devenv.sh && adb install -r <apk>`.
4. **Push the branch to the fork** (`git push origin contrib/<topic>`), never upstream.
5. **Propose it to Brian:** an issue here, labelled `upstream-proposal`, titled `Upstream: <what changes>`, with:
   why upstream's users need it (the evidence, bar point 2), what upstream already has (point 3), the verdict
   you expect from upstream's rules and why, the branch and a compare link
   (`https://github.com/Gathin23/cleanyourtv/compare/main...briansilah:tv-vibehack:contrib/<topic>`), the
   checks you ran, and the upstream pull request text, ready to send. When it touches the launcher, also label
   it `needs-tv-test`, and post the walkthrough on it (`walkthrough.py comment --pr <issue number>`), so
   `/test-on-tv` tests upstream's build on the TV.
6. **Stop your emulator** (`tools/emulator.py stop <serial>`) and remove the worktree.

Brian approves by adding `upstream-approved` (he can edit the text first); the next run sends it (section 3).
No answer means no: close a proposal that hasn't been approved in 30 days, saying it wasn't sent.

Finish with two or three sentences: what you followed up, what you sent, and what you proposed, or why nothing
cleared the bar today.
