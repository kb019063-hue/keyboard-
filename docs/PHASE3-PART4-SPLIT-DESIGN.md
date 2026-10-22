# Phase 3, part 4 — split keyboard

The last of the four gaps, and the one whose correctness depends on a single invariant: **drawing
and hit-testing must agree about where every key is**. A split whose visuals and taps disagree is a
keyboard that mis-types, which is worse than no split at all.

## The discovery that shaped it

CleverKeys does not have one geometry path; it had three independent x-accumulations that happened
to agree: `KeyboardGeometry.computeKeyRects` (accessibility rects, swipe-model centres, continuous
space), `KeyboardGeometry.keyAt` (the tap/swipe hit-test), and `Keyboard2View.onDraw` (the drawn
keys). Adding a split to any one of them would have broken the agreement; adding it to all three
separately would have created three chances to get it wrong.

So part 4 introduces the walk they share: `KeyboardGeometry.rowSpans(row, params, startX)` returns
every key's horizontal span in a row, in order, with split mode applied. All three consumers now
take their x from it. With `splitGap = 0` and `splitScale = 1` the helper reproduces the old
accumulation in the same float operation order, so splitting off is a no-op rather than a rounding
change — and the existing `KeyboardGeometryTest` still passes untouched.

## The gap cannot simply be inserted

Every row's keys sum to the full content width, so pushing the right half over by the gap would run
it off the screen edge. The halves therefore shrink as well: `SplitConfig.keyWidthScale` multiplies
the key width by `(content - gap) / content`, so left half + gap + right half fills exactly the
width the unsplit keyboard filled. The gap opens at the row midpoint, decided in row-width *units*
(`isRightHalf`) rather than pixels, so the answer is independent of the scale the compensation
applies. `SplitConfig` is pure Kotlin with no `android.*` import, the same arrangement as
`OneHandedConfig` and `FloatingConfig`, and its maths is integer where pixels are involved.

The content width used for the scale is the same one `_keyWidth` itself is derived from
(`width - marginLeft - marginRight`), so the two can never drift.

## What a touch in the gap does

It falls to the key on its right — exactly as the existing inter-key gaps already fall to the key
after them (`keyAt`'s GAP FIX comment). `SplitKeyboardGeometryTest` pins that, plus the midpoint,
the exact-fill property, and the invariant that matters most: for every rect, `keyAt` at its centre
returns that rect's key, split on.

## Surface and verification

Settings → Appearance: a **Split Keyboard** switch with a gap slider (4–25%, step 1) shown only
while on; the halves' width needs no control because it follows from the gap. A toolbar command
`split_toggle` (⇤⇥, two UTF-16 units) toggles it without leaving the keyboard. Toggling writes the
same preference the switch writes; the service answers the change with a `requestLayout`, because
split mode holds no service-side state — the next draw and the next hit-test both read the geometry
params fresh.

Command catalogue 244 → 245; search index 175 → 177 entries under `appearance`; 69
`SETTINGS_DEFAULTS` keys, none writerless under `SettingsSurfaceDriftTest`'s F-8 rules; translation
drift unaffected.

Split composes with the other modes rather than fighting them: it is pure geometry inside the
keyboard, so one-handed scaling (a transform on the wrapper) and floating (the window's own layout
params) apply around it unchanged. A split floating keyboard is narrow but coherent; that is the
user's choice to make, not the code's.

What no emulator run can prove is how a split keyboard *feels* at the thumbs, and whether the
shrunk halves crowd their labels at the smallest gap on a small screen. Both are device checks, and
both are worth thirty seconds on the phone: split on, gap to minimum, type a sentence; gap to
maximum, type another.
