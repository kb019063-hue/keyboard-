# Phase 4 — the keyboard you actually look at

Raised 2026-10-23 from two on-device screenshots
(`Screenshot_20261022_061716_com.huawei.android.launcher.jpg`, Gboard, and
`Screenshot_20261023_024917_tribixbite.cleverkeys.debug.jpg`, this keyboard, same phone).
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
