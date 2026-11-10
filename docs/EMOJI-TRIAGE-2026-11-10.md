# The emoji "bug hunt" list, checked against the code (2026-11-10)

A previous session produced a report claiming to have found six emoji defects and fixed all of
them across three "phases", with a summary table marking every item **✅ Done**. That report's own
file (`docs/EMOJI_BUGS_AND_GBOARD_IMPROVEMENTS.md`) was never written, and **none of the changes it
claims are in this repository** — the branch it ran on was never pushed.

This document records what was actually verified, what was done, and what was rejected.

## Verdict on each claim

| # | Claim | Verdict |
| --- | --- | --- |
| 1 | `hardwareAccelerated="false"` causes scroll lag; set it `"true"` | ❌ **Wrong, and the fix would be a no-op** |
| 2 | Emoji cells are too small; 15–20 % smaller than Gboard 2026 | ⚠️ **Real premise, wrong diagnosis, unavailable fix** |
| 3 | `tooltipDismissHandler` is not cleared on detach | ❌ **Already handled** — but it hid a **real** bug (see 3b) |
| 3b | *(found here)* the recents write is cancelled by detach | ✅ **Real — fixed** |
| 4 | Tooltip clips off-screen near the top edge | ✅ **Real — fixed** |
| 5 | `searchByName` blocks the UI thread with an unlimited fallback | ❌ **Not real** — the fallback is already capped at 100 |
| 6 | Equal-weight category buttons should be dynamic pills | ⚠️ **Design opinion, not a defect** — and the cited design leaves ~2 dp per button on a phone |

## The details

### 1. `hardwareAccelerated` — the attribute is on the wrong element

`android:hardwareAccelerated` is an **`<application>` / `<activity>` manifest attribute**. It is not
a `View` attribute, so its presence on a layout root either fails lint or is silently ignored —
which it is here. The IME is already hardware accelerated at the manifest level for both the
application and the instrumentation:

```xml
<manifest … android:hardwareAccelerated="true">
  <application … android:hardwareAccelerated="true">
```

So switching the layout attribute to `"true"` changes nothing. The claim that it matches "Gboard's
February 2026 update, 120 ms → 115 ms" cites an article that does not exist; the searches find no
such change, and a **4 %** improvement would not be worth a code change even if it did.

The `"false"` is also deliberate and consistent: `keyboard.xml`, `emoji_pane.xml`, `gif_pane.xml`
and `clipboard_pane.xml` all carry it, and `keyboard.xml`'s header documents why the surface paints
its own background. Removing it from one pane is not a fix, it is an inconsistency.

### 2. Emoji cell size — real premise, but not actionable as stated

Gboard **did** enlarge emoji and its panel containers: 9to5Google (2025-05-15) reports the beta's
"larger, spaced icons" in "rounded rectangles", and a 2026 report describes a default increase. So
the premise is sound.

Two problems with the proposed fix:

- The grid is `numColumns="auto_fit"` with `columnWidth="45sp"`. Under `auto_fit`, the column width
  is a **floor**: `GridView` then stretches each column to fill the row. Switching it to `52sp`
  changes how many columns fit, so the visible effect depends on screen width and can go either
  way — it is not the uniform "15–20 % bigger" the claim implies.
- `minHeight` in `EmojiGridView.EmojiView` is a **height floor inside a cell**, not the cell size.
  Raising it to 42 dp pads each emoji and can add rows without making any glyph larger.

Cell size is a visual decision on the owner's phone. It is not a correctness bug and should not be
changed blind.

### 3. The handler "leak" — already handled, but it pointed at a real bug

`onDetachedFromWindow` did not clear `tooltipDismissHandler` callbacks, and the claim is that this
leaks. It does not leak in practice: the handler is a field of the view, the view owns it, and a
detached view that is garbage becomes garbage with it. The pending 2-second dismiss is harmless.

**What the same code did do is lose data.** `scheduleSaveLastUsed` debounced the recents write with
the *View's own* `postDelayed`. A detached View **discards its message queue**, and this pane
detaches every time the user switches back to the keyboard. So:

> Tap an emoji, then close the emoji pane within 500 ms → the write is cancelled and that emoji is
> never recorded in recents.

**Fixed** (`EmojiGridView`): the debounce now runs on a process-lifetime `MAIN_HANDLER`, and the
payload is snapshotted into `pendingSaveSet` / `pendingSavePrefs` when it is armed. That keeps
exactly the property the old code needed and did not have: the deadline survives detach **and** the
queued `Runnable` cannot hold the detached view alive. Cancelling it on detach would have looked
tidier and would have restored the bug.

Detach now clears the tooltip timers only — decoration, correctly dropped — and deliberately leaves
the recents write queued. Both halves are pinned by a source-reading drift test.

### 4. Tooltip clipping — real, fixed

The bubble was always drawn **above** the cell and horizontally **centred** on it. Consequences:

- The grid's top row has the pane's search bar above it, so the bubble left the keyboard surface.
- The first and last cell of every row pushed a 200 dp-max bubble past the side edge.

**Fixed** with a new pure object, `EmojiTooltipPlacement`, which mirrors how Gboard behaves: draw
above if it fits, otherwise flip below, and slide horizontally back inside the bounds rather than
hanging off an edge. `EmojiTooltipManager` now measures, asks the policy, and converts the answer to
the `showAsDropDown` offsets. Ten JVM tests cover the middle case, the top-row flip, the
no-room-anywhere clamp, both edge slides, a full sweep across a row, and the offset sign.

### 5. Search latency — not real as described

`Emoji.searchByName` first queries the trie index (limit 60) and only falls back to the legacy
`nameMap` scan when it produced fewer than 20 hits. That fallback is bounded:

```kotlin
if (results.size >= 100) break
```

So "iterating `nameMap` with unlimited iterations" is not what the code does. The claim's proposed
fix — "cap the fallback at 80" — would *reduce* results for no measurable gain.

There is a genuine question here — the fallback runs on the main thread and `initNameMap()` can run
inside it — but turning it into a coroutine with `StateFlow` is a redesign of the search path with
its own test surface, not a bug fix. Left alone deliberately; noted for a future round.

### 6. Category bar — design opinion, and the numbers do not work

Gboard's container redesign is real. But the proposed `24dp` horizontal padding **inside a pill**
would consume almost the entire button on a phone: the bar must hold the recents clock plus every
group — nine buttons at 1080 px is ~120 px each, and 24 dp padding is ~63 px *per side* ~ 126 px.
The pills would collapse to empty slivers. Gboard's panel is a horizontal scroller with far fewer
containers than nine.

Equal weight is not a defect; it is why all nine are reachable and equally hittable. A scroller is
a reasonable future change with different arithmetic, not a drop-in.

## What changed in this round

| file | change |
| --- | --- |
| `EmojiTooltipPlacement.kt` | **new** — pure flip-or-slide geometry |
| `emoji/EmojiTooltipManager.kt` | routes through the policy, clamps to the window's visible frame |
| `emoji/EmojiGridView.kt` | recents debounce moved to a process handler + payload snapshot; detach clears tooltip timers only |
| `EmojiTooltipPlacementTest.kt` | **new** — 10 tests: 8 behavioural, 2 drift guards |

## Rejected on purpose

- Flipping `hardwareAccelerated` (no-op, and wrong attribute for a layout).
- Changing the emoji cell size (a visual call for the owner's device, not a bug).
- Capping the search fallback at 80 (makes results worse; the loop is already bounded).
- Converting the category bar to a padded scroller (the arithmetic collapses the buttons).
- Adding a floating delete FAB (the source report itself recommends against it).
