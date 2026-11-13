# Keyboard UI, from the owner's report of 2026-11-13

Owner's words, kept because they are the specification:

> 1. The clipboard: it's arrangements, order, I can't even go back to normal keyboard typing because
>    there's no back to press. Also, even though I'm in the clipboard, scrolling through for what I
>    want, the keyboard is still active there (like uhm, the letter keyboard is covering of the
>    clipboard. You know what I mean right?), also, there's no easy way or even a way to delete or
>    discard the saved clipboards I don't need.
> 2. It still moves when typing. Moving up and down when I start typing. Even though it has reduced
>    compared to earlier.
> 3. Also, you don't need to put the suggestion in another place like it happens in the tools space
>    (just like Google, that's why when the suggestion are active the only tools visible are the more
>    tools sheet on the left and mic on the right. And yh, the suggestion should be only 3 or 4. Make
>    some research or web search here)

Their answers to the four questions put to them the same day:

| Question | Answer |
|---|---|
| How should the clipboard get room and a way back? | **Take the keys' place, like Gboard** |
| How should deleting a clip work? | **Press and hold (second option), or swipe a clip sideways** |
| What should one tap on a clip do? | **Paste it at once, like Gboard** |
| How should the keyboard stop moving while typing? | **Always keep the suggestion row's space** |

## 1. The clipboard

Three separate causes:

- **No way back that anyone would find.** The close control was a 32 dp down-arrow wedged between
  the date-filter icon and the search field (`clipboard_close_button`, `arrow_down_float`). Nothing
  said "back to typing".
- **The keys were still live under a 30 %-tall list.** `clipboard_pane_height_percent` defaults to
  30 % of the *screen*, so the pane covered about six rows' worth of a list that routinely holds
  hundreds, while the letter keyboard kept its own space below. A drag inside the list scrolled the
  list; there was no way for it to be otherwise, and no room to see what you had.
- **Deleting had no direct route.** The per-row delete button existed only inside edit mode, and
  clearing several clips meant entering selection mode first.

What changed (`ClipboardPanePolicy`, `KeyboardReceiver`, `ClipboardManager`,
`ClipboardHistoryView`, `res/layout/clipboard_pane.xml`):

1. **The pane takes the keyboard's whole height**, measured (toolbar + band + keys) rather than
   taken as a percentage of the screen, so one-handed, split and floating layouts are all covered,
   with a floor of a third of the screen for a very short keyboard. The **keys are hidden** (never
   removed — the typing state lives in that view) so they cannot cover the list, and the toolbar
   row above stays usable so settings, emoji and the microphone are one tap away.
2. **A back arrow, first in the header, top left.** That is where Gboard's own clipboard, emoji and
   GIF panes put theirs.
3. **Holding a clip** opens Paste / Show all / Edit / Pin or Unpin / Copy / **Delete**. **Swiping a
   clip sideways** deletes it with the existing five-second Undo, as before.
4. **One tap pastes the clip and closes the pane.** This is Gboard's contract, quoted from Google's
   own clipboard text: *"Tap on a clip to paste it in the text box. Use the edit icon to pin, add or
   delete clips."* Browse (expand a long clip) moved to the hold sheet, which is the one thing that
   is not Gboard's arrangement and is recorded as our decision.

The order of the list is unchanged: newest first, `ORDER BY timestamp DESC`. The owner said the
arrangement bothered them; what actually made the pane unusable was six visible rows over a live
keyboard, so that is what changed. If the order itself is wanted differently (oldest first, grouped
by app, pinned always on top), that is a separate, explicit request.

## 2. It still moves while typing

Cause: the suggestion row was **sized to zero when it had nothing to show and opened the moment
words arrived**. Each change of that height changes the height of the whole keyboard, and the
platform then makes the app behind the keyboard lay itself out again — its message list, if it is a
chat — on the same UI thread that is trying to show the letter just typed. The 2026-11-12 change
(latch, `StripBandLatch`) reduced it to one move per field; the owner still felt it, which is what
*"it has reduced compared to earlier"* says.

Fix, by the owner's choice: **the row keeps its full height, always.** It never resizes, so nothing
above or below it ever moves because of the keyboard. A row that keeps its height and shows nothing
would be the blank band they complained about on 2026-10-23, so it fills itself, in this order
(`SuggestionStripBandPolicy`):

1. the suggestions, when there are any;
2. a temporary message ("Added to dictionary", dictation status), as before;
3. **the word being typed, dimmed**, and tapping it puts the caret back at the end of that word —
   the same idiom Gboard uses for an empty strip. It is dimmed (the sub-label colour) so it cannot
   be mistaken for a suggestion;
4. **`Swipe to type`** when there is nothing before the caret.

Nothing is echoed in a password field, and a keyboard built with prediction and swipe typing both
off has no row at all. The `always visible` preference remains and now describes every keyboard.

The word is read from `onUpdateSelection`, the report that already knows where the caret is: one
bounded `getTextBeforeCursor(32, 0)` per caret change, never on the letter path. It is re-bound only
when the word actually changes, and it never resizes anything.

## 3. The suggestions belong in the row, not in the tools panel

- **Three suggestions** (`WordPredictor.MAX_PREDICTIONS_TYPING` 5 → 3). Gboard's strip shows three
  words (*"a little row of word suggestions above the keyboard"*, *"the suggestion strip offers three
  likely words as I type"*); the owner asked for "only 3 or 4". The typo-tolerant search keeps its
  old "the row is full" threshold (`FULL_ROW_FOR_FUZZY = 5`) on purpose: narrowing the row must not
  make the fuzzy pass run on every keystroke, because this change is not allowed to cost speed.
- **The toolbar collapses to its two ends while suggestions are showing** — the four-dot tools
  button on the left, the microphone on the right, as the owner described Gboard behaving. This is
  `ToolbarCompactPolicy`, which existed and was **switched off on 2026-11-04** because it read as
  "part of the keyboard disappears when I type". It reads differently now: the row beneath it never
  collapses (point 2), so nothing below the toolbar vanishes any more. Positions, not command names,
  decide which buttons survive, so a customised toolbar keeps its own first and last.
- No suggestion is moved into another surface (the tools panel, a popup, a second row). They stay
  in the one row above the keys.

## The research

- Gboard's overflow button (four rounded squares) sits at the left end of the row and the
  microphone at the right; the toolbar can be rearranged and extended by dragging icons in that
  menu — [Android Police](https://www.androidpolice.com/gboard-quick-access-toolbar-revamp/).
- Gboard's strip carries three suggested words as you type — [Yahoo Tech](https://tech.yahoo.com/apps/articles/finally-stopped-making-typos-underrated-143410700.html); the emoji-suggestion write-up also describes the strip as "three words related to the term you're typing" — [en.cybercomputersol.com](https://en.cybercomputersol.com/10259652-how-to-disable-emoji-emoticon-suggestion-on-gboard-keyboard).
- Gboard's clipboard: *"Tap on a clip to paste it in the text box. Use the edit icon to pin, add or
  delete clips."* — and a pencil-shaped edit icon selects clips for bulk deletion — [Business
  Insider Africa](https://africa.businessinsider.com/tech-insider/how-to-use-gboards-clipboard-on-an-android-device-so-you-easily-paste-text-and-images/gcqfk0z), [TechGig](https://content.techgig.com/technology/how-to-operate-gboards-clipboard-on-smartphones/articleshow/90142321.cms), [Relic](https://relic.space/blog/where-is-the-clipboard-on-android).

Third-party descriptions, not a specification of the Gboard the owner has in front of them. Where
they disagree with his screenshot or his eye, his eye wins.

## What is unverified

Everything here needs a phone to be called done — this repository cannot build in the sandbox
(`AGENTS.md`). The checks to run are listed at the end of the message that shipped this, and the
report to paste back is the "Keyboard speed" card, whose second line also now shows a keyboard
height that should not change while typing.
