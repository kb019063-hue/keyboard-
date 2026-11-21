# Bug hunt — 2026-11-15, part 5: the skip ratchet

[Part 4](BUG-HUNT-2026-11-15-PART-4.md) recovered 70 tests that a class-wide `@Before` `Assume`
had silently disabled, and ended with a suggestion: ratchet the skip count, the way
`VerboseLogRatchetTest` ratchets ungated log lines. This is that ratchet, built and green.

`SkipRatchetTest`, four cases, registered in `build.gradle`.

| # | Finding | State |
|---|---|---|
| Q1 | A guard in a JUnit lifecycle method skips the whole class; 20 such guards remain and nothing capped them | ratcheted at 20 |
| Q2 | `UpstreamArtefacts.require` site count unmonitored | ratcheted at 25 |
| Q3 | 63 direct `Assume`/`@Ignore` sites across 29 files, unmonitored | ratcheted at 63 |
| Q4 | The obvious stronger rule — "every test in a guarded class must reference its artefact" — cannot be asserted | rejected, with evidence |

## The rule that is exact, and the one that only looks exact

The ratchet's spine is **Q1**, and it is not a heuristic. `@Before`, `@BeforeClass`, `@BeforeEach`
and `@BeforeAll` all run on behalf of the whole class, so an `Assume` — or an
`UpstreamArtefacts.require`, which is one — inside one skips every test in it. There is nothing to
argue about case by case: a guard in a lifecycle method is *at least* a class-wide skip, and that
is precisely the mechanism that cost 70 tests.

The check finds each lifecycle annotation, walks to its declaration, brace-balances the body, and
looks for any guard call inside it. Measured on this tree: **20**. Cap: 20.

The other two are ceilings on the sanctioned and the tolerated counts — 25 `require` sites, and 63
direct `Assume`/`@Ignore` sites outside `UpstreamArtefacts`.

## What I rejected, and why it matters

The rule I *wanted* to write was "every test in a guarded class must reference the artefact it
guards on". It is the rule a reader assumes is being enforced. It cannot be asserted honestly:

- A `@Test` body does not include the `@Before`, so per-body scanning only sees direct use.
- Tests reach a langpack through a field initializer or a helper (`GeoTestFixtures`) rather than in
  their own body. My first heuristic reported **`GeoAccuracyJcukenRuTest`'s six tests as
  artefact-free** — a class named for the Russian JCUKEN layout, all six of which need
  `russianCkdt()`, because that class names `russianCkdt()` and nothing else the scanner knew.
- In this checkout the signal collapses further: several real langpack paths sit behind
  `GeoTestFixtures`, whose only mention of `scripts/dictionaries` is on a `File(...)` line.

So the check would have been wrong in both directions — calling Russian tests free, and calling
English-only tests dependent. A ratchet that cries wolf gets relaxed rather than fixed, which is
worse than no ratchet. That separation is a review activity; part 4 §P6 is where it lives.

## Calibration: both drafts had wrong numbers

Worth recording, because it is the failure mode of this kind of test.

1. **First draft** counted `assumeTrue|assumeFalse|assumeNoException|@Ignore` with a ceiling of 71
   and asserted "no file may name `Assume` except `UpstreamArtefacts`". The second assertion is
   **false on this tree**: 63 direct sites across 29 files, all pre-existing. The test would have
   failed the moment it was added — and the tempting reaction to that is to raise the ceiling,
   which is how a ratchet becomes decorative. The rule was wrong, not the tree.
2. That draft also **scanned itself**: its own regex literal `assumeTrue|assumeFalse|…` lives in
   the source it reads, so it counted itself as a violation. Now `SkipRatchetTest.kt` is excluded
   by path.
3. **Import lines** inflate any count: nine files `import org.junit.Assume.assumeTrue` and skip
   nothing by doing so. Imports are excluded.
4. **Comments dominate the raw text.** This repository discusses `Assume` constantly — including in
   the KDoc directly above the regex. Matching raw text counted documentation as guards. Block and
   line comments are now stripped before counting, and a small sanity assertion keeps the ceiling
   equal to the measurement so a future author cannot quietly round it.

The final numbers are **20 / 25 / 63**, each one counted on this tree and pinned so that lowering is
the only direction that stays honest.

## It fires

A ratchet nobody has seen fail is a guess. I injected a `@Before`-guarded `Assume` back into
`CtcLanguagePresetTest` — the class whose guard part 4 removed — and re-ran the same extraction:

```
lifecycle guards now: 21  => ceiling 20: FAIL (ratchet would fire)
```

Then restored the file (`git checkout --`, tree clean) and confirmed the count returned to 20. The
injected guard is caught by the mechanism that exists to catch it, not by luck.

## Verified by CI

| Workflow | Run (on `7b71bee`) | Result |
|---|---|---|
| `Build APK` | `44891548223` | **green** |
| `Full unit-test suite` | `44891549786` | **green** — **4851 tests, 0 failed, 134 skipped in 431 classes** |
| `Emulator smoke test` | `44891546897` | **green** |

`4851 / 431` is +4 tests and +1 class over the part-4 tip, which is exactly `SkipRatchetTest`'s four
cases. `skipped` stayed at **134** — the ratchet's ceiling assertions are about source counts, and
the runner confirms the new class skips nothing. That the two numbers are different quantities is
itself asserted (`ceilingsTrackTheMeasuredTree`), so nobody can "fix" a site ceiling by comparing it
to the runner's skip count.

## What this ratchet does not do

- **It does not run JUnit**, so it cannot count skips the way `full-test-suite.yml` does. It counts
  the causes. The runner's `S skipped` figure is the ground truth, quoted in the test's doc and in
  its failure messages; when the two disagree, CI is right.
- **It cannot tell a necessary class-wide guard from a needless one.** Fifteen classes still carry
  one, and most are honest — `ReleaseRecordDriftTest` and friends assert on files this fork does not
  vendor, and a format check with no file to check has nothing to say. The ratchet stops the number
  growing; it does not judge the ones that are there. Judging them is part 4 §P6, and two are named
  there as likely wins (`CtcImportedPackSupportTest`, `GeoDecoderCoreTest`).
- **It does not touch production code.** The APK is unchanged; the green `Build APK` re-confirms
  that rather than testing a fix.
