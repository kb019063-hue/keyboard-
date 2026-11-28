# Key surface, shift, popups and the space bar — audit 2026-11-28

The owner tested the one-row build, said "you did half good", and when the four row defects were
fixed asked for the rest of the surface to be swept the same way: key tiles, shift and
auto-capitalisation, long-press popups, the space bar and backspace, and the number-row switch.
This is what the sweep found, in the owner's terms.

## 1. Key tiles: one pitch, and the gap and radius already measured

- **Gap and corner radius: nothing to change.** The two Gboard themes carry the numbers the owner
  measured against his own screenshots — `keyBorderRadius` 6 dp on a 1080-px-wide screen and
  `keyGap` 3 dp — and both rows (`Dark`, `GboardDark`) agree. The tile inset is drawn INSIDE the
  key cell (`Keyboard2View.drawKeyFrame`), so neighbouring tiles read as separate rounded keys with
  the background showing through, which is what the screenshots show.
- **Row pitch: fixed.** The Gboard-style layout gave its digit row `height="0.75"` and its bottom
  row `height="0.95"` — HeliBoard's compact conventions, carried over. Gboard has one key size: with
  its number row on it is taller by exactly one ordinary row, and the space-bar row is the same
  height as the letter rows. Both rows are now a whole row, and so are the shared
  `res/xml/number_row*.xml` fragments that other layouts borrow. The keyboard is a quarter of a row
  (about 21 dp on a 1080×2400 phone at the default height) taller for it.
- **Enter key: already Gboard's.** `colorKeyEnter` is the blue, the glyph is white, and the enter
  role's frame radius is capped at half the shorter side, so the one-key-wide enter draws as Gboard's
  round action key rather than as another square tile.
- **Space bar language label: fixed** — see 4.

## 2. Shift and auto-capitalisation: three defects

`Autocapitalisation` decides shift from the field's caps modes. Three things it got wrong:

1. **A new field inherited the old field's shift.** When auto-capitalisation could not run — the
   setting off, or a field that declares no caps modes, such as a URL or search box — `started()`
   returned *without publishing a decision*, so the latched shift of the previous field stayed on.
   End a sentence in a message, tap a URL field, and the keyboard came up shifted. Every field now
   publishes its decision, including "unshifted".
2. **Moving the caret never re-asked the editor.** The caps mode was re-read only when the field had
   just been cleared. Place the caret after a full stop (a tap, an arrow key, a suggestion
   replacing a word) and shift stayed off, so the next letter was lowercase. Every real caret move
   now asks the editor, through the existing 50 ms coalescing delay.
3. **All-caps fields never got CAPS LOCK.** `TYPE_TEXT_FLAG_CAP_CHARACTERS` was not read at all, so
   a field asking for capitals (codes, serials) opened on a lowercase keyboard. Such a field now
   opens locked, and stays locked while text is typed into it, whatever the auto-capitalisation
   setting says — it is the field asking, not a guess.

`Autocapitalisation.Callback.update_shift_state` grew the third argument this needs (`lock`); the
keyboard already had `set_shift_state(latched, lock)` for it.

## 3. Long-press popups: checked, no defect found

- The subkey popover's grid is **clamped inside the key view** (`SubkeyPopoverGeometry.gridCentre`),
  so a top-row key's north row and an edge key's outer column cannot land off-screen — the reason
  touch coordinates are clamped to the view in the first place.
- The grid's cells are **the held key's own row height and width**, so a shorter row gets shorter
  cells; sectors are normalised per axis, which is what keeps "one cell right and one cell up" a
  north-east pick on a tall key.
- The selected cell is nudged back inside the view when the selection scale pushes it past an edge,
  and an open-then-release never inserts anything (`isArmed` waits for the finger to move).
- Nothing changed. The existing pins (`SubkeyPopoverGeometryTest`, `SubkeyPopoverSlotsTest`) cover
  the clamping and the sector boundaries; the row-height agreement above is what would have broken
  them if the pitch change had been made in code rather than in the layout file.

## 4. Space bar and backspace: two defects

1. **The language name followed the layout, not the languages.** The space bar printed "English"
   whenever it was a space key. Gboard prints the language name only while more than one language is
   in play; with one language its space bar is blank. That distinction reconciles the two owner
   reports that looked contradictory ("a blank bar reads as a missing key", 2026-11-04; "I don't want
   the word English on the space bar"). The label now needs the frame's second language
   (`ConfigSnapshot.active_secondary_language`), which is null with Multi-Language off — the default
   — and on a single-language layout.
2. **One space in a new field could become a period.** The double-space rule pairs the space just
   typed with the previous one, and that memory (`lastTypedChar`) was never cleared when the field
   changed. Switch fields within half a second, tap into a field whose text already ends in a space,
   and type a single space: it paired with the *previous field's* space and produced ". ". The
   memory is now cleared when editing starts. The existing guard that verifies a space actually
   precedes the cursor (audit A-5) stays as the second line of defence.

Backspace key-repeat itself is sound: the repeat message is cancelled on release
(`Pointers.stopLongPress`), a deferred press emits its first delete before the repeat begins, and
the repeat interval is the user's own setting. `ConfigDefaultsTest` pins the shipped timings
(600 ms to the first repeat, then 25 ms), so retuning them is a decision for the owner rather than a
bug fix.

## 5. The number-row switch: now decides both ways

`LayoutModifier` consulted `add_number_row` only to **add** a row, so a layout that carries its digits
inside itself — the Gboard-style layout does, `embedded_number_row="true"` — kept them however the
preference was set. The switch worked on every layout that borrows `res/xml/number_row.xml` and was
silently dead on the one layout the keyboard ships as its default.

The preference is now authoritative in both directions. The row to remove is found by a strict
recogniser (`KeyboardData.embeddedNumberRowIndex`): ten keys, one unit wide and unshifted, labelled
with the ten digits in any order. A symbol row, a phone pad, or anything that is not ten whole units
is left alone, because deleting the wrong row is worse than leaving a digit row behind. Removal goes
through `KeyboardData.withoutRow`, which recomputes the layout's height, so the keyboard gets shorter
by exactly the row that went away.

## Files

| Area | File |
| --- | --- |
| Pitch | `src/main/layouts/latn_qwerty_us_gboard_style.xml`, `res/xml/number_row*.xml` |
| Number-row switch | `KeyboardData.kt` (`withoutRow`, `embeddedNumberRowIndex`), `LayoutModifier.kt` |
| Shift / caps | `Autocapitalisation.kt`, `KeyEventHandler.kt` |
| Space bar | `Keyboard2View.kt` (`drawSpaceLanguageLabel`), `KeyEventHandler.kt` (`started`) |
| Pins | `KeyRowPitchTest`, `SpaceBarLanguageLabelTest`, `EmbeddedNumberRowTest`, `AutocapitalisationTest`, `DoubleSpacePeriodTest` |
