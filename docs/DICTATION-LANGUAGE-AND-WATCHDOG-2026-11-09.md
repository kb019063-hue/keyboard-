# Dictation: wrong language, and a session that never ends

2026-11-09. Two defects in the mic key's dictation path (`voice/`). Both are in the *decision*
layer, so both are pinned by pure JVM tests rather than by the emulator, which has no microphone
and no speech service (see "Testing" below).

Predecessor: `docs/DICTATION-NO-MATCH-2026-11-04.md` fixed the *symptom* of the first defect.

---

## 1. Dictation asked for the device locale, not the keyboard's language

**What the code did.** `DictationController.localeLanguageTag()` read
`context.resources.configuration.locales[0]` — the language *the phone is set to*.

**Why that is wrong.** This keyboard carries its own per-layout active language
(`Config.activeLanguages().primary`, driven by `LayoutLanguageBinding`). The two disagree
whenever the user types in a language the phone is not set to — which is the normal case for
anyone using a multilingual keyboard. A phone set to English with a Spanish layout asked the
recogniser for `en`, the recogniser decoded Spanish audio as English, and the result was gibberish
or `ERROR_NO_MATCH`, which the bar reports as "Didn't catch that".

**The 2026-11-04 round did not fix this.** It made the tag *negotiable*
(`VoiceLanguagePolicy.pick`: `en-GH` → `en-GB`/`en-US` when the service has no Ghanaian model),
which stopped an unsupported *region* from producing NO_MATCH. But negotiation started from the
wrong tag in the first place: it worked hard to find a decodable version of a language the user was
not speaking.

*(Correction, 2026-11-11: the negotiation itself never ran, because the query behind it cannot
return an answer; the silent retry did the rescuing. See
[`BUG-HUNT-2026-11-11-PART-2.md`](BUG-HUNT-2026-11-11-PART-2.md), D-b. The fix below does not
depend on it.)*

**Fix.** `VoiceLanguagePolicy.wanted(keyboardLanguage, deviceLocaleTag)` — the keyboard's active
language wins; the device locale is only the fallback for the case where the keyboard has none
(first run, or `Config` not yet initialised). `DictationController` takes a
`keyboardLanguage: () -> String?` lambda, read per session so a layout switch between two presses
is honoured, and wired in `KeyboardReceiver.dictation()` from
`Config.globalConfigOrNull()?.activeLanguages()?.primary`.

`globalConfigOrNull` rather than `globalConfig`: the very first press can beat Config
initialisation, and a dictation key that throws during startup is worse than one that falls back
to the device locale.

Pinned by `DictationLanguageSourceTest` (9 tests).

---

## 2. A recogniser that never called back wedged the mic key permanently

**What the code did.** `sessionActive = true` was set in `press()`, and cleared only from a
recogniser callback (`onResults` / `onError`), from `cancel()` (editor going away), or from a
`startListening` that threw. There was **no timer anywhere in the file**.

**Why that is a real failure, not a theoretical one.** The recogniser contract is that it ends a
session with `onResults` or `onError`, but that is not guaranteed in practice: a service process
killed mid-session, and several OEM builds, simply go silent. Because `DictationPolicy.onPress`
answers a press while listening with `STOP_LISTENING`, the sequence is:

1. Press → "Listening…" → recogniser never calls back.
2. Every later press → `STOP_LISTENING` → cancels a session that is already dead.

The mic key becomes a button that can only ever fail, forever, with no way back except killing the
keyboard process. This is the "the mic key does nothing now" shape of report.

**Fix.** A single watchdog:

- `DictationPolicy.SESSION_WATCHDOG_MS` = 60 s (generous on purpose — the cost of being too long
  is one dead press; the cost of being too short is truncating a real sentence, which is exactly
  the pre-2026-11-04 bug caused by the removed `*_SPEECH_INPUT_*_MILLIS` extras).
- `DictationPolicy.onSessionExpired(elapsedMs, sessionActive)` — pure, so the boundary is tested
  (exactly at the limit fires; 1 ms short does not; a finished session never fires).
- `DictationController` arms it in `startListeningWithNegotiatedLanguage` — the one place a
  session actually begins, so the silent language retry gets a **fresh** window instead of
  inheriting the first attempt's.
- On expiry it ends the session **and calls `releaseRecognizer()`**. Dropping the instance is not
  optional: a wedged recogniser answers later starts with `ERROR_RECOGNIZER_BUSY`, so only a fresh
  instance restores the key.
- All end-of-session paths now funnel through `endSession()`, which clears the flag *and* removes
  the timer. A path that cleared the flag on its own would leave the watchdog armed to fire into
  the next press.

Pinned by `DictationSessionWatchdogTest` (7 tests, including two source-reading guards: the
watchdog reports a visible message and releases the recogniser; and every line that sets
`sessionActive = false` removes the timer within six lines of itself).

*(Changed 2026-11-11: the elapsed time was read from the wall clock while the timer runs on
uptime, so the check could come up 1 ms short of the limit and was never run again. Both
readings are uptime now. See [`BUG-HUNT-2026-11-11-PART-2.md`](BUG-HUNT-2026-11-11-PART-2.md),
D-a.)*

---

## Testing

Truth about this environment, unchanged from the last round: **dictation cannot be end-to-end
tested in CI.** The emulator has no microphone and no speech recogniser, so `emulator-smoke.sh`
only exercises the self-test commit path (`voice=commit`). Anything that depends on real audio must
therefore be expressed as a pure policy function, which is why both fixes were pushed into
`DictationPolicy` / `VoiceLanguagePolicy` rather than written inline in the Android plumbing.

Both new test classes are registered in `build.gradle`'s literal `pureTestClasses` array —
without that they are silently never run (`TestRunnerListDriftTest` enforces this).

---

## What was deliberately not changed

- **Partials are still discarded.** `EXTRA_PARTIAL_RESULTS` is requested because several
  recognisers only arm their endpointing when it is set, but `DictationPolicy.onTranscript` still
  commits only a final result. A live transcript fighting the prediction engine for the suggestion
  strip is a worse experience than one clean commit.
- **The single silent retry stays.** See `VoiceLanguagePolicy.shouldRetryWithDefault`.
- **The status-line format stays.** `"<message> (<plain reason>)"` is what the troubleshooting
  table in `docs/DICTATION-NO-MATCH-2026-11-04.md` is written against.
