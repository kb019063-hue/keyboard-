# Bug hunt, part 15 — `swipe/geometric/` part 4: the shape channel is measured in the wrong frame

Date: 2026-11-15 (work committed 2026-11-22)
Branch: `arena/cbf26556-keyboard`
Base: `b10ed37` (part 14, geometric memory accounting)
Scope: `PathScorer`'s per-channel distance maths — `shapeDistance` (line 138), `longestSide`
(165), `locationDistance` (214) including both tunnel paths, `directionPenalty` (292) — plus
`LayoutGeometry`'s `dKw`/`aspect` convention and the shape-magnitude calibration in
`GeometricEngineConfig` (`shapeSigma`, `minShapeScaleKw`, `shortWordShapeFloorKw`).

This pass completes the read surface that parts 11, 13 and 14 left open, and it found the most
consequential thing in the geometric subsystem since part 11: **the shape channel measures
vertical deviation in the wrong frame**, so a vertical word is charged a fraction of what a
horizontal word is charged for the same physical finger error.

It is reported, not fixed, for a reason this series already established. Both halves matter.

---

## Summary

| # | Finding | Severity | State |
|---|---------|----------|-------|
| S1 | `shapeDistance` derives its normalization scale from the **raw** (u, v) bbox, so a vertical stroke's scale is overstated by `aspect`; the shape channel is `aspect²` asymmetric in distance and `aspect⁴` in score | **high** (calibrated score channel, every vertical word) | **reported** — test pins it |
| S2 | No ablation or accuracy harness covers the shape channel's frame, so the asymmetry was invisible to the suite | medium (process) | **fixed** (new test) |
| S3 | `locationDistance` is already symmetric — it is the convention `shapeDistance` departs from | — | sound |
| S4 | `locationDistance`'s two tunnel paths (legacy `locationTunnelHalfWidth`, OQ-10 `orderingSlackTunnelW`) and their clamping | — | sound |
| S5 | `directionPenalty`'s zero-length-segment guard and cap | — | sound |
| S6 | `d²/2σ²` with a `σ_s = 0.30` calibrated to horizontal behaviour | — | context for S1 |

Production files changed: **none** — this is a reported finding with a characterization test.
New file: `GeoShapeFrameTest.kt` (3 tests, never skips).

---

## S1 — the shape channel's frame

Every channel in the geometric scorer measures distance through
[`LayoutGeometry.dKw`](src/main/kotlin/tribixbite/cleverkeys/swipe/geometric/LayoutGeometry.kt),
which implements the documented convention:

```kotlin
fun dKw(u1, v1, u2, v2): Float {
    val du = u1 - u2
    val dv = (v1 - v2) / aspect      // ← physical y = Δv / aspect
    return sqrt(du * du + dv * dv) / meanKeyWidth
}
```

`shapeDistance` cannot use `dKw` — it compares *shape-normalized* coordinates, not absolute
ones — so it derives its own scale per path:

```kotlin
val clamp = config.minShapeScaleKw * layout.meanKeyWidth
val gScale = max(longestSide(g, gcx, gcy), clamp)
val tScale = max(longestSide(t, tcx, tcy), clamp)
// then (u - cu)/scale, (v - cv)/scale — no aspect anywhere
```

`longestSide` takes the bbox extent in **raw u and raw v** and returns the larger. But a
normalized `v` extent of `δ` is a physical extent of `δ/aspect`, while a `u` extent of `δ` is a
physical extent of `δ`. So for a path whose long axis is **vertical**, the raw bbox extent
overstates the physical one by exactly `aspect`, the scale is `aspect` too large, and both
normalized coordinates end up `aspect` too small.

### How much

Measured with the real formula (`resamplePoints = 32`, `shapeSigma = 0.30`, `kw = 0.10`): one
physical geometry — a 4-key-pitch stroke with a half-key-pitch perpendicular bulge — evaluated
horizontally and rotated 90°, which a frame-correct metric must score identically:

| aspect | d_shape horizontal | d_shape vertical | distance ratio | **score ratio** |
|---|---|---|---|---|
| 1.0 | 0.03532 | 0.03532 | 1.00× | 1.00× |
| 1.5 | 0.05298 | 0.02355 | 2.25× | 5.06× |
| **2.0** | **0.07064** | **0.01766** | **4.00×** | **16.00×** |
| 2.5 | 0.08831 | 0.01413 | 6.25× | 39.06× |
| 3.0 | 0.10597 | 0.01177 | 9.00× | 81.00× |

Both columns move, and that is the point: in the raw frame neither orientation is measured in
physical units. The horizontal word's `d_shape` *grows* with the aspect (its perpendicular bulge
is a raw-`v` extent, so it is counted `aspect` times larger than it physically is), while the
vertical word's shrinks (its long axis is raw-`v`, so the inflated scale divides the deviation
down). The ratio between them is exactly `aspect²`, and the **score** ratio is `aspect⁴`, because
the channel enters as `−w·d²/2σ_s²`. At the default `aspect = 2.0` the shape cost of the
identical deviation is:

```
horizontal:  -0.02773
vertical:    -0.00173     ← 1/16th
```

`σ_s = 0.30` is calibrated against the horizontal behaviour, so in practice **a vertical word is
effectively exempt from the shape channel**. Its ranking is then carried almost entirely by the
location channel and the `−λ_f·ln(1+rank)` prior.

Note the horizontal word is not "correct" either — its cost rises with the aspect for the same
physical bulge. Fixing the frame would move **both** orientations, which is why this is a
selection change and not a one-sided correction: at `aspect = 2.0` the horizontal word would keep
roughly its aspect-1.0 cost (0.03532 instead of 0.07064, i.e. half) and the vertical word would
rise to meet it. A σ re-fit after that change is unavoidable, and is step 3 of the plan below.

### Why this is a real class of word, not a curiosity

Vertical strokes are ordinary on a QWERTY-family layout — any word whose keys run down a column
rather than across a row. The 4-key-pitch case above is a mid-length word; the clamp
(`minShapeScaleKw·kw = 0.15` normalized-u) does not rescue it, because a vertical 2-key stroke
already has a raw `v` extent of `2·kw·aspect = 0.40` at aspect 2.0 — well above the clamp. The
clamp only protects genuinely short spans, and it protects them in both orientations.

### Why it is reported and not fixed

This is exactly the distinction part 11 established with `detectCorners` (G2):

> a frame bug in a *measurement* can be fixed directly (`countReversals`, safe because
> `reversalConfidenceTempSlope = 0f` makes it a no-op); a frame bug in a *calibrated score term*
> needs a full-grid measurement first — "the angle is now right" ≠ "the bonus is now better".

`shapeDistance` is unambiguously a calibrated score term. Correcting the frame would change the
absolute shape cost of **every** word in **every** decode, in a direction that is right in
principle and unmeasured in practice — it would make vertical words *stricter*, which is a
selection change, and `σ_s`, `minShapeScaleKw` and `shortWordShapeFloorKw` were all fitted
against the current numbers. As in part 11, the correction is a measurement session:

1. change `longestSide` to take the aspect (or feed it physical coordinates), so both the bbox
   and the scale are isotropic;
2. run `GeoAccuracyQwertyEnTest` + the other five accuracy harnesses, and `GeoAblationTest` at
   the full grid (`-PgeoFull`);
3. compare top-1/3/5 per tier, and re-fit `shapeSigma` **after** the frame fix, not before —
   otherwise the fix is measured against a σ tuned for the bug;
4. only then decide.

That cannot run in this sandbox (no Gradle, no kotlinc), and doing step 1 without steps 2–4 would
ship an unmeasured selection change into the one channel that decides between same-finger words.

**One thing worth noting for whoever does run it:** the fix is *not* a re-tune of σ_s. If the
asymmetry is removed and accuracy does not move, that is a strong signal that the shape channel
is doing less work than its σ implies — which is itself worth knowing, and is a second reason the
measurement is the deliverable rather than the edit.

## S2 — the suite could not see it

Three reasons, each individually sufficient:

- **No ablation touches the frame.** `GeoAblationTest` has two assertions — the frequency prior
  and the corner-anchor bonus — and `shortWordShapeFade_keeping_doesNotRegress`, which toggles
  `shortWordShapeFloorKw`. None varies the shape scale's frame.
- **The two `shapeDistance` tests are in a class that is skipped here.**
  `GeoDecoderCoreTest` (which holds `pathScorer_shapeDistance_isScaleAndTranslationInvariant` and
  `…shapeScaleClamp_keepsDegenerateSpansFinite`) calls
  `UpstreamArtefacts.require(LANGPACK_DIR)` in `@Before`, and this fork does not vendor the
  language packs — so all 3 of its shape assertions are skipped wholesale, and its own KDoc says
  so: *"failed — and not silently green"*.
- **Both surviving assertions are frame-invariant by construction.** Scale+translation
  invariance is required by SHARK2 Eq. 1 and holds in *any* isotropic-or-not frame, because both
  paths are transformed identically. The clamp test only asserts `isFinite`. Neither can see an
  asymmetry between two *different* paths.

`GeoShapeFrameTest` fills that gap. It asserts:

- the distance ratio is `aspect²` (`isWithin(0.01f)`), so the asymmetry is pinned as behaviour;
- the **score** ratio is `aspect⁴` — the assertion that caught my own first draft, which had
  claimed `1/aspect²` for the cost and failed CI at `expected : 0.25 but was : 0.0625`;
- `aspect = 1.0` gives a ratio of exactly 1, isolating the aspect as the cause;
- the **location** channel on the identical rotated pair is symmetric (`~1.00×`), which is the
  contrast that makes `shapeDistance` the outlier rather than a convention.

## S3–S5 — checked and found sound

- **`locationDistance` is already correct.** Both branches go through `dKw`, so the rotated pair
  costs the same (asserted in the new test at ≤2%). The strict path is bit-identical when
  `locationTunnelHalfWidth = 0` and `orderingSlackTunnelW = 0` (both defaults), which is why the
  test can exercise it without setting up a tunnel.
- **The two tunnel paths.** The legacy tunnel clamps `[0, n−1]`; the OQ-10 slack path clamps to
  `[1, last−1]` and applies **only** to interior indices, leaving both endpoints strictly
  matched — matching its KDoc's claim that end-weighted fidelity is never relaxed. The slack is
  gated three ways (config width > 0, template long enough, gesture sloppy enough), each with the
  right default to be inert.
- **`directionPenalty`.** Zero-length segments are skipped rather than producing `atan2(0,0)` or
  a division by zero, so the penalty stays finite on short/degenerate templates; it is capped by
  `directionPenaltyCap` and weighted, and it is default-off at 0.30/0.5 only when the weight is
  non-zero.
- **`buildAlpha`.** `require(n >= 2)`, and the tent weights are normalized to sum 1, so
  `d_loc` is a weighted *mean* in kw units rather than a sum that grows with N.

---

## What this pass did not do

- **Did not change `longestSide` / `shapeDistance`.** Reasons and the measurement plan are
  above. This is the same call as part 11's G2, and for the same reason.
- **Did not re-fit `shapeSigma`.** It is calibrated for the current (asymmetric) behaviour by
  construction; re-fitting before the frame fix would bake the bug in.
- **Did not test on a device or with a real corpus.** The six accuracy harnesses are the
  measurement; this sandbox cannot run them.
- **Did not examine `TemplateGenerator`'s variant-1 loop geometry against the same frame
  question.** The loop is a square in raw u/v, so its `v` side is physically `aspect` times taller
  than its `u` side. That is a *different* consequence of the same missing convention (the loop
  is drawn as a physical square per its own KDoc, but is emitted as a raw square), and it belongs
  with the same measurement session rather than being changed separately. Logged here so it is
  not lost.
- **Did not quantify how many shipped lexicon words run vertically.** That is a corpus query
  (project each word, compare the bbox height against its width), and it would size the blast
  radius of S1. It needs the layouts + dictionaries, which the accuracy harnesses already load.

---

## CI

| run | head | suite | APK | smoke |
|-----|------|-------|-----|-------|
| 1 | `f95f9d4` | **failed** — 1 of 4888: `GeoShapeFrameTest#a perpendicular deviation costs aspect-squared less on a vertical stroke`, `expected : 0.25 but was : 0.0625` | pass | — |
| 2 | `bba3e50` (**final**) | **green** — 4891 run, 0 failed, 134 skipped, 440 classes | pass | pass |

Part 14 ended at 4888 run / 439 classes; this pass adds **3 tests and 1 class** (4891 / 440).
Skipped stays at **134**, so the skip ratchet is untouched.

Run 1 failed on my own arithmetic again, and usefully: I had written that the score ratio was
`1/aspect²` when the term is `d²/2σ²`, so the distance ratio `aspect²` squares into `aspect⁴`.
The failure message (`expected : 0.25 but was : 0.0625`) is the finding stated in one line — the
vertical word's shape cost is 6% of the horizontal one's, not 25%. That made S1 **more** severe
than the draft said, which is the second time in two passes that a test written to describe
behaviour corrected the description (part 14's `10` vs `9`).
