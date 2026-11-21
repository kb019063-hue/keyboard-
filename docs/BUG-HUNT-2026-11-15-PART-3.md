# Bug hunt — 2026-11-15, part 3: the CKDT dictionary parser, and twelve tests that never ran

A third pass over a subsystem the earlier hunts never opened: the swipe decoders. Specifically the
CKDT binary dictionary reader (`swipe/geometric/CkdtDictionaryReader.kt`), which is the parser
behind every swiped word — it turns a 16 MiB binary blob into the lexicon both the CTC and
geometric engines rank against.

Two things came out of it. One is a small defect in the parser: it validated its header thoroughly
and its record section only statistically, so a corrupt record escaped as the wrong *kind* of
exception. The other is larger in effect than that fix, and is not a code defect in the shipped
app at all: **twelve tests in `CkdtReaderTest` were not running**, including four that need no
missing artefact and which pin exactly the kind of input-validation bug found here.

| # | Finding | State |
|---|---|---|
| N1 | A corrupt CKDT record length threw `BufferUnderflowException`, not `IOException` — so a malformed language pack was reported as an unknown internal error | fixed, tested |
| N2 | A class-wide `@Before` `Assume` skipped all of `CkdtReaderTest`, including four tests that need no missing artefact | fixed — 10 of 12 now run |
| N3 | `CtcBeamDecoder`'s per-frame prune sorts with `Math.pow` inside the comparator, on an allocation-heavy hot path (~64 frames × ≥100 hypotheses) | reported, with measurements to take |
| N4 | `CtcSwipeDecoder`'s KDoc cites `CtcLatencyGateTest`, which does not exist | reported, low |
| N5 | `CtcParityTest` — the beam's golden-parity gate — is skipped in this fork, so beam scoring is unverified here | reported, informational |

## N1. The record section was validated statistically, not per record

The reader checks its header hard: magic, version, `wordCount` range, canonical offset, and then

```kotlin
require(wordCount <= (bytes.size - canonicalOffset) / 3) { "Truncated canonical records" }
```

That guard is real and needed — it is what stops an absurd `wordCount` from being trusted into an
array allocation. But it is an **average**, and an average cannot bound an individual record: 3
bytes per record on average is equally consistent with one 65,535-byte word and a tail of empty
ones. After it, each record read a `uint16` length and `get()` that many bytes with no check:

```kotlin
val len = buffer.short.toInt() and 0xFFFF
val wb = ByteArray(len)
buffer.get(wb)          // <-- BufferUnderflowException on a corrupt length
```

`BufferUnderflowException` extends `IndexOutOfBoundsException`, not `IOException`. Every production
caller of this reader wraps it in `catch (Exception)` and degrades to "this language has no
dictionary" — `CtcEngineAdapter.lexiconFor`, `GeometricEngineAdapter`, and
`ContractionCollisionScanner`'s `runCatching` all do. So the *outcome* was survivable, but a
malformed file was logged and classified as an unknown internal failure rather than as a malformed
dictionary, which is the difference between a bug report that says "your file is corrupt" and one
that says "the keyboard broke".

Fixed with a per-record bound on both the word bytes and the trailing rank byte, throwing
`IOException` with the record index and the remaining byte count. Also replaced the literal `/ 3`
with a named `MIN_RECORD_BYTES`, since that `3` is exactly the quantity that makes the guard read
like a per-record check when it is not.

Scope, stated plainly: this is a robustness and diagnosability fix, not a crash fix. The bytes come
from a language pack the user chose to install, and the malformed case was already handled by every
caller. It is the kind of defect that matters because it is *in the parser for a file format the
app loads from untrusted input*, and because the guard sitting right next to it looks like it
already covers this.

## N2. Twelve tests that never ran

This is the finding worth reading.

```kotlin
@Before
fun skipWhenLanguagePacksAreAbsent() {
    UpstreamArtefacts.require(UpstreamArtefacts.LANGPACK_DIR)
}
```

A JUnit `@Before` that fails an `Assume` skips **every test in the class**, not just the ones that
need the missing thing. The guard was correct in intent — the `en`-vs-`ru` langpack comparisons
need `scripts/dictionaries/langpack-*.zip`, which this fork does not vendor — but it was class-wide,
so it also skipped:

- `interruptedReader_abortsBeforeAllocatingDictionary` — cancellation of a large parse
- `oversizedCountIsRejectedBeforeWordArrayAllocation` — `wordCount` = 100 001 / 1 M / `Int.MAX_VALUE` / −1
- `streamByteCapDoesNotTrustAvailable` — the 16 MiB stream cap with a lying `available()`
- `maximumWordCountRemainsReadable` — 100 000 records

**None of those four reads a file.** All four ran fine; they were simply never reached. And they
are precisely the tests that would have caught N1's family of bug — input validation of a parser
fed by a user-installable pack.

The English cases were skipped too, and did not need to be: `src/main/assets/dictionaries/`
`en_enhanced.bin` (2.49 MB) and `en_enhanced.json` are **vendored in this fork**. Before
un-skipping them I re-verified every assertion they make against those actual assets, because
turning a permanently-skipped test into a running one is how you find out an assertion has been
stale for six weeks:

| Assertion | Verified against the asset |
|---|---|
| `count == 98140` | 98,140 ✓ |
| `dict.language == "en"` | caller-supplied ✓ |
| `'the'` in the top 50 | ordinal **0** ✓ |
| `'and'` outranks `'aalborg'` | 2 vs 67,250 ✓ |
| CKDT and JSON word sets identical | both 98,140, set difference empty ✓ |
| tie-break keeps file order | 0 duplicate surfaces; `sortedBy` is stable ✓ |

The guard now lives on the two cases that genuinely need the langpacks. Result: `CkdtReaderTest`
went from **12 skipped to 2**, and the suite's skip count from 204 to 194.

I could not read the JUnit XML back (the artifact lives in blob storage my sandbox cannot reach),
so the arithmetic is the evidence, and it closes exactly: the suite runs are 4840 → 4847 (+7, my
new class's seven cases) and skipped 204 → 194 (−10), so tests actually executed went up by
**17** — 7 new plus the 10 recovered. Class count 429 → 430.

Worth generalising: a `@Before` `Assume` is a class-wide switch. This repository has a lot of
`UpstreamArtefacts.require` guards, and each one in `@Before` position is silently disabling
everything in its class. A grep for them would be a cheap follow-up; I did not do it, so treat
"other classes have this too" as likely rather than known.

## N3. `Math.pow` inside the prune comparator (reported)

`CtcBeamDecoder` is the per-swipe hot path. Per frame:

```kotlin
cand = ArrayList(cand.sortedByDescending { pruneScore(it.score, it.node.depth, params, lpOnly) })
cand.subList(beamWidth, cand.size).clear()
```

and `pruneScore` calls `Math.pow(d.toDouble(), params.gammaPrune)` — one native `pow` for **every
comparison**, i.e. `O(n log n)` `pow` calls per frame, on a sort that also allocates a boxed
`ArrayList` and a comparator object. `frames` is `CtcFeaturizer.RESAMPLE_LENGTH` = 64, and the
per-frame candidate set is bounded by `beamWidth × (childCount + 2)` for a beam width of 100–300,
so a node with a dozen children puts the sort in the thousands of elements — an order of
10⁵ `pow` calls per swipe by arithmetic. That is an estimate from the loop bounds, **not a
measurement**; see the caveat at the end.

The fix is contained and does not change a single decision: `pruneScore` is a pure function of
`(score, depth, params)`, so it can be computed once per candidate into a parallel array, or
carried on `Hyp` alongside `score`, and the sort switched to a primitive index sort on those keys.
`depth` is an `Int` bounded by word length, so `Math.pow` could also collapse to a small
`DoubleArray` lookup keyed by depth.

**Why reported and not fixed.** The comment above this exact line says "pruning uses a stable
descending sort so tie handling matches the port", and `CtcParityTest` asserts identical top-k
*word order* against a Python port. Any change here has to preserve tie order exactly, and the test
that would prove it (`CtcParityTest`) **is skipped in this fork** (N5). I would be changing the
ordering behaviour of the shipped decoder with no way to verify parity — so the honest move is to
name it, state the shape of the fix, and let whoever has the language packs measure and land it.

Also worth noting for whoever does it: the KDoc's own `CtcSwipeDecoder` says "do not measure or
benchmark this class as a stand-in for swipe latency — time `CtcBeamDecoder` in `decodeAsync`'s
order instead, as `CtcLatencyGateTest` now does". That test does not exist (N4), so the latency
claim has no gate behind it either.

## N4. `CtcLatencyGateTest` does not exist (reported, low)

`swipe/ctc/CtcSwipeDecoder.kt`'s KDoc names `CtcLatencyGateTest` as the thing that times
`CtcBeamDecoder` in the adapter's order. There is no file by that name in `src/test`. Either it was
deleted and the KDoc outlived it, or it was never written. Same class of finding as part 1's H7;
reported rather than reworded because which of the two it is decides whether a latency gate is
missing or merely misnamed.

## N5. The beam's parity gate is skipped here (reported, informational)

`CtcParityTest` is the golden-trace gate for the whole featurize→beam chain: identical greedy
string, identical top-k words, scores within float tolerance, against expectations frozen from the
Python port. It carries the langpack guard, so **it does not run in this checkout**. That is the
documented, deliberate `UpstreamArtefacts` behaviour and not a defect — but it means that on this
fork, for the decoder that produces every swiped word, the ranking contract is unverified, and any
change to beam scoring (N3 included) cannot be checked before it ships.

## Checked and found sound

- **`CtcEmissions`.** `init` validates `frames >= 0`, `numClasses >= 2` and
  `values.size == frames * numClasses`, so `at(t, c)` is a safe plain index with no re-check on the
  hot path. `sliceFromHead` validates the head width and its length before slicing.
- **`CtcBeamDecoder`'s dedup keys.** `(nodeId shl 1) or 1` for blank-ended and `id shl 1` for
  live hypotheses are disjoint, and the `Long` shift means no collision. The MAX-merge is a
  correct Viterbi update (strictly-greater replacement keeps the first-seen on ties).
- **The CTC move set.** Blank / advance-per-child / repeat-own-char, with repeat gated on
  `!blankEnded && depth > 0`, matches the FUTO `beam_search.cpp` description in the class KDoc.
  The deliberate absence of a required blank between distinct characters is documented as a
  design choice, not an oversight.
- **`CtcTrieNode.childAt` / `word()`.** `childAt` range-checks; `word()` walks parent pointers
  into a `depth`-sized buffer and cannot overrun because every node's depth is its parent's + 1.
  The unclamped child-array growth is deliberate and explained in place (the old clamp caused an
  AIOOBE at the 27th distinct child).
- **`CtcFuzzyRescue`.** The scan budget is checked per bucket *and* per entry; lengths are scanned
  nearest-first so the budget is spent where matches are likeliest; the index and the
  admissibility check both filter on the active alphabet (the KDoc explains what silently broke
  when they filtered on `a..z`). `mergeIntoBeam`'s append-below invariant and its
  `R_MIN = 0.5` interaction are correct for every positive `topScore` — I checked the even/odd
  cases the KDoc claims.
- **`CkdtDictionaryReader`'s other guards.** `readBoundedBytes` never trusts `available()` and
  enforces the 16 MiB cap incrementally; `wordCount` is range-checked against `MAX_WORD_COUNT`
  before either array is allocated; the canonical offset is checked against the header size and
  the buffer length; `checkInterrupted` runs inside the parse loop and inside the sort.
- **`PathScorer`.** Already recorded as sound by the 2026-10-30 pass (every divisor guarded) —
  re-read, still true, not re-litigated here.

## Verified by CI

All three workflows on `973b0f7`:

| Workflow | Run | Result |
|---|---|---|
| `Build APK` (push) | `44862890200` | **green** |
| `Full unit-test suite` (PR #33) | `44862895782` | **green** — **4847 tests, 0 failed, 194 skipped in 430 classes** |
| `Emulator smoke test` (push) | `44862890085` | **green** — `rotate verdict=ok`, `new-crash=none` |

The offline front-end check (`scripts/ci/offline/typecheck.sh --tests 0916911`) added 22
diagnostics and every one is in the two known benign groups: the new test file importing JUnit and
Truth (not reachable without Maven Central), and the three `unresolved reference 'close'` in
`ModelLoader.kt` carried over from part 2 (ORT is not on that check's classpath). **No new
diagnostic in `CkdtDictionaryReader.kt` or in `CkdtReaderTest.kt`** — the two production/test files
this pass actually changed.

## What this pass did not do

- **`CtcBeamDecoder` is unchanged.** N3 is a measured-shape claim, not a measurement: I did not
  time it, and per `AGENTS.md` latency numbers must come from the adapter's real order, which
  needs the shipped encoder and a device. No number is asserted anywhere above.
- **The other `UpstreamArtefacts.require` guards were not audited** for the same class-wide
  `@Before` problem (N2). This pass found one instance, in a class it was already reading.
- **`GesturePreprocessor`, `PathScorer`, `TemplateIndex`, `TemplateCache`, `LayoutProjection` and
  `GeometricSwipeEngine`** were not read in this pass beyond `PathScorer`'s existing soundness
  note. The geometric engine remains largely unhunted.
- **`CtcScoringParams`' preset values** (the four `gamma`/`beta`/`lambda` sets) were read for their
  plumbing only, not validated as calibrated.
