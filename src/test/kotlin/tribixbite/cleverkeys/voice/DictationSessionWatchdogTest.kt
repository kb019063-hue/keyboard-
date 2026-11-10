package tribixbite.cleverkeys.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dictation session watchdog (2026-11-09).
 *
 * The defect this pins: `DictationController` set `sessionActive = true` in [DictationPolicy.press]
 * and only ever cleared it from a recogniser callback (`onResults` / `onError`), from `cancel()`
 * (the editor going away) or from a start that threw. A recogniser that simply never calls back
 * leaves the flag true forever, and because [DictationPolicy.onPress] answers a press while
 * listening with `STOP_LISTENING`, the mic key becomes a stop button for a session that is already
 * dead. The user's report is "the mic key does nothing now": every press says "Listening…" and
 * every press after that only cancels.
 *
 * The recogniser is not required to be well behaved here. Several builds wedge after
 * `startListening` and then answer later starts with `ERROR_RECOGNIZER_BUSY`, so the timeout is a
 * real recovery path, not a defensive decoration.
 */
class DictationSessionWatchdogTest {

    @Test
    fun `a live session expires at the limit`() {
        assertTrue(
            DictationPolicy.onSessionExpired(
                elapsedMs = DictationPolicy.SESSION_WATCHDOG_MS,
                sessionActive = true
            )
        )
    }

    @Test
    fun `a session expires past the limit`() {
        assertTrue(
            DictationPolicy.onSessionExpired(
                elapsedMs = DictationPolicy.SESSION_WATCHDOG_MS + 5_000L,
                sessionActive = true
            )
        )
    }

    @Test
    fun `a session inside the limit is left alone`() {
        // One millisecond short must NOT fire: the boundary is the contract, and a timer that
        // fires early would truncate a long dictated paragraph.
        assertFalse(
            DictationPolicy.onSessionExpired(
                elapsedMs = DictationPolicy.SESSION_WATCHDOG_MS - 1L,
                sessionActive = true
            )
        )
    }

    @Test
    fun `an already finished session never expires`() {
        // The timer is removed on every end-of-session path, but a callback already in the queue
        // can still run. It must be a no-op rather than clobber a *new* session's state.
        assertFalse(DictationPolicy.onSessionExpired(elapsedMs = Long.MAX_VALUE, sessionActive = false))
    }

    @Test
    fun `a stale stamp cannot expire a fresh session`() {
        // The stamp is taken where a session starts, so the elapsed value here is small even
        // though the controller has existed for a long time. This is the shape the controller
        // feeds in -- a fresh stamp with a live session.
        assertFalse(DictationPolicy.onSessionExpired(elapsedMs = 0L, sessionActive = true))
    }

    @Test
    fun `the watchdog is long enough for a dictated paragraph`() {
        // Regression guard on the constant itself: a dictation session must not be cut off mid
        // sentence. 60 s is generous by design; anything under 30 s would fail real users, and
        // the pre-2026-11-04 bug was exactly a session ended too early (the removed
        // *_SPEECH_INPUT_*_MILLIS extras).
        assertTrue(DictationPolicy.SESSION_WATCHDOG_MS >= 30_000L)
    }

    /**
     * The controller maps the expiry to `R.string.voice_typing_nothing_heard`. Pinned as a source
     * fact because the mapping lives in the controller, which cannot run off-device.
     */
    @Test
    fun `the controller reports an expired session with the nothing-heard message`() {
        val source = java.io.File(
            "src/main/kotlin/tribixbite/cleverkeys/voice/DictationController.kt"
        )
        assertTrue("DictationController.kt must exist", source.exists())
        val text = source.readText()

        assertTrue(
            "the watchdog must exist",
            text.contains("DictationPolicy.SESSION_WATCHDOG_MS")
        )
        assertTrue(
            "the watchdog must report a message the user can see",
            Regex(
                """session expired with no recogniser callback[\s\S]{0,600}?voice_typing_nothing_heard"""
            ).containsMatchIn(text)
        )
        assertTrue(
            "an expired session must release the recogniser, or every later press gets " +
                "ERROR_RECOGNIZER_BUSY",
            Regex(
                """session expired with no recogniser callback[\s\S]{0,600}?releaseRecognizer\(\)"""
            ).containsMatchIn(text)
        )
    }

    /**
     * Every path that clears `sessionActive` must disarm the timer in the same breath.
     *
     * Not a count: a count would have to be edited every time a legitimate path is added, and the
     * next author would edit it without thinking. The invariant is local and absolute -- whichever
     * line clears the flag, the watchdog must be removed within the next few lines of it. A path
     * that clears the flag and leaves the timer armed fires into the *next* press.
     */
    @Test
    fun `every path that ends a session disarms the timer`() {
        val lines = java.io.File(
            "src/main/kotlin/tribixbite/cleverkeys/voice/DictationController.kt"
        ).readLines()

        var checked = 0
        for ((index, line) in lines.withIndex()) {
            if (line.trim() != "sessionActive = false") continue
            checked++
            val window = lines.drop(index).take(6)
            assertTrue(
                "line ${index + 1} clears sessionActive but does not remove the watchdog nearby:\n" +
                    window.joinToString("\n"),
                window.any { it.contains("removeCallbacks(watchdog)") }
            )
        }
        assertTrue("expected at least one sessionActive clear", checked > 0)
    }
}