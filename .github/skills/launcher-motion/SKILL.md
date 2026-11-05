---
name: launcher-motion
description: Use when changing the launcher's springs, fades or transitions. Preserve its measured tvOS motion, interruption behavior and Android Remove animations setting; compare clips and frame costs on your own emulator before a TV test.
---

# Launcher motion

The launcher follows the measured tvOS curves in [docs/transitions.md](../../../docs/transitions.md).
Keep those values rather than substituting generic motion tokens.

## Rules

- Motion explains focus and navigation; it must not delay D-pad input. Retarget a running spring
  without discarding its velocity. Cancel an old animator before replacing it.
- Honor Android's **Remove animations** preference. `ValueAnimator.areAnimatorsEnabled()` is
  available on every supported API (26+). DynamicAnimation 1.0.0 springs do not honor it automatically:
  cancel the spring, set its final state and apply all related properties together when disabled.
- Preserve the same focus destination, visibility and final geometry with motion on or off.
  Check both endpoints and rapid reversal, not only a fully settled opening.
- Prefer render properties to layout work. Keep frames cheap on 1 GB TVs; measure with
  [launcher-tv-testing](../launcher-tv-testing/SKILL.md), not intuition.

## Before and after

The profile corner used to keep sliding with animator duration scale 0. It now cancels the spring and
sets openness directly to 0 or 1; its normal spring is unchanged. Other existing springs still need
individual checks before claiming that reduced motion works throughout Home.

Film the same D-pad sequence with scale 1 and 0, restore the emulator's scale to 1 afterwards, and
inspect intermediate frames. Screenshots alone cannot prove the absence of motion. Daily runs use
only their own emulator, never the TV.

## Sources

- [Apple HIG: Motion](https://developer.apple.com/design/human-interface-guidelines/motion):
  optional, brief, precise and cancellable motion.
- [Android spring animation](https://developer.android.com/develop/ui/views/animations/spring-animation):
  velocity, retargeting and cancellation.
- [ValueAnimator.areAnimatorsEnabled](https://developer.android.com/reference/android/animation/ValueAnimator#areAnimatorsEnabled()):
  the system-wide animator preference, including duration scale 0.
