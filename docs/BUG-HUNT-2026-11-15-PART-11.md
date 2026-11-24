# Bug hunt, part 11 — `swipe/geometric/`: turn angles in the wrong frame

Date: 2026-11-15 (work committed 2026-11-21)
Branch: `arena/cbf26556-keyboard`
Base: `9d2beeb` (part 10, `ml/`)
Scope: `src/main/kotlin/tribixbite/cleverkeys/swipe/geometric/` — `GesturePreprocessor` (365 lines),
`LayoutGeometry`, `PathScorer`, `TemplateIndex` (317), `TemplateCache` (243), `CandidateRanker`.

The first pass over the geometric decoder. It found one real defect with a clean fix, and then a
second — the same defect in a second place — that **CI proved must not be fixed the same way**.
Both are below. The second one is the more useful part of this document.

---

## Summary

| # | Finding | Severity | State |
|---|---------|----------|-------|
| G1 | `countReversals` measured its turn angle in raw normalized (u, v); normalized u/v is only a physical frame when the key area is square | medium (confidence signal only) | **fixed** |
| G2 | `detectCorners` has the **same** frame defect — but fixing it moved live ranking, so it was reverted | medium | **reverted, reported** |
| G3 | `templateCornerIndices` bounded its loop by `config.resamplePoints` rather than the buffer length, so it threw AIOOBE for any caller passing a shorter polyline | low (latent — the production path always matches) | **fixed** |
| G4 | The pin test that requires the gesture and template corner detectors to agree could not catch a frame divergence, because its fixtures are frame-invariant | medium (process) | **fixed** (documented + new test) |
| G5 | No test covered the aspect-dependence of any turn-angle rule | medium (process) | **fixed** |
| G6 | `TemplateIndex`'s bbox margin already handles aspect correctly — checked as a control | — | sound |

Production files changed: 2 (`GesturePreprocessor.kt`, `PathScorer.kt`). New file:
`GeoPhysicalFrameTurnTest.kt` (5 tests, never skips).

---

## The shared defect

`LayoutGeometry` normalizes everything into (u, v) ∈ [0,1]². That is **not** a physical frame: it
is a unit square only when the key area is square. The class carries the real anisotropy in
[`aspect`](src/main/kotlin/tribixbite/cleverkeys/swipe/geometric/LayoutGeometry.kt) =
`keyAreaWidthPx / keyAreaHeightPx`, with the documented convention `physical y = Δv / aspect`:

```kotlin
fun dKw(u1, v1, u2, v2): Float {
    val du = u1 - u2
    val dv = (v1 - v2) / aspect      // ← Δv divided by the aspect
    return sqrt(du * du + dv * dv) / meanKeyWidth
}
```

Two functions in `GesturePreprocessor` obey that convention. Three did not:

| function | frame used before | feeds |
|----------|-------------------|-------|
| `nonCornerWobbleDeg` | physical (`Δv / aspect`) | location-distance slack |
| `LayoutGeometry.dKw` | physical | everything |
| `countReversals` | **raw (u, v)** | confidence temperature |
| `detectCorners` | **raw (u, v)** | corner-anchor bonus |
| `PathScorer.templateCornerIndices` | **raw (u, v)** | corner-anchor bonus |

The last three take an `acos` over consecutive segment vectors computed from raw u/v deltas. Because
scaling one axis is not a rotation, the measured angle depends on the aspect.

### How much

Both thresholds are compared against that angle, so the error is directly a threshold error:

| physical turn | measured at aspect 1.0 | 1.5 | 2.0 | 2.5 | 3.0 |
|---|---|---|---|---|---|
| 55° (corner threshold) | 55.0° | 51.7° | 47.0° | 42.4° | 38.2° |
| 90° | 90.0° | 75.4° | 64.9° | 56.8° | 50.3° |
| 150° (reversal threshold) | 150.0° | 136.5° | 124.3° | 113.5° | 104.0° |

At the default `aspect = 2.0` that the test fixtures themselves use:

- a physical **160° hairpin** measured **141°** and was **not counted** as a reversal;
- a physical **55° corner** measured **47°** and was **not detected** as a corner.

The defaults are `cornerAngleThresholdDeg = 55` and `reversalAngleThresholdDeg = 150`
(`GeometricEngineConfig`). So on an ordinary keyboard, corners were under-detected and reversals
were under-counted — silently, with no error and nothing in any log.

---

## G1 — `countReversals`: fixed

**Fix:** divide `Δv` by `layout.aspect`, exactly as `nonCornerWobbleDeg` and `dKw` already did. The
function now takes the layout, so it cannot measure in a frame the caller did not intend.

**Why this one was safe to change, and worth stating precisely.** `reversalCount` reaches ranking
only through `CandidateRanker.effectiveTemperature`:

```kotlin
val slope = config.reversalConfidenceTempSlope
if (slope <= 0f || reversalCount <= 0) return config.softmaxTemperature
```

and `reversalConfidenceTempSlope` **defaults to `0f`** — documented as "at the default slope `0` the
multiplier is exactly 1 (bit-identical no-op)". With the default config, therefore, `reversalCount`
cannot move a single score, and the change provably cannot move an accuracy floor. For anyone who
opts into the knob, the old value was simply wrong on every non-square key area.

That is not a guess: the accuracy harnesses (`GeoAccuracyQwertyEnTest` and siblings) run in CI
against the shipped language packs and passed, with `GeoAblationTest` still running its full grid.

## G2 — `detectCorners`: reverted, and reported

The same frame fix applied to `detectCorners` (and to `PathScorer.templateCornerIndices`, which a
pin test requires to produce identical indices) **failed CI**:

```
GeoAblationTest: cornerAnchorBonus_keeping_doesNotRegress
  removing the corner-anchor bonus must not improve accuracy
  (it is a valid, bounded tie-breaker, not a bias)
```

That assertion is `withBonus >= noBonus - 1e-9`. It failed, meaning that after re-framing the
corner detectors, **turning the bonus off scored better than leaving it on** — the bonus had become
a net bias rather than a tie-breaker.

This is the interesting part. The fix was *more correct* by the reasoning above and still wrong to
ship, because the corner channel is not a measurement, it is a **calibrated score term**. Its two
sides (gesture corners, template corners) are compared for index proximity and the ratio becomes a
bounded `+cornerAnchorBonus` (0.25). Making detection physically correct on both sides changes which
corners exist, and therefore changes that ratio for real decodes — in a direction nobody had
calibrated. "The angle is now right" does not imply "the bonus is now better".

**Decision:** reverted to raw u/v, with the finding written into the KDoc where the code is, and the
reason — that it needs a measured A/B against the accuracy floors rather than a plausible argument.
`templateCornerIndices` stays in the same frame, because the pin test requires the two to agree.

This is worth carrying forward as a rule for this subsystem: **a frame bug in a measurement can be
fixed directly; a frame bug in a score term needs a grid measurement first.**

## G3 — `templateCornerIndices` threw AIOOBE for short buffers

```kotlin
private val n = config.resamplePoints          // 32
...
for (i in 1 until n - 1) {                     // ← loop bounded by the CONFIG
    val bx = t[2 * (i + 1)] - t[2 * i]         // ← but indexes the BUFFER
```

On the production path `t` is always a template this scorer resampled itself, so `t.size == 2n` and
the two agree. For any other caller it reads past the end: the new test passes a 3-point polyline
and CI reported `ArrayIndexOutOfBoundsException: Index 6 out of bounds for length 6`.

**Fix:** bound the loop by `t.size / 2`. This is not hypothetical tidying — it is what surfaced
G2's divergence, because the pin test compares this function against
`GesturePreprocessor.detectCorners` on an arbitrary polyline.

## G4 — the pin test could not have caught the divergence

`GeoDecoderCoreTest` asserts:

> "template/gesture corner detectors must agree on the same polyline"

which is exactly the invariant that would catch one side being re-framed and not the other. It
passed before the fix and after it — and it also passed while the two were framed identically but
*wrong*. Its fixtures are a purely horizontal back-track and an axis-aligned L:

```
backtrack = [0.1 0.5, 0.3 0.5, 0.5 0.5, 0.35 0.5, 0.2 0.5]   // all y equal
ell       = [0.1 0.2, 0.5 0.2, 0.5 0.6]                      // axis-aligned
```

No aspect can change the angle of a purely horizontal or purely axis-aligned turn. **Fix:** the
comment now says so explicitly, and the aspect-sensitive coverage moved into the new test class.

## G5 — no test described aspect-dependence

Before this pass, no test anywhere asserted that a turn angle is independent of the key area's
shape. `GeoPhysicalFrameTurnTest` (5 tests) now does, at aspects 1.0 / 1.5 / 2.0 / 2.5 / 3.0, and
includes a **reproduction of the old rule** so the finding stays pinned even if production code
changes shape again:

| test | asserts |
|------|---------|
| a physical hairpin counts as a reversal at every aspect | 160° → 1 reversal at 1.0…3.0 |
| the raw-frame rule this replaced lost that reversal | old rule → 0, new rule → 1, at aspect 2.0 |
| a physical turn below the threshold is never a reversal | 60° → 0 reversals at every aspect |
| a square key area is unaffected by the fix | new rule == old rule at aspect 1.0, 30…160° |
| `countReversals` is wired from the resampled polyline | `process()` populates it from `g.points` with the same layout |

It needs no language packs and never skips.

## G6 — `TemplateIndex` bbox margin, as a control

The one place in this package that already had to think about aspect gets it right, and the comment
shows someone reasoned it through:

```kotlin
val marginU = layout.meanKeyWidth * 0.5f
val marginV = marginU * layout.aspect   // "the same PHYSICAL distance mapped back"
```

with a note that this keeps the coarse bbox filter isotropic in key-width units, "matching `d_kw`".
It also documents why the margin is needed at all (single-key and axis-aligned boxes would be
degenerate, and the u8 quantization step would clip a hairline boundary). Sound.

## Checked and found sound

- **`resampleArcLength`.** Arc-length uniform, first/last points preserved, and degenerate input
  (0 points, 1 point, all-coincident) returns `target` finite points rather than NaN — the
  `total <= 0f` branch is the guard the class KDoc promises.
- **`pointAlongPath`.** Clamps at the far endpoint for a path shorter than the inset; a zero-length
  segment advances the anchor instead of looping. Returns the endpoint unchanged at `insetKw <= 0`,
  so the endpoint-inset dual anchors are a true no-op at the default… which is **0.30**, not 0 —
  the KDoc's "at `endpointInsetKw == 0` (default)" is stale on the default value, though the
  behaviour it describes is right. Recorded here rather than "fixed", since it is one word.
- **`boundingBox` / `polylineLengthKw`.** Straightforward, no empty-array hazard (callers always
  have ≥ 1 point and the loop starts at 1).
- **`LayoutGeometry.nearestKeys`.** Clamps `k` to the key count, deterministic tie-break by lower
  key id (NFR-4), and allocates only the two result arrays.
- **`LayoutGeometry.init`.** Rejects non-positive / non-finite `aspect` and `meanKeyWidth`, which is
  the guard that keeps a zero-height race from poisoning every `dKw` into NaN.
- **`PathScorer` scoring composition.** All channel terms are ≤ 0 and the sanity penalties are
  bounded; the corner bonus is capped and normalized by the template corner count.
- **`TemplateIndex` bucket indexing.** `ByteArray` key ids with an explicit 1..256 validation, and
  the collapsed-length scratch overflow was already fixed (plain `prevId` local, with a comment
  naming the 65/66-key bug it replaced).

## What this pass did not do

- **Did not re-frame `detectCorners` / `templateCornerIndices`** (G2). Doing it properly needs a
  measured A/B: change the frame, run `GeoAccuracyQwertyEnTest` + the other five accuracy harnesses
  and `GeoAblationTest` at the full grid (`-PgeoFull`), compare top-1/3/5 per tier, and only then
  decide. That is a measurement session, not an edit, and this sandbox cannot run the harnesses.
- **Did not read `GeometricEngineAdapter.kt` (773 lines) or `GeometricEngineConfig.kt` (383) in
  full.** I read the constants this pass depends on (`resamplePoints`, both angle thresholds,
  `endpointInsetKw`, `cornerAnchorBonus`, `reversalConfidenceTempSlope`, `effectiveExtremityNeighbors`)
  and the validation block. The adapter is the next obvious target in this subsystem.
- **Did not touch `CandidatePruner`, `CandidateRanker`, `PathScorer`'s channel maths,
  `TemplateGenerator`, `GeometricDictionary` or `GeometricUserWordMerge`.**
- **Did not change any threshold.** G1 is a frame correction, not a retune; `cornerAngleThresholdDeg`
  and `reversalAngleThresholdDeg` are untouched, so the fix does not double-correct.
- **Did not correct the stale `endpointInsetKw` default in that one KDoc line** — noted above
  instead, since it is prose rather than behaviour.
- **Did not verify on a device or with a real corpus.** CI runs the synthetic accuracy grids; the
  real-corpus replays (`GeoRealCorpusReplayTest`, `GeoFutoTrain100kReplayTest`, …) are skipped in
  this fork because the FUTO corpora are not vendored.

## CI

| run | head | suite | APK | smoke |
|-----|------|-------|-----|-------|
| 1 | `8104a33` | **failed** — 2 of 4879 (`GeoAblationTest`, `GeoPhysicalFrameTurnTest`) | pass | — |
| 2 | `3b1e29c` | **`45044298014` green** — 4879 run, 0 failed, 134 skipped, 437 classes | `45044292972` pass | `45044292991` pass |

Part 10 ended at 4874 run / 436 classes; this pass adds **5 tests and 1 class** (4879 / 437).
Skipped stays at 134, so the skip ratchet is untouched. `GeoAblationTest` ran its full grid in
10.2 s on the green run — it was not among the 21 skipped classes, so the accuracy assertions this
revert protects are genuinely being executed.

Run 1 is the most useful build in this whole series: the ablation failure is what distinguished a
*measurement* bug from a *score-calibration* bug, and the AIOOBE is what proved the pin test's
fixtures had never exercised the aspect at all. Both were caught before the change could reach a
user.
