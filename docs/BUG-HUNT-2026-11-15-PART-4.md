# Bug hunt — 2026-11-15, part 4: 70 tests were not running

This pass followed up the one loose thread from [part 3](BUG-HUNT-2026-11-15-PART-3.md). That
report found a class-wide `@Before` `Assume` skipping four tests in `CkdtReaderTest` that needed
none of the missing artefacts, and noted that the pattern "appears wherever
`UpstreamArtefacts.require` is used". It does. This is the audit of all of it.

**The headline is not a code defect.** The shipped app is unchanged in this pass. What changed is
that **70 tests which had never executed in this checkout now execute and pass**, covering the
hash gate that keeps ORT off user-supplied bytes, the geometric pruner's whole Phase-3 behaviour,
the CTC λ-selection contract, and the script-table invariants. Skipped went from **204 to 134**;
`@Test` count and class count are flat (4847 / 430) because those tests were always being
*counted* — they were being skipped, not absent. That is exactly why nobody noticed.

| # | Finding | State |
|---|---|---|
| P1 | A `@Before`/`@BeforeClass` `Assume` skips the whole class. 24 of 26 `UpstreamArtefacts.require` sites are in that position, hiding **203** `@Test` methods | audited |
| P2 | `CtcLanguagePresetTest` — all 25 tests guarded against a dependency the class does not have (no file access anywhere) | fixed, 25 recovered |
| P3 | `CtcPackModelTest` — guard hid 11 of 18, including the entire `verifiedBytes` hash gate | fixed, 11 recovered |
| P4 | `CandidatePrunerTest` — guard hid 15 of 18, including the concurrency stress and both memory-budget checks | fixed, 15 recovered |
| P5 | `CtcScriptSupportTest` — guard hid 9 of 13 pure table invariants | fixed, 9 recovered |
| P6 | 15 classes remain class-wide-guarded; most genuinely need an artefact, but `CtcImportedPackSupportTest` and `GeoDecoderCoreTest` show the same over-broad shape | reported |

## P1. How the pattern hides tests

`UpstreamArtefacts.require` calls `Assume.assumeTrue`. In a `@Before` that skips **every test in
the class**; in a `@BeforeClass` it skips the class before any test runs. The intent was always
"skip the tests that need the missing file". The effect was "skip the class", and the two only
coincide when every test in the class needs the file. Across the 19 guarded classes, four had that
mismatch (P2–P5) and five more look like they do (P6).

The reason this is invisible: the suite's own summary reports `N run, F failed, S skipped in C
classes`, and a skipped test is still *counted in C*. Class count and total `@Test` count never
moved. The only signal is the skip number, and nothing was watching it.

## P2. `CtcLanguagePresetTest`: guarded against something it never touches

The strongest case, and the one that shows the guard was applied by reflex rather than by
dependency:

```
$ grep -n "File(\|readText\|readBytes\|FileInputStream\|assets" CtcLanguagePresetTest.kt
(no matches)
```

**No file access of any kind.** All 25 tests assert the preset *table* —
`CtcScoringParams.presetFor(...)` and `CtcLanguageSupport.SUPPORTED`. `langpack-<code>.zip`
appears in the file only inside comments explaining where a lexicon comes from per language, which
is presumably what put the idea of the guard in someone's head.

So the entire λ-selection contract went unverified in this fork: that `en` keeps λ 4.0, that the
CKDT-scale languages use 2.0, that the fallback is `tunedV2`'s λ, that script languages take
`tunedRuCkdt` verbatim, that selection is case- and region-insensitive, and that **only λ** varies
across languages. Guard removed entirely.

I verified the two constants the assertions hinge on before enabling them
(`LAMBDA_EN_JSON_SCALE = 4.0`, `LAMBDA_CKDT_SCALE = 2.0`), rather than trusting that a test which
had never run would pass.

## P3–P5. The other three

| Class | `@Test` | Recovered | What was hidden |
|---|---|---|---|
| `CtcPackModelTest` | 18 | **11** | `verifiedBytes` rejects a mismatched hash / an unpinned model / an over-cap model; the size-cap headroom; the fingerprint tracking; the APK-ships-only-the-Latin-encoder check |
| `CandidatePrunerTest` | 18 | **15** | deterministic cap + `\|ln ratio\|` tie-break; best-K; LRU eviction; fingerprint & dictVersion cache misses; both memory budgets; the 4-thread concurrency stress |
| `CtcScriptSupportTest` | 13 | **9** | alphabet codepoint-sort/uniqueness/head-width; the recorded campaign alphabets; the a–z default; the defensive copy; gap accounting; script↔language agreement; router reachability; frame budget |

`CtcPackModelTest` is the one that matters most. `verifiedBytes` is the gate that stops the app
handing ORT bytes a language pack supplied — the exact property part 2's session-lifecycle work
assumed was under test. Eleven of its eighteen tests had never run here. They pass.

For `CandidatePrunerTest`, only 3 of 18 use the 50k Russian dictionary that is genuinely absent;
the English, French and German CKDT binaries are vendored. The 15 English-only tests were being
skipped alongside them.

In each case I checked the dependency per test rather than per class, and verified the specific
claims against the real source or assets before un-skipping — the alphabet invariants against
`CtcScriptSupport`'s table (all six sorted, unique, max codepoint well under the 64-key head), the
λ constants against `CtcScoringParams`. Details in each commit message.

## P6. What is left, and what it looks like (reported)

Fifteen classes still carry a class-wide guard. Most are honest: `ReleaseRecordDriftTest`,
`ReleaseMetadataDriftTest`, `TranslationGlossaryTest`, `CuratedInstrumentationListTest` and
`ReleasePackagingDriftTest` assert on `docs/RELEASE_RECORD.md`, `RELEASE_NOTES.md`, `fastlane/`,
`docs/i18n/glossary.json` and `.github/scripts/emulator-ci.sh`, none of which exist here, and a
file-format check with no file to check has nothing to say.

Two look like the same over-broad shape and are worth a follow-up:

- **`CtcImportedPackSupportTest`** (25 tests) — 19 reference no langpack; the class tests the
  import *cache and resolver*, much of which is pure. I did not change it: several of its cases
  write an imported pack and then resolve it, so the dependency is real for some, and separating
  them properly needs more care than this pass had budget for.
- **`GeoDecoderCoreTest`** (30 tests) — 28 reference no langpack. A decoder core test suite that
  is entirely skipped is a large gap, and it is the single biggest remaining win.

I am deliberately not claiming a number for these. My static heuristic that "looks artefact-free"
over-reported badly — it flagged `GeoAccuracyJcukenRuTest`'s six tests as free when the class is
named for the Russian JCUKEN layout and all six need `russianCkdt()`. The flags that mattered in
P2–P5 were confirmed by reading every test, and that is what the remaining two need.

## Verified by CI

| Workflow | Run (on `2ff80ed`) | Result |
|---|---|---|
| `Build APK` | `44872050927` | **green** |
| `Full unit-test suite` | `44872056048` | **green** — **4847 tests, 0 failed, 134 skipped in 430 classes** |
| `Emulator smoke test` | `44872050932` | **green** |

Skip count across the audit, every step green before the next:

| Commit | Skipped | Recovered | Moved by |
|---|---|---|---|
| `b3fc743` (part 3's code) | 204 | — | baseline before any un-skipping |
| `973b0f7` (part 3's report) | 194 | 10 | `CkdtReaderTest`: 12 skipped → 2 |
| `f49e3a9` | 168 | 26 | `CtcPackModelTest` 11 + `CandidatePrunerTest` 15 |
| `f51328e` | 159 | 9 | `CtcScriptSupportTest` |
| `2ff80ed` | 134 | 25 | `CtcLanguagePresetTest` |
| **total** | **134** | **70** | |

Each decrement matched the exact number of tests whose guard I moved — 26, then 9, then 25 — which
is the evidence that the tests actually ran, rather than the guard being deleted while the tests
found some other way to skip. `run` stayed flat at 4847 throughout, because these tests were
always being counted; only their skipped status changed.

## What this pass did not do

- **No production code changed.** This is a test-infrastructure change; the APK is unchanged from
  part 3's, and the green `Build APK` is re-confirming that rather than testing a fix.
- **The remaining 15 guarded classes were not individually audited** (P6). Two are named as likely
  wins; the rest look genuine.
- **`autocorrect/ChannelRankingEval.kt` and `NeighbourTiebreakEval.kt`** each have *two* guard
  sites in the same file — a `@Before` and a `@BeforeClass` — which suggests a per-method guard was
  added alongside an existing class-level one. Worth reading before changing; not read here.
- **Whether the skip count is otherwise watched.** I did not look for a CI check that would have
  caught this drift, because none of the existing gates did. A ratchet on `skipped` (the way
  `VerboseLogRatchetTest` ratchets ungated log lines) would have surfaced it years earlier, and
  is a cheap follow-up that does not depend on any of the missing artefacts.
