# Bug hunt — 2026-11-15, part 6: `personalization/`

Parts 4 and 5 were about the test suite. This one goes back to production code, into a package no
earlier pass had opened: `src/main/kotlin/tribixbite/cleverkeys/personalization/` — 1091 LOC over
three source files.

It found a privacy leak in the code that *stores what the user types*, and a thread the typing path
did not need.

| # | Finding | Severity | State |
|---|---|---|---|
| P1 | `UserVocabulary` logged each evicted word — the user's own typing history — to logcat, ungated | High (privacy) | **fixed** |
| P2 | The leak was invisible to `VerboseLogRatchetTest`; the injected-logger blind spot is general | Medium (process) | reported |
| P3 | `performCleanupAsync` spawned a raw non-daemon thread from inside the typing-path lock | Medium (performance) | **fixed** |
| P4 | Eviction sorts the entire vocabulary to free a single slot | Low (performance) | reported |
| P5 | `mutationVersion` bumps on a path that mutates nothing | Low (correctness) | reported |
| P6 | `MIN_USAGE_THRESHOLD` is declared and effectively unused | Low (dead code) | reported |
| P7 | `VerboseLogRatchetTest` counts commented-out and quoted `Log.d` text as real calls — it caught my own KDoc | Low (test tooling) | reported |

## P1 — the vocabulary wrote the user's words to logcat

`UserVocabulary` tracks which words the user actually types, so that prediction can boost them. That
makes its contents the most sensitive data in the IME: not settings, not a preference, but your
typing history. Every entry is a word you wrote.

The eviction path logged each one:

```kotlin
private fun removeLowestValueWords(count: Int, currentTime: Long) {
    vocabulary.values
        .sortedBy { it.getPersonalizationBoost(currentTime) }
        .take(count)
        .forEach { usage ->
            vocabulary.remove(usage.word)
            log("Removed low-value word: ${usage.word}", null)   // <- user text
        }
}
```

`AGENTS.md` is explicit that user text never appears in logcat, and the codebase already takes that
seriously elsewhere: `Logs` and `PrivacyManager` strip words out of their own lines precisely so that
a diagnostic message cannot carry a keystroke. This path had no such stripping.

Two things made it worse than an ordinary logging slip, and together they explain how it survived
every previous wave.

**It was not gated on verbose logging.** The file contains **zero** references to
`ENABLE_VERBOSE_LOGGING`. The singleton logger is a plain `Log.d`, so this printed on every eviction
in the shipped debug build, on every device, whenever the vocabulary grew past its cap.

**A ratchet exists for this class of bug and could not see it.** Part 5's sibling guard,
`VerboseLogRatchetTest`, counts ungated `Log.d/i/v` call sites and enforces a ceiling of 242. It did
not fire here, and it could not: it matches the literal text `Log.d(...)`, while this call site is
spelled `log(...)` — an injected `(String, Throwable?) -> Unit`. Grepping the package confirmed this
was the only line carrying user text; `PersonalizationEngine` logs only its enabled/aggression/clear
events, and `UserWordUsage` is a pure data class with no logging at all.

The fix logs the count once, after the loop:

```kotlin
log("Removed $count low-value word(s)", null)
```

A count is what an eviction log is for. Nothing that diagnoses this path needs to know *which* words
went.

## P2 — the blind spot is not specific to this class

P1 is fixed, but the reason it was invisible is structural and outlives the fix.

Any class that takes a `log: (String, Throwable?) -> Unit` and passes user-supplied content through
it is invisible to the 242-ungated-`Log.d` ratchet. The ratchet reads source text; it cannot follow a
lambda into the function that eventually calls `Log.d`. `UserVocabulary` is where this surfaced, but
it is not the only injectable-logger class in the codebase, and the ratchet gives no coverage of the
rest.

This is reported rather than fixed because the fix is a design decision, not a patch: either the
ratchet grows a second rule that taints arguments flowing into an injected logger, or injected
loggers stop accepting free-form strings. Both are larger than one wave. What is recorded here is
that a green ratchet count does not mean the logging rule holds.

## P3 — a raw thread, created under the typing lock

`performCleanupAsync` was:

```kotlin
private fun performCleanupAsync() {
    thread {
        cleanupStaleWords()
    }
}
```

and its only caller is inside `recordWordUsage`'s `synchronized` block:

```kotlin
synchronized(this) {
    ...
    if (timestamp - lastCleanup > CLEANUP_INTERVAL_MS) {
        performCleanupAsync()     // on the typing thread, holding the lock
    }
    ...
}
```

So the typing thread allocated and started a **non-daemon** thread, once per `CLEANUP_INTERVAL_MS`
(24 h), while holding the lock every keystroke contends for. Thread creation is not free, and a
non-daemon thread outlives the IME — this is the same anti-pattern the class KDoc says the debounced
persister was introduced to replace, left behind in one method.

The store already owns a `ScheduledExecutorService`: it is passed to `DebouncedPersister` for
write-back. Submitting the cleanup there needs no new machinery, serializes cleanup against
persistence instead of racing it, and is torn down with the store:

```kotlin
private fun performCleanupAsync() {
    try {
        scheduler.submit {
            try {
                cleanupStaleWords()
            } catch (e: Exception) {
                log("Stale-word cleanup failed", e)
            }
        }
    } catch (e: RejectedExecutionException) {
        // Store is tearing down; the next process start runs cleanup on load anyway.
        log("Stale-word cleanup skipped (scheduler shut down)", null)
    }
}
```

Two details that matter. The inner `catch` is not decoration: an exception escaping a submitted task
kills that executor's worker silently, so the second tick of cleanup would never run. And the outer
`catch` handles the shutdown race — teardown can shut the scheduler down between the caller's check
and the submit, and crashing the typing path over a maintenance task would be a worse bug than the
one being fixed. `scheduler` moved from a constructor parameter to `private val`.

## P4–P6 — measured, not fixed

**P4: full sort to free one slot.** `removeLowestValueWords` does
`sortedBy { getPersonalizationBoost() }.take(count)`, which is O(n log n) over the whole vocabulary
for what is usually `count == 1`. `enforceCap` passes the entire overflow so the sort earns its keep
there, but the hot path — one new word arriving at a full vocabulary — pays for a total order to
find a single minimum. A `count`-sized min-heap, or an O(n) scan when `count == 1`, would be
proportional to what is evicted rather than to what is stored. Not fixed: at the default cap (5000)
one sort per eviction is not a measured latency problem, and this wave's job was the leak.

**P5: `mutationVersion` bumps on a lookup-only path.** `recordWordUsageWithReceipt` increments
`mutationVersion` at the top of its `synchronized` block, before it discovers the word is too short
to record and returns `null`. A version bump that no mutation corresponds to is a small correctness
smell in the rollback scheme (`IncrementReceipt`/`rollbackReceipt` compare versions to decide whether
a rollback is still safe): it can make an unrelated rollback look stale and be refused. Rejecting a
rollback is the conservative direction, so this costs no data — but it makes the version a proxy for
"someone entered the critical section" rather than "the map changed".

**P6: `MIN_USAGE_THRESHOLD = 2` is dead.** Constant declared, no read. `isStale` uses a bare literal
(`usageCount == 1`) that is *related but not equal* — it is the opposite comparison — so this is not
a case of a constant waiting to be wired up; it is a leftover. Either delete it or express the
staleness rule in its terms.

One more, noted while reading: `recordWordUsage` takes `synchronized(this)` for every keystroke's
word, and the cleanup threshold check lives inside that same block. The check is a single long
comparison, so this is not the measured bottleneck, but it is the reason P3's thread creation landed
on the lock in the first place.

## P7 — the log ratchet counts prose

The first CI run of this wave failed, and the failure is worth keeping.

`VerboseLogRatchetTest` reported **243** unguarded `Log.d/i/v` calls against a ceiling of 242. My
diff added no `Log.d` call. It added a comment that quoted one: explaining P1's blind spot, the KDoc
said the call site is spelled

```
`log(...)`, not `Log.d(...)`
```

The ratchet matches `\bLog\.[div]\(` against raw source text and does not strip comments, so the
sentence *describing* the leak counted as an instance of it. Rewording it back to 242 was the fix.

Two things follow. The ratchet is textual, so it will fire on documentation, disabled code and
string literals; when it does, the first question is "is this a call or a mention?" — a check for
`Log.d` in a comment costs nothing. And, more usefully, this is evidence the guard is live: it
reacted to a documentation edit in a file nobody thinks about, which is the behaviour you want from
a ratchet. Part 5's `SkipRatchetTest` strips comments for exactly this reason; this older ratchet
does not, and tightening it would be a small, separate change (strip comments before counting, then
re-baseline). Not done here, because re-baselining a 242-call ceiling belongs in a wave that is
about the ratchet, not in a privacy fix.

## The test

`UserVocabularyLoggingTest`, four cases, registered in `build.gradle` (`pureTestClasses`). It injects
its own logger into a synchronized list and its own `ScheduledThreadPoolExecutor`, shut down in
`@After`.

The two eviction paths are genuinely different code routes, and both are covered:

1. **Hot path** — fill to a cap of 100, clear the log, record one further word, assert exactly one
   eviction happened and no line names any word.
2. **Load path** — write 150 words at a cap of 200, flush, then construct a second store over the
   same storage with a cap of 100. The constructor calls `enforceCap`, which trims 50 entries. This
   route cannot be observed by clearing the log (it happens during construction), so the assertion
   runs over the whole log. It is also the realistic scenario: the user lowered
   `personalization_max_words` after the blob was written.
3. **Whole-run sweep** — 150 words into a cap of 130, so eviction happens mid-fill, plus an explicit
   `cleanupStaleWords`, then assert no line contains any recorded word and the logger was called at
   all (so the assertion cannot pass vacuously).
4. **The count survives** — asserts the eviction line still reports the number, and specifically
   that the old `"Removed low-value word:"` string is gone.

Both the `MIN_VOCABULARY_CAP = 100` clamp and the scoring were checked before trusting the
assertions: `currentCap()` is `maxWords().coerceAtLeast(100)`, so caps of 100/130/200 are honoured
as written, and modelling `getPersonalizationBoost` (`log10(n+1)+1`, times a 1.0 recency for
same-timestamp entries) confirms the victim of each eviction is a word the test planted.

**It fires.** Re-injecting the old logging line makes the assertions fail; the fix was restored by
script and the tree re-checked. A test for a leak that cannot fail is not evidence of anything.

Typecheck against the base (`scripts/ci/offline/typecheck.sh --tests 97ab129`) reports **0
diagnostics novel to the tree**. The 18 per-file diagnostics are the expected missing
JUnit/Truth/`android.util.Log` noise — 4 `@Test`, `@After`, the assertions and the `junit` imports,
which that offline classpath does not have.

## Verified by CI

| Workflow | Run | Result |
|---|---|---|
| `Build APK` | `44912140325` | **green** |
| `Full unit-test suite` | `44912157357` | **red** — 4855 run, 1 failed, 134 skipped in 432 classes; the one failure was P7, not a leak |
| `Emulator smoke test` | `44912140379` | **green** |

The failing test was **not** one of mine: all four `UserVocabularyLoggingTest` cases passed, as did
the other 4850. The single failure was `VerboseLogRatchetTest#unguardedDebugLogCalls_doNotGrow`, on
the KDoc quoted in P7, and it is fixed in a follow-up commit on this branch.

## Checked and found sound

- **`UserWordUsage`** (152 LOC) — pure data class. `getPersonalizationBoost` is frequency × recency;
  `calculateFrequencyScore` is `log10(n+1)+1`, which stays monotonic and non-zero at `n = 1` (a bare
  `log10` would have scored a first use as 0 and made it un-boostable); `getRecencyScore` decays in
  three documented bands; `isStale`'s two conditions are disjoint by construction. No logging.
- **`PersonalizationEngine`** (413 LOC) — logs only its own state transitions (enabled, aggression
  level, clear). No user text anywhere. No `Log.d` outside those three sites.
- **`normalizeWord`** — `lowercase().trim()` is used as the map key consistently on every write and
  read path, including `importFromJson`, so a word cannot be stored under one key and boosted under
  another.
- **`clearAll`** — removes the storage key inside the same lock `writeToStorage` holds across
  serialize+write, so an in-flight flush cannot resurrect a cleared vocabulary. (Not new to this
  wave; confirmed to be the reason the ordering holds.)
- **`loadFromStorage`** — logs only the loaded *count*, and `importFromJson` only the imported
  count. Both were already clean.
- **The debounced write-back** — the class KDoc's claim that it replaced a raw thread with a ~5 s /
  ~30 s persister is accurate for `flush`/`requestFlush`; `flush()` runs the write inline under
  `flushExecutionLock`, which is what makes a synchronous `flush()` in a test meaningful.

## What this pass did not do

- **It did not review the learning/rollback semantics.** `IncrementReceipt` and `rollbackReceipt`
  carry two audit-tagged fixes (2026-09-26, 2026-08-06) and a version-comparison protocol with
  `mutationVersion`. I read it far enough to say P5 is conservative-but-imprecise; I did not
  attempt to prove the protocol correct under concurrent rollback.
- **It did not measure performance.** P3 and P4 are argued from the code (thread creation under a
  contended lock; O(n log n) to remove one element), not from a profile. There is no on-device
  latency harness in this checkout for this package.
- **It did not touch `importFromJson`'s trust in the JSON.** The blob is local, but it is user
  importable via the settings import path, and `importFromJson` clears the live vocabulary before
  validating that the new one parses. A reference to its fail-open/fail-closed behaviour is all I
  have; it deserves its own pass.
- **It did not address P2's root cause**, the injected-logger blind spot in the log ratchet. That
  needs a decision on the ratchet's design, not a patch on this class.
- **It left P4–P6 unfixed** — reported with reasoning and a suggested fix each, deliberately, to
  keep the diff reviewable and the privacy fix isolated.
- **Other packages remain unhunted**: `gif/` (9 files, 2484 LOC), `voice/` (`DictationController`
  619), `a11y/` (3 files, 696), `ml/` (`SwipeMLDataStore` 735, `SwipeMLData` 532), the geometric
  `GesturePreprocessor`/`TemplateIndex`/`TemplateCache`, settings persistence, the emoji grid build,
  `Pointers.kt`, `KeyEventHandler.kt`, `TrigramStore` import/export, `BigramModel`.
