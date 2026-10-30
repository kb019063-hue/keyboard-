# Phase 4 — the keyboard you actually look at

Raised 2026-10-23 from two on-device screenshots (the owner's Gboard, and this keyboard on
the same phone). Those two bitmaps, and the rendered previews below, were deleted on
2026-10-30: they were stale, so they no longer sit in the tree. Every claim they supported
is written out in this document instead.
Four complaints, in the owner's words: the layout looks chaotic next to Gboard; the mic key does not
dictate; the new tools are invisible on the keyboard itself; the settings screen is one amateurish
vertical pile that wants a hamburger menu.

Each has a concrete cause in the tree. This document names the causes and the plan; nothing here is
implemented yet.

## 1. Why it looks chaotic, and what "calm" costs

CleverKeys descends from Unexpected Keyboard, whose design philosophy is the opposite of Gboard's:
**every symbol a key can produce is printed on the key**, in up to four corners. The default layout
(`src/main/layouts/latn_qwerty_us.xml`) puts the digits in each Q-row key's north-east corner *and*
a second symbol in the north-west, plus `loc`-hidden extras that surface as sub-labels — `se="loc
esc"` under Q, `home`/`end`/`tab`/`capslock` on shift, `delete_last_word` on backspace. Gboard prints
one hint per key and hides the rest behind long-press. Same 34 keys, two philosophies; the density in
the screenshot is the philosophy, not a bug.

**Decision:** make Gboard's calm the default while keeping Unexpected Keyboard's reachability.

- Render **one** corner hint per key by default (the most useful: the digit on the Q row, the common
  punctuation elsewhere), with the remaining corners available exactly as today by long-press and
  swipe — nothing is lost, only unprinted.
- A new setting, **"Print every symbol on keys"** (default off), restores the dense rendering for
  anyone who has learned it. It is a rendering preference in `Keyboard2View`'s label pass, not a
  second layout file, so the 100+ shipped layouts do not fork.
- The `loc esc` sub-label under Q and the `Ctrl`/`Fn`-style bottom clutter become part of the same
  gate: hidden by default, present behind the toggle and behind long-press.

**A real bug found in the same screenshot:** the keyboard's bottom row is clipped by the screen edge.
The phone uses gesture navigation, so the IME window extends into the navigation-bar region and the
view does not pad for it. Fix: honour navigation-bar insets when the keyboard view lays itself out
(`WindowLayoutUtils` / the input-view setup), which also protects the floating window's placement.

## 2. The mic must dictate, not hand over

`voice_typing` currently dispatches `Event.SWITCH_VOICE_TYPING` →
`VoiceImeSwitcher.switch_to_voice_ime()` (`KeyboardReceiver.kt:606`), which *switches the active IME*
to a voice IME. On this Huawei that produced the toast "Google voice typing - Automatic" and nothing
else: the switch is the feature, and on a device where the voice IME is not enabled or not
cooperative it is a dead key.

**Decision:** real in-service dictation.

- New `voice/DictationController`: `SpeechRecognizer.createSpeechRecognizer()` inside the IME
  service, `startListening(RecognizerIntent)`, results committed through the existing
  `InputConnection` with the same delivery discipline the GIF pipeline uses (pure policy class,
  unit-tested: final result commits, partial results shown in the suggestion strip only while
  listening, errors surface as a short toolbar state, never as a crash).
- New `RECORD_AUDIO` runtime permission, requested through a small translucent activity the first
  time the mic is pressed, with a rationale line. The manifest gains the permission and the activity;
  nothing else changes shape.
- When no recognizer is installed, the key falls back to today's IME-switch behaviour and says so in
  its description. The existing `voice_typing_chooser` command stays for people who want the old
  behaviour deliberately.

## 3. The new tools are one key away, not ten settings away

The toolbar defaults are ten commands (`ToolbarConfig.kt:50`):
`config,voice_typing,switch_clipboard,undo,redo,select_word,copy,paste,cursor_left,cursor_right`.
Incognito, floating, split, one-handed and sound exist only inside settings sections — invisible
from the keyboard, which is exactly the complaint.

**Decision:** a Gboard-style **"more" key** (four-dot grid) as the first toolbar default, opening a
quick sheet over the keyboard with the five feature toggles, theme, and settings — built on the same
dialog mechanism the existing command picker already uses, so no new window type and no new CI
blind spot. Toggling from the sheet goes through the same code paths as the settings switches
(`IncognitoPolicy`, `FloatingConfig`, `SplitConfig`, …), so there is one source of truth per feature.
The sheet's rows carry their current state, so the key doubles as an indicator.

## 4. Settings: a drawer, one section at a time

`SettingsScreen.kt` is a single `verticalScroll` Column stacking all nineteen sections behind
collapsible headers. That is why it reads as an amateurish pile, why the search bar scrolls away,
and why the CI hunts need twelve-scroll budgets.

**Decision:** `ModalNavigationDrawer` (hamburger) with the sections as drawer items; the content pane
shows **one section at a time**; the search field moves into a top app bar and stays there.
Section composables, the persistence layer and the generated search index are untouched — only the
container changes. `executeSearchAction` already collapses everything and scrolls to a control;
with one section on screen it becomes simpler, and the §7d search route keeps working.

**CI consequence, handled first:** §7b and §7c climb and scan a stacked list. With a drawer they must
open the drawer and pick a section. The offline harness (`scripts/ci/offline/`) gets drawer behaviour
before the smoke script changes, so the new navigation is verified in thirty seconds locally rather
than fifteen minutes per guess on the emulator.

## Order

1. **Mic dictation** — a key that does nothing is a bug; smallest surface.
2. **Calm layout + inset fix** — the first thing the eye meets.
3. **Toolbar "more" sheet** — makes the four shipped features reachable from the keyboard.
4. **Settings drawer** — the largest change, last, with the harness updated ahead of it.

Each lands as its own commit with a green build and smoke run, exactly as phases 1–3 did.

## Part 5 — the Gboard alignment that parts 1–4 left out

Raised 2026-10-24, the same day parts 1–4 landed, from the owner's follow-up: *"as you can see in
the screenshot (Google's keyboard), I want mine to look exactly like that. Same layout, how the
toolbar is arranged etc."* Parts 1–4 built the machinery (calm labels, dictation, insets, the drawer,
the more-tools sheet) but left three gaps between that machinery and Gboard's screen.

### 1. `loc` corners were being printed at all

The grammar reserves `loc <key>` for placement — `latn_qwerty_us.xml` says so in its header:
*"Symbols prefixed with 'loc ' are not visible on the keyboard."* `KeyboardData` parses that into
`Key.F_LOC` and `LayoutModifier` nulls the slots whose key no Extra-keys checkbox enables, but the
draw loop never consulted the flag. So the slots that *did* survive — the ones the defaults turn on
(`esc`, `tab`, `home`, `end`, `menu`, `page_up`, `page_down`, `compose`) — printed as sub-labels: `esc`
under Q, `home`/`end`/`tab`/`capslock` on shift, `menu`/`page_up`/`page_down` on backspace. Calm mode
part 2 hid most of them by accident, by picking one higher-priority corner. `Keyboard2View` now skips
`F_LOC` corners in both the calm-mask computation and the draw loop, for `print_all_key_symbols` too.
They stay reachable by corner swipe — the same contract as every other unprinted corner.

### 2. The layout had no number row, and the letters still carried hints

The owner's Gboard runs with its number row on; the shipped `latn_qwerty_us_gboard_style.xml`
followed Gboard's *default* (row off, digit hints on the top letters) instead. It now embeds a
ten-key digit row at `height="0.75"` with no symbol hints, sets `embedded_number_row="true"` so the
`number_row` preference cannot add a second one, and the letters carry no hints at all.

Making it *reach* the phone needed a second change: `LayoutsPreference.DEFAULT` is a `SystemLayout`,
and a `SystemLayout` resolves to whatever the current subtype's `default_layout` names. So the 13
Latin-script subtypes in `res/xml/method.xml` now name the Gboard-style layout. Non-Latin and
azerty/qwertz subtypes keep their own arrangement.

### 3. The toolbar was HeliBoard's ten, not Gboard's seven

`more_tools,voice_typing,switch_clipboard,undo,redo,select_word,copy,paste,cursor_left,cursor_right`
— ten buttons, with the mic second. Gboard puts seven: grid, undo, select, clipboard, gear, GIF, mic.
`redo`/`copy`/`paste` and both cursor keys are already on the keys that own them (long-press and
corner swipe), so the default is now
`more_tools,undo,select_word,switch_clipboard,config,switch_gif,voice_typing`.

`switch_gif` was missing from `CommandRegistry` — it existed as a `KeyValue` event and as a layout
key, but not as a command, so the GIF pane could not be put on the toolbar and the picker never
offered it. It is a command now, with `cmd_switch_gif`/`cmd_switch_gif_desc`.

`scripts/ci/emulator-smoke.sh` computed toolbar tap points by dividing the row into **ten** slots and
tapped index 1 for the mic; both are now derived from an explicit `TB_N=7` and the mic is index 6.
Left alone, 7g's more-key tap would have landed on the undo button and reported "dispatched but no
sheet" — a harness bug blamed on the product.

## Part 7 — bug audit after the Gboard pass (2026-10-25)

The seven-toolbar pass compiled and the smoke job was green, but its annotations said
`MTSHEET ... log='none'` and `VOICE ... log='none'`: coordinate taps on the first and last
toolbar buttons dispatched nothing. The harness stood those two checks down rather than failing,
so a dead toolbar could still ship. The root cause and the other defects found in the same audit
are recorded in [`BUG-AUDIT-2026-10-25.md`](BUG-AUDIT-2026-10-25.md).

The toolbar fixes are:

- The icon `ImageView` is no longer clickable or focusable; its `FrameLayout` wrapper owns the
  click, ripple, long-press label and 40 dp hit target. A clickable child consumed `ACTION_UP`
  before the parent listener could fire.
- The icon box is now a real 24 dp with no 8 dp internal padding on each side. The old geometry
  left 6 dp for the glyph.
- `redo` gets a mirrored `ic_tool_redo`; it previously reused the undo arrow.
- Compact mode keeps the first and last rendered buttons by position, with a flexible spacer
  between them, instead of hardcoding `more_tools` and `voice_typing`. A custom toolbar that
  removed either command no longer loses every button while suggestions are showing.
- A wholly unrecognisable `toolbar_keys` preference falls back to the seven defaults instead of
  hiding the toolbar.

The audit also repairs dictation/audio-focus lifecycle leaks, backup defaults for thirteen phase
1-4 preferences, clipboard password-manager detection across app switches, more-tools double-tap
state, key-sound pool ownership, a duplicate `loc esc` placement, and the layout checker that was
supposed to catch that duplicate.

The audit's fixes were verified by a green `Build APK` and, after a follow-up harness repair for a
killed-emulator-launcher state, a green `Emulator smoke test` (`fails=0 anr=0`, both activity
focus checks passing). The offline smoke harness was also corrected to model the real seven-button
toolbar (mic last at index 6) rather than the old ten, so its coordinate-fallback scenarios test the
geometry the script actually taps. See [`BUG-AUDIT-2026-10-25.md`](BUG-AUDIT-2026-10-25.md).

## Part 8 — the Gboard screenshot is the default the phone actually opens (2026-10-26)

Part 5 added `latn_qwerty_us_gboard_style.xml` and pointed most English subtypes at it, but a phone
could still open the dense PC layout from the October 23 screenshot. Three holes:

- `en-GB` still named `latn_qwerty_gb` (symbols on every letter, no digit row). There was no
  `en-GH` subtype at all, so a Ghana English locale had nothing exact to match.
- When no subtype resolved — a fresh install, an emulator, or an unrecognised locale —
  `KeyboardComponentGraph` loaded `R.raw.latn_qwerty_us`, the dense layout.
- Settings, the theme editor and the layout manager previewed that same dense file, so the
  preview and the live keyboard could disagree.

Those now all resolve to `latn_qwerty_us_gboard_style`. `en-GB` keeps its `£` extra key; `en-GH`
is a new subtype with the cedi (`₵`) as an extra key. An upgraded install whose `layouts`
preference is still exactly the old single default (`["latn_qwerty_us"]` or
`["latn_qwerty_gb"]`) is re-pointed once by `GboardDefaultLayoutMigration`. A custom layout, a
second layout, or any other named choice is left alone.

`tools/check_layout.py` fails `preBuild` if the digit row, the hint-free letters, the English
subtype defaults, the no-subtype fallback, or the previews drift. A rendered check of the layout
file is `docs/gboard-style-preview.png`, generated on demand by
`tools/render_layout_preview.py` and deliberately not checked in (a stale render in the tree
is worse than no render).

What this does **not** copy from the Gboard photograph, on purpose:

- Google's icons, typeface, or translucent key art. The toolbar uses this keyboard's own seven
  vectors. The default theme is Harbor (part 9), not a copy of Gboard's wallpaper.
- The word "English" on the space bar. The space key is the special-font space glyph
  (`KeyValue` `0xE00D`). Putting a text label there would type that label.
- The blue search magnifier. That is the field's action key (Search), which the existing
  Enter/action swap already draws when the editor asks for it.

## Part 9 — the purple default is retired (2026-10-26)

The owner rejected the deep-purple default. Five built-in themes were added, and **Harbor**
(night navy) is now `Defaults.THEME`. An install still stored as `cleverkeysdark` is moved to
Harbor once (`ThemeDefaultMigration`); any other saved theme is left alone. Purple remains in
the picker as "Mr. Brown's Keyboard Dark".

| Theme | Look |
|---|---|
| Harbor | Night navy keys, white letters. The default. |
| Daybreak | Cool daylight blue, white keys, ink labels. |
| Signal | Black field, electric-blue letters, a light key when pressed. |
| Ember | Charcoal keys with a lacquer-red edge. Not a flat red slab. |
| Kiln | Warm clay paper. Red only on the pressed key. |

The comparison sheet is `docs/theme-wardrobe.png`, generated on demand by
`tools/render_theme_wardrobe.py` from the same hex values as `res/values/themes.xml`, and no
longer checked in.
