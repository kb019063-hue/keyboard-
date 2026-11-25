# Clipboard look pass — owner's verdict, 2026-11-25

The owner's words:

> I don't like the clipboard UI/UX and the UI/UX of the others too

Clarified the same day: **clipboard only** for this round; the problems are **getting around
(the back/height/scrolling area), order and organisation, and visual style** — *not* actions,
which already take the number of taps he wants. On direction: **"It doesn't even look like
Gboard. Not even close."**

So this round changes the clipboard surface, not the keyboard panels, emoji, GIF, tools,
suggestion strip or Settings.

## What Gboard actually draws

Reference: published screenshots of Gboard's clipboard (light and dark). The relevant geometry,
not Google's artwork:

- header: a back arrow, a visible **"Clipboard"** title, and an edit pencil on the right;
- a short section label — **Recent** / **Pinned** — above the clips;
- **rounded white cards** on the keyboard-coloured field, with real gutters, several lines of
  text per card and link/image decorations where relevant;
- no permanent search field, no result counter and no tab icon strip in the resting state.

Our retention, search, regex, filters, tags, to-dos and bulk actions stay. Those are the fork's
features; the owner asked for a Gboard *look*, not for Gboard's five-item expiry policy.

## What changed

| File | Change |
|---|---|
| `res/layout/clipboard_pane.xml` | Gboard header (back, title, search, filter, pencil), labelled Recent/Pinned/To-dos chips, collapsible search field, a section label over the list, no permanent result counter. |
| `res/layout/clipboard_history_entry.xml` | Each clip is a rounded card with a gutter, a taller touch target and up to three lines of text instead of one clipped line. |
| `res/drawable/clipboard_card_bg.xml`, `clipboard_tab_chip_bg.xml`, `ic_clipboard_search.xml`, `ic_clipboard_filter.xml` | Card/chip geometry and our own Material-style icons. No Google asset or typeface. |
| `ClipboardManager.kt` | Search is a header action that reveals a full-width field and hides the chips while open; tab chips and section label follow `switchToTab`; the result counter is only shown when it carries information (search, filter, selection, undo). Runtime-theme colours reach every new view. |
| `ClipboardPaneLayout.kt` (same file) | Phone stacks the header, chips and status row; a wide/landscape pane puts them side by side so a full card still fits. A selection drops the chips and keeps the five 48 dp actions inside the pane. |
| `ClipboardHistoryView.kt` | Runtime-theme card tint; selection highlight is a foreground overlay so it no longer replaces the rounded card with a rectangle. |

Every id the manager, the layout tests and the instrumented tests look up is still present. The
three tab `ImageView`s keep their icons, click listeners and 1.0/0.5 alpha contract — they now sit
inside labelled chips.

## What this deliberately does not do

- Pinned clips remain on their own **Pinned** tab. Gboard shows Pinned and Recent as two sections
  of one list; merging the two tables here would change which table an edit/delete acts on, so it
  is a separate, explicit request, not a side effect of a visual pass.
- The order of the Recent list is still newest first. The owner called the old arrangement
  bothersome, but the previous pass established that the cause was a live keyboard covering the
  list. A different sort order is still not requested.
- No Gboard icon, font, artwork or asset was copied. The shapes are geometry and theme colours.

## Verification

`scripts/ci/offline/typecheck.sh 3ed6bd0b…` was clean before the push. That check cannot see `R`,
AndroidX or a layout inflater, so it is not a build verdict. The build, emulator smoke and unit
tests on the head commit are recorded in the message that ships this. **The phone still has to
answer the only question that matters here: does the pane now read as Gboard's clipboard?**
