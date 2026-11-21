# Bug hunt — 2026-11-15, part 7: `voice/`

Part 6 went into `personalization/`. This one opens `voice/` — 978 LOC over four files, previously
unhunted: `DictationController` (619), `DictationPolicy` (195), `VoiceLanguagePolicy` (119),
`VoiceTypingConfig` (45).

This package is the best-documented code in the repository. Almost every branch carries a dated
comment explaining the user report that produced it, and the architecture is deliberately split so
that the branch table is pure Kotlin and testable off-device (41 existing tests). I went in
expecting to find little, and mostly that was right — but the split has a consequence the comments
do not mention.

| # | Finding | Severity | State |
|---|---|---|---|
| V1 | A second mic tap during the first press's async language query is ignored; the deferred start opens the microphone and commits the cancelled transcript | Medium (behaviour, privacy) | **fixed** |
| V2 | `queryLanguagesOnce` calls `unregisterReceiver` on a receiver that was never registered — always throws, always caught | Low (dead code) | **fixed** |
| V3 | The silent retry releases audio focus and immediately re-acquires it | Low (performance) | **fixed** |
| V4 | `DictationController` — the 619-line file where the bugs live — has no unit test, by construction | Medium (process) | reported |
| V5 | The emulator smoke run cannot exercise the in-process recogniser either | Low (coverage) | reported |

## V1 — the cancelled press that listens anyway

This is the finding of the wave, and it is a race between two halves of a correct design.

On the **first** press of a process, the controller cannot listen yet: it has to ask the recognition
service which language tags it supports (`ACTION_GET_LANGUAGE_DETAILS`, a broadcast), because asking
for a tag the service has no model for is what produces "Couldn't catch that" on a device whose
locale is `en-GH`. So `startListeningWithNegotiatedLanguage` is deferred until the answer arrives.

Meanwhile `press()` sets `sessionActive = true` **synchronously** — deliberately, and the class KDoc
says why: "that is what makes a fast double-tap a start-then-stop instead of two starts".

So in that window the flag says *listening* while no recogniser exists. A second tap is answered by
`DictationPolicy` with `STOP_LISTENING` — correct — and then:

```kotlin
private fun stopListening() {
    try {
        recognizer?.stopListening()   // recognizer is still null
    } catch (t: Throwable) { ... }
}
```

`?.` on null does nothing. No exception, so the `catch` that would have called `endSession()` is not
reached, and **`sessionActive` stays `true`**. The broadcast then answers and the deferred start runs
unconditionally:

```kotlin
private fun startListeningWithNegotiatedLanguage(forceDefault: Boolean = false) {
    val active = obtainRecognizer()      // creates it
    ...
    active.startListening(intent)        // opens the microphone
```

The microphone opens for a press the user cancelled, and the transcript it hears is **committed**,
because `DictationPolicy.onTranscript` gates on exactly the flag the ignored stop failed to clear:

```kotlin
fun onTranscript(text: String?, isFinal: Boolean, sessionActive: Boolean): Delivery {
    if (!sessionActive) return Delivery.DISCARD
    ...
```

The window is as wide as the recogniser's reply to the broadcast — milliseconds for a service that
answers promptly, hundreds for a slow or remote one. The keyboard shows nothing in that gap (no
"Listening…" yet, because that message is emitted after `startListening`), so tapping again because
nothing seemed to happen is the natural response. The old code punished exactly that.

Two accounts of the impact, so neither is overstated:

- **What it is not:** permanent. The deferred start armed the 60 s watchdog, so the session ends by
  itself. It is not the "mic key does nothing now" report that `DictationSessionWatchdogTest` covers.
- **What it is:** the microphone is live after the user cancelled, and a final result types text
  into their editor. For a dictation feature that is the wrong outcome twice over.

The fix is two guards, one line each:

```kotlin
// stopListening: a stop with nothing to stop must still end the session
if (recognizer == null) { endSession(); return }

// startListeningWithNegotiatedLanguage: never revive a cancelled session
if (!sessionActive) { abandonAudioFocus(); return }
```

Either alone would stop the microphone opening; both are present so the invariant does not depend on
which of the two paths is reached first, and so a future caller that invokes the start method
directly cannot skip it. `endSession()` on the stop path also removes the watchdog callback and
releases audio focus, which is what makes the flag and the resources agree.

**Why the split did not catch it.** The architecture puts every *decision* in `DictationPolicy` and
every *effect* in `DictationController`, and the decisions are exhaustively tested. This bug is not a
wrong decision — the policy's double-tap answer was right — it is the controller failing to carry one
out. The 41 tests cover the table and none of the state machine.

## V2 — a receiver that is never registered, unregistered anyway

```kotlin
val receiver = object : android.content.BroadcastReceiver() {
    override fun onReceive(ctx: Context?, broadcast: Intent?) {
        try {
            context.unregisterReceiver(this)
        } catch (t: Throwable) {
            Log.w(TAG, "unregister language receiver threw: $t")
        }
```

`receiver` is passed to `sendOrderedBroadcast` as the **result receiver** argument. It is never
passed to `registerReceiver`. The platform documentation for `Context.unregisterReceiver` is explicit:

> Throws `IllegalArgumentException` — if the receiver was not previously registered or already
> unregistered. ([Context | API reference](https://developer.android.com/reference/android/content/Context))

So the call threw `IllegalArgumentException` on every single language query, and the `catch` swallowed
it into a `Log.w` that reads like a real diagnostic. The result receiver is owned by the broadcast
and needs no release. Removed — the `catch` was hiding the mistake, not handling a failure.

This is a small bug with a diagnostic lesson: a `try/catch` around a call that can only fail by being
wrong turns a crash into a log line nobody reads.

## V3 — focus released and re-acquired in the same breath

The silent retry (one attempt with no language tag, when a forced tag produced a decode failure) did:

```kotlin
abandonAudioFocus()
main.post { startListeningWithNegotiatedLanguage(forceDefault = true) }
```

and that method begins with `requestAudioFocus()`. So the session released transient audio focus and
immediately re-requested it — while the suggestion bar still said "Listening…" and the user
experienced one continuous session. `requestAudioFocus()` already returns early when `focusHeld`, so
the correct behaviour is simply to keep it. The line is gone; the retry now holds the focus
throughout.

## V4 — the untested 619 lines

`voice/` has 41 tests across five classes, and every one of them tests a pure object. Nothing
constructs `DictationController`, which is where V1, V2 and V3 all lived. That is not an accident of
effort — the class needs a `Context`, a main `Looper` and a `SpeechRecognizer` — but it means the
largest file in the package has no regression net, and the two bugs that matter were both in it.

`DictationStopDuringStartTest` (new, 4 tests) pins the invariants V1 broke, including a control case
that asserts the pre-fix sequence *does* open the microphone, so the test cannot pass vacuously. It
models the state machine rather than driving it, because driving it needs the three dependencies
above. A Robolectric shadow of `SpeechRecognizer` would let the real class be exercised — Robolectric
4.11.1 is already a `testImplementation` and 10+ existing tests use it — but that is a piece of test
infrastructure, not a bug fix, and it is the honest next step for this package rather than something
to bolt onto this pass.

## V5 — the smoke run cannot reach the recogniser either

`scripts/ci/emulator-smoke.sh` §7e is careful and honest about this: the CI emulator image has no
speech service and no microphone, so `isRecognitionAvailable` is false and the policy takes the
fallback branch. The run proves the *self-test* path — the mic key commits a fixed transcript through
the real `InputConnection` — which is genuinely valuable and is how the commit path is verified at
all. It does not, and cannot, exercise a real session, so V1's window cannot open there.

## Checked and found sound

- **`DictationPolicy.onPress`** — the whole table, in the documented order, and the order is load
  bearing. `listening` first (a running session must be stoppable whatever else changed);
  self-test before availability (it exists to prove the commit path on a device with no recogniser);
  no-recogniser before permission (asking for the mic leads nowhere without one); `permissionRefused`
  before `REQUEST_PERMISSION` (Android stops prompting after two denials, so a third ask is a panel
  that can never succeed). All six terminal states are reachable and 18 tests cover them.
- **`onTranscript`** — three independent reasons to discard (dead session, partial, blank), in the
  right order. Partials are deliberately dropped: the suggestion strip belongs to the prediction
  engine.
- **`commitPrefix`** — blank field gives no space, trailing whitespace gives none, otherwise exactly
  one. Correct for the "two dictations in a row" case, which is what it is for.
- **`onFailure`** — every one of the nine error codes maps somewhere sensible, and the default is
  `UNKNOWN` rather than a swallow. `ERROR_SERVER`→`SERVICE_UNREACHABLE` is the right grouping for
  the offline case.
- **The watchdog's clock handling** — the comment claims the stamp is taken where a session actually
  begins, and it is: `sessionStartedAt = SystemClock.uptimeMillis()` sits at line 339, immediately
  after `active.startListening(intent)` and before `postDelayed`. Using `uptimeMillis` (the clock
  `postDelayed` counts in) rather than wall time is correct and was a real bug fix (2026-11-11).
  I suspected this was mis-stamped and was wrong — checking it cost one grep and is why it is written
  up here rather than in the table.
- **The watchdog's recovery** — it calls `endSession()`, then `releaseRecognizer()`, then tells the
  user. Releasing the *instance* is the important half: a wedged recogniser answers every later start
  with `ERROR_RECOGNIZER_BUSY`, so dropping it is what lets the next press work.
- **`VoiceLanguagePolicy.pick`** — the negotiation ladder is right: exact tag, then same language and
  region, then the service's own preference, then the generic tag, then first offered (the service's
  list is most-relevant-first). The `offered.isEmpty()` and `want == null` early returns are both
  correct, and so is handing the choice back to the service when it does not know the language at all
  — forcing a tag is what turns a good attempt into `NO_MATCH`.
- **`normalise`** — `en_GH`→`en-GH`, trims, drops empty, rejects a single character. Used on both
  sides of every comparison.
- **`shouldRetryWithDefault`** — requires a forced language (otherwise the attempt is identical) and
  fires only for the five codes a language mismatch can produce. Permission and microphone failures
  are excluded on purpose, because they would fail identically twice.
- **`VoiceTypingConfig`** — one list drives the dropdown, the persisted string and the parsed enum,
  so they cannot disagree, and an unknown value parses to the default rather than crashing.
- **Audio-focus release on the other paths** — `endSession`, `cancel`, `destroy` and a start that
  threw all release it, and `cancel`'s `focusHeld` clause closes the "failed start left focus held
  after the flag cleared" leak the comment describes. V3 was the one path that released it *early*.
- **`KeyboardReceiver`'s wiring** — preferences are read through `Config.safeGet*` (a hand-edited or
  imported preference with the wrong type cannot crash the mic key), the keyboard language is read
  through a lambda rather than captured so a layout switch between presses is honoured, and
  `onFinishInputView` cancels the session so a transcript cannot land in the next field.

## What this pass did not do

- **It did not exercise `DictationController` against a real or shadowed recogniser.** V1's fix is
  asserted by a state-machine model, not by driving the class. See V4 for why, and what it would take.
- **It did not test on a device.** Every claim here is from source, the Android documentation, and
  modelling the state machine in Python.
- **It did not change `DictationPolicy`.** No finding required it; the table is the strongest code in
  the package.
- **It did not review `VoiceImeSwitcher`** (outside this package) or the settings UI for these
  preferences, beyond confirming the call sites read their values safely.
- **It did not measure anything.** V3 is argued from the API's early-return, not from a profile.
- **`gif/`, `a11y/`, `ml/`, the geometric gesture classes, settings persistence, the emoji grid,
  `Pointers.kt`, `KeyEventHandler.kt`, `TrigramStore`, `BigramModel` remain unhunted.**

## Verification

Typecheck against the base (`scripts/ci/offline/typecheck.sh --tests 97ab129`) reports **0
diagnostics novel to the tree**; the 32 in the new test file are the expected missing
JUnit noise (4 `@Test`, three assertions, the `org.junit` imports). `DictationController` itself
reports none.

| Workflow | Run (on `13e7566`) | Result |
|---|---|---|
| `Build APK` | `44916828427` | **green** |
| `Full unit-test suite` | `44916832668` | **green** — **4859 tests, 0 failed, 134 skipped in 433 classes** |
| `Emulator smoke test` | `44916828433` | **green** — including §7e's self-test commit, and confirming it never reaches a real session (V5) |

`4859 / 433` is +4 tests and +1 class over part 6's tip, which is exactly
`DictationStopDuringStartTest`'s four cases. `skipped` stayed at **134**: the new class skips
nothing.
