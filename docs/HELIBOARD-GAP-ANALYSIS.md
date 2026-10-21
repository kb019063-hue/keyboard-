# HeliBoard → CleverKeys gap analysis and build design

**Base:** this fork (CleverKeys `b2e2577`), unchanged as the host app.
**Compared against:** HeliBoard `e97acdbe7ad16ef06d89d71d35ed1096e6d69f4c` (2026-10-18).

Goal: take what HeliBoard has that CleverKeys lacks, and build it into the existing base. No base
swap, no wholesale merge.

## Method

README feature lists lie, and naive greps lie worse. An early pass here reported "38 files match
`split`" for CleverKeys — that was `String.split()`, and "140 files match `float`" was the Kotlin
`Float` type. Everything below comes from anchored patterns (`SplitKeyboard|split_keyboard|isSplit`,
`OneHand|one_hand|onehand`, …) run against both source trees, plus reading the actual
implementations. Where a count is given, it is files matching the anchored pattern.

## The gap

| Capability | HeliBoard | CleverKeys | Verdict |
|---|---|---|---|
| Toolbar (suggestion-strip) keys | 16 files, `ToolbarKey` enum of **30** entries, `ToolbarMode` (EXPANDABLE / TOOLBAR_KEYS / SUGGESTION_STRIP / HIDDEN) | **0** | **Missing — and it gates the three below** |
| One-handed mode | 14 files; `KeyboardWrapperView.kt` (166 lines) | **0** | Missing |
| Split keyboard | 11 files; in `KeyboardBuilder`, `KeyboardSwitcher`, `KeyboardWrapperView` | **0** | Missing (hardest of the three) |
| Floating keyboard | 9 files; `FloatingKeyboardUtils.kt` (171 lines) | **0** | Missing |
| System spell-checker provider | `AndroidSpellCheckerService.java` (230) + `SentenceLevelAdapter.java` (174) + factory + settings activity; registered in the manifest under `android.service.textservice.SpellCheckerService` | **0** (no matches for `spellcheck`) | Missing — other apps can use HeliBoard as their spell checker |
| Key-press **sound** | `"sound_on"` pref (`Settings.java:75`) | **0** sound keys; 8 haptic keys only | Missing |
| Theme **background image** | present | **0** matches for `backgroundImage` | Missing |
| Incognito one-tap toggle | `ToolbarKey.INCOGNITO` + force-incognito (disables learning, autocorrect, auto-cap, clipboard history) | **0**; `PrivacyManager.kt` is about *consent and data collection*, not a typing incognito | Missing |
| `PAGE_UP` / `PAGE_DOWN` | toolbar keys | **0** | Missing |
| Translations | **117** locales | 23 | Missing, but low value per unit effort (see below) |

## What CleverKeys already has — do not rebuild

This matters more than the gap table, because it is where the naive "port the feature" instinct
wastes effort.

- **Clipboard:** 21 preference keys, unlimited count (`CLIPBOARD_HISTORY_LIMIT = 0`) and
  never-expiring (`CLIPBOARD_HISTORY_DURATION = -1`), tags, todos, regex search, inline editing,
  media capture, URL sanitisation, private-copy. HeliBoard has 25 clipboard *files* to CleverKeys'
  110 and a simpler SQLite DAO. **No work needed; this is why CleverKeys is the base.**
- **Themes:** 43 predefined themes plus a DIY theme creator (`theme/` has 12 files including
  `CustomThemeManager`, `PredefinedThemes`, `ThemeEditorPreview`, `MaterialThemeManager`). Dynamic
  colour (3 files) and day/night (1 file) are **already present** — contrary to what HeliBoard's
  marketing emphasises, these are not gaps. Only *background image* is.
- **Gestures:** open ONNX swipe engine, 227 short-swipe commands, 204+ per-key commands.
- **Numpad / D-pad / number row:** partial support already exists (`Config.kt`, `KeyboardData.kt`,
  `LayoutModifier.kt`), unlike the four hard zeros above.
- **Layouts:** 89 XML layouts vs HeliBoard's 82 JSON layouts in `assets/layouts/main/` plus 12 other
  categories. Comparable breadth, different format.

## The finding that shapes the design

`customization/ActionType.kt` (175 lines) already defines **33 actions**:

```
COMMAND COPY CURSOR_DOC_END CURSOR_DOC_START CURSOR_DOWN CURSOR_END CURSOR_HOME CURSOR_LEFT
CURSOR_RIGHT CURSOR_UP CUT DELETE_WORD INTENT KEY_EVENT PASTE PRIMARY_LANG_TOGGLE PRIVATE_COPY
REDO REPLACE_TEXT SECONDARY_LANG_TOGGLE SELECT_ALL SHOW_TEXT_MENU SWITCH_BACKWARD SWITCH_FORWARD
SWITCH_IME TEMPLATE TEXT TEXT_ASSIST TIMESTAMP UNDO VOICE_INPUT WORD_LEFT WORD_RIGHT
```

Mapping HeliBoard's 30 `ToolbarKey` entries onto them:

| HeliBoard key | CleverKeys equivalent | |
|---|---|---|
| VOICE | `VOICE_INPUT` | ✅ |
| UNDO / REDO | `UNDO` / `REDO` | ✅ |
| COPY / CUT / PASTE | `COPY` / `CUT` / `PASTE` | ✅ |
| SELECT_ALL | `SELECT_ALL` | ✅ |
| SETTINGS | `INTENT` | ✅ |
| LEFT / RIGHT / UP / DOWN | `CURSOR_LEFT/RIGHT/UP/DOWN` | ✅ |
| WORD_LEFT / WORD_RIGHT | `WORD_LEFT` / `WORD_RIGHT` | ✅ |
| FULL_LEFT / FULL_RIGHT | `CURSOR_DOC_START` / `CURSOR_DOC_END` | ✅ equivalent |
| PAGE_START / PAGE_END | `CURSOR_HOME` / `CURSOR_END` | ✅ equivalent |
| EMOJI / CLIPBOARD / NUMPAD / DPAD | existing panes/layouts in `PredictionViewSetup.kt`, `Config.kt` | ✅ partial |
| SELECT_WORD | `SHOW_TEXT_MENU` covers it indirectly | ⚠️ |
| AUTOCORRECT / CLEAR_CLIPBOARD | exist as *prefs*, not as dispatchable actions | ⚠️ needs a thin action |
| **ONE_HANDED / FLOATING / SPLIT** | — | ❌ missing |
| **INCOGNITO** | — | ❌ missing |
| **PAGE_UP / PAGE_DOWN** | — | ❌ missing |
| CLOSE_HISTORY / BACKGROUND_GATHERING | HeliBoard-specific, no meaning here | n/a |

**≈22 of 30 keys are already implemented.** So the toolbar is a *UI surface over an existing
dispatch layer*, not a set of new capabilities. That is what makes it the right first move.

Blast radius is small and known: 10 files reference `ActionType`, and only two dispatch on it
exhaustively — `CustomShortSwipeExecutor.kt:44` and `XmlAttributeMapper.kt:23`. Adding action
entries means updating those two `when` blocks; nothing else is forced.

## Feasibility, honestly

**HeliBoard's view-layer code cannot be dropped in.** `KeyboardWrapperView.kt` is only 166 lines,
but 9 of its 23 imports are HeliBoard-internal: `KeyboardActionListener`, `KeyboardSwitcher`,
`KeyboardIconsSet`, `KeyCode`, `ColorType`, `Constants`, `Settings`, `FoldableUtils`, `prefs`.
`FloatingKeyboardUtils.kt` similarly pulls `KeyboardSwitcher`, `Settings`, `setFloatingSize`,
`DebugFlags`, `R`.

So these are **reference implementations, not transplants**. What ports is the *technique* — and it
is a clean one: wrap the keyboard view in a `FrameLayout`, apply `scaleX`/`scaleY`, flip
`oneHandedGravity` between `Gravity.LEFT` and `Gravity.RIGHT`, mirror the toggle button with
`scaleX = -1f`, and add stop/switch/resize handles. CleverKeys has the hooks: `Keyboard2View.kt`
(2,416 lines), `InputCoordinator.kt` (which already exposes `keyboardHeightPx()`),
`KeyboardDimensionsHelper.kt`, and `KeyboardHeightPrefs.kt`.

That is re-implementation with a working reference beside us — materially safer than designing from
scratch, but it is *not* "use exactly HeliBoard's code", and I would be misrepresenting it to claim
otherwise. The two places HeliBoard code genuinely *can* be used close to verbatim are the spell
checker service (AOSP-derived, self-contained behind a standard Android SPI) and the sound pref.

**Split keyboard is the hard one.** HeliBoard does it in `KeyboardBuilder` — i.e. inside the layout
engine, splitting the key grid. CleverKeys parses XML layouts into `KeyboardData`. Reproducing
split means changing how key geometry is computed, which touches hit-testing, gesture/swipe
coordinates and the magnifier. Defer it; do not promise it early.

**Translations are low value per effort.** 117 vs 23 locales looks like the biggest gap, but the
projects' string keys do not correspond, so only overlapping keys can be reused, and each needs
manual verification. Deprioritise.

**Layouts are a data port, not a code port.** HeliBoard JSON → CleverKeys XML needs a converter.
That converter is pure Python and can be written and tested *entirely inside this sandbox* — no
JDK, no CI round-trip. Cheap, and the only item here I can fully verify locally before you see it.

## Proposed phases

Each phase lands behind the existing CI: `assembleDebug` plus the emulator smoke test, which
already asserts install → IME selected → launcher foreground → **keyboard rendered and bound** →
text committed → no crashes. Every phase extends that harness with assertions for its own feature,
so nothing reaches your phone unverified.

**Phase 1 — Toolbar foundation.** *The prerequisite; do this first.*
Toolbar row + `ToolbarMode` + an ordered/pinnable key list, dispatching to existing `ActionType`s.
Add the genuinely missing actions (`PAGE_UP`, `PAGE_DOWN`, incognito toggle, autocorrect toggle,
clear clipboard) and update the two exhaustive `when` sites. Settings screen to choose and reorder
keys. Reference: `ToolbarUtils.kt` (281), `ToolbarKeysCustomizer.kt` (162), `ToolbarScreen.kt` (156).
Payoff: undo/redo/copy/paste/arrows/doc-start/doc-end one tap away instead of via long-press menus.
Testable: assert the toolbar renders, tap a key, assert the effect.

**Phase 2 — One-handed mode.** Smallest of the three view modes and the biggest ergonomic win.
Reference: `KeyboardWrapperView.kt` (166). Hooks: `Keyboard2View`, `InputCoordinator`,
`KeyboardDimensionsHelper`. Exposed as a toolbar key from Phase 1.

**Phase 3 — Floating keyboard.** Reference: `FloatingKeyboardUtils.kt` (171). Needs window sizing
and drag-to-move; moderate risk around insets.

**Phase 4 — Small self-contained wins.** Key-press sound (HeliBoard `sound_on` ports nearly
directly), theme background image, and the incognito one-tap toggle that bundles the existing
learning / autocorrect / auto-cap / clipboard-history prefs into one switch.

**Phase 5 — Optional, larger.** System spell-checker provider (~430 lines, new manifest service,
new settings activity) and the layout-data converter for HeliBoard's language breadth.

**Deferred.** Split keyboard (layout-engine surgery). Translations (poor key correspondence).

## License obligations this creates

Both upstreams are GPL-3.0, so the result stays GPL-3.0 and `LICENSE` + `NOTICE` are preserved.

- Re-implementing a *technique* from GPL code carries no additional obligation beyond what forking
  already does, but this file records HeliBoard as the reference so the provenance is auditable.
- **Porting the spell checker service is different.** It is AOSP-derived, and HeliBoard ships
  `LICENSE-Apache-2.0` alongside its GPL for exactly that reason. If Phase 5 happens, that license
  file must be added here and the ported files must keep their original headers.
- HeliBoard's icon is CC-BY-SA-4.0. Not used; no obligation unless that changes.
- This repository is **private**. Building and installing APKs on your own device is not
  distribution and creates no obligation. Handing APKs to other people does — at that point the
  repo must become public or the source must accompany the binary.

## Recommendation

Start with **Phase 1**, then **Phase 2**. Together they are the bulk of the daily-use difference
between the two keyboards, they reuse the most existing CleverKeys machinery, and they are the two
phases the emulator harness can most convincingly verify. Phase 4's incognito toggle is the
cheapest visible win and could be pulled forward if you want something small first.
