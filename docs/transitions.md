# Transitions: opening apps, coming back to Home, scrolling the grid

How the launcher moves when you open an app, come back to Home, and scroll through the grid. The
motion copies Apple TV's. Everything here was measured frame by frame from screen recordings.

## What Apple TV does

### Sources

- **Opening and returning**: a screen recording from an Apple TV 4K (3rd generation) on tvOS 26:
  3840×2160, variable frame rate (about 35 fps), opening Settings from the dock and pressing Home,
  twice.
- **Scrolling from the dock into the grid and back**: HalfManHalfTech, [Apple tvOS 26 is Epic. Hands
  On First Look at 15+ New Features & Changes](https://www.youtube.com/watch?v=MIRP9gpvvUs),
  4:44–5:12, a direct capture at 1080p30.
- **Scrolling between grid rows** (tvOS 17, many apps): Proper Honest Tech, [Apple TV is INCREDIBLE
  when you know how to use it!](https://www.youtube.com/watch?v=lqth5ytlrOo), 3:20–3:32, 1080p30.

**Method.** Each frame was extracted with ffmpeg. To measure the zoom and fade, every frame was
modelled as a mix of two still frames, one of Home and one of the app. Home was scaled about a centre
point, and the fit found the scale and the opacity of each picture. The best centre was the middle of
the screen.

For scrolling, a band around the dock row was template-matched in every frame. Springs were then
fitted to the curves.

### Measured

| | Apple TV |
|---|---|
| **Opening an app: the tile** | Pressed in: relative to the tiles around it, 1.20× to 1.11× in about 100 ms, while Home moves away |
| **Opening: Home** | Grows toward you, centred on the screen: 1.00 to 1.15 in 180 ms, about 1.0× per second and still growing as it disappears. It fades fast at first: half gone after about 35 ms, 90 % gone after 90 ms, invisible after 150 ms. The fit is (1 − t/240 ms)^4.8 |
| **Opening: the app** | Comes from behind Home, centred on the screen, *not* out of the tile. Its launch screen fades in: half after 200 ms, 90 % after 420 ms. The icon on it grows from about 0.8× over about 900 ms. The app's own screen then replaces it with a 250 ms cross-fade |
| **Home or Back: the app** | Shrinks away (to 0.85× in 200 ms) and fades out in about 200 ms |
| **Home or Back: Home** | Falls back into place, centred, from about 1.3×: half-way after about 200 ms, 90 % after 440–470 ms, settled after about 700 ms. That fits a critically damped spring at ω ≈ 8.25 rad/s (ζ 0.94). Home is fully shown after about 200 ms and keeps settling after that |
| **Home or Back: what you see** | Focus is still on the app you opened, with the top shelf already showing it. Because the zoom is centred on the screen, tiles near an edge come in from that side (the left dock tile moves in from the left more than it moves up) |
| **Down from the dock into the grid** | The whole page moves up: the dock row goes to the top of the screen, and the rows below get the rest. The top shelf moves up with the page, dims and blurs. The clock and profile in the corner fade out |
| **Down from the dock: the curve** | A spring with no overshoot: stiffness about 225, damping ratio about 1.35. 90 % after about 390 ms, settled after about 750 ms. Going back up is the same (stiffness 203, damping ratio 1.32) |
| **Between grid rows** | The page moves only when the focused row would leave the screen, and by one row: 90 % after about 360 ms, settled after about 700 ms. Holding the D-pad carries the motion on smoothly |

## What Android lets a launcher do (Android 14, Google TV)

| | Can we control it? |
|---|---|
| Our own window's content | Yes: anything. Scale and alpha on the root view, drawn from a hardware layer, cost one textured quad per frame. |
| The opened app's window | Only through `ActivityOptions` at launch. Between tasks, Android 12+ still honours `makeScaleUpAnimation`, `makeClipRevealAnimation` and the thumbnail scale-ups ([DefaultTransitionHandler](https://cs.android.com/android/platform/superproject/+/android14-release:frameworks/base/libs/WindowManager/Shell/src/com/android/wm/shell/transition/DefaultTransitionHandler.java)). It ignores `makeCustomAnimation` across tasks unless `overrideTaskTransition` is set, and only system apps may set it. The scale-up is fixed: 336 ms, decelerate-cubic scale, and the fade done in its first half. |
| The app's launch screen and first frame | No: the app draws them. |
| Morphing the tile into the app ("shared element") | No. Remote transitions, which let Google's own launcher animate both windows, need `CONTROL_REMOTE_APP_TRANSITION_ANIMATIONS`, a signature/privileged permission. |
| Home's window after a Home press | Partly. On Google TV `HomeButtonService` starts Home, so it passes the options: a scale-up from the whole screen, which is a plain 336 ms fade. The app stays put underneath. |
| Home's window after Back | No: the system's task-close animation runs. On this TCL Google TV it is `atv_fade_out_bottom`, a 400 ms fade that starts slowly. Stock Android slides the windows sideways instead (500 ms, `task_close_enter`). |
| Home brought back by a plain HOME intent (`am start`, or where this launcher is the home app) | No: the system's task-to-front animation runs. On this TV it is `atv_fade_in_top`, a 400 ms fade; on stock Android it is a sideways slide. |
| `windowAnimationStyle` in our theme | Only affects activity transitions inside our own task, not the system's task transitions. |

So the launcher animates **its own window**: it pushes Home away when an app opens, and lets Home fall
back into place when it shows again. The system **fades the windows**. Where the system does more than
fade (stock Android's slides), Home doesn't add its own motion on top. `Transitions.systemFadeMs`
reads the system's animations once, in the background, to decide.

## What the launcher does

| | Before | Now | Apple TV |
|---|---|---|---|
| **Opening an app** | The system's cross-fade; Home doesn't move | The tile presses in to 0.92× of its focused size (Select's key down), and Home grows to 1.2× in 200 ms while fading as (1 − t)^3.5. The app's window grows from 0.86× as it fades in (the system's 336 ms scale-up), centred | Press 1.20 → 1.11 of the neighbours; Home to 1.15 in 180 ms, gone after 150 ms; the app from behind, centred |
| **Home** (the remote) | The system's 300 ms cross-fade | The system fades Home's window in. Home falls from 1.3× on the tvOS curve: a critically damped spring at 8.25 rad/s, run for 900 ms. It shows the app you came from | From about 1.3×, ω ≈ 8.25, settled after about 700 ms |
| **Back** | The system's 400 ms fade | The same fall inside the system's fade | Same as Home |
| **Down from the dock** | The focused row slid into a band at the bottom; the dock climbed one row per press | The page moves on a spring (stiffness 225, damping ratio 1.35) until the dock row reaches the top of the screen's safe area. The grid fills the screen: the clock and buttons scroll away with the page and fade over 110 dp | Stiffness about 225, damping ratio about 1.35; the dock row goes to the top |
| **Between rows** | RecyclerView's smooth scroll, restarting at every press | The same spring. A press while it is still moving carries its speed on. The page moves only as far as needed to show the whole focused tile and its name | 90 % after about 360 ms |
| **A settings panel or other see-through app** | The system's animation | Home stays as it is under it | – |

### Where it is

- `Transitions`: the launch and Home options, the see-through check, and reading the system's
  task animations.
- `HomeMotion`: Home's push and fall on the root view.
- `GridScroller`: the scroll spring, the row targets, and the layout manager (it lays out a row's
  worth beyond each edge so the next row is always there to take focus).
- `MainActivity.onLaunchApp`, `comeBackFromApp`, `gridScroll` and `followPage`.
- `AppAdapter.Holder.press`: the pressed tile.

### Performance

All the motion is translation, scale and alpha: no layout runs while it plays. During the push and the
fall, Home is drawn once into a hardware layer and then only composited. The pressed tile finishes its
press first, so the layer doesn't change while Home moves. The layer takes 8 MB of graphics memory
while it plays and is freed afterwards. See the pull request for before/after numbers from the TCL TV.
`tools/perf.py`'s warm start now counts the 900 ms fall: about 55 frames instead of 2.
