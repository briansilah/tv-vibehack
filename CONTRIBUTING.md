# Contributing

Thanks for helping more TVs lose their ads. Three kinds of contributions are especially welcome.

## 1. Add your TV model

Every brand ships different bloat. If you ran the prompt (or did it by hand) on a TV that isn't in
`examples/`, add it:

1. Create `examples/<brand>-<model>/debloat.txt`. Copy the format from
   `examples/xiaomi-mi-tv-4a/debloat.txt`: one package per line, grouped with a comment that says
   what the group is, and a trailing comment on anything non-obvious ("re-enable for firmware
   updates", "needed for HDMI-CEC", etc.).
2. Put the exact model name, Android version and firmware build in the header so people can tell
   whether it matches theirs. `tools/tv.py --probe` prints all three.
3. Only list packages you have actually disabled and lived with for a few days. Note anything that
   broke and what you re-enabled.
4. Add a row to `examples/README.md`.
5. If your TV's Live TV app or stock launcher has a different package name, say so in the header;
   `tools/restore.sh` and `launcher/build-and-install.sh` accept them as arguments.

Never list packages to *uninstall*. Everything in this repo disables with `pm disable-user`, which
is reversible from the TV's own settings if the tools are gone.

## 2. Improve the launcher

The launcher lives in `launcher/` and is deliberately small: plain Java, AndroidX RecyclerView and
DynamicAnimation, no Kotlin, no Compose. It targets TVs with 1 GB of RAM, so:

- Blur only where it is measured cheap on a real TV (the dock's Liquid Glass blurs a small
  half-resolution layer, redrawn only when what's behind it changes, and falls back to a plain tint on
  older or low-RAM TVs). No video backgrounds, no polling, no background services.
- The network only for the top shelf's pictures and public catalogues (README, "The top shelf"):
  fetched off the main thread with short timeouts, cached on the TV so it works offline, without
  accounts, keys or identifiers, and within each source's terms. Never telemetry.
- Anything that depends on a specific TV goes in `app/src/main/res/values/config.xml`, not in code.
- Keep D-pad navigation deterministic. Test Up/Down/Left/Right from every edge.
- Verify on a real TV, not only the emulator. The easiest way is remote key injection plus a
  screenshot: `tools/tv.py 'input keyevent KEYCODE_DPAD_DOWN; screencap -p /sdcard/s.png'` then
  `tools/tv.py --pull /sdcard/s.png s.png`. Include a screenshot in your pull request.
- For performance changes, include a before/after table from `tools/perf.py compare`, measured on
  the same TV ([docs/performance.md](docs/performance.md)).
- Coding agents: [.github/skills/launcher-tv-testing](.github/skills/launcher-tv-testing/SKILL.md) says which
  checks a change needs and how to spend as little time on the TV as possible.

Build with `launcher/build-and-install.sh` (JDK 17, Android SDK platform 34 and
build-tools 34.0.0; on macOS `brew install openjdk@17 android-commandlinetools` then
`sdkmanager "platforms;android-34" "build-tools;34.0.0"`). It reaches the TV the way `tools/tv.py`
does: the `adb` binary, or `adb_shell` with `TV_HOST=<ip>` (see [Connecting](README.md#connecting)).

Logic that doesn't need a TV (e.g. `AmbientColor`, which picks the top shelf's glow colour, `CoverArt`,
which cuts an app's logo out of its banner, and `ShelfFeeds`, which orders the top shelf's titles) has
JVM unit tests: `cd launcher && ./gradlew testDebugUnitTest`.

Ideas that fit the direction: an idle screensaver with clock and ambient colour, per-TV icon packs
for apps without banners, a settings screen to edit the dock from the TV, localisation.
Ideas that don't: ads, telemetry, accounts, tracking, or anything that sends what you watch anywhere.
The top shelf's suggestions are the exception that fits: what the apps already publish on the TV,
and public catalogues fetched without identifiers.

## 3. Improve the prompt and tools

`PROMPT.md` is what most people will actually use. If your assistant got stuck somewhere, the fix
usually belongs in the prompt as one extra sentence, not in a script. Keep it brand-neutral; put
brand-specific knowledge in `examples/`.

Every script reaches the TV through `tools/tv.py`, which uses the `adb` binary or, with `TV_HOST`
set, the pure-Python `adb_shell` client. Keep both working: `adb_shell` exists because the stock
`adb` is blocked on some macOS setups, and the `adb` binary is the only way to reach TVs that offer
just wireless debugging. `tools/tests` covers both offline:
`python3 -m unittest discover -s tools/tests`. If you find a cleaner workaround, open an issue first.

## Pull requests

- One topic per PR. Small is fine.
- CI (`.github/workflows/ci.yml`) must pass. On every PR it builds the launcher (debug and
  release), runs its unit tests, and checks the Python tools and shell scripts. The debug APK is
  attached to each run as the `tvhome-debug-apk` artifact.
- Say which TV you tested on.
- No new dependencies in the launcher without discussing it in an issue.
- Be kind in reviews. Most contributors here are doing this for their parents.

By contributing you agree your work is released under the repo's MIT licence.
