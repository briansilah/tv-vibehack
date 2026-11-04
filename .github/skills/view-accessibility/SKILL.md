---
name: view-accessibility
description: Use when changing the launcher's custom Android Views, accessibility labels, choice rows or colour swatches. Preserve native role and saved-state semantics independently of D-pad focus.
---

# Accessible launcher views

Keep the TV's existing appearance and deterministic D-pad navigation. Expose the same information to
TalkBack through Android's accessibility nodes and events, not additional visible controls.

- **Name the thing, not its type.** Use localized labels from resources; follow `ux-writing`.
  App names already come from their installed packages. Do not append "button" or "radio button".
- **Expose saved choice separately from highlight.** A D-pad highlight previews a choice; it does not save
  it. Top Shelf option rows and colour swatches expose the native `RadioButton` role, `checkable=true`
  and `checked` from the same saved-choice comparison that draws the checkmark.
- **Keep events consistent with nodes.** Custom choice delegates set the role and checked state on both
  `AccessibilityNodeInfo` and `AccessibilityEvent`. Retain the superclass's label, focus and click handling.
- **Do not make navigation rows radio buttons.** Home Settings' summary rows and Custom open another
  page; they retain their label/value descriptions. Only controls that actually pick a choice are checkable.
- **Rebuild semantics with the page.** Settings recreates its views after saving a choice. Each delegate
  captures that page's saved state; if a future change updates choices in place, update its accessible state
  and notify accessibility services too.

## Before and after

| Before | After |
|---|---|
| Off has a visible checkmark but reports `checked=false` | Off reports a radio button with `checked=true` |
| Moving the highlight leaves no way to identify the saved option | Highlight and checked state are independently inspectable |
| Colour swatches expose only a colour name | Named radio choices expose the same state as their checkmarks |

## Check

On an owned emulator, repeat UIAutomator dumps before and after. All choice controls must be checkable;
only the saved option is checked. Move focus without pressing OK and confirm the checked option stays put.
Choose a different option, reopen its page, and check again after a cold restart. Restore saved settings.
Inspect the same screenshots at font scales 1.0 and 1.3; labels, checkmarks and navigation must not change.

## Sources

- [Android: custom Views accessibility](https://developer.android.com/guide/topics/ui/accessibility/views/custom-views)
- [Android: accessible Views](https://developer.android.com/guide/topics/ui/accessibility/views/apps-views)
- [Android TV accessibility](https://developer.android.com/training/tv/accessibility)
- [WCAG 2.2: Name, Role, Value](https://www.w3.org/WAI/WCAG22/Understanding/name-role-value.html)
- [Apple WWDC19: Writing great accessibility labels](https://developer.apple.com/videos/play/wwdc2019/254/)
