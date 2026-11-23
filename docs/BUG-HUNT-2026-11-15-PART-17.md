# Bug hunt, part 17 — `popover/`: a wedge is not a square, and a case-insensitive lookup on the wrong namespace

Date: 2026-11-15
Branch: `arena/cbf26556-keyboard`
Base: `58f213c` (part 16, `customization/`)
Scope: `popover/` in full — `SubkeyPopoverGeometry` (91 lines), `SubkeyPopoverState` + metrics +
the assign-intent codec (173), `SubkeyPopoverSlots` (89), `SubkeyPopoverRenderer` (273),
`SubkeyAssignActivity` (346) — plus the `Pointers` half that owns the popover (open / track /
dwell / release, ~150 lines) and the hit-test it feeds.

This is the surface part 16 touched only through its call sites. It is the *other* place a
( key, direction ) slot is edited, and it shares `SwipeDirection`, `ShortSwipeMapping` and
`SubLabelSizing` with the keyboard's own draw path — so it is where the two can silently disagree.

One real routing defect is fixed. The geometry finding is **reported**, and the reason is worth
stating plainly: the code's own doc comment makes a promise it does not keep, and the fix changes
every corner selection, so it needs a decision rather than a patch. An earlier draft of this pass
"fixed" the neutral zone; measurement showed the change was a no-op at the shipped settings, and it
was reverted. That is recorded below rather than hidden.

---

## Summary

| # | Finding | Severity | State |
|---|---------|----------|-------|
| P1 | A slot is a 45° **sector** from the grid centre, but a cell is drawn as a **square**: a finger well inside a drawn cell can select its neighbour (35 px left of the drawn N cell's centre selects NW; the boundary is 30 px) | **medium** (every off-centre corner reach; contradicts the module's stated guarantee) | **reported** — 3 tests pin it |
| P2 | The neutral-zone sliders accept 20 %–150 % independently, so at the top of the range the cancel region covers the inner part of a drawn cardinal cell (50 px right of centre cancels, though it is inside the drawn E cell) | low (only at non-default settings) | **reported** — 1 test pins it |
| P3 | `CommandRouting.editingValue` matched `AvailableCommand` **case-insensitively** against every stored command name, so 5 lowercase catalogue names were resolved as their SCREAMING_SNAKE legacy constant and silently took the wrong execution path | **high** (correctness of 5 commands, two of which changed what the mapping does) | **fixed** + 3 tests |
| P4 | `SubkeyAssignActivity` launches with `FLAG_ACTIVITY_CLEAR_TASK`, which clears the *host app's* task since the activity has its own `taskAffinity` | medium (visible: typing context lost when the assign screen opens) | **reported** — needs a device check |
| P5 | `SubkeyPopoverSlots.isUsable` refuses Placeholder subkeys but the popover ignores `loc ` prefix handling done by `parse_key_attr` | — | **sound** (checked, see below) |
| P6 | Renderer draw path: per-frame allocations, `PathMeasure` reuse, paint mutation, edge clamping | — | **sound** |
| P7 | State/dwell lifecycle: timer ownership, arming, selectability, release-in-neutral | — | **sound** |

Production files changed: `ActionType.kt` (+`fromLegacyName`), `CommandRouting.kt`.
Tests: `CommandRoutingTest` +3 (13 total), `SubkeyPopoverGeometryTest` +4 (15 total).
Suite: **4910 run / 0 failed / 134 skipped / 440 classes** (`a4b382b`).

---

## P3 — a case-insensitive lookup applied to the wrong namespace

`CommandRouting.editingValue` decides whether a stored command name denotes an editing key, and the
executor skips its own handling when it does. It read:

```kotlin
// Legacy names match case-insensitively, exactly as the executor's AvailableCommand.fromString.
val name = AvailableCommand.fromString(commandName)?.let { LEGACY_EDITING_NAMES[it] } ?: commandName
```

`AvailableCommand.fromString` is `entries.find { it.name.equals(name, ignoreCase = true) }`. That
case-insensitivity is correct for its own purpose — matching the enum's 27 names however the user
types them. Applied to *every* stored command name it is wrong, because the catalogue's names are
lowercase identifiers and **14 of the enum's 27 constants lowercase onto a real catalogue command**:

```
COPY→copy  PASTE→paste  CUT→cut  UNDO→undo  REDO→redo
CURSOR_LEFT→cursor_left  …  WORD_LEFT→word_left  DELETE_WORD→delete_word
SWITCH_FORWARD→switch_forward  SWITCH_BACKWARD→switch_backward
```

For the nine that remap to themselves the mistake was invisible: `copy` resolved to `COPY`, was
remapped straight back to `copy`, and the answer was right for the wrong reason. Five were not:

| stored / catalogue name | legacy meaning (executor `when`) | catalogue command of that name |
|---|---|---|
| `switch_forward` | defer to the keyboard service | `Event` key: switches layout forward |
| `switch_backward` | defer to the keyboard service | `Event` key: switches layout backward |
| `showTextMenu` | defer the text menu to the view | **no** `KeyValue` — the guard returns null |
| `primaryLangToggle` | defer the language toggle | **no** `KeyValue` |
| `secondaryLangToggle` | defer the language toggle | **no** `KeyValue` |

For the first two, the guard returned null, so the executor ran the **catalogue** command — an
`Event` key — where the legacy path had deliberately deferred to the view. For the last three the
guard also returned null and the executor ran the catalogue name, which has no `KeyValue` at all,
instead of the legacy command `Keyboard2View` implements. In both directions the mapping executed a
different action than the one it names. Measured: 14 of 27 enum names collide; 5 diverge.

Fixed by matching the legacy namespace **exactly**, which is what "is this a legacy spelling?"
actually asks:

```kotlin
fun fromLegacyName(name: String): AvailableCommand? = entries.find { it.name == name }
```

and splitting the resolution out so it is testable without a `KeyValue` of a particular kind:

```kotlin
fun editingKeyName(commandName: String): String? =
    AvailableCommand.fromLegacyName(commandName)?.let { LEGACY_EDITING_NAMES[it] } ?: commandName
```

All 14 legacy spellings still resolve (a test pins each one, and that each target really is an
`Editing` key); the 5 catalogue names now resolve to themselves and return null from `editingValue`
as they should. The comment that claimed the two lookups were deliberately identical is replaced
with the reason they cannot be.

---

## P1 — reported: the slot is a wedge, the cell is a square

`SubkeyPopoverGeometry`'s own documentation states the guarantee:

> Selection is hit-tested in the grid's DRAWN coordinates, so what the user sees under the finger is
> what gets selected.

That is not what it does. `slotAt` classifies by the **angle** of the displacement, normalised by
the cell size — an eight-way 45° **sector** whose apex is the grid centre. But each cell is *drawn*
as a square one cell wide and one cell tall (`SubkeyPopoverRenderer.drawSlot`, `CELL_FILL` 0.9).
A wedge cannot contain a square. The two shapes have different boundaries, and the divergence grows
with the square's aspect ratio and with the radius.

Measured on a 100 × 150 px cell at the shipped 60 % default, over the drawn square of each cell —
for every point that selects a different slot, the one CLOSEST to the cell's centre, i.e. how far in
the error reaches:

| cell | closest wrong point (px from grid centre) | selects | radius |
|---|---|---|---|
| N | (−35, −128) | NW | 0.383 cells — **46 %** of the way in from the square's corner |
| E | (85, −53) | NE | 0.383 cells, 46 % |
| S | (−35, 128) | SW | 0.383 cells, 46 % |
| W | (−85, −53) | NW | 0.383 cells, 46 % |
| NE / SE / SW / NW | e.g. (121, −75) | E | 0.541 cells, 23 % |

So a deliberate press toward N that starts 35 px left of where N is drawn selects NW; the boundary
sits at 30 px. Overall agreement between "cell under the finger" and "slot selected" is **87.8 %**
of the drawn-cell area (321 600 sampled points).

(An earlier draft of this table quoted different points with a "20.7 px / 41 %" penetration figure.
Re-deriving it showed those points select the CORRECT cell — the sweep had been read the wrong way
round. The numbers above are the reproducible ones; the test that pins this uses
`slot(-45f, -150f) == NW`, which is 45 px left of the N cell's centre and well inside it.)

**Why it is not fixed here.** The two candidate rules are not tweaks, they are different controls:

1. **Keep the sector**, document the guarantee honestly. Sector selection has a real virtue — the
   eight slots tile the plane with no dead area — and the error only matters within ~20 px of a
   cell's inner corners.
2. **Nearest drawn cell** (`argmax` over the eight cell rectangles, cancel zone first). This makes
   the documented guarantee exactly true, and leaves the 8 cells a diamond-shaped fringe with no
   slot — a release there would do nothing instead of picking the nearest, which may be worse.

Option 2 is probably right for a popover whose whole point is "what you see is what you get", but it
changes the selection of every near-corner movement, and it wants a finger on a device to judge.
Reported with the numbers; `SubkeyPopoverGeometryTest` now pins both the counter-example and the
property the sector rule *does* guarantee (every cell's centre and a quarter-cell radius around it
are unambiguous), so a future change cannot silently move the boundaries inward.

---

## P2 — reported: the cancel zone can swallow the cells it is supposed to sit between

The neutral zone is a rectangle of `neutralWidthFraction` cells wide and `neutralHeightFraction`
tall, from two independent sliders that each accept 20 %–150 %
(`Config.Defaults.SUBKEY_POPOVER_NEUTRAL_MIN/MAX`). At the top of that range the cancel half-extent
is 0.75 cells, which reaches past the inner edge of the drawn W/E (and N/S) cells:

```
cell 100 × 150 px, finger 50 px right of centre — inside the drawn E cell (which spans 50…150 px):

  neutral w=60 %  → E
  neutral w=150 % → nothing (cancelled)
```

The E cell's *centre* is still reachable at 150 %, so this is not a dead control — but a user who
widens the cancel zone to stop mis-selections can, at the same settings, cancel selections they were
aiming at. Not fixed: clamping the neutral zone to what the grid can spare would silently move a
slider the user set, and the right answer (clamp? warn? cap the slider at 100 %?) is a UI decision.
Pinned in `aNeutralWidthLargerThanTheCell_swallowsTheHorizontalSlots_reportedNotFixed`.

### A fix I wrote, measured, and reverted

The obvious reading of P2 is "make the neutral zone square in cell units", which is what I
implemented first — one shared `neutralHalfFraction` used by both the hit test and the renderer.
Then I measured it:

- at the shipped default (60 % / 60 %), it changes **0 of 361 201** sampled points — the two
  half-extents are already equal, so the rectangle was already square in cell units;
- with the two sliders disagreeing it changes 6.0 % of points, but in neither case makes a cell
  unreachable, so it fixes no defect and only makes the cancel region smaller than the user asked
  for;
- the renderer and the hit test already agreed **exactly** (`nw = cw * w` with a half-extent of
  `cw * w / 2`, same as `slotAt`) — so the "shared definition" half of the change fixed nothing at
  all.

It was reverted (`git checkout`), and neither the geometry nor the renderer changed in `a4b382b`.
The measurement is recorded because the instinct — "two places compute it, share it" — is normally
right, and the reason it was wrong here is specific: they were already the same expression.

---

## P4 — reported: `CLEAR_TASK` on an activity with its own task affinity

```kotlin
Intent(context, SubkeyAssignActivity::class.java)
    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
```

`SubkeyAssignActivity` declares `android:taskAffinity="tribixbite.cleverkeys.subkeyassign"`, so
`NEW_TASK` already gives it a task of its own — that part is deliberate and documented ("finishing
it returns there"). `CLEAR_TASK`, however, clears **the task being launched into**, and the comment
on `VoicePermissionActivity` in the same manifest shows the team already knows the finer point: an
activity that gets stopped (by a permission dialog) must not be `noHistory`. `CLEAR_TASK` has a
similar shape of risk — it is documented as "clear any existing task associated with the activity",
i.e. its own affinity group, not the host app's, which is why this is filed as *medium and
unverified* rather than a bug: the observable failure (returning to the app, or to the home
screen, after assigning a subkey) depends on how the host task is resolved on the device. It needs
a device check; on a keyboard that is started from an IME the surrounding task state is not
something a JVM test can model.

The narrow claim I can make from the code: `NEW_TASK` alone is sufficient for the documented
behaviour, so `CLEAR_TASK` is doing something the design does not ask for. Removing it is a
one-line change that should be confirmed on a device, not merged blind.

---

## Checked and found sound

- **`SubkeyPopoverSlots.isUsable`.** It rejects `Kind.Placeholder` and blank payloads, and I
  checked the thing it could plausibly get wrong: whether a *named* subkey (`tab`, `up`, `alt`,
  `action`) is silently dropped because `getString()` is its symbol rather than its name. It is
  not — every one of those is built with a real symbol (`tab` → `0xE00F`, `up` → `0xE005`), so the
  slot shows a glyph and the popover and the key agree. Measured across the shipped layouts: every
  populated subkey on a popover-eligible key has a non-blank payload. (`esc` is not a `KeyValue`
  name at all; it falls through to a String key reading "esc" and is offered as such, which matches
  what the key draws.)
- **`SubkeyPopoverRenderer`'s draw path.** `rect`, `arcRect`, both `Path`s, the `PathMeasure`, the
  interpolators and every `Paint` are fields; `draw` allocates nothing. The dwell border rebuilds
  its perimeter path only when the four edges change (`dwellKey` guard) and re-fills the segment
  each frame, which is the correct split. `label_paint` returning a shared, mutated `Paint` is safe
  because `drawText` saves and restores `textAlign` and sets `textSize`/`alpha` per call; nothing
  between the two calls reads those fields. Selection draws the active cell last, so the 1.3× scale
  is never clipped by a neighbour; `edgeShift` pulls an enlarged edge cell back inside the canvas.
- **State and dwell lifecycle.** `SubkeyPopoverState` mutates only through `track`, which returns
  "changed" so the caller restarts the timer and ticks haptics once per slot. Arming
  (`ARM_FRACTION` of the *smaller* cell side) is what stops a clamped grid from selecting on
  release-without-movement. Dwell ownership is single-writer: `Pointers` allocates `dwellWhat` from
  its `uniqueTimeoutWhat` counter and `removeMessages` before re-issuing; `onSubkeyPopoverDwell`
  clears `dwellWhat` before closing, and `closeSubkeyPopover` removes the message — so the
  release-in-neutral path cannot fire an edit screen for a stale slot. `finishSubkeyPopover` handles
  all four slot outcomes, including "popover closed by a second finger" (`state == null` → nothing
  typed), which is exactly the case that matters because the pointer keeps
  `FLAG_P_POPOVER_MODE` until release.
- **`SubkeyAssignExtras`.** A pure codec with injectable getters/putters, and `read` returns null for
  a missing or unknown key code, direction or mode so the activity closes rather than guessing —
  which it does, with a log line. Round trip is host-testable and already tested.
- **Label sizing parity.** `PopoverSlotLabel.sizeScale` comes from the *same*
  `SubLabelSizing.scaleFor` the keyboard uses, and the renderer multiplies the glyph size by it, so
  a cell's icon keeps its on-key proportions. The assign screen's badge uses `previewSp` with the
  same rule.

---

## What this pass did not do

- **Did not run the app.** No emulator or Gradle in the sandbox; CI is the only compile signal. P4
  is exactly the kind of thing that needs a device.
- **Did not change the selection rule (P1) or the slider range (P2).** Both are behaviour decisions
  with a UI consequence; both are pinned with measured numbers instead.
- **Did not audit `Pointers`' hold arbitration as a whole** (long-press vs repeat vs popover vs
  TrackPoint), only the popover's branch of it; it belongs with the gesture subsystem, not here.
- **Did not re-open any closed subsystem.** Waves 4–19 stay closed.
- **Did not add a Compose test harness** for `SubkeyAssignActivity`/`IntentEditorDialog`; both are
  read for validation and data-flow correctness only.

---

## Verification

- `a4b382b` — CI: **4910 run / 0 failed / 134 skipped / 440 classes**; Build APK success; Emulator
  smoke success.
- Delta: **+7 tests** (part 16's `58f213c` was 4903), same 440 classes.
- Every number above was computed, not estimated: the 14-of-27 name collisions and 5 divergences
  (script over `ActionType.kt` and `CommandRegistry.kt`), the per-cell penetration radii and the
  87.8 % area agreement (dense sweep against the drawn squares), the 0-of-361 201 no-op measurement
  that caused the revert, and the layout-wide subkey scan for `isUsable`.
- One figure did NOT survive re-checking and is corrected above in place: the first draft of the P1
  table read the penetration sweep backwards. Same error class part 15 caught twice and part 16
  caught once — the check is now "recompute the stated figure from a fresh script before shipping
  the sentence".
