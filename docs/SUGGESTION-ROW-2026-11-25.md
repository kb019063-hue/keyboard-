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
  (`KeyboardToolbar.setHiddenForStrip`). The strip draws the row's two ends itself
  (`KeyboardToolbar.rowEnds` → `SuggestionBar.setRowEnds`): the tools affordance on the left and
  the microphone on the right, so the row reads `☰ | H │ How │ Hello | 🎤`, which is Gboard's
  row.

Consequences that were checked:

- **The keyboard's total height does not change** between the two states (40 dp of band either
  way). The owner's 2026-11-13 complaint — *"it still moves when typing. Moving up and down when
  I start typing"* — was about the keyboard resizing mid-typing; this design keeps the resize
  out of it by making the band a constant.
- **No flapping.** The band is handed to the strip as soon as a word is being typed, not only
  when a suggestion exists (`SuggestionBar.ownsRow` = occupied *or* the placeholder is the word
  before the caret). A keystroke with no suggestion yet therefore does not flash the full toolbar
  for a frame.
- **The ends are Gboard's ends, not the toolbar's.** Tools on the left, microphone on the right,
  by name. (First shipped as "the user's own first and last command" — see the correction below:
  that put a ✕ where Gboard has a mic on the owner's phone.)
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

## 3. The mic's circle was already there

`res/drawable/ic_tool_mic.xml` is Gboard's button as shipped: the Material Symbols mic knocked out
of a filled disc, one path with `fillType="evenOdd"`. Nothing needed to be drawn around it — see
the correction below for what happened when something was.

## Corrections after the owner tested the first version (2026-11-28)

The owner installed it and said *"you did half good"*. Four things were wrong, three of them mine
in the first version:

1. **A second circle around the microphone.** `ic_tool_mic` is already a disc with the mic knocked
   out of it, so wrapping it in an oval background (with the glyph tinted the bar's colour) drew a
   disc inside a disc: a donut, in both rows. Removed. Every glyph — the mic included — is tinted
   with the row's ink, exactly as the toolbar did before this pass.
2. **The row's ends followed the toolbar's order, so the mic was missing.** They were "first and
   last of the configured list". The owner's saved toolbar ends with `close`, so the words were
   bracketed by a grid and a ✕ and there was no microphone in the row at all — the most
   recognisable thing about Gboard's. The ends are now the two commands themselves
   (`KeyboardToolbar.MORE_TOOLS` on the left, `VOICE_TYPING` on the right). The empty-field toolbar
   stays fully configurable; the suggestion row's two affordances do not.
3. **A word being typed hid the whole row's commands.** The echoed word took the entire band and
   the toolbar is GONE while the strip owns it, so for as long as a word had no suggestion the row
   was one dimmed word with no tools and no microphone. It now takes the middle cell (weight 1)
   between the two ends — which is also what Gboard shows for a word it has nothing to suggest.
4. **With the always-visible preference off, the band went blank.** Visibility was "alwaysVisible,
   else GONE when there are no suggestions", which is not the same question as "does this band
   belong to the strip". A strip that owns the band is now drawn (`applyRowVisibility`), whatever
   the preference says; the preference still keeps it drawn when the toolbar would otherwise take
   the band.

Two smaller ones fixed at the same time: an end command with no toolbar drawable now falls back to
its text symbol instead of vanishing, and clearing the placeholder's row takes its two end buttons
with it, so an emptied row can never leave two orphan icons behind (while the word row keeps its
ends — the placeholder's teardown is split from the row's).

## What was not changed

- The three-word count, equal thirds, the fixed strip height, the placeholder policy, provenance
  markers and password mode (2026-11-13 / 2026-11-25 first pass).
- The clipboard surface, its tabs and its pane height.
- Any theme's colour values: the row is drawn from `suggestionTextColor`,
  `suggestionSecondaryTextColor` and `suggestionBackgroundColor`, as before.

## Tests

`SuggestionBarPaletteTest` carries the pins, next to the existing strip-width one:
`theStripPaintsNoPillAndSeparatesWordsWithThinRules`,
`theStripCarriesTheToolbarsEndsInTheSameRow`, `theBandAboveTheKeysIsOneRowNotTwo`,
`theMicIsGboardsDiscFromItsOwnDrawableNotASecondCircle`,
`theRowsEndsAreGboardsEndsNotTheToolbarsFirstAndLast`,
`theStripIsDrawnWhileItOwnsTheRow` and
`aWordBeingTypedSitsBetweenTheEndsInsteadOfSwallowingTheRow`. They read the sources the way the
existing strip test does, because a `View` cannot be instantiated in a JVM unit test.
