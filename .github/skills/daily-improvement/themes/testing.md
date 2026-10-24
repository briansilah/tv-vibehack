# Tuesday: ease of testing

**Goal:** every change can be checked faster and more surely, by agents and by people, with fewer things that
only a person at the TV can check. Better tests and tools make every other day's work cheaper.

## Scout

1. **Logic that changes often and has no test.** Classes changed most in the last 60 days, next to their
   tests:

   ```bash
   git log --since=60.days --format= --name-only -- launcher/app/src/main/java | sort | uniq -c | sort -rn | head -20
   ls launcher/app/src/test/java/com/gathin/tvhome/
   ```

   A class high on the list without a `<Class>Test.java`, or whose logic is stuck in `MainActivity` or a view
   where a JVM test can't reach it (as `AmbientColor`, `GridScroller` and `ShelfChoices` were pulled out), is a
   candidate.
2. **Fixes without a regression test:** `git log --since=60.days -i --grep=fix --stat` where no test file
   changed in the same commit.
3. **How long the loop takes.** Time each, warm: `./gradlew testDebugUnitTest`, `./gradlew assembleRelease`,
   `python3 -m unittest discover -s tools/tests`, and emulator boot to first screenshot. CI:

   ```bash
   GH_REPO=briansilah/tv-vibehack gh run list --workflow ci.yml --limit 20 --json conclusion,createdAt,updatedAt,event \
     --jq '.[] | "\(.conclusion) \(.event) \(((.updatedAt|fromdate)-(.createdAt|fromdate))/60|floor) min"'
   ```

   Failures on `main` with no code change are flaky tests.
4. **Manual steps.** What the skills (`launcher-tv-testing`, `tv-lease`, this one) and the docs ask an agent
   or a person to do by hand that a tool could do; what `perf.py plan` says no scenario measures; step kinds a
   walkthrough is missing; checks a reviewer has to do by eye.
5. **Tool coverage**, measured only (don't add it to `requirements.txt`):
   `tools/venv/bin/pip install coverage && tools/venv/bin/python -m coverage run -m unittest discover -s tools/tests && tools/venv/bin/python -m coverage report`.

## Metrics and the bar

| Metric | How |
|---|---|
| Untested high-churn classes | the list in 1, before and after |
| Loop time | seconds for each command in 3, median of three warm runs |
| CI time and flakiness | median minutes; failures on `main` without a code change |
| Manual steps | count of steps in skills and docs that a tool now does |
| Coverage | lines covered in `tools/` (Python) |

## Research

- [Fundamentals of testing Android apps](https://developer.android.com/training/testing/fundamentals) and
  [what to test](https://developer.android.com/training/testing/fundamentals/what-to-test)
- *Software Engineering at Google*, [Testing Overview](https://abseil.io/resources/swe-book/html/ch11.html) and
  [Unit Testing](https://abseil.io/resources/swe-book/html/ch12.html): small, fast, deterministic tests
- Martin Fowler, [The Practical Test Pyramid](https://martinfowler.com/articles/practical-test-pyramid.html)

## Typical fixes

Pull logic out of an activity or view into a plain class with a JVM test; a regression test for a recent fix;
a tool or a `perf.py` / `walkthrough.py` option that replaces a manual step; a faster or cached build step;
test fixtures from real TV output (`tools/tests/fixtures`).

## Check it

The metric before and after; every new test run five times in a row to show it isn't flaky; CI green; the
loop no slower.

## Pitfalls

- No new launcher test dependencies (Robolectric, Mockito) without an issue first.
- Tests don't touch the network, the clock or a device: inject them, as the existing tests do.
- A refactor to make code testable must not change behaviour: the walkthrough and the perf scenarios prove it.
