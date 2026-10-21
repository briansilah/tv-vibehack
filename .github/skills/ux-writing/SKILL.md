---
name: ux-writing
description: Use when writing or changing any words the launcher shows on the TV (strings.xml and its translations): settings rows, their values and explanations, an app's actions (hold OK), headers, buttons, hints, accessibility labels. Apple's UX-writing guidance (WWDC sessions and the Human Interface Guidelines) boiled down, the launcher's word list, and before → after examples, so every label is short, plain, consistent and easy to act on from the sofa.
---

# UX writing for the launcher

Every word on the TV is read from about three metres away, with a remote, often by someone who just wants
to watch something. Write so they understand at a glance and know what will happen when they press OK.

This is Apple's guidance, from the WWDC sessions by Apple's UX writers and the Human Interface Guidelines
(HIG), cut down to what this launcher needs. Sources are at the end.

## The four questions (WWDC22, Writing for interfaces)

Ask these of every screen and every string, then read it out loud.

1. **Purpose.** What is this screen for? Put the most important idea first; cut what doesn't serve it.
2. **Anticipation.** What will they want to know next? Answer it before they have to ask (what OK does,
   what happens to their other choices, why the screen doesn't show what they picked).
3. **Context.** Where are they? On a sofa, far from the screen, with a five-way remote: fewer words, bigger
   ideas, no screens full of reading.
4. **Empathy.** Plain words that anyone (and a translator, and a screen reader) understands. No idioms, no
   jokes, no blame.

## Rules

**Words**

- **Be clear, then be brief.** Use the words people already know. If fewer words say it, use fewer.
  ("Simply enter your license plate number to quickly pay for parking." → "Enter your license plate number
  to pay for parking.", WWDC25)
- **Cut filler**: *simply, just, easily, quickly, please, sorry, oops*. Don't say the same thing twice
  (in a label and its explanation, or in two sentences).
- **Lead with the why.** "To see an app's Top Shelf, move it into the Dock.", not "Move an app into the Dock
  to see its Top Shelf." (WWDC25)
- **Describe the result, not the mechanism.** Say what they'll see ("Titles from Netflix, a new one every
  10 seconds"), not how it's made (feeds, Watch Next, caches). The exception: a message for whoever set up
  the TV, such as a feature turned off in `config.xml`.
- **Name things so the name sets the right expectation, fits the app's voice, and translates** (WWDC26).
  Say it in a sentence: "Netflix's Top Shelf shows its App Colour" should make sense. Prefer an ordinary
  word to an invented or internal one ("Original" told nobody what they'd get).
- **Possessives sparingly; no "we".** "Favourites", not "Your Favourites". The launcher doesn't talk about
  itself.
- **The voice stays the same; the tone can change** (WWDC24). Ours is calm, plain and helpful, like Apple
  TV's own Settings.

**Settings** (HIG Settings, Writing, Lists and tables, Pickers)

- **The default is what's best for most people**, so most never need to open Settings. Mark it
  ("Default") so people can find their way back.
- **Few settings, grouped.** Related rows go in one section under a short header (a noun phrase, no full
  stop). Settings that affect everything come first, and long per-app lists last, so nothing is buried
  at the end of a list.
- **A label first; an explanation only if the label isn't enough.** It says what the choice does (the "on"
  state) in one or two short sentences. Don't explain "off".
- **The value on a row is the choice's name**, the same word as on the page it opens. When the screen
  shows something else for a reason (nothing to show yet, another setting overrides it), say why, and what
  to do about it, in one sentence.
- **Options in a predictable order**: the default first, then from simplest to richest, and a row that
  opens more (›) last.
- **Link, don't give directions.** Open the right page (an app's actions open its Top Shelf page) instead
  of telling people where to go.

**An app's actions** (hold OK; HIG Menus, Context menus)

- **Only what people need here and now**, in at most three groups with a gap between them. Ours: where the
  app goes (Move, Move to Dock), then the app itself (its Top Shelf, App Info), then the whole Home Screen
  (Liquid Glass).
- **Start with a verb**, in title style, without articles ("Move to Dock", not "Move to the Dock").
- **An ellipsis (…)** when the action needs more from them before it's done (it opens a page or a slider).
- **Hide what doesn't apply**; don't show it dimmed (Move to Dock shows only outside the Dock).
- **Everything here is reachable another way too** (Home Settings has every app's Top Shelf and Liquid
  Glass).

**Capitalization** (Apple Style Guide)

- **Title style** for buttons, actions, setting rows and their values, page titles and section headers:
  capitalize every word except articles, coordinating conjunctions, *to*, and prepositions of four letters
  or fewer (unless first or last). A preposition that's part of the verb is capitalized: "Move Out of Dock".
- **Sentence style** for explanations, hints and messages, which end with a full stop.
- **Places on the screen are proper nouns**: Top Shelf, Dock, Home Screen.
- British spelling (Colour, Favourites), as in the rest of the launcher. The English strings are the source.

**Accessibility** (WWDC19, Writing great accessibility labels)

- A focusable view without text gets a label for what it is, not its type ("Red", not "Red colour
  button"). Rows read as "label, value".

## The launcher's word list

One word per idea. Use these, and never the ones in the last column.

| Say | Meaning | Not |
|---|---|---|
| Top Shelf | The whole screen behind the Dock, for the highlighted Dock app | top shelf art, banner, hero |
| Dock | The row of apps along the bottom of Home | top row, favourites |
| Home Screen | The launcher's main screen, in prose (the app is called Home) | launcher, home page |
| highlight | Moving to something with the remote | focus, select (OK *selects*) |
| Picture | An app's Top Shelf by default: its own picture if it's on this TV, else its Artwork, else its App Colour | Static Image, image, still |
| Artwork | A picture the launcher paints for an app | art, illustration |
| Cover Art | An app's logo on shapes in its colours | cover, logo art |
| App Colour | The app's name on a glow in its own colour | Original, glow, plain |
| Suggestions | Titles from the app, a new one every 10 seconds | dynamic content, feed, recommendations |
| Custom | Artwork, Cover Art or a colour you pick | More, Other |
| Same for All Apps | One Top Shelf choice for every app at once, over their own | All Apps, Per App, global |
| Liquid Glass | How see-through the Dock and the top right are | glass, transparency |

German (`values-de`): Top Shelf, Dock, Home-Bildschirm, Bild, Artwork, Cover, App-Farbe, Vorschläge,
Eigene, Für alle Apps gleich, Liquid Glass.

## Before → after (this launcher)

| Before | After | Why |
|---|---|---|
| Static Image | Picture | An everyday word; "static" is jargon |
| Original | App Colour | Says what you get; "Original" only meant "as before" |
| All Apps: Per App | Same for All Apps: Off | The old pair contradicted itself |
| Top Shelf (an app's action) | Change Top Shelf… | Starts with a verb; … because it opens a page |
| App info | App Info | Title style, as every action |
| What %1$s suggests, full screen: what you were watching first, then what's popular. A new title every 10 seconds. | Titles from %1$s, a new one every 10 seconds: what you're watching first, then what's popular. | The result first, then the detail |
| Their top shelf shows once you move them into the Dock. | To see an app's Top Shelf, move it into the Dock. | Leads with the why |
| Artwork made for %1$s. Up from it opens the app. | Artwork made for %1$s, in its colours and shapes. | Says what it is; Up no longer opens the app |

## Before you commit

- Read every new string out loud, where it shows. Does it make sense without the screen around it?
- Check it fits: an action is one line (340dp), a row's label and value share one line, an explanation is
  at most two lines under the preview.
- Same word for the same thing everywhere, per the word list. Add new ideas to the list.
- Translate every changed string in `values-de` (and any other `values-*`) in the same style, with the same
  word list.
- Update the README and docs that quote the old wording.
- Screenshot before and after on your emulator for the pull request.

## Sources

- WWDC22 10037, [Writing for interfaces](https://developer.apple.com/videos/play/wwdc2022/10037/)
- WWDC24 10140, [Add personality to your app through UX writing](https://developer.apple.com/videos/play/wwdc2024/10140/)
- WWDC25 404, [Make a big impact with small writing changes](https://developer.apple.com/videos/play/wwdc2025/404/)
- WWDC26 290, [Craft clear names for features and labels in your app](https://developer.apple.com/videos/play/wwdc2026/290/)
- WWDC19 254, [Writing great accessibility labels](https://developer.apple.com/videos/play/wwdc2019/254/)
- WWDC17 813, [Writing great alerts](https://developer.apple.com/videos/play/wwdc2017/813/)
- HIG: [Writing](https://developer.apple.com/design/human-interface-guidelines/writing),
  [Settings](https://developer.apple.com/design/human-interface-guidelines/settings),
  [Menus](https://developer.apple.com/design/human-interface-guidelines/menus),
  [Context menus](https://developer.apple.com/design/human-interface-guidelines/context-menus),
  [Lists and tables](https://developer.apple.com/design/human-interface-guidelines/lists-and-tables),
  [Pickers](https://developer.apple.com/design/human-interface-guidelines/pickers),
  [Top Shelf](https://developer.apple.com/design/human-interface-guidelines/top-shelf),
  [Designing for tvOS](https://developer.apple.com/design/human-interface-guidelines/designing-for-tvos)
- Apple Style Guide, [capitalization](https://support.apple.com/guide/applestyleguide/c-apsgb744e4a3/web)
