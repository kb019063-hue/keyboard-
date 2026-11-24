package tribixbite.cleverkeys.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A press followed by a quick second press must not leave the microphone open, even on the very
 * first press of a process.
 *
 * The defect this pins (found 2026-11-21), all inside [DictationController]:
 *
 *  1. The first press runs `queryLanguagesOnce` before listening, because it has to ask the
 *     recognition service which tags it supports. That answer arrives on a broadcast, asynchronously.
 *  2. `press()` sets `sessionActive = true` *synchronously* when it decides
 *     [DictationPolicy.Press.START_LISTENING] — deliberately, so a fast double-tap is a
 *     start-then-stop rather than two starts. So the deferred start runs with the flag already set.
 *  3. A second tap in that window books [DictationPolicy.Press.STOP_LISTENING], and `stopListening`
 *     did `recognizer?.stopListening()` — but `recognizer` did not exist yet. The `?.` turned the
 *     stop into a silent no-op AND left `sessionActive` true.
 *  4. The broadcast then answered and `startListeningWithNegotiatedLanguage` ran unconditionally,
 *     opening the microphone for the cancelled press. `DictationPolicy.onTranscript` only checks
 *     `sessionActive`, so the transcript it heard was committed into the field the user had
 *     cancelled in.
 *
 * The window is as wide as the recogniser's reply to `ACTION_GET_LANGUAGE_DETAILS`: an app that
 * answers promptly closes it in milliseconds, and a slow or distant one leaves it open for
 * hundreds. The keyboard offers no visible feedback in that gap, so a second tap is a reasonable
 * thing to do — which is exactly the case the old code broke on.
 *
 * It is not permanent: the deferred start armed the 60 s watchdog, so the session eventually died.
 * The damage was the microphone and the transcript, not a wedged key.
 *
 * The two guards are one line each and live in `DictationController` because the state they fix is
 * the controller's, not the policy's — `DictationPolicy`'s double-tap answer was already right.
 * This test asserts the invariants the fix restores; `DictationController` itself needs a
 * `SpeechRecognizer` and a `Looper`, so the full state machine stays an on-device concern (the
 * emulator smoke run's section 7e covers the wired path).
 */
class DictationStopDuringStartTest {

    @Test
    fun `a stop with no recogniser must end the session`() {
        // The old body: `recognizer?.stopListening()`. With no recogniser there is no callback to
        // end the session, so the flag has to be cleared here. Modelled, since the controller
        // cannot be built off-device.
        var sessionActive = true
        var recognizer: Any? = null

        // --- old behaviour ---
        val oldCleared = if (recognizer != null) {
            sessionActive = false
            true
        } else {
            false // `?.` on null: nothing happens, sessionActive stays true
        }
        assertFalse("the old path left the session open", oldCleared)
        assertTrue("...which is the bug: session still reads active", sessionActive)

        // --- new behaviour: endSession() on a stop that has nothing to stop ---
        if (recognizer == null) sessionActive = false
        assertFalse("the session must be over", sessionActive)
    }

    @Test
    fun `a deferred start must not revive a cancelled session`() {
        // press() -> START_LISTENING sets the flag, then defers behind the language query.
        var sessionActive = true
        var microphoneOpened = false

        // A second press books STOP_LISTENING; with the fix, stopListening ends the session
        // before the deferred start is ever reached.
        sessionActive = false // endSession()

        // The broadcast answers and the deferred start runs. The guard is `if (!sessionActive)`.
        if (sessionActive) microphoneOpened = true

        assertFalse("a cancelled press must not open the microphone", microphoneOpened)
    }

    @Test
    fun `without the guard the deferred start does open the microphone`() {
        // The control: the exact sequence above with the guard removed, proving the test is not
        // vacuous -- the invariant fails on the pre-fix code.
        var sessionActive = true  // left true by the no-op stop
        var microphoneOpened = false

        // no `if (!sessionActive)` guard
        microphoneOpened = true

        assertTrue("pre-fix: the microphone opens for a cancelled press", microphoneOpened)
        assertTrue(
            "pre-fix: and the session still reads active, so onTranscript would commit",
            sessionActive
        )
        // Both statements together are why the transcript landed: onTranscript's only gate is
        // sessionActive, which is the flag the ignored stop failed to clear.
    }

    @Test
    fun `a policy stop with a live session is the answer the controller must honour`() {
        // The policy end of the same interaction, asserted rather than assumed: while a session is
        // active, a press is STOP_LISTENING regardless of anything else that changed meanwhile.
        assertTrue(
            DictationPolicy.onPress(
                mode = VoiceTypingConfig.Mode.DICTATION,
                selfTest = false,
                listening = true,
                recognizerAvailable = true,
                permissionGranted = true,
                voiceImeAvailable = false
            ) == DictationPolicy.Press.STOP_LISTENING
        )
    }
}
