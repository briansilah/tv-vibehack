---
name: view-drawing
description: Use when changing custom View drawing in the launcher. Keep reusable drawing objects out of onDraw, refresh cached graphics when their inputs change, and prove that pixels and D-pad behavior stay the same.
---

# Custom View drawing

The launcher runs on low-memory TVs. Remove repeat drawing work without changing its appearance or navigation.

- Allocate reusable Paint, Path and Rect objects with the View, not in its draw methods or tile loops.
- Reset a reused Path before each independent shape. Save and restore each Drawable's bounds around drawing it.
- Build a shader when its colour or geometry changes, before invalidating the View. Never cache a shader without
  updating it when an input changes.
- Use the coordinate system in which the shader is drawn. ShelfPreview draws in a fixed 960 by 540 dp Home
  canvas, then scales the canvas to the preview's width; its glow does not depend on the preview's layout size.
- Measure the exact claim. Lint findings or compiled allocation sites can prove less recurring allocation code;
  they do not prove a frame-time or memory improvement. Keep speed claims for measured before/after runs.

## Before and after

ShelfPreview used to construct a gradient and its colour array on every glow draw, and a Path and Rect for every
Dock tile. It now builds the gradient in showGlow and reuses the tile drawing objects. The geometry, colour stops,
clipping and Drawable bounds restoration stay the same.

Run lintRelease, inspect compiled allocation sites, and replay the same preview walkthrough before and after:
Picture, App Colour, Custom's picture and all eight colours, then Back and a cold restart. Compare the preview's
pixels as well as looking at its pictures. Follow launcher-tv-testing for the core tour and scoped performance
checks. Daily runs use only their own emulator; the TV waits for /test-on-tv.

## Sources

- [Android: Optimize a custom view](https://developer.android.com/develop/ui/views/layout/custom-views/optimizing-view):
  eliminate onDraw allocations; initialize objects outside frequently called drawing routines.
- [Android: Improve your code with lint](https://developer.android.com/studio/write/lint):
  run the correct variant explicitly and fix structural findings rather than hiding them.
- [Android Path.reset](https://developer.android.com/reference/android/graphics/Path#reset()):
  clear the previous path's geometry before reusing it.
