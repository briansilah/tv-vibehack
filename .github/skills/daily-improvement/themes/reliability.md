# Sunday: reliability and code health

**Goal:** Home never crashes, never hangs and never shows a broken state, whatever the TV, the network or the
apps installed; and the code stays small and easy to change.

## Scout

1. **Shake it.** On your emulator, with the launcher on screen, a monkey run limited to the launcher's
   package, then the log:

   ```bash
   adb logcat -c
   adb shell monkey -p com.gathin.tvhome --pct-syskeys 0 --throttle 150 -s 42 -v 3000 > monkey.txt
   adb logcat -d -b crash > crash.txt; adb logcat -d | grep -E 'ANR in|FATAL EXCEPTION|StrictMode|tvhome.*(Exception|Error)' > errors.txt
   ```

   Every crash, ANR or exception is a candidate, with its stack. Re-run with the same `-s` seed to reproduce.
   Monkey can save an app order or a setting: the emulator is read-only, so restart it before measuring
   anything else.
2. **Edge cases.** Each on the emulator, with a screenshot:
   - no network (`adb shell svc wifi disable; svc data disable`, then enable both)
   - the process ending at any point (`walkthrough` `cold_restart` from each screen)
   - few apps and many (the `tvperf` AVD has about 30, 20 of them `com.example.tvstub*`); an app installed or
     removed while Home shows
   - German (`cmd locale set-app-locales com.gathin.tvhome --locales de-DE`), 4K (`Television_4K`)
   - an app without a banner or icon; a Top Shelf picture that fails to load
3. **The code.** Android Lint (on 2026-10-24 `main` had 2 errors and 29 warnings, so the task ends in
   BUILD FAILED; the report is written anyway):

   ```bash
   (cd launcher && ./gradlew -q lintRelease); R=launcher/app/build/reports/lint-results-release.txt
   tail -1 $R                                                     # "2 errors, 29 warnings"
   grep -oE ': (Warning|Error): .*\[[A-Za-z]+\]$' $R | grep -oE '(Warning|Error)|\[[A-Za-z]+\]$' | paste - - | sort | uniq -c | sort -rn
   ```

   Errors first, then the kinds that matter on a TV (`DrawAllocation`, `Overdraw`, `UnclosedTrace`). Also: dead
   code (methods and resources nothing uses; Lint's `UnusedResources`), duplicated logic, the longest methods
   (`MainActivity`), and docs that no longer match the code (README, `docs/`, skills).

## Metrics and the bar

| Metric | How | The bar |
|---|---|---|
| Crashes and ANRs | monkey, 3000 events, fixed seed | 0 |
| Edge cases that break | 2 | 0 |
| Lint warnings | `lintRelease`, by kind | fewer each week; no errors |
| Dead code and resources | Lint, grep | 0 |
| Docs that contradict the code | reading | 0 |

## Research

- Android: [Android vitals](https://developer.android.com/topic/performance/vitals) (crashes, ANRs),
  [ANRs](https://developer.android.com/topic/performance/vitals/anr),
  [Improve your code with lint](https://developer.android.com/studio/write/lint),
  [StrictMode](https://developer.android.com/reference/android/os/StrictMode)
- [TV app quality](https://developer.android.com/docs/quality-guidelines/tv-app-quality)
- Google Testing Blog, [Code Health](https://testing.googleblog.com/search/label/Code%20Health): short
  posts on keeping code simple, one problem each

## Typical fixes

Guard the failing case and show a sensible state; a regression test that reproduces the crash; work off the
main thread to remove an ANR; delete dead code and resources; split a long method; fix a doc that says the wrong
thing.

## Check it

The same monkey seed and edge cases again: 0 crashes where there were some; the Lint count before and after;
JVM tests for the fixed logic. Code-only clean-ups still get `perf.py plan`'s scenarios, and a walkthrough of
the screens the code draws.

## Pitfalls

- A clean-up must not change behaviour: no "while I'm here" changes to how it looks or works.
- Monkey's `--pct-syskeys 0` keeps it away from Home and Power; never run monkey on the TV.
- Put the network, locale and settings back before you stop.
