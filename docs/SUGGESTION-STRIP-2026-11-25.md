# Suggestion strip pass — 2026-11-25

The owner picked **Suggestion strip** as the next surface after the clipboard was merged
(`2b93b0e`): word suggestions, count, pill/highlight, message row and spacing.

## What the current strip did wrong

The strip keeps a fixed-height row and three words (the 2026-11-13 decisions stay), but each
word was a `WRAP_CONTENT` chip with a 4 dp right margin. Three short words therefore clustered
at the left edge and left most of the band empty — nothing like the evenly spread row the owner
sees in Gboard. The empty-row placeholder (the dimmed current word, or "Swipe to type") used
`CENTER_VERTICAL`, so it sat at the left with a 16 dp inset rather than in the middle.

## What changed

- Up to three suggestions get `layout_width=0`, `weight=1` cells: equal thirds, each word
  centred in its own third. Four or more (debug scores, long custom prompts) keep the old
  `WRAP_CONTENT` scrolling layout, so nothing becomes unreachable.
- The empty-row placeholder is centred horizontally. It stays dimmed and keeps its caret-restore
  tap; only its position changed.
- No change to the word count, the highlight pill, provenance markers, the fixed row height,
  password mode or the compact toolbar. Those are separate, already-decided behaviours.

## The research

Gboard's stable channel has rolled out Material 3 Expressive to shortcuts and Settings, with
larger labels and containerless shortcut chips; pill-shaped *keys* are still in beta
([9to5Google, 21 Jul 2026](https://9to5google.com/2026/07/21/gboard-shortcuts-m3-expressive-redesign/)).
The word-count and no-resize decisions were researched in
[`KEYBOARD-UI-2026-11-13.md`](KEYBOARD-UI-2026-11-13.md) and are not re-litigated here. Published
screenshots of Gboard's suggestion chips show wide, centred pills, which is why the commit pill
stays as it is and the cells simply get equal width.

## Verification

Offline typecheck with tests, Build APK, Emulator smoke and the full suite are recorded in the
message that ships this. The strip's spacing is a visual judgement: CI measures that the row
does not resize, not that the thirds look right on the owner's phone.
