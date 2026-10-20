---
name: pull-requests
description: Use when writing or editing a pull request's title or description in this repository. Keeps descriptions short, in exactly three parts (Why, What, How tested), with screenshots and only the perf numbers that matter, instead of long write-ups.
---

# Pull requests: short, in three parts

A reviewer should understand the pull request in under a minute. The diff shows the code, and `docs/` holds
what's worth keeping, so the description doesn't repeat either.

## Title

What changes for the user, in a few words: `Top shelf: pass over pictures too small to show sharp`, not
`Update TopShelf.java`. 70 characters at most.

## Body

Exactly these three sections, about 200 words in all, not counting images and tables:

```markdown
## Why
The problem, in one to three sentences: what the user saw, or what was slow or broken. Link the issue if there is one.

## What
- The fix, as a few bullets about behaviour, not a tour of the files.
- Anything a reviewer must know: a trade-off, a setting, something left for later.

## How tested
- What you ran: unit tests, the emulator, the TV (say which TV).
- Before/after screenshots or a short clip for anything you can see.
- For performance: only the rows that matter from `tools/perf.py compare`, with the full table folded away.
```

Fold long evidence:

```markdown
<details><summary>Full perf.py compare table</summary>

| Metric (median) | main | this PR | change |
|---|---|---|---|
| ... | | | |

</details>
```

## Leave out

- Research write-ups, sources and measured-value tables: put what's worth keeping in `docs/` and link it.
- Decision essays and the options you didn't pick. One bullet in What if a reviewer needs it.
- File-by-file change lists, and anything else the diff already shows.
- How the work went: review rounds, agents, dead ends, merges of `main`.
- Restating `CONTRIBUTING.md` or the testing skill.

## Before you open it

Read it back and cut anything a reviewer wouldn't miss. If it's still long, the pull request probably does two
things: split it.
