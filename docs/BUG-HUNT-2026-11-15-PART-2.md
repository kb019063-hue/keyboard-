# Bug hunt — 2026-11-15, part 2: the ONNX session layer and the context LM

A second pass over a different subsystem from [part 1](BUG-HUNT-2026-11-15.md): the ONNX Runtime
session lifecycle (`onnx/ModelLoader.kt`, `swipe/OnnxCtcEmissionModel.kt`,
`swipe/CtcEngineAdapter.kt`) and the learned context model (`contextaware/`). Neither had been
audited before — the earlier hunts' "not hunted" lists name both.

One defect is fixed with a test. Three more are reported. The most useful result is arguably the
one that *didn't* become a fix: several things that look like bugs in `contextaware/` are
deliberate, and the code says so in the one place a reader would look. Those are written up under
"Checked and found sound" because the reasoning is the valuable part.

Status: **UNVERIFIED**. Nothing here has been through CI yet. The front-end check and what it does
and does not prove are at the end.

| # | Finding | State |
|---|---|---|
| M1 | `loadModel` created an `OrtSession.SessionOptions` per load and closed it nowhere — a native handle leaked on every model load | fixed, pinned |
| M2 | If adopting a freshly created session failed, both native handles were abandoned (the load-failure catch only counted an attempt) | fixed |
| M3 | `readModelBytes` enforces a *minimum* model size but no maximum, and `readBytes()` is a full copy into the IME heap | reported, low — not reachable from untrusted bytes today |
| M4 | `ModelLoader.validateSession` is dead code | reported, low |
| M5 | `BigramEntry` denominators (`word1Frequencies`) deliberately outlive the entries they counted; the export/import path cannot express them | reported, informational |

## M1. A native handle was allocated on every model load and never released

`ModelLoader.createOptimizedSessionOptions()` constructs `OrtSession.SessionOptions()` and the
bytes-based `loadModel` handed it to `ortEnvironment.createSession(modelData, sessionOptions)`.
That was the last mention of it: `grep` for `sessionOptions.close` across `src/main` found nothing,
before or after the session was created.

This is not a GC-visible leak. From ORT's own `OrtSession.java`:

```java
public SessionOptions() {
  nativeHandle = createOptions(OnnxRuntime.ortApiHandle);   // native alloc
  ...
}
@Override
public void close() {
  if (!closed) { ... closeOptions(OnnxRuntime.ortApiHandle, nativeHandle); closed = true; }
  else { throw new IllegalStateException("Trying to close a closed SessionOptions."); }
}
```

The Java object is a wrapper around a C++ handle that only `close()` frees, and the docs are
explicit that `createSession` copying the options does not transfer ownership of that handle. So
each load leaked one options handle for the life of the process. `ModelLoader` is constructed
inside `CtcEngineAdapter.modelOrNull` (`CtcEngineAdapter.kt:397`) — i.e. once per model load — and
`MAX_LIVE_SESSIONS` is 2, with an adapter rebuilt on every IME teardown rather than only at
process death. The number of leaks per process is therefore bounded by the number of loads, not
unbounded; the leak is real but small in count. Its byte size is **not measured** — I could not
resolve the ORT AAR (no Maven Central), so treat "how big" as unknown rather than negligible.

### Why the one-line fix is wrong, and what the fix does instead

The obvious patch — `close()` right after `createSession` — is forbidden by the same docs:

> The SessionOptions object **must not be closed until all sessions which use it are closed**, as
> otherwise it could release resources that are in use.

So the handle has to live exactly as long as the session, and be released *after* it. The options
now travel with the session to whoever owns it:

- `ModelLoader.LoadResult` carries `sessionOptions` and gains `closeSession(result)`, which closes
  the session and then the options, each in its own `try` so a failed session close cannot strand
  the options handle.
- `OnnxCtcEmissionModel` takes the options as an optional third constructor parameter and closes
  them second in `close()`. That class already owned the session, so it is the natural owner; the
  parameter is nullable so test callers that build a session directly are unaffected.
- `CtcEngineAdapter.modelOrNull` passes `loaded.sessionOptions` through.

`LoadResult` was a `data class` and is now a plain `class`: it holds a native handle, and generated
`equals`/`hashCode`/`copy` on an `AutoCloseable` is the kind of API that invites a second close.
Nothing used them — `LoadResult` has one consumer and no destructuring, `copy` or equality in
`src/main` or `src/test`.

## M2. A failed session adoption abandoned both handles

The same call site:

```kotlin
OnnxCtcEmissionModel(ortEnvironment, loaded.session).also { emissionModels[asset] = it }
```

Sits inside a `catch (e: Exception)` that records a failed attempt and returns null. If
construction threw (the model wrapper allocates; the map's `removeEldestEntry` closes a session and
can throw), the freshly created `OrtSession` was simply dropped with no `close()`, and before M1
the options with it. Bounded by `MAX_MODEL_LOAD_ATTEMPTS = 3`, so at most three abandoned sessions
per asset per process — moderate, and only on a path that is already failing.

Fixed by wrapping the adoption in its own `try`/`catch (e: Throwable)` that calls
`loader.closeSession(loaded)` and rethrows, so the existing failure handling still runs and the
attempt counter still latches. `Throwable` rather than `Exception` because the handles need
releasing even on an `OutOfMemoryError` — allocation is the thing that fails here.

## M3. A model read has a floor but no ceiling (reported, low)

`readModelBytes` rejects a stream under `MIN_MODEL_SIZE_BYTES` (1 KiB) and otherwise drains it with
`readBytes()`. There is no upper bound, so the whole model is a second full copy in the IME heap
(one copy on the stream, two during the grow-and-copy of `readBytes`).

I expected this to matter and it mostly does not, in the current code:

- The **only** production caller is `CtcEngineAdapter`, and its language-pack branch goes through
  `CtcPackModel.verifiedPackModel`, which caps the file at `MAX_PACK_MODEL_BYTES` (8 MiB) *before*
  reading. The APK-asset branch reads a build artefact, which is not attacker-controlled.
- The `content://` and `/sdcard/...` branches of `loadModelBytes` — which is where an arbitrary
  user-chosen file would arrive — have **no caller in `src/main`**. They are reachable only from
  code that does not exist yet.

So this is a latent hazard rather than a live bug: the safe pattern was applied to the pack path
and not to the shared reader beneath it. If a model-import UI is ever wired to those branches, this
becomes a heap-exhaustion path in the IME process, and IME processes are the ones the system
penalises for memory. Worth a ceiling in `readModelBytes` at the point someone adds that caller;
not worth a change today, and I am not making one, because an invented cap would break nothing
visible and prove nothing.

## M4. `validateSession` is dead code (reported, low)

`ModelLoader.validateSession` has zero call sites in `src/main` or `src/test`. Either it should run
after `createSession` (a session that constructs but fails a smoke inference is exactly the
NNAPI-driver case the execution-provider fallback chain exists for), or it should go. Left alone
because choosing between those is a behaviour decision, not a cleanup.

## M5. The denominators deliberately outlive their entries (reported, informational)

`BigramStore` keeps a per-context `word1Frequencies` total next to the entry list, and
`pruneIfNeeded` drops entries **without** decrementing it, while `removeBigram` /
`unrecordBigram` / `purgeEntries` **do** decrement (via `rescaleAfterRemoval`). That asymmetry
looks like a bookkeeping bug and is not: `MAX_BIGRAMS_PER_WORD` drops continuations that the
observed total still counts, and the format-v2 blob root comment (ARC-080) is explicit that the
surviving list cannot reconstruct the denominator — keeping the true total is the entire point of
v2. Decrementing on eviction would re-create the inflation ARC-080 fixed.

The related asymmetry in exemption semantics is also fine, for a reason that depends on call order:

- `ContinuationBudget.enforce` matches `keep` by identity (`===`), and both stores pass the object
  they just inserted into the list it is in, so identity holds.
- `ContinuationBudget.globalVictims` matches `isKept` by value (word1+word2 / prefix+word3),
  because by then the list has been rebuilt from `copy()`d entries.

So "the same policy uses two different notions of 'the entry I just wrote'" is real but harmless.
Recording it because the next person to touch either store will notice it and have to re-derive
this.

Where v2 *does* stop is the backup payload: `exportToJson`/`importFromJson` are a separate,
deliberately unversioned contract (the same comment says so) that carries entries and frequencies
but not the denominators. A backup round-trip therefore cannot reproduce `restoreTotals`, and
probabilities shift slightly on restore. Whether that matters depends on
`NextWordPredictor.MIN_LEARNED_PROBABILITY`, which a restore is unlikely to cross. Informational,
no change proposed.

## Checked and found sound

- **`OrtEnvironment` lifetime.** `CtcEngineAdapter` calls `OrtEnvironment.getEnvironment()`, which
  is process-global and refcounted by ORT itself. The adapter never closes it — correct, since
  another adapter (or the next IME teardown) would be handed a closed environment.
- **Session eviction.** `emissionModels` is an access-ordered `LinkedHashMap` whose
  `removeEldestEntry` closes the session it drops, capped at `MAX_LIVE_SESSIONS = 2`. The newest
  entry can never be its own eldest victim, so a decode cannot have the session it is using closed
  under it.
- **Session-cache thread-safety.** The LRU is not synchronized, which is correct here: all access
  is on `PredictionTaskRunner`'s *single-threaded* executor (`submitBackground` and
  `cancelAndSubmit` both submit to one `newSingleThreadExecutor`). It would not survive a second
  decode thread, and the comment claiming the decode thread owns eviction is the thing that keeps
  that honest.
- **`shutdown()`.** Closes every session only after the decode thread is confirmed dead, and
  deliberately leaks them if it is not, on the stated grounds that a leak beats a native crash in
  a keyboard. `close()` implementations swallow teardown exceptions. All of this is more careful
  than the average teardown path, which is what made the missing options `close()` look like an
  oversight rather than a decision.
- **`OnnxCtcEmissionModel.emit`.** All three input tensors and the `OrtSession.Result` are in
  `use` blocks, and the output tensor is read out of the result before it is released. No leak
  per inference.
- **`BigramEntry.calculateProbability`.** Guards `word1TotalFrequency == 0`, so the
  entries-and-denominator split cannot produce a NaN or an infinity.
- **`restoreTotals`.** Clamps the denominator to at least the sum of surviving entries, so a
  hand-edited or truncated blob cannot yield a conditional probability above 1.
- **`ContinuationBudget.enforce` / `pickVictim`.** The `victimIndex < 0` break, the
  `graceSlots`-aware sub-floor split, and the `bulk` (`keep == null`) branch are covered by
  `ContinuationBudgetTest`, which already exercises ten cases including grace slots, staleness and
  bulk import. I did not find a case it misses.
- **`pruneIfNeeded`'s `keepWord1`/`keepWord2` exemption.** Matches structurally, so it survives the
  `copy()`-and-rebuild that precedes it. Correct, unlike the identity match in `enforce` — see M5
  for why both are right for their position.

## What this pass did not do

- **No CI run.** Everything above is a reading of the source. Per `AGENTS.md` the sandbox cannot
  build (no Android SDK, no Gradle distribution, no Maven Central), so GitHub Actions is the
  compiler and a green run is the only verdict. M1 and M2 are **unverified** until then.
- **The leak size is unmeasured.** I confirmed the handle exists and is never freed; I did not
  measure bytes per `SessionOptions` on-device, and could not resolve the AAR to read the native
  side. The claim is "a handle leaks per load", not "N KB leak per load".
- **`TrigramStore`'s import/export and `ContextModel`'s rollback path** were read only around the
  parts the above touches (`enforce` call sites, `recordTrigram`'s insert-then-bound order). Their
  remaining several hundred lines, and `BigramModel` (the non-learned shipped LM), were not
  audited.
- **The offline front-end check** was run with `--tests` against base `0916911`. It reports 45
  diagnostics new *to the file*, and every one is in one of two benign groups: the new test file
  importing JUnit and Truth, which that check cannot see (no Maven Central), and three
  `unresolved reference 'close'` in `ModelLoader.kt` at the three new `close()` calls on
  `OrtSession`/`SessionOptions` values. ORT is not on that check's classpath — android.jar, the
  stdlib and coroutines only — and the base tree already reports the identical diagnostic at
  `OnnxCtcEmissionModel.kt:102` and `CtcReplayEngine.kt:235,236`, which are the `.close()` calls
  that shipped long ago. The `close()` methods exist: they are verified against ORT's published
  `OrtSession.java` source, quoted above, rather than assumed. Nothing else in the four changed
  production files gained a diagnostic.

The pinning test is `ModelSessionOptionsLifetimeTest` (six cases, registered in `build.gradle`).
It is a source scan, not a behavioural test, for the same reason as the existing
`ModelLoaderOrtCacheTest`: `onnxruntime-android`'s native library cannot load in a JVM unit test,
and what needs pinning is the *order* of teardown calls, which a scan can check and a unit test
cannot reach. It asserts the options are carried out, closed on the failure path, never closed
before `createSession`, and closed after the session in both owners.
