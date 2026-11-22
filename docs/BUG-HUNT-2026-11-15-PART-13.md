# Bug hunt, part 13 — `swipe/geometric/` part 2: the cache, the collector, and a merge rule that disagreed with itself

Date: 2026-11-15 (work committed 2026-11-22)
Branch: `arena/cbf26556-keyboard`
Base: `8c372c5` (part 12, `swipe/GeometricEngineAdapter`)
Scope: the geometric surfaces part 11 and 12 left unread — `TemplateCache` (243 lines),
`TemplateGenerator` (259), `CandidatePruner` (152), `CandidateRanker.rank`, `GeometricUserWordMerge`
(76), `GeometricSwipeEngine` (164), plus `TemplateIndex`'s CSR bucket build and
`GeometricEngineAdapter.engineFor`.

This pass found one real defect with a clean fix, one measured performance hazard that **cannot be
fixed without a measurement**, and closed out the remaining read surface in this subsystem. The
performance finding is the more interesting half, because the obvious fix is wrong.

---

## Summary

| # | Finding | Severity | State |
|---|---------|----------|-------|
| G1 | `GeometricUserWordMerge` case-folded when *matching* user words but not when *emitting* them, so two case variants of one personal-dictionary word became two ordinals with one template | medium (double work, possible bonus disagreement) | **fixed** |
| G2 | `TopKScored` never drops ties, so a large equal-score group grows the working set to the survivor count and re-sorts it per offer — ~146x the bookkeeping, ~100 ms, in the pathological case | medium (performance, anomalous input) | **reported, measured** |
| G3 | A K-bounded collector — the "obvious" fix for G2 — changes selection, not just cost | — | **rejected by simulation** |
| G4 | The engine and its cache are rebuilt when a tunable knob changes, so the cache key needs no config component | — | sound |
| G5 | `TemplateCache`'s two-tier concurrency (coarse Tier-A lock, independent Tier-B monitors, build outside the lock) | — | sound |
| G6 | `CandidatePruner`'s "no ordinal dedupe needed" claim rests on **disjoint buckets**, not on "one word, one bucket" | — | sound, argument tightened |

Production files changed: 2 (`GeometricUserWordMerge.kt`, `GeometricSwipeEngine.kt` — the latter
documentation only). Tests: 1 added to `UserDictionaryReachesSwipeTest`.

---

## G1 — the merge was not deduped on its own rule: fixed

`GeometricUserWordMerge` decides whether a user word replaces a base word by a **case-folded**
comparison (`userLower` / `disabledLower`), so to this merge `zeta` and `Zeta` are the same word. The
list it *prepends* was not deduped on that same rule.

```kotlin
user.sortWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
val userLower = user.mapTo(HashSet()) { it.first.lowercase(Locale.ROOT) }   // one entry
val words = ArrayList<String>(base.size + user.size)
user.mapTo(words) { it.first }                                             // two entries
```

Both copies then reached the index, and because `LayoutProjection` maps a word to a **key-id
sequence**, the two copies produced the *identical* template. Three consequences, in ascending order
of importance:

1. **Duplicate work.** Both ordinals were emitted into the same extremity bucket by
   `TemplateIndex.build` (the second pass emits one entry per typeable ordinal, with no dedupe), so
   both were pruned through and both were scored — for zero extra information.
2. **No possible gain.** The second copy sits at a worse ordinal, and the rank prior is
   `−λ_f·ln(1 + r(w))`, so it scores strictly lower whenever the geometry is otherwise equal. The
   ranker's dedupe then discarded it. It could only ever cost time.
3. **Bonus disagreement.** `bonusByWord` is keyed by the word *exactly as listed*. Two case variants
   are two different keys, so one copy could carry a swipe-priority bonus and its twin could not —
   meaning which of the two won the ranker's dedupe (tie on score broken by ordinal) decided whether
   the user's raised word actually got raised.

**Reachability, stated honestly.** The production path (`UserDictionarySnapshot.mergeWithCustom`)
already de-dupes case-insensitively, preferring the preference over the provider row. So this is not
reachable through today's one caller — it is reachable through the function's own contract: it is a
public pure API whose documented matching rule is case-folded, and it accepted a list that its own
semantics call a single word. That is why the fix is in the pure layer with a test rather than a
defensive guard in the adapter.

**Fix** keeps the first entry after the existing `(frequency desc, word asc)` sort — i.e. the
highest-frequency spelling — so *which* one survives is a consequence of the documented ordering
rather than of insertion order.

---

## G2 — `TopKScored`'s tie guarantee costs more than it looks: reported, measured

`TopKScored` documents that it "MUST retain a superset of the true top-K under any tie", and its
admission test is strict `<` against `worstAdmitted`. The consequence is one nobody had written down:
when a candidate is admitted and finds no vacancy, **nothing is evicted**, so the working set only
grows. With a group of N candidates sharing the exact worst admitted float score, `items.size`
approaches `min(N, survivors)`, and `kthLargest` copies and sorts that whole set on **every
subsequent offer**.

Simulated at the production bounds (`maxCandidatesScored = 1200`, `maxResults` in 3..15):

| cap | bounded bookkeeping | unbounded (all-ties) | ratio |
|-----|--------------------|----------------------|-------|
| 10 | 51,863 ops | 7,572,348 ops | **146x** |
| 15 | 88,324 ops | 7,572,713 ops | **86x** |

At ~15 ns/op that is roughly **114 ms** of pure bookkeeping on the decode thread, for one swipe, in
the all-ties case. Every non-tied case stays at K and is unaffected.

### Why it is still not fixed

The fix looks mechanical: keep a K-sized set, admit only strictly better candidates, evict the worst.
I simulated exactly that against the current collector over 4000 randomized candidate sets (distinct
scores / small tie groups / all-ties / mixed), comparing the **final ranker output**:

```
trials 4000 | mismatches: 3010  (75%)
```

It is not output-equivalent, and the reason is intrinsic: the current collector preserves a whole tie
group, while a bounded one keeps whichever members it saw. Different surviving members → different
ordinal tie-breaks → different words on the bar. So the rewrite is a **selection change**, and by the
rule this series settled in part 11 (a frame/threshold bug in a *measurement* can be fixed directly; a
change to a *calibrated score path* needs a full-grid A/B first), it cannot ship on reasoning alone.

Worth recording: my first rewrite attempt was worse than wrong — it evicted `items[0]`, which is the
**best** item, so it would have discarded the winner on every offer. The simulation is the only
reason that did not reach CI.

The one realizable trigger — duplicates of one personal-dictionary word landing in a bucket — is
closed instead at the source by G1. What remains is a genuine `Float` score collision across
*different* words, which is a numerical accident rather than something a user can cause.

**If someone picks this up:** the measurement is cheap in principle — the all-ties case can be
constructed directly as a unit test (`TopKScored` is private, so via a `PathScorer` stub or by making
the collector `internal`), and the accuracy A/B is the same `-PgeoFull` grid part 11 describes. The
decision is whether a tie group *should* be preserved at all; a bounded collector is defensible if the
answer is "no", and the ranker's `(score desc, ordinal asc)` sort would then need to be shown to
absorb the difference.

---

## G6 — the pruner's safety argument was right, for a different reason than stated

`CandidatePruner` explains why it never dedupes ordinals across buckets:

> "A word can only ever land in ONE bucket (its own first/last pair), and the deduped start/end id
> sets make every probed (s, e) pair distinct — so unioning the buckets yields disjoint ordinal sets,
> no ordinal dedupe needed."

The first clause is not true in general — **two different words can share a first/last key pair** (and
after G1, two entries of one word certainly could), so a single bucket is not one word. The
*conclusion* still holds, because it does not depend on that clause: a word's bucket is determined by
its own `(first, last)` key ids, so each ordinal appears in exactly one bucket, and distinct probed
pairs therefore yield disjoint ordinal sets regardless of how many words share a bucket. The
double-count hazard is probing the *same* pair twice, which `unionDistinct` prevents.

Checked and left as-is — the reasoning is one sentence away from being airtight, and this is a note
rather than an edit because the code is correct and the comment is not misleading enough to risk a
diff in the hot prune loop.

---

## Checked and found sound

- **`TemplateCache`'s Tier-A/Tier-B locking.** Tier-A mutations take one coarse monitor; each
  `CachedIndex` has its own Tier-B monitor, so template lookups do not contend on the hot path;
  `getOrBuild`/`warmUp` build **outside** the coarse lock and re-check on insert, which is exactly the
  right shape (a lost race installs one of two structurally-identical indices). `memoContains` uses
  `containsKey` rather than `get` specifically so benchmarks do not perturb the LRU order — a
  detail that would be easy to get wrong in the opposite direction.
- **`CachedIndex.template` builds under the memo monitor.** Deliberate and documented: generation is
  µs-cheap, and holding the lock keeps LRU accounting consistent without an outside-lock build plus
  re-check. Correct call at this cost profile.
- **The cache key needs no config component.** `IndexKey = (fingerprint, language, version)` looks
  under-specified, and it would be a real bug — a stale index built with a different
  `resamplePoints`/`doubleLetterLoopRadius` — except `GeometricEngineAdapter.engineFor` bakes the three
  tunable knobs into an immutable `GeometricEngineConfig`, holds the knob triple, and **rebuilds the
  engine (and therefore the cache) when the triple changes**. So a stale-config index is not
  representable. Verified rather than assumed, since this is precisely the kind of key that rots when
  someone adds a knob and forgets the cache.
- **`GeometricEngineAdapter.engineFor` clamps before constructing.** `GeoKnobRanges.clamp*` on all
  three knobs, so a hand-edited or backup-restored config cannot run values the sliders cannot
  display. (Part 12's A6 is the adjacent finding on the *import* side; this is the read side, and it
  is guarded.)
- **`CandidatePruner`'s stage-3 ordering.** Full stable sort by `(|ln ratio| asc, ordinal asc)` with a
  documented rationale for preferring a sort over a partial select — deterministic (NFR-4), and the
  cost is bounded by the summed bucket size, not the 98k lexicon.
- **`TemplateGenerator`'s single-key and doubled-letter variants.** The single-key case is
  loop-**primary** (not plain-primary) specifically so the template has nonzero bbox and path length,
  which keeps every downstream division and `ln` finite. The doubled-letter loop is inserted at each
  run in the **pre-collapse** sequence, which is the correct input — the collapsed sequence has by
  definition lost the repetition.
- **`GeometricUserWordMerge`'s `bonuses` array.** `null` when no user word carries a bonus, so an
  un-raised personal dictionary produces a byte-for-byte identical merged dictionary to the
  pre-priority merge. `coerceAtLeast(0f)` prevents a negative bonus from becoming a penalty.
- **`GeometricSwipeEngine.decode`'s guards.** Degenerate points, non-positive key area, non-finite
  coordinates (explicitly, because NaN defeats every downstream comparison), empty dictionary, dead
  layout, zero-length gesture — all short-circuit to a shared `emptyResult` before any work.

---

## What this pass did not do

- **Did not rewrite `TopKScored`** (G2/G3). Reasons and the simulation numbers are above; the
  blocker is that it needs an accuracy A/B this sandbox cannot run (no Gradle, no kotlinc).
- **Did not add a regression test for G2.** `TopKScored` is a private nested class, so pinning the
  hazard needs either a visibility change or a scoring stub — both are worth doing *together with* the
  decision, not before it.
- **Did not reword `CandidatePruner`'s comment** (G6). The code is correct; the comment's first clause
  is loose. Left as a note to avoid a diff in the prune loop's hot path.
- **Did not read `TemplateIndex` in full** (317 lines). I read its CSR build, `bucket`, the packed
  accessors and its KDoc; not the quantization helpers or `estimatedBytes`.
- **Did not read `CandidateRanker` in full.** I read `rank`'s total order and the tie-break it
  delegates to, which is what G2/G1 turn on.
- **Did not profile on a device.** G2's ~100 ms figure is a simulation of the bookkeeping's asymptotic
  shape at the production bounds, not a measurement of wall-clock on hardware; it is stated as an
  order of magnitude.
- **Did not touch the accuracy harnesses, any threshold, or any score weight.**

---

## CI

| run | head | suite | APK | smoke |
|-----|------|-------|-----|-------|
| 1 | `df693ee` | **`45081810432` green** — 4885 run, 0 failed, 134 skipped, 438 classes | `45081803855` pass | `45081803840` pass |

Part 12 ended at 4884 run / 438 classes; this pass adds **1 test**, class count unchanged (4885 / 438).
Skipped stays at **134**, so the skip ratchet is untouched.

No red builds this pass, which is worth a sentence only because the previous one had two: the
difference was writing the dedupe against the sorted list I already had, instead of introducing an
optional and then reaching through it.

Also recorded, because it cost time last pass and did not this one: **verify `git ls-remote` matches
`git rev-parse HEAD` after a push.** A `--force-with-lease` push accepts whatever object you hand it,
so "the push succeeded" says nothing about whether you published the commit you meant to.
