# "Sorry, couldn't get that" — the mic key's first field report

_2026-11-04. The owner tapped the mic, spoke, and the suggestion bar said the session caught
nothing. This note records what was wrong in the request we build, what changed, and what to look
at if it survives._

## What the user saw

A failed session, not a crash: the recogniser started (the bar said "Listening…"), audio was
captured, and the session ended with `ERROR_NO_MATCH`/`ERROR_SPEECH_TIMEOUT` — the two codes
[DictationPolicy.onFailure](../src/main/kotlin/tribixbite/cleverkeys/voice/DictationPolicy.kt) maps
to `NOTHING_HEARD` ("Didn't catch that. Try again.").

## Four things in our own `RecognizerIntent` that can produce it

1. **We forced the phone's locale tag.** `EXTRA_LANGUAGE = context.resources.configuration
   .locales[0].toLanguageTag()` — on a phone set to English (Ghana) that is `en-GH`. A recogniser
   with no `en-GH` model does not necessarily say so: it accepts the session, hears audio, decodes
   nothing and reports `ERROR_NO_MATCH`. That is indistinguishable from a dead microphone.
2. **We overrode endpointing with very short limits**: `MINIMUM_LENGTH 1000 ms`,
   `COMPLETE_SILENCE 1500 ms`. A normal dictated sentence is longer than that; cutting the
   utterance during speech produces `ERROR_NO_MATCH`.
3. **The app never declared the recognition service in `<queries>`.** targetSdk is 34, so Android
   11+ package-visibility filtering applies to the `android.speech.RecognitionService` intent that
   `SpeechRecognizer` resolves before every session. The manifest declared `InputMethod` and
   nothing else. When resolution is filtered, the recogniser can end up bound to nothing and the
   session fails with `ERROR_CLIENT`.
4. **No second chance.** Whatever the first attempt did, the user saw a message and had to press
   again with the identical request.

## What changed

* `voice/VoiceLanguagePolicy` (new, pure Kotlin, JVM-tested):
  * `pick()` asks the recogniser what it advertises (`ACTION_GET_LANGUAGE_DETAILS`) and only ever
    requests one of those tags: exact match first, then the same language with another region,
    then the generic tag, then the service's own preference. A language it does not know at all is
    left to the service instead of being forced.
  * `shouldRetryWithDefault()` allows exactly **one** silent retry with no language at all, and
    only for the failures a language mismatch actually produces (NO_MATCH, SPEECH_TIMEOUT, SERVER,
    NETWORK, NETWORK_TIMEOUT). A microphone or busy failure is reported at once — retrying cannot
    help and would just double the wait.
* `DictationController`:
  * negotiates the language once per process (the query is cached) and logs what the service
    advertises;
  * the three `*_SPEECH_INPUT_*_MILLIS` extras are gone — the recogniser's own endpointing is what
    Gboard gets;
  * after the service-default retry also fails, the language override is disabled for the rest of
    the process, so the next press does not repeat a request that has already failed twice;
  * failure lines now carry a plain-language reason in brackets: "Didn't catch that. Try again.
    (no match)" / "(network)" / "(microphone)". Without it, the owner's report could not tell a
    language mismatch from a dead mic, and that ambiguity is why this took two rounds.
* `AndroidManifest.xml`: `<queries>` now declares `android.speech.RecognitionService` as well as
  `android.view.InputMethod`.

## If it still fails

The bracketed reason in the status line is the next clue:

| reason | what it means | next thing to try |
| --- | --- | --- |
| `no match` | audio arrived, nothing decoded | download the language for offline dictation, or check the recogniser's language list in Settings › System › Languages › Speech |
| `nothing heard` | the session timed out with no speech | check the mic is not muted/occupied; try the self-test to prove the commit path |
| `network` / `service error` | the recogniser could not reach its service | offline pack, or connectivity |
| `client error` | the recogniser refused the request outright | almost always a binding/visibility problem — check logcat for `SpeechRecognizer` |
| `microphone` | the mic would not open | another app is recording |

The full session log is one line per press and one per error, tag `DictationController`:
`press mode=… recognizer=… permission=… decision=…`, `recogniser advertises N languages,
prefers …`, `startListening language=… retriedWithDefault=…`.

## Not covered by CI

The emulator smoke run exercises the **self-test** path (a fixed transcript through the real
commit path); it has no microphone and no speech service, so no automated test can reproduce this
class of failure. `VoiceLanguagePolicyTest` pins the negotiation table instead, and this file is
the manual recipe.
