# The tools panel — why it stopped being a dialog (2026-11-09)

Owner report, in his words:

> Another critical issue: when I press more, when the tools open, the design is really bad.
> And it's not even how Gboard looks like. Check that part. It's really important.

He is right, and the reason is structural rather than cosmetic, which is why this change replaced
the surface instead of restyling it.

## What was there

Phase 4 part 3 put the five feature toggles behind an `AlertDialog`:

```kotlin
AlertDialog.Builder(this)
    .setTitle(R.string.more_tools_sheet_title)
    .setMultiChoiceItems(labels, checked) { _, which, isChecked -> … }
    .setNeutralButton(R.string.activities_theme_title) { … }
    .setPositiveButton(R.string.cmd_config) { … }
    .setNegativeButton(R.string.common_close, null)
    .create()
```

shown through `Utils.show_dialog_on_ime`. It worked — the smoke test's 7g section proved the
command dispatched and a surface came up — but it produced Android's stock list-of-checkboxes in a
floating card. That is a **form**. Gboard's panel is a **launcher**: a four-wide grid of icon
tiles with a word under each, drawn on the keyboard's own surface, where most tiles are one-shot
actions and only some draw a lit state.

Three things about the old surface could not be fixed by theming:

1. **No icons, no grid.** `setMultiChoiceItems` is a text list with checkboxes. There is no cell to
   put a glyph in, and no place for a caption under one.
2. **The wrong container.** A dialog floats over the app. Gboard's panel is part of the keyboard:
   as wide as the keys, on the same field colour, unable to cover the row it was opened from.
3. **Half the tools were not on it.** Emoji and clipboard — the two tools a user is most likely to
   reach for — existed only as dialog *buttons*, so they read as "escape hatches" rather than
   tools. A user who customised the toolbar and dropped them had no path to them from the panel at
   all, which is exactly the complaint ("I didn't see many tools") the sheet was built to answer.

## What replaced it

`more_tools` now opens the keyboard's **fourth pane**, next to emoji, clipboard and GIF:

| | |
| --- | --- |
| model | `MoreToolsPanel` — 9 keys, 5 switches + 4 launchers, 4-column grid, pure Kotlin |
| view | `MoreToolsPanelView` — header (title + X) over a scrolling 4-wide tile grid |
| host | `KeyboardReceiver.showToolsPanel`, in `contentPaneContainer` |
| event | `KeyValue.Event.SWITCH_TOOLS` / `SWITCH_BACK_TOOLS`, mapped from `more_tools` |
| state write | `CleverKeysService.setToolsPanelSwitch(key, enabled)` |

Nine tiles in three rows:

```
incognito  floating  split     one-handed
sound      emoji     clipboard theme
settings   ·         ·         ·
```

The blank cells are deliberate: `MoreToolsPanel.gridRows` pads the last row, so the tiles above
keep one width instead of the ragged line rendering narrower cells than the rows above it.

### Why a pane and not a custom dialog

The panes already solve every problem the dialog had: `contentPaneContainer` is as wide as the
keyboard, it sits under the toolbar on the keyboard's own surface, it is sized by the user's
existing `clipboard_pane_height_percent` (default 30 % of the screen), and it inherits the whole
pane lifecycle — the #148 host guard, the routing-state clear, `resetContentPaneState` on app
switch. Reusing it cost one enum value (`PaneType.TOOLS`) and one branch.

### Why the switches kept their own setters

Every switch routes to the same explicit setter its command uses (`setIncognitoEnabled`,
`setFloatingEnabled`, `setSplitEnabled`, `setOneHandedEnabled`, `setKeySoundEnabled`), and the
panel passes **the value its own lit tile is showing** rather than re-reading `Config`. This is the
same defence the dialog's `isChecked` array provided: a fast second tap cannot read a stale
`Config` and invert the feature the user just turned on.

Launchers reuse existing events (`SWITCH_EMOJI`, `SWITCH_CLIPBOARD`) and existing activities
(`ThemeSettingsActivity`, `SettingsActivity`), so the panel adds no second dispatch path for
anything.

## What did not change

- **The command name.** `more_tools` is still the registry name, the toolbar preference value and
  the key in every translated `cmd_more_tools` string. Only its destination moved — and it is now
  an `Event`, routed by kind rather than rescued by `CommandRouting.isOwnNameFallback`. That is
  strictly stronger than before: the 2026-11-04 defect ("tapping it typed `more_tools` into the
  note") cannot recur through this command at all.
- **The CI evidence contract.** `MoreToolsPanel` logs `press` before the build and
  `shown rows=<n> <states>` after it. The smoke script's 7g ladder (press+shown passes, press
  without shown fails, silence stands down with a warning) reads the same two lines under a new
  tag, so the verdicts kept their meaning. `scripts/ci/offline/fakeadb.py` was updated with it.
- **The five strings.** Each tile's caption is the command registry's own string, so the panel is
  translated everywhere the commands already are. Only two new strings were added, for the
  TalkBack state word (`on` / `off`).

## Icons

Eight new `ic_tool_*.xml` glyphs, from the same source as the seven toolbar ones:
[Material Symbols Rounded](https://github.com/google/material-design-icons), Apache-2.0, path data
only, attribution in `NOTICE`. `docs/ICON-SOURCING.md` already documents the extraction recipe and
the `viewBox="0 -960 960 960"` → `translateY="960"` conversion, which is exactly how these were
produced.

| tool | Material Symbols | | tool | Material Symbols |
| --- | --- | --- | --- | --- |
| incognito | `visibility_off` | | emoji | `mood` |
| floating | `picture_in_picture_alt` | | clipboard | `assignment` (already shipped) |
| split | `splitscreen` | | theme | `palette` |
| one-handed | `mobile_friendly` | | settings | `settings` (already shipped) |
| key sound | `volume_up` | | close | `close` |

## How it was checked without a phone

There is no JDK or Android SDK in this workspace, so the panel was verified as far as this
environment allows and the rest is left to CI:

- `MoreToolsPanelTest` pins the nine keys and their order, the 5/4 switch/launcher split,
  that launchers are never lit, and the 4-wide padded grid. **Needs a JDK to run.**
- `scripts/ci/offline/run.sh` — the whole offline smoke harness — passes, including the `mtdeaf`
  scenario that models "dispatched but nothing appeared". **Run and green.**
- Every symbol the panel touches (`Theme` fields, service setters, receiver helpers, `R.string`,
  `R.drawable`) was checked to exist by grep; there is no compiler here to do it.

Not verified here, and worth a phone before merging: tile proportions on a real screen, whether
three rows fit inside the default 30 % pane height (they need ~44 dp header + 3 × 88 dp ≈ 308 dp,
so on a 1080×2400 phone at 30 % ≈ 720 px / ~2.75× density ≈ 262 dp they will **scroll** — the
grid scrolls by design, but the owner may prefer a taller default), and how the lit tile reads
under each theme.
