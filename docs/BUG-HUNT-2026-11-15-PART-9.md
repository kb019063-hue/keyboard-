# Bug hunt, part 9 — the GIF picker: its tool and its look

Date: 2026-11-15 (work committed 2026-11-21)
Branch: `arena/cbf26556-keyboard`
Base: `408a560` (part 8, `a11y/`)
Scope: `src/main/kotlin/tribixbite/cleverkeys/gif/`, the GIF pane's layout, and — for the same
reason — the emoji pane beside it.

This pass was asked to work on "it's tool, and it's look. The gif/clipboard" — so unlike parts 4–8
it is not report-only. Every finding below marked **fixed** has code behind it. Nothing in this
document is a suggestion that was silently dropped: the ones not fixed are stated as not fixed,
with the reason.

---

## Summary

| # | Finding | Severity | State |
|---|---------|----------|-------|
| G1 | Grid cell height hard-coded at 80 dp while only the column count is configurable — cell aspect swings 2.4× across the supported range | **high** (every user, default config included) | **fixed** |
| G2 | `GifGridView.dpToPx` used `Resources.getSystem().density`, not the pane's density; the category bar directly above it used the context's | medium (display-override / split-screen only) | **fixed** |
| G3 | Search debounce of 150 ms was shorter than the gap between keystrokes, so it coalesced nothing: every keystroke ran its own FTS5 pass on the IME's one connection | **high** (typing into a keyboard) | **fixed** |
| G4 | No selected state anywhere in the category bar — 18 emotion buttons, no indication of which one you are looking at | **high** (the "look") | **fixed** |
| G5 | GIF pane had no back control, only a small down-arrow at the far end of the header; the clipboard pane had already been fixed to a 40 dp back arrow at top-left | **high** (way out of a full-height pane) | **fixed** |
| G6 | Emoji pane carried the same down-arrow, so the three full-height panes disagreed in icon, side, size, padding and wording | medium (consistency) | **fixed** |
| G7 | Pagination bar fixed at 28 dp with 11–14 sp text clips at `fontScale` 2.0 | medium (accessibility) | **fixed** |
| G8 | Emoji category bar had the same missing selected state as G4, and its `onTouch` fires on `ACTION_DOWN` | medium | **fixed** (selected state) / **reported** (DOWN) |
| G9 | `GifGroupButtonsBar.dpToPx` truncates with `toInt()` where the pager uses `roundToInt()` | low | reported |
| G10 | No test of any kind covered the grid geometry, so G1 and G2 could regress unnoticed | medium (process) | **fixed** |

Production files changed: 8. New files: `gif/GifGridGeometry.kt` (43 lines),
`gif/GifGridGeometryTest.kt` (105 lines, 7 tests).

---

## G1 — the grid's cells were the wrong shape, and changed shape as you changed a setting

`GifGridView.onCreateViewHolder` built every cell as:

```kotlin
layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, dpToPx(80))
```

The height was a literal 80 dp. The **only** thing the user can configure about the grid is the
column count — `gif_thumbnail_columns`, validated to 2..5, default 3
(`Config.GIF_THUMBNAIL_COLUMNS` / `Defaults.GIF_THUMBNAIL_COLUMNS`). The width of a cell is
therefore `(pane − padding) / columns`, and nothing made the height follow it.

On the default 3 columns, a 392 dp pane gives a 129 × 80 dp cell: aspect 1.62:1, with
`Scale.CENTER_CROP` cutting the sides off every picture. At 2 columns it is 194 × 80 dp (2.42:1).
At 5 columns it is 78 × 80 dp — nearly square. So changing one setting re-shaped every thumbnail,
and at no setting except 5 was the shape square:

| columns | cell | aspect | result |
|---------|------|--------|--------|
| 2 | 194 × 80 dp | 2.42 | crop cuts the sides |
| 3 (default) | 129 × 80 dp | 1.62 | crop cuts the sides |
| 4 | 97 × 80 dp | 1.21 | slight sides crop |
| 5 | 78 × 80 dp | 0.97 | near-square |

**Fix:** the cell is now square, and its side comes from the pane's own width
(`GifGridGeometry.cellSizePx`). A row of `columns` cells is exactly the usable width at every
column count, no cell is ever narrower than 56 dp, and the shape no longer depends on the column
setting. `adjustViewBounds` is deliberately *not* set: with a fixed width, letting the ImageView
re-derive its own bounds from the drawable is what fights the cell in the first place.

Measured against the real function on a 1080 px pane at 2.75 density (5 px padding each side):

```
columns=2: 535px square (194.5dp)  row 1070px vs 1070px usable  fits
columns=3: 356px square (129.5dp)  row 1068px vs 1070px usable  fits
columns=4: 267px square ( 97.1dp)  row 1068px vs 1070px usable  fits
columns=5: 214px square ( 77.8dp)  row 1070px vs 1070px usable  fits
```

The 56 dp floor only binds below a 284 dp pane at 5 columns — a fold's cover screen, not a phone
— so it never changes the normal case. It is there because a cell computed to zero height would
make the whole grid vanish with no error.

A first attempt derived the constant from `Key.EMPTY`-style defaults and got it wrong; see the
note at the end of part 8 about modelling assertions before writing them. This time every number
in this section came from running the actual algorithm in `python3` first.

## G2 — the grid and the bar above it used different densities

```kotlin
// GifGridView, companion
private fun dpToPx(dp: Int) = (dp * Resources.getSystem().displayMetrics.density).toInt()

// GifGroupButtonsBar, 6 px higher in the same pane
private fun dpToPx(dp: Int) = (dp * context.resources.displayMetrics.density).toInt()
```

`Resources.getSystem()` returns the **primary display's** metrics as the framework sees them. It
ignores the configuration the view was actually inflated with. The two disagree whenever the host
supplies an overridden configuration — a `ContextThemeWrapper` with a changed density, a window
resized in split-screen, a host running the IME under a forced density.

Under such an override, with the context at density 2.0 and the system reporting 2.75:

- the tile padding came out `2 × 2.75 = 5.5 px` where it should be 4.0 px (37% too large);
- the cell floor came out 154 px where it should be 112 px;
- while the category buttons **5 px above** used `44 × 2.0 = 88 px`, correctly.

So two controls in one pane scaled differently under the same configuration, silently — no
exception, nothing in a screenshot at the default configuration.

**Fix:** the conversion moved into `GifGridGeometry.dpToPx(dp, density)` and every call site passes
the view's own density (`parent.context.resources.displayMetrics.density`), which is the same
source `GifGroupButtonsBar` already used. The density is now a *parameter*, so a call site cannot
reach for the system metric by accident, and the conversion is assertable without Android.

(First attempt was a `Context.dpToPx` member extension inside the `companion object`. That only
resolves where the companion is an implicit receiver — not inside `GifRecyclerAdapter`, the class
that actually calls it — so it was replaced with the explicit parameter before this was pushed.)

## G3 — the search debounce was shorter than a keystroke

```kotlin
private const val SEARCH_DEBOUNCE_MS = 150L
```

The comment above it said "short enough to feel instant, long enough to coalesce a fast burst of
typing." It is not. 150 ms is shorter than the interval between keystrokes for anyone typing at a
normal pace — roughly 250 ms per character. Simulated:

```
keystrokes at 250 ms/char (a brisk but normal pace):
  debounce 150 ms  -> 5 of 5 keystrokes start an FTS pass
  debounce 300 ms  -> 0 of 5 (one pass, after the word)
```

Each pass is an FTS5 `MATCH` over the pack index plus a 100-row read, on the IME's single SQLite
connection, **while the user is typing into the keyboard**. Two costs compound it:

- `searchJob.cancel()` only fails the coroutine; it does not interrupt a query already inside
  SQLite. A 200 ms pause followed by another key still pays for the full traversal.
- `countSearchResults` runs a second traversal whenever the first page is full.

**Fix:** 300 ms, which is the value `DictionaryManagerActivity` already uses for the same job. A
short word now settles into one pass instead of one pass per letter. The `cancel()` limitation is
left as-is — it is real, but the 100 ms window removal makes it much rarer, and making SQLite
interruptible is a change to the DB layer, not to this pane.

## G4 / G8 — nothing showed which category was on screen

`GifGroupButtonsBar` renders 18 buttons (recent, all, 16 emotions) into a `HorizontalScrollView`.
Tapping one swapped the grid and left the bar pixel-for-pixel identical. There was no
`isSelected`, no alpha change, no background change — `grep -n "isSelected\|state_selected"` over
the file returned nothing.

Worse, the first button is `RECENTLY_USED` while `GifGridView.loadDefaultView` *silently falls
back to `ALL`* when there is no usage history. On a fresh install the pane showed the All list
with no way to know that the button that looked "current" was not the one loaded.

The clipboard pane already solved this: `ClipboardManager.updateTabHighlighting` uses alpha 1.0
for the active tab and 0.5 for the rest, and the pane pagers use 1.0 / 0.3. **Fix:** the same
idiom, via `setSelectedCategory` (GIF) and `setSelectedGroup` (emoji), with 0.45 for the inactive
buttons — dim enough to read as "not current", bright enough to stay legible and tappable.

Alpha was chosen over a background tint on purpose: the panes are repainted from `ThemeProvider`
at open time (`applyRuntimeThemeToPane`), which would clobber any tint set earlier, and a
`state_selected` background would need a new drawable per theme.

Wiring the *right* category took some care, because the grid's initial load is asynchronous and
can loop:

- `loadDefaultView` is launched from the view's own scope, so it finishes whenever it finishes.
- It sets `currentCategory = RECENTLY_USED`, loads, and if the page is empty moves to `ALL` and
  loads again, then calls `notifyPagination()`.
- `GifGridManager.shownCategory()` now exposes the landed category rather than the requested one.

So the bar is marked from the `onPaginationChanged` callback of the grid — which fires on all of
`setCategory`, `nextPage`, `previousPage` and now `loadDefaultView` — instead of from the tap
handler, which could not know about the fallback.

After the extraction, the same defect in the emoji pane (`EmojiGroupButtonsBar`, which had the
same absence) was fixed with `setSelectedGroup` and `EmojiGridView.currentGroup()`; it has the same
`lastUsed.isEmpty()` fallback to group 0.

**Reported, not fixed (G8b):** `EmojiGroupButtonsBar.onTouch` acts on `ACTION_DOWN` and returns
`false` for everything else. The GIF bar's own `GifCategoryTapDetector` was written specifically to
avoid this ("firing on DOWN made every scroll gesture switch category and wipe the in-progress
search"). The emoji bar is a plain `LinearLayout`, not a scroll container, so a scroll gesture
cannot reach it today and the practical risk is low — but the two bars disagree on a rule that was
learned the hard way, and a future change that makes the emoji bar scrollable would reintroduce
the bug. Left alone because changing it now would alter behaviour with nothing to test it against.

## G5 / G6 — three full-height panes, three different ways out

Every pane takes the keyboard's entire height; the keys are not drawn under it. So the only way
back to typing is a control in the pane's own header.

`clipboard_pane.xml` was fixed on 2026-11-13 with a comment explaining exactly this — a 40 dp
Material back arrow at `paddingStart="4dp"`, top-left, "where Gboard's own clip/emoji panes put
theirs; the close control used to be a small down-arrow between the filter and search icons, which
is not where anyone looks for back". `res/drawable/ic_clipboard_back.xml` was added for it.

The GIF and emoji panes never got that change. Measured from the layouts:

| | GIF pane | Emoji pane | Clipboard pane |
|---|---|---|---|
| icon | `arrow_down_float` | `arrow_down_float` | `ic_clipboard_back` |
| side | 36 dp | 36 dp | 40 dp |
| padding | 6 dp | 6 dp | 8 dp |
| position | far end of header | far end of header | first in header |
| label | "Close GIF picker" | "Close emoji picker" | "Back to typing" |
| id | `gif_close_button` | `emoji_close_button` | `clipboard_close_button` |

**Fix:** the GIF and emoji headers now use the clipboard pane's control — same drawable, same
40 dp, same 8 dp padding, same `?android:attr/selectableItemBackgroundBorderless`, same position
first in the header, same `@string/clipboard_back` label. All three ids are unchanged, so
`EmojiSearchManager` and `KeyboardReceiver` keep working: the former keys off
`R.id.emoji_close_button` (`EmojiSearchManager:49,99`), the latter off `R.id.gif_close_button`. The
`labelViewIds` arrays already listed both ids, so runtime theming reaches the new icon with no
further change.

`emoji_close` and `gif_close` are now unreferenced. Deliberately not deleted: they are translated
strings, and removing a string that a translator has already handled is a translation-hygiene
change, not a bug fix.

## G7 — the pagination bar clipped at large font sizes

`gif_pagination_bar` was a fixed `28dp` holding 14 sp and 11 sp text and `match_parent`-height
children. At `fontScale` 2.0, 14 sp text is ~28 dp tall — the glyphs are clipped and the touch
targets (48 dp wide, but 28 dp tall) fall under the 48 dp minimum.

**Fix:** `wrap_content` with `android:minHeight="40dp"`, which is what the clipboard pane's
pagination bar effectively gets (48 dp). Text now grows the bar instead of being cut.

## G9 — inconsistent rounding

`GifGroupButtonsBar.dpToPx` returns `(dp * density).toInt()` — truncation. The pane pagers and the
new geometry use `roundToInt()`. At 44 dp on a 2.625 density the difference is a round-off, not a
bug, and `toInt()` is the existing convention in this file. **Reported, not fixed:** changing it
would move every category button by a pixel for no visible gain.

## Checked and found sound

- **Insertion path.** `onGifSelected` → `insertGif` → `SWITCH_BACK_GIF`; `recordGifUsage` is
  launched in the managed `scope` with the E-1 crash guard (`GifGridView:264`).
- **Recycling.** `onViewRecycled` clears the drawable; `itemAnimator = null`; Coil memory cache
  capped at 32 MB with the disk cache disabled because the pack files are already on disk. Correct
  for an IME.
- **"No results" timing.** Keys off the async `onResultsChanged` callback, not the previous query's
  count (E-5). Sound.
- **Pagination arithmetic.** `hasNextPage() = (page+1)*100 < totalItems`; the search path skips
  `countSearchResults` when the first page is partial, which is sound because a partial page *is*
  the complete result set.
- **FTS input.** `GifFtsSanitizer` is covered by its own test; nothing in this pass found a route
  around it.
- **RTL.** `PanePagerArrows.apply` swaps the prev/next glyphs in the GIF pagination bar. The new
  back control is leading-edge positioned with `paddingStart`, so it mirrors correctly; it is a
  back arrow, which does not mirror.
- **`ContextThemeWrapper(context, R.style.emojiTypeButton)`** in both bars: the style resolves
  `?attr/colorKey` / `?attr/colorLabel` against the IME's runtime theme. That is why the alpha
  approach for the selected state was the safe one.
- **`GifCategoryTapDetector`** (UP-within-slop, `ACTION_CANCEL` treated as not-a-tap) — covered by
  `GifCategoryTapDetectorTest` and correct.

## What this pass did not do

- **Did not touch `GifDatabase.kt` (855 lines), `GifPackManager.kt` (476) or
  `GifAssetManager.kt` (320)** beyond reading. Pack import, validation and the ZIP path were the
  subject of wave 4 and have their own tests; a second pass inside one visit would be shallow.
- **Did not make SQLite interruptible**, so `searchJob.cancel()` still cannot stop a query that
  has already entered SQLite (G3).
- **Did not add a GIF-pane screenshot test.** There is no Robolectric or instrumented test
  infrastructure in this repo, and the sandbox has neither. The geometry is covered as a pure
  function instead; the *appearance* is not covered by anything.
- **Did not fix G8b** (emoji bar fires on DOWN) or **G9** (truncating `toInt()`).
- **Did not audit `clipboard_pane.xml` as a whole** (340 lines). The clipboard pane was read only
  far enough to establish the header convention it had already adopted; its own layout was the
  subject of wave 4.
- **Did not remove** the now-unused `gif_close` / `emoji_close` strings.
- **Did not verify on a device.** No emulator, no Gradle, no `kotlinc` in the sandbox — CI compiles
  and runs the tests, and the layout changes are checked structurally (XML parses; tag balance;
  ids unchanged).

## CI

| run | head | suite | APK | smoke |
|-----|------|-------|-----|-------|
| 1 | `9d52c2d` | **failed** — 1 of 7 | pass | — |
| 2 | `39dce7a` | **`44941627842` green** — 4870 run, 0 failed, 134 skipped, 435 classes | `44941622810` pass | `44941622836` pass |

Wave 11 ended at 4863 run / 434 classes; this pass adds **7 tests and 1 class** (4870 / 435).
Skipped stays at 134, so the skip ratchet is untouched. No production file outside `gif/`,
`emoji/`, `KeyboardReceiver.kt` and the two pane layouts was changed.

Run 1's single failure is worth recording, because the numbers in it were all correct:
`assertThat(widths).isInOrder()` asserts **ascending** order, and the list is strictly decreasing
by construction. Truth has no "descending" matcher, and `isInOrder()` reads as neutral when you
are skimming. Replaced with `zipWithNext().all { wider > narrower }`, which carries its own
direction. This is the second time in two waves that a *matcher's* semantics — not the value under
test — was the thing that was wrong; see part 8 on the `shift = 1f` fixture.

## Consequence for the test count

`GifGridGeometryTest` is 7 tests, registered in `pureTestClasses` with no square brackets anywhere
in the surrounding comment (see part 8's V8 — a `]` inside those arrays silently unregisters every
class below it). Registration was re-checked with the drift test's own parser before pushing:
440 registered, 440 on disk, 0 unregistered.
