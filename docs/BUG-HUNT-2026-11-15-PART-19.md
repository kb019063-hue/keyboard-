# Bug hunt — 2026-11-15, part 19

**Scope: the floating window's *range of motion*.** Part 18 ended by recommending drag-to-dock as
"its own pass". Opening that pass meant asking a prior question the recommendation had not asked:
*can this window be dragged down at all?* It cannot. It is pinned at the bottom-most legal position
from the moment it appears, on every device measured, and has been for the whole life of phase 3.

That is why this part is not the dock gesture. Implementing dock-on-drag-down into a window that
cannot move down would have produced a feature that could never fire while appearing complete — the
worst outcome available. The dock gesture stays recommended; it needs P8 fixed first.

Part 18's P6 (the grip moved to the bottom) is unaffected and stays fixed: the grip is now at the
right edge, which is what makes a dock gesture *conceivable*. The window still cannot travel.

Method note: the geometry in this part is **reconciled against a device measurement**, not derived
from the constants alone. `dumpsys` in CI run `45307511686` reported the input-method frame
`[108,1302][972,2274]` on a 1080x2400 screen at density 2.625, and every figure below reproduces
that frame to the pixel before being applied to other cases. Where a number here disagrees with a
constant's own comment, the measurement wins and the comment is the bug.

---

## Findings

| # | Finding | Severity | State |
|---|---------|----------|-------|
| P8 | The floating window is **pinned at `maxYFraction`** — bottom-flush at the bottom of the IME's area — on every device measured, at every width 50–100 %. It cannot be dragged down, and dragging down cannot even bank a fraction, because the drag handler clamps the stored value to the same cap. `floating_y_fraction` is therefore **dead weight** on such devices: it reads `0.62` and means `0.5726` | **medium** (a whole axis of a drag gesture does nothing; the persisted default is a lie) | **reported** |
| P9 | `DEFAULT_Y_FRACTION = 0.62` exceeds `maxYFraction` on every geometry measured (which tops out at **0.5726** for a 43 %-tall window), so `MAX_Y_FRACTION = 0.9` and the 0.62 default are both **unreachable** for any window taller than **38 %** of the available height — which is most of them | low (dead constant; the documented "a keyboard that floats" position is never what shows) | **reported** |
| P10 | The y clamp and the y drag are resolved against the **IME's available height**, but `FloatingConfig`'s documentation, `MAX_Y_FRACTION`'s rationale and part 18's own arithmetic all describe them as fractions of the **screen**. On a device with a 48 dp navigation bar those differ by 5.3 %, and the docs state "`y = 0.9 * 2400` puts the bottom edge at 3070 px on a 2400 px screen" as though 0.9 were reachable — it is not, on either reading | low (documentation; nothing behaves wrongly) | **reported** |
| P11 | A dock gesture dragged to the bottom edge would have **nothing to confirm it with**: `ImeToastBanDriftTest` forbids toasts in the IME (Android 13+ never shows them for a keyboard), and the existing confirmation path, `confirmToolSwitch`, writes into the suggestion strip — which in a floating window is underneath the grip the finger is still holding | low, but blocking for that gesture | **reported** |

---

## P8. The floating window has no downward travel, and the drag handler cannot give it any

### The measurement

CI run `45307511686`, floating on, at the default width (80 %):

```
NFFRAME-DIAG frame=[108,1302][972,2274] screen=1080x2400 shown=mInputShown=true
```

The frame's own numbers fix the coordinate space without my having to guess it:

- The window is **972 px** tall and its bottom edge is at **2274**. The screen is 2400, so there are
  **126 px = 48.0 dp** of system bar below the input method's area.
- The clamp is `top = availableHeight − windowHeight`. `2274 − 972 = 1302`, which is the measured
  `T1` to the pixel. So the window is resting **exactly on the clamp**, not on the stored fraction.

### Why it cannot move

`maxYFraction(2274, 972) = (2274 − 972) / 2274 = 0.5726`, and the drag handler clamps to that same
value:

```kotlin
cfg.floating_y_fraction = FloatingConfig.clampYFraction(
    cfg.floating_y_fraction + dy.toFloat() / dm.heightPixels,
    dm.heightPixels,
    floatingWindowHeightPx()
)
```

`clampYFraction` is `f.coerceIn(0f, maxYFraction(...))`. A downward drag *adds* to a value that is
already at the maximum, so every downward dy is discarded. Downward travel is **0 px**.

The general rule: downward travel exists iff `maxYFraction > storedFraction`, i.e. iff
`windowHeight < (1 − 0.62) × availableHeight = 0.38 × availableHeight`. Measured travel:

| window height | share of the 2274 px area | `maxYFraction` | down-travel available |
|---|---|---|---|
| **972 px (measured, 80 % width)** | **42.7 %** | **0.5726** | **0 px** |
| 900 px | 39.6 % | 0.6042 | 72 px |
| 800 px | 35.2 % | 0.6482 | 172 px |
| 700 px | 30.8 % | 0.6922 | 272 px |
| 600 px | 26.4 % | 0.7361 | 372 px |

At the minimum width (50 %) the window is narrower but not shorter, so every width from 50 % to
100 % lands in the first row. That follows structurally rather than by sampling: the window's height
is **measured** (`floatingWindowHeightPx` reads the root's laid-out height), never computed from
`floating_width_percent` — `FloatingConfig.window` uses the width only for `widthPx` and `x`, and
`floatingWindowHeightPx` does not reference the width at all. The one caveat is a narrow enough
window making the suggestion strip wrap to a second line, which would make the window *taller* and
so reduce travel further — it cannot create any. The only way to regain downward travel today is to
make the window **shorter**, and the width knob cannot: it is a horizontal handle that moves one
percent of width per percent of screen.

### What this means in practice

The window opens bottom-flush and can be dragged **up** and sideways. Dragging it back down returns
it to where it started, and no further. A user who drags up, then wants the keyboard lower again,
gets the same position they began with — which is indistinguishable from "drag down does nothing"
if they never dragged it up first.

The persisted `floating_y_fraction` is not harmless dead weight. It is a **number that reads as a
position and is not one**: it stores 0.62, displays at 0.5726, and after any upward drag stores the
dragged value. Restoring a stored 0.45 later does work — but the user has no way to tell from the
setting that 0.62 was never in effect, and the clamp silently rewrites the meaning of the number
whenever the window's height changes (a suggestion strip appearing, the toolbar hiding, a rotation).

### Not fixed, and why

The fix is a position policy, not a defect with one right answer. Two shapes are defensible and they
disagree:

1. **Give the window downward travel by shrinking the cap** — e.g. cap at
   `maxYFraction − DOCK_MARGIN`, leaving a deliberate gap above the bottom edge. This is what makes
   a dock gesture possible at all, but it *changes where the keyboard sits by default*, which is a
   visible product decision.
2. **Keep it bottom-flush and drop the y axis below a threshold** — if the window is height-pinned,
   say so: hide the vertical meaning, delete `DEFAULT_Y_FRACTION`'s pretense, and make the default
   honest. That costs no behaviour but accepts a keyboard that cannot be placed low.

Both are one-line changes in `FloatingConfig` and both need the same prior decision — *should the
floating keyboard be able to sit lower than it opens?* — which is a question about the product, not
about the code. Recorded rather than guessed at, per the standing instruction.

## P9. Two constants that can never take effect

`MAX_Y_FRACTION = 0.9` and `DEFAULT_Y_FRACTION = 0.62` are both above `maxYFraction` for any window
taller than 38 % of the available height. Measured here: 42.7 %, cap 0.5726, so the effective default
is 0.5726 and 0.9 is never reached on any device in the earlier table (all of which land at 0.5726).

`MAX_Y_FRACTION` is not dead code — it is the fallback used when `windowHeightPx == 0`, before the
first layout pass, which is deliberate and documented. `DEFAULT_Y_FRACTION` has no such second life:
it is the value written into a fresh install and it is immediately overridden by the clamp.

Worth keeping only if P8 is fixed by giving the window travel (option 1 above), since then 0.62
becomes reachable again for short windows. Deleting it before that decision is made would be
premature; recording it now means the decision is not made blind.

## P10. The clamp is measured in one space and documented in another

`FloatingConfig`'s header says the y fraction "places the window's top edge, capped by
`[maxYFraction]`", and `MAX_Y_FRACTION`'s comment reasons in **screen** pixels:

> `y = 0.9 * 2400` puts the bottom edge at 3070 px on a 2400 px screen

Measured, the space is the **IME's available height** (2274), not the screen (2400). The arithmetic
in that comment is right about the *fraction* being wrong and wrong about the space it is wrong in:
0.9 is unreachable either way, but `0.9 × 2400` is not the edge case that matters, because the value
never gets that far. Nothing behaves incorrectly — the clamp and the drag agree with each other — but
every stated example is computed in the wrong units, so a future change reasoned from those comments
would be reasoned from a false premise. This is exactly the class of stale note that produced part
18's P5, where a comment describing the grip's position outlived the grip's position.

Corrected alongside this part (comments only, no behaviour).

## P11. A dock gesture would have no way to say "docked"

Not urgent while the gesture does not exist, but it constrains how it can be built, so it belongs
with the others rather than being discovered halfway through implementing P6's recommendation.

Two confirmation mechanisms exist and neither fits:

- **A toast** is what Gboard uses, and is what this codebase used until the toasts silently stopped
  appearing. `ImeToastBanDriftTest` now forbids them in the IME files, because Android 13+ shows an
  app's toasts only with notification permission or an activity in front, and a keyboard has
  neither. The drift test is right and must not be relaxed for this.
- **`confirmToolSwitch`** writes into the suggestion strip. In a floating window the strip is
  directly above the grip — i.e. *under the thumb that just finished the drag*.

So the dock gesture needs either a haptic (`performHapticFeedback`, which is what Gboard actually
does and is the natural choice for a snap) or a visible state change on the grip itself before
release. Recorded so the next pass does not start by reaching for a toast, discover the drift test,
and redesign mid-implementation.

---

## Checked and found sound

- **`clampYFraction` and `moveFloating` agree.** Both use the same `screenHeight`/`windowHeightPx`
  pair, so a drag cannot bank a fraction that the next layout would reject — the reason given in
  `clampYFraction`'s comment is accurate, and it is what makes P8 a *dead* axis rather than a
  *flickering* one.
- **The upward direction works, including its clamp.** Dragging up is unbounded until `y = 0`, and
  `maxYFraction` returns 0 for a window taller than the screen (verified by the existing
  `FloatingConfigTest` case asserting `maxYFraction(2400, 3000) == 0`).
- **The x axis is genuinely different from the y axis**, and correctly so: `xPixels` treats the
  fraction as a share of *travel* (`(screenWidth − widthPixels) × f`), so 0.5 is centred at any
  width and every fraction from 0 to 1 is reachable at every legal width. The asymmetry with y is
  what makes P8 possible, and it is not itself a bug — travel-relative x is the right model.
- **`FloatingRoot`'s grip reports raw pixel deltas and holds no arithmetic** (part 18's change did
  not disturb this), so P8's fix belongs in `FloatingConfig` and stays JVM-testable.
- **The window height re-applies on layout changes** (`addOnLayoutChangeListener` →
  `updateSoftInputWindowLayoutParams`), so a suggestion strip appearing re-clamps the position
  rather than leaving it stale. Confirmed by reading the listener's own termination argument: it
  assigns only when a value actually changed.
- **`applyFloatingWindowLayoutParams` assigns `attrs` only when something changed**, so the
  re-entrant call from the layout listener terminates.

## What this pass did not do

- **Did not implement the dock gesture.** P8 has to be decided first; see above. Part 18's
  recommendation to build it next stands, with P8 as its prerequisite.
- **Did not change any behaviour.** This part edits documentation comments in `FloatingConfig` to say
  what the code actually does, and adds the findings above. No constant, no calculation and no
  layout was touched, so the test count and the suite's verdicts are unchanged by design.
- **Did not measure a second device.** All geometry here is one Pixel-8-class emulator at 1080x2400,
  density 2.625. The *rule* (travel exists iff the window is under 38 % of the available height) is
  derived, not sampled, but the 0 px figure is measured on one device only. A second device with a
  different navigation bar would confirm or break the 48 dp assumption; it would not change the rule.
- **Did not re-open the grip's edge.** Part 18 closed it, with a drift test pinning it.

## Verification

- The geometry was reconciled before being used: `maxYFraction(2274, 972) = 0.5726`, and
  `0.5726 × 2274 = 1302`, which equals the `T1` CI measured. Both the "bottom-flush" and "at the
  clamp" claims follow from that identity rather than from inspection.
- The dead-axis conclusion was checked against the drag path it depends on (`clampYFraction` inside
  `moveFloating`), not only against the layout path.
- The reachability table was computed for five window heights, and the claim that *all* legal widths
  land in the same row was checked by noting the knob changes width, not height
  (`floating_width_percent` appears in no height calculation).
