# Bug hunt, part 12 — `swipe/GeometricEngineAdapter`: one tag, three name spaces

Date: 2026-11-15 (work committed 2026-11-22)
Branch: `arena/cbf26556-keyboard`
Base: `f9e3579` (part 11, `swipe/geometric/`)
Scope: `swipe/GeometricEngineAdapter.kt` (773 lines) in full, plus every consumer of the two
language-name functions it disagreed with: `CtcEngineAdapter`, `CtcLanguageSupport`,
`LanguagePackManager`, `ContractionManager`, `WordPredictor` (three loaders),
`ContractionCollisionScanner`, `LayoutLanguageBinding`, `LayoutsPreference`, `Config`,
`InputCoordinator`.

This pass started as a read of the adapter's concurrency and memoisation, which turned out to be
sound, and ended on a single question that the file itself had written down and answered wrongly:
**when a language tag carries a region, which name does each lookup use?** The answer is three
different names, and the adapter used the wrong one for two of them.

---

## Summary

| # | Finding | Severity | State |
|---|---------|----------|-------|
| A1 | `dictionaryFor` keyed the personal dictionary on the raw tag while CTC normalised it, so a layout bound to a region code split one user's words across two engines | **high** (silent, user-visible word loss) | **fixed** |
| A2 | Same family: the geometric pack lookup also used the raw tag | low (latent — no regional pack can become loadable today) | **fixed** |
| A3 | `ContractionManager` composed contraction paths from the raw tag, so a region binding served contractions in **no** language | **high** (silent, whole-feature loss) | **fixed** |
| A4 | Three more copies of that same composition in `WordPredictor` and one in `ContractionCollisionScanner` | medium (same bug, three more surfaces) | **fixed** |
| A5 | A pack installed under a region code is readable by neither engine, though the importer permits it | low (unreachable today) | **reported** |
| A6 | `pref_primary_language` is unvalidated on backup import, so a restore can write an arbitrary non-bare tag | low (defence in depth) | **reported** |
| A7 | The TODO's stated pre-condition ("every producer feeds a bare code") was false, and its own cross-reference in `SwipeContractionPolicy` had already rotted | medium (process) | **fixed** |
| A8 | `geometryFor`'s memo, the `rectIdx` recovery, the 2-slot `dictionaryMemos`, and `decodeAsync`'s generation scheme | — | sound |

Production files changed: 5 (`GeometricEngineAdapter.kt`, `ContractionManager.kt`,
`WordPredictor.kt`, `ContractionCollisionScanner.kt`, `SwipeContractionPolicy.kt` — the last two by
comment only) plus one KDoc in `LanguagePackManager.kt`. New file:
`LanguageTagResolutionTest.kt` (5 tests, never skips).

---

## The shared cause

Three name spaces are answered by three different spellings of "the language", and the data in each
uses a different one:

| name space | correct code | why |
|---|---|---|
| preference keys (`custom_words_*`, `disabled_words_*`, `swipe_priority_*`) | **base subtag** | a personal word list is not regional |
| contraction data (`contractions_*.json`, `langpacks/*/contractions.json`) | **base subtag** | contractions are per-script |
| bundled dictionary asset (`dictionaries/*_enhanced.bin`) | **base subtag** | no shipped dictionary carries a region |
| installed pack directory (`langpacks/<code>/`) | the code the pack was **installed under** | the importer uses `manifest.code`, and pack eligibility is keyed on the normalised code |

`CtcLanguageSupport.normalize` (and `SwipeContractionPolicy.baseSubtag`, which delegates to it) cut
at the first `-`/`_`. `GeometricEngineAdapter.dictionaryFor` and `ContractionManager` did not.

### Why the old code believed this was unreachable

`dictionaryFor` carried a TODO saying a region-bearing tag was "latent today, NOT a live bug: every
producer feeds a bare code (`Locale.getDefault().language`, and the `pref_primary_language*` settings
lists)". The first three links of that chain are true — and irrelevant, because the layout binding
path bypasses all of them:

```
LayoutsPreference.loadLayoutsWithBindings      → LayoutLanguageBinding.effective(entry, declared)
LayoutLanguageBinding.VALID_CODE               ^[a-z]{2,3}(?:[_-][a-z0-9]{1,16}){0,4}$   ← accepts regions
CustomLayout.parse                              → normalizeEntry(language)  (same validator)
LayoutLanguageBinding.resolve(bindings, …)      → ActiveLanguages(primary = bound)  ← no stripping
Config.recomputeActiveLanguages                 → Config.primary_language
InputCoordinator.performGeometricSwipeTyping   → decodeAsync(…, getCurrentLanguage() ?: primary_language)
```

`VALID_CODE`'s own KDoc says the region segments exist "so every installable language can be bound",
and `resolve` deliberately returns `bound` unchanged rather than the user's primary. The reachability
is pinned by a test (`LanguageTagResolutionTest`), not asserted in prose.

---

## A1 — the personal dictionary split: fixed

**Symptom.** With a layout bound to `fr-CA`:

- geometric engine reads `custom_words_fr-ca`, `disabled_words_fr-ca`, `swipe_priority_fr-ca`;
- CTC engine reads `custom_words_fr`, `disabled_words_fr`, `swipe_priority_fr`.

A word the user added was therefore offered or invisible depending only on which engine the router
picked for that layout. No error, no log, nothing in the UI — the same finger produces different
results on the same keyboard.

**Fix** is one line: `val lang = CtcLanguageSupport.normalize(language)`. The TODO explicitly warned
*"do not add a fourth copy"*, and this adds no copy — it is the third call site of one definition.
The bundled-asset fallback and the pack directory follow the same subtag, which is where CTC's own
resolution already pointed.

`CtcLanguageSupport` and its package were verified pure (`java.util.Locale` is its only import), so
this does not pull Android into a class the geometric unit tests load.

## A2 — the pack path: fixed, and why it is not more than that

The pack lookup had the same raw-tag composition. It is **not** a live bug: a pack is only read if
CTC serves it from a pack, and that membership (`sourceFor` → `CtcImportedPackSupport` →
`CtcInstalledPacks`, whose verdict cache is keyed on the normalised code) can only ever be satisfied
for a bare code. So a regional pack is invisible to both engines, before and after this pass.

It was fixed anyway because it is a one-token change with no new failure mode, and because leaving
two spellings of the same path in one function is how A1 happened.

**A fix that was tried and deliberately discarded.** Delegating to
`CtcLanguageSupport.langpackRelativePath` looked better — its KDoc calls itself "the one constant the
eligibility check, the trie build and the geometric engine must all agree on". It was reverted
because that answer is *gated on the measurement resolver*: on the first swipe after a pack import it
can still report "no pack" and the geometric engine would silently drop to the bundled asset. A
`stat` has no such timing dependency, which matches how this path behaved before. This is recorded
because "ask the function that documents itself as the single source of truth" is the obvious move,
and it would have been a regression.

## A3 — contractions in no language: fixed

`ContractionManager.loadLanguageContractions` built `dictionaries/contractions_$langCode.json` and
`langpacks/$langCode/` from the raw binding, and `loadLanguagePairedContractions` built
`contraction_pairs_$langCode.json`. A layout bound to `fr-CA` asked for `contractions_fr-ca.json`,
found nothing, and logged:

```
D ContractionManager: No contraction file for fr-CA (this is normal for some languages)
```

which is the *reassuring* message for a language that ships no contractions. French ships one. The
result is worse than the wrong language: the bar simply stops showing `l'a`, `d'accord`, `c'est`,
with nothing on screen to explain it and a log line saying this is normal.

Severity is high for the same reason A1 is: silent, whole-feature, and invisible to any test that
passes a bare code.

## A4 — three more copies: fixed by naming it once

`WordPredictor`'s three contraction loaders (`loadContractionKeysIntoMaps`,
`loadPrimaryContractionKeys`, `loadSecondaryContractionKeys`) each composed
`getContractionsPath(language)` and `"dictionaries/contractions_$language.json"` inline, and
`ContractionCollisionScanner.replaceMappings` did the same. Six call sites, four of them in the
typing pipeline rather than the swipe pipeline, so this bug was reachable from ordinary
autocorrect/next-word too.

Fixed by putting the naming in one place, `ContractionManager`'s companion:

```kotlin
fun packCode(language: String?): String = SwipeContractionPolicy.baseSubtag(language)
fun bundledContractionsAsset(language: String?) = "dictionaries/contractions_${packCode(language)}.json"
fun bundledPairedContractionsAsset(language: String?) = "dictionaries/contraction_pairs_${packCode(language)}.json"
```

**Why not normalise inside `LanguagePackManager` instead.** Because `getDictionaryPath` must *not*
normalise — a dictionary may legitimately be per-region, and that is the one method where the raw
code is right. Centralising inside that type would have made the correct case wrong to fix the
incorrect one. The distinction is recorded in `getContractionsPath`'s KDoc: vocabulary is regional,
contractions are not.

Log lines still print the caller's own tag (`for fr-CA`), so a device log names the binding rather
than the file.

## A5 — a regional pack is readable by neither engine: reported

The importer accepts `langpack-fr-CA.zip` (same regex as `VALID_CODE`) and installs it to
`langpacks/fr-ca/`. Neither engine reads it: CTC's membership and verdict cache are keyed on the
normalised `fr`, and the geometric engine now agrees with them.

Not fixed, because making it work is not a path change — it needs **four** things to agree on a
region key first: `CtcImportedPackSupport`'s exclusion table and eligibility, `CtcInstalledPacks`'s
persisted verdict cache, `CtcScoringParams.presetFor`'s λ lookup (whose per-language table is keyed
by script), and the evaluator's projection choice. Changing only the path would make an eligible-
looking pack decode at the wrong λ, which is worse than not reading it. Worth noting that the *pack
format's* model is per-script, so the cheapest eventual answer may be to reject region codes at
import with a clear message rather than support them — that is a product call, not a bug fix.

## A6 — `pref_primary_language` is unvalidated on restore: reported

`SettingsValidation.validateString` ends in `else -> true` for unknown string keys, and
`pref_primary_language` is not in the validated allowlist; `SettingsImportPlanBuilder` iterates
every key in the backup rather than a whitelist. A hand-edited or third-party backup can therefore
write an arbitrary tag into `primary_language`.

This is **not** an escalation of A1–A4: after this pass the decoders normalise, so an arbitrary tag
degrades to its base subtag (or to `""` → the English base) rather than splitting anything. It is
recorded because "untrusted input reaches a preference the decoders read" is worth knowing, and
because the fix belongs in the import validator, which is a different subsystem.

## A7 — the TODO's premise, and a rotted cross-reference: fixed

Two pieces of documentation were actively misleading:

- the `dictionaryFor` TODO asserted the unreachability that A1 disproves. Replaced with the chain
  above, so the next reader can check it instead of trusting it.
- `SwipeContractionPolicy.baseSubtag`'s KDoc said *"`GeometricEngineAdapter.dictionaryFor` is already
  a near-miss (see its TODO)"* — pointing at a TODO that no longer existed after this pass, and
  counting a third consumer that no longer exists. Updated to name the real state: two consumers of
  one definition.

---

## Checked and found sound

The adapter's internals were the entry point for this pass and needed no change.

- **`geometryFor`'s memo.** Keyed on a `WeakReference<KeyboardData>` *identity* plus the geometry
  params plus the frame, which is the right key: `KeyboardData` is immutable and replaced wholesale,
  and the `WeakReference` prevents the memo from pinning a layout the user has switched away from.
  The `@Volatile` is unnecessary — every caller is confined to the decode thread — but harmless.
- **The `rectIdx` recovery in `buildGeometry`.** This is the one place a subtle off-by-one would
  hide: it recovers a key's row-major index by walking `rowSpans` and must agree with
  `KeyboardGeometry.computeKeyRects` exactly. It does, and the reason is worth recording —
  both sides filter on `key.keys[0] != null`. `rowSpans` emits a span for *every* cell, but
  `computeKeyRects` drops null-primary ones, so "index into the emitted spans" and "index into the
  computed rects" coincide only because the filter is identical on both sides. They are.
- **`dictionaryMemos`.** A bounded, access-ordered `LinkedHashMap` capped at two
  (`removeEldestEntry: size > 2`), which is the right shape for "current + previous language". The
  failure path removes only the failing language's slot rather than clearing, so a transient read
  error for one language does not discard the other.
- **`decodeAsync`'s generation scheme.** A generation is claimed even for a degenerate swipe (so a
  later result cannot be mistaken for it), re-checked on the main thread in `postIfNewest`, and a
  superseded decode calls back *not at all* rather than delivering a stale result.
  `InterruptedException` is rethrown rather than swallowed, which is what makes the runner's
  foreground-slot supersession actually stop the work.
- **`userWordsOf`.** Tolerates malformed JSON (logs and degrades to the platform snapshot), guards
  blank keys, and `UserDictionarySnapshot.mergeWithCustom` keeps the ordering/filtering policy in
  the pure layer where its tests are.
- **`enPairingBaseFrequencies`.** Degrades to an empty map on any failure rather than failing the
  dictionary build the decode depends on, and rethrows `InterruptedException` so cancellation is
  not silently converted into "promotion disabled".
- **`CtcLanguageSupport`'s purity.** `java.util.Locale` is its only import, so the normaliser this
  pass now depends on is loadable from pure tests — which is why the new test class can pin it.

---

## What this pass did not do

- **Did not fix A5 (regional packs).** Reasoned above: it needs four subsystems to agree, and a
  path-only change would decode at the wrong λ.
- **Did not add region support to `pref_primary_language` validation (A6).** Different subsystem
  (backup import), and the decoders now fail safe anyway.
- **Did not re-read `TemplateCache.kt` (243), `CandidateRanker.kt`, `TemplateGenerator.kt`,
  `CandidatePruner.kt`, `GeometricUserWordMerge.kt` (76) or `GeometricSwipeEngine.kt` (164).**
  Those remain the open surface in this subsystem.
- **Did not run any accuracy harness locally** — there is no Gradle or Kotlin compiler in this
  sandbox. The claim that this pass cannot move an accuracy floor rests on the change being a
  pure name resolution on `String` values that are bare codes in every shipped configuration: for
  `fr`, `normalize("fr") == "fr"`, so nothing that passes a bare code moves. CI ran the full
  accuracy suite (`GeoAccuracy*`, `GeoAblationTest`) on both green runs.
- **Did not touch the `LanguagePackManager` path composition itself** (A4's rationale), nor
  `getDictionaryPath`, which is correct as-is.
- **Did not verify on a device.** A1–A4 are reachable only by binding a layout to a region code in
  Layout Manager, which no test device here is configured for.

---

## CI

| run | head | suite | APK | smoke |
|-----|------|-------|-----|-------|
| 1 | `7d26a6a` | **failed — compile error**: `Only safe (?.) or non-null asserted (!!.) calls are allowed on a nullable receiver of type 'java.io.File?'` at `GeometricEngineAdapter.kt:725` | failed | failed |
| 2 | `5be75d7` | **failed** — same class of failure, unrelated surface (job log unreachable from this sandbox; annotations only) | — | failed |
| 3 | `296c0fa` | **`45060754592` green** — 4884 run, 0 failed, 134 skipped, 438 classes | pass | pass |

Part 11 ended at 4879 run / 437 classes; this pass adds **5 tests and 1 class** (4884 / 438).
Skipped stays at **134**, so the skip ratchet is untouched.

Run 1 is worth keeping in the record. The unit suite caught a Kotlin nullability error that would
otherwise have shipped, and it caught it in the *test* task (which compiles main sources) while the
APK job was still queued — the annotation `the unit-test task produced NO results - the test sources
did not compile. Nothing was verified.` is exactly the right message. The cause was a real design
smell, not a typo: while trying to delegate to `langpackRelativePath` I introduced an optional
`File?` and then left one `.isFile` usage behind. Reverting that delegation (see A2) removed the
optional entirely, which is the outcome that is both simpler and free of the timing dependency.

Run 2's failure is recorded honestly as **not diagnosed**: `gh api .../jobs/<id>/logs` returns a
blob-store EOF in this sandbox and the annotations do not name the cause. It was superseded by a
squash-equivalent replacement commit before the diagnosis was possible, and the same change is green
in run 3.

The two commits here were rewritten once (`--force-with-lease`, local-only, branch predates any
review) because the first split carried the compile error in the middle commit. Order now: A1/A2
first (`220bdfd`), then A3/A4 (`1c5c8d9`), each independently green-shaped.
