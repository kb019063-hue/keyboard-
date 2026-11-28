# Suggestion row + toolbar: one row, no pill — 2026-11-25

The owner sent four screenshots (two of their own keyboard, two of Gboard's dark one) with
*"the suggestion strip and toolbar of mine and Gboard's … Mine doesn't look right. Can you make
it look like Gboard?"*

Both differences were confirmed with the owner before any code was written, because each one is
the opposite of a shipped decision:

| question | owner's answer |
| --- | --- |
| Gboard shows **one** row above the keys (toolbar when empty, suggestions + tools/mic when typing); this keyboard showed **two** stacked rows | **One row like Gboard** |
| Gboard's first suggestion has **no blue pill**, and thin dividers between words; this keyboard had a pill and no dividers | **No pill + dividers** |

## 1. One row, not two

`SuggestionBarPane` used to build `toolbar (40 dp)` above `topPane (40 dp)` above the keys, and
both were always visible: 80 dp of chrome, the top half of it a second row of icons above the
words. Gboard has one 40 dp band, and what sits in it depends on whether there is anything to
say.

Now the two views take turns owning that band:

- **Field empty** (no suggestions, nothing typed): `topPane` collapses to height 0 and the
  toolbar is the row — all the commands the user configured, exactly as in the owner's empty
  screenshot.
- **Typing**: the strip takes the band and the toolbar goes `GONE`
  (`KeyboardToolbar.setHiddenForStrip`). The strip draws the toolbar's **first and last
  commands at its own ends** (`KeyboardToolbar.rowEnds` → `SuggestionBar.setRowEnds`), so the
  row reads `☰ | H │ How │ Hello | 🎤`, which is Gboard's row.

Consequences that were checked:

- **The keyboard's total height does not change** between the two states (40 dp of band either
  way). The owner's 2026-11-13 complaint — *"it still moves when typing. Moving up and down when
  I start typing"* — was about the keyboard resizing mid-typing; this design keeps the resize
  out of it by making the band a constant.
- **No flapping.** The band is handed to the strip as soon as a word is being typed, not only
  when a suggestion exists (`SuggestionBar.ownsRow` = occupied *or* the placeholder is the word
  before the caret). A keystroke with no suggestion yet therefore does not flash the full toolbar
  for a frame.
- **The ends are the user's own ends.** Positions, not command names: a customised toolbar gets
  its own first and last command in the strip. A toolbar switched off lends nothing to the strip
  and the strip keeps the band, as before.
- **Routing is shared.** A tap on the grid inside the strip calls
  `KeyboardToolbar.dispatchCommand`, the same path the toolbar's own buttons use, so the two can
  never disagree about what a command does.
- **Panes.** The clipboard, emoji, GIF and tools panes still own the band while they are open
  (`applyStripHeight` never fights them); when a pane closes the receiver asks the service to put
  the row back (`CleverKeysService.restoreRowAfterPane`), so an empty field gets its toolbar back
  instead of a blank band.
- `compact` mode (`ToolbarCompactPolicy`) was the earlier approximation — two end buttons pushed
  apart with the middle hidden, but still a second row. It is no longer driven; its policy and
  tests stay for the moment.

## 2. No pill, thin dividers

- The first suggestion is no longer painted with `SuggestionPillPolicy`'s accent pill, bold text
  or inset padding. All three words are plain, same weight, same strip-text ink. The commit index
  is still tracked (space-commit, accessibility) — it is simply not painted differently.
- A 1 dp, 24 dp-tall vertical rule in the strip's secondary ink now sits between neighbouring
  words (`SuggestionBar.createDividerView`), interleaved in the child order
  (`[sug0, divider, sug1, divider, sug2]`). Pooled like the chips, so a keystroke allocates
  nothing. A single word (or a one-word centred prompt such as "Add to dictionary?") has none, and
  an empty strip has none.
- Chip margins are gone: the divider is the spacing, as on Gboard.

## 3. The mic is a filled circle

Gboard's mic sits in a filled circle rather than as a bare glyph. Both the toolbar's mic and the
strip's mic end are drawn as an oval filled with the strip's secondary ink, with the bar's own
background as the glyph colour (`micFillColor`/`micGlyphColor`), so the same code reads correctly
on a light and a dark theme. The glyph is 20 dp inside the 40 dp hit target.

## What was not changed

- The three-word count, equal thirds, the fixed strip height, the placeholder policy, provenance
  markers and password mode (2026-11-13 / 2026-11-25 first pass).
- The clipboard surface, its tabs and its pane height.
- Any theme's colour values: the row is drawn from `suggestionTextColor`,
  `suggestionSecondaryTextColor` and `suggestionBackgroundColor`, as before.

## Tests

`SuggestionBarPaletteTest` gained four drift checks next to the existing strip-width one:
`theStripPaintsNoPillAndSeparatesWordsWithThinRules`,
`theStripCarriesTheToolbarsEndsInTheSameRow`, `theBandAboveTheKeysIsOneRowNotTwo` and
`theToolbarsMicSitsInAFilledCircle`. They read the sources the way the existing strip test does,
because a `View` cannot be instantiated in a JVM unit test.
