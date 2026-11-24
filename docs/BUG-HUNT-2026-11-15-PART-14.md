# Bug hunt, part 14 — `swipe/geometric/` part 3: the memory accounting, and a false finding I threw away

Date: 2026-11-15 (work committed 2026-11-22)
Branch: `arena/cbf26556-keyboard`
Base: `97cbef7` (part 13, `swipe/geometric/` cache/collector/merge)
Scope: `CandidateRanker` in full (136 lines — the tie-break both of part 13's findings turn on),
`TemplateIndex`'s quantization and `estimatedBytes`, `WordTemplate`'s packing, `TemplateCache`'s
`memoBytes`/`MEMO_ENTRY_OVERHEAD_BYTES`, and a repo-wide locale sweep that was prompted by
`CandidateRanker.rank`'s `lowercase()` dedupe key.

This pass found a documentation defect with a clean, honest fix and no behaviour change, and it
**killed a much louder finding** on the way. Both halves are worth keeping: the second one is the
more useful record, because the loud finding was one grep away from being reported as fact.

---

## Summary

| # | Finding | Severity | State |
|---|---------|----------|-------|
| H1 | `TemplateCache`'s KDoc claimed "≈ 2.3 MB" per index; the formula it derives from answers ~1.2 MiB Tier-A at the shipped size | low (docs), medium (process — the number gated a stated NFR) | **fixed** |
| H2 | Neither existing memory assertion could catch H1 — one is in a fork-skipped class, the other only checks a total is *under* the bar | medium (process) | **fixed** (new runnable test) |
| H3 | "98k × 128 B ≈ 25 MB" for eager materialization conflates a per-*variant* payload with a per-*word* one | low (docs) | **fixed** |
| H4 | `MEMO_ENTRY_OVERHEAD_BYTES = 112` is conservative, not inflated | — | sound |
| H5 | 185 `lowercase()` call sites look like a default-locale hazard and are not — `lowercase()` is Kotlin's locale-invariant form | — | **false finding, rejected** |
| H6 | `CandidateRanker`'s ordering, dedupe, and `softmaxTo1000` numerics | — | sound |
| H7 | `TemplateIndex`'s u8/u16 quantization round-trips symmetrically through its accessors | — | sound |

The only production file changed is `TemplateCache.kt`, and only in comments. New file:
`GeoMemoryAccountingTest.kt` (3 tests, never skips).

---

## H1 — the memory claim did not survive its own arithmetic

`TemplateCache`'s class KDoc stated:

> Eager full materialization (98k × 128 B + overhead ≈ 25 MB) is explicitly rejected; the memo
> keeps the per-index footprint at **≈ 2.3 MB** (NFR-2).

`TemplateIndex.estimatedBytes` is the function that figure is supposed to come from. Reproducing it
at the shipped size:

| component | bytes | MiB |
|---|---|---|
| `firstKeyId` + `lastKeyId` + `collapsedLen` (3 × ByteArray) | 294,420 | 0.281 |
| `idealPathLenQ` (ShortArray) | 196,280 | 0.187 |
| `bboxQ` (4 × ByteArray) | 392,560 | 0.374 |
| `bucketEntries` (4 B × typeable) | 386,000 | 0.368 |
| `bucketOffsets` ((keyCount²+1) × 4) | ~3,140 | 0.003 |
| 8 array headers | 128 | — |
| **total** | **1,272,676** | **1.214** |

The word count is not an assumption: the CKDT v2 header of the shipped `en_enhanced.bin` carries
**98,140** at byte offset 12. So the doc figure was roughly **double** the formula's answer, and it
was wrong in the reassuring direction — claiming more memory used than the engine actually uses.

The combined steady-state figure the KDoc was reaching for is defensible, and now written down from
the parts:

```
~1.2 MiB  Tier-A   (TemplateIndex.estimatedBytes, above)
~1.3 MiB  Tier-B   (4096 entries at 132-264 B payload + 112 B documented overhead each)
≈2.5 MiB  combined — exactly the enforced NFR-2 bar
```

Note the near-exactness: the memo capacity and the bar were evidently chosen together, and both are
sized by S. `MEMO_ENTRY_OVERHEAD_BYTES` is the only soft term, so the worst-case total is slightly
above 2.5 MiB rather than below it. That is a **spec-vs-practice** observation, not a code bug, and
it is the reason the enforced test's `< 2.5` would fail on a fully-populated memo *if* every entry
cost the full overhead. It passes in practice because the overhead is an upper bound.

## H2 — why a doubled doc figure survived: nothing executable checked it

Two memory assertions exist. Neither can fail because of a wrong number in prose:

- `CandidatePrunerTest.memory_tierAIndex_underNfr2Budget_at98k` and
  `memory_fullCacheWithFilledMemo_underNfr2Budget` — the second is a genuinely good test (it fills the
  memo to capacity so the estimate is the real steady state). **But `CandidatePrunerTest` is skipped
  wholesale in this fork** (absent upstream artefacts), so neither runs here at all.
- `GeoBenchmarkTest.memory_indexPlusFullMemo_underNfr2_andRuntimeSmoke` — this one *does* run, and it
  fills the memo the same way. It asserts `total < 2.5`. That passes whether the doc says 2.3 or 1.2,
  because it only has a ceiling.

So the situation was: an enforced budget, a function that computes the number, a doc that quotes a
different number, and no assertion connecting them. H1's fix is accompanied by
`GeoMemoryAccountingTest`, which is **fixture-free arithmetic** — it pins the per-word byte cost
(9 B), the per-typeable-word cost (4 B), the key-count insensitivity of the headline figure, the
per-variant payload (2N shorts = 128 B at N=32), and the fact that the old 2.3 figure exceeds the
formula's answer by more than 0.8 MiB. A future added packed array or a changed `resamplePoints`
moves a test number instead of silently invalidating prose.

### The test caught me, which is the point

The first version of that test asserted the per-word cost was `10` bytes. Its own addends
(`3 + 2 + 4`) sum to **9**. CI failed within four minutes:

```
GeoMemoryAccountingTest#tierA per-word structural cost is 10 bytes plus one int per typeable word:
  expected: 10 but was : 9
```

I had written "10 B/word" into the `TemplateCache` KDoc *and* into the commit message before the
test existed. A prose-only fix would have shipped a wrong number in the same pass that corrected a
wrong number — which is the whole argument for asserting arithmetic rather than describing it. Both
the KDoc and the commit were corrected.

## H3 — the eager-materialization figure mixed up its unit

> Eager full materialization (98k × 128 B + overhead ≈ 25 MB)

`WordTemplate.points` is `variantCount × 2N` shorts. At the default `resamplePoints = 32`, `2N` shorts
is **128 B per variant**. So:

- one variant per word → 98,140 × 128 B = **12 MiB**
- two variants (a doubled letter, which the generator emits for every word with a repeated key) →
  **24 MiB**

"128 B" is right about the *unit* and 98k×128 is right about the *arithmetic*; what was wrong is the
"≈ 25 MB", which is neither 12 nor a clean multiple of 12 — it looks like the 24 MiB number rounded up
and mislabelled. Corrected, and the `memoBytes` KDoc now says "per variant" explicitly.

## H5 — the finding I threw away

`CandidateRanker.rank` dedupes with `c.word.lowercase()`. With no locale argument, that *looks* like
the classic default-locale hazard (`I`/`i` in a Turkish locale, where `"I".lowercase()` is `ı` and two
apparently-identical keys stop matching). A grep found **185** `lowercase()` call sites across the
main sources and **0** uses of `Locale.setDefault`, which would have made it a single repo-wide
report — a satisfying one, and wrong:

**`String.lowercase()` with no argument is Kotlin's locale-invariant form** (it is what
`toLowerCase()` used to be, and the deprecated default-locale function). `lowercase(Locale)` is the
explicit one. So all 185 sites already behave the same on every device locale, and there is nothing
to fix.

Verified rather than assumed, because the conclusion inverts a well-known Android lint warning:

| form | locale | count in `src/main` |
|---|---|---|
| `.lowercase()` | **invariant** (safe) | 185 |
| `.lowercase(Locale.X)` / `.lowercase(Locale.ROOT)` | explicit | 33 |
| `.toLowerCase()` / `.toUpperCase()` (deprecated, default-locale) | **sensitive** | **0** |

The deprecated forms are the ones `DefaultLocale` lint flags, and there are none. Recorded here so
the next pass does not spend the same hour: **this repo is clean on locale-sensitive case handling,
and a grep for `lowercase()` is not evidence to the contrary.**

The `String.format` sweep that followed was similarly quiet: 36 calls, 10 without an explicit
`Locale`, all of them log or diagnostics messages using `%d`/`%s`/`0x%08X` — none format a float
into a user-visible string, which is where a locale would change what the user reads. (Noted as an
improvement rather than a finding: pinning `Locale.ROOT` on the diagnostic ones is a one-line habit
that costs nothing and removes the question.)

A related hypothesis that also died on contact: because `effectiveTemperature` has no upper clamp on
`reversalConfidenceTempMax`, a large multiplier could in principle push `softmaxTo1000`'s
`round(1000 · p)` down to 999 for a *single* candidate, breaking the "a lone candidate reads 1000"
property. It does not: with one candidate `p = 1.0` exactly, for **any** finite positive T. The 999
boundary needs `T < T₀/7.6`, i.e. a *smaller* temperature than the default — unreachable by a
multiplier the config requires to be `≥ 1`. Hypothesis discarded, no change made, no finding reported.

---

## Checked and found sound

- **`CandidateRanker.rank`'s total order.** `score desc → ordinal asc → word lexicographic` is a
  genuine total order (ordinal is unique per dictionary entry), which is what makes the output
  reproducible under NFR-4 and what the dedupe's "first occurrence is already the best" claim rests
  on. The defensive `c.score > existing.score` re-check in the dedupe is provably dead given the sort
  — and correctly left in as a total-function guard.
- **`CandidateRanker.softmaxTo1000`'s numerics.** Max-subtraction before `exp`, `Double` accumulation,
  explicit `sum <= 0` degenerate branch, and `Math.round(1000 · p)` clamped by construction to 0..1000.
  A single candidate yields exactly 1000 (verified analytically, see H5). An all-equal set yields a
  flat `round(1000/K)` — the documented anti-fake-confidence property, and the reason min-max was
  rejected.
- **`CandidateRanker.effectiveTemperature`.** At `slope = 0` it returns `config.softmaxTemperature`
  unchanged (no multiplication by 1.0, so no float drift), and it scales only the temperature — which
  is monotone on the scores, so ranking order is invariant to `reversalCount` exactly as documented.
- **`TemplateIndex`'s quantization.** `quantUnit` stores into a `Byte` (signed!) but `dequantUnit`
  masks with `0xFF`, so `0.75 → -64 → 0.75` round-trips; `quantLen`/`dequantLen` pair correctly on
  `0xFFFF`. This is the classic sign-extension trap and it is not present. Verified by reading the
  pairs together, and by confirming every `bboxQ` lane has a matching accessor with the same lane
  index.
- **`TemplateIndex.estimatedBytes`'s inclusivity.** It counts the packed arrays, the CSR tables and
  array headers, and deliberately excludes the caller-owned dictionary strings (the index holds zero
  `String` references) and the Tier-B memo (owned by `TemplateCache`). That split is what makes
  `TemplateCache.estimatedBytes` an honest whole-cache number rather than a double count.
- **`TemplateIndex.build`'s bbox inflation.** A half-key margin, with the vertical half scaled by
  `aspect` so the inflation is isotropic in key-width units — the same convention `dKw` uses. Without
  it, an axis-aligned word (`to`, both keys on the top row, zero V-extent) would have a degenerate box
  and the overlap prune would be meaningless for it.

---

## What this pass did not do

- **Did not change any allocation, array width, or capacity.** H1–H3 are accounting corrections; the
  engine's memory use is unchanged and the enforced bar still holds. A pass that "fixed" a doubled doc
  figure by halving a buffer would have been the wrong move in both directions.
- **Did not tighten the 2.5 MiB assertion** to account for `MEMO_ENTRY_OVERHEAD_BYTES` making the
  worst case marginal (see H1's last paragraph). That is a spec decision about whether the bar is
  "typical" or "worst case", and it belongs to whoever owns NFR-2, not to a bug hunt.
- **Did not re-enable `CandidatePrunerTest`** in this fork. It is skipped for absent upstream
  artefacts (its fixtures need dictionaries this checkout does not vendor), and that is a deliberate
  fork-level decision. Its memory tests are still dead here — `GeoBenchmarkTest` and the new arithmetic
  test cover the same ground without the fixtures.
- **Did not sweep the remaining `String.format` sites** for `Locale.ROOT` (10 diagnostic calls). Noted
  as an improvement in H5, not applied — it is churn without a demonstrated failure.
- **Did not read `PathScorer`'s channel maths** (part 11 read its composition and the corner rule;
  the per-channel distance functions remain unread in detail), nor `TemplateIndex`'s `build` beyond
  the quantization and bbox path.
- **Did not profile on a device.** H1's table is the formula's arithmetic, which is what the estimate
  is defined as; it is not a measurement of retained heap. `GeoBenchmarkTest`'s runtime-delta smoke
  does measure that, and passed.

---

## CI

| run | head | suite | APK | smoke |
|-----|------|-------|-----|-------|
| 1 | `3228fb5` | **failed** — 1 of 4888: `GeoMemoryAccountingTest#tierA per-word…`, `expected: 10 but was : 9` | pass | — |
| 2 | `91e7955` (**final**) | **`45090710640` green** — 4888 run, 0 failed, 134 skipped, 439 classes | `45090704215` pass | `45090704121` pass |

Part 13 ended at 4885 run / 438 classes; this pass adds **3 tests and 1 class** (4888 / 439). Skipped
stays at **134**, so the skip ratchet is untouched.

Run 1 is the useful one again, and not for the reason I expected: the failure was **my own
arithmetic, inside the test written to catch wrong arithmetic**. It cost one four-minute cycle and
prevented a wrong byte figure from entering the same KDoc that this pass was correcting. The
mechanism worked; the summary line `expected: 10 but was : 9` was all it took to see it.
