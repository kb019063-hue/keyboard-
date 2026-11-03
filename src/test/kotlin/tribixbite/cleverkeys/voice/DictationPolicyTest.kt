package tribixbite.cleverkeys.voice

import org.junit.Assert.assertEquals
import org.junit.Test
import tribixbite.cleverkeys.voice.DictationPolicy.Press
import tribixbite.cleverkeys.voice.VoiceTypingConfig.Mode

/**
 * Pins the mic key's decision table (phase 4 part 1).
 *
 * The regression this whole file exists for is the owner's phone: the key switched the IME, the
 * system toasted "Google voice typing", and nothing was ever dictated. So the rows that matter
 * most are the fallback ones -- what the key does when there is no recogniser, and what it does
 * when there is neither a recogniser nor a voice IME, because "silently nothing" is not an allowed
 * answer in either case.
 */
class DictationPolicyTest {

    private fun press(
        mode: Mode = Mode.DICTATION,
        selfTest: Boolean = false,
        listening: Boolean = false,
        recognizerAvailable: Boolean = true,
        permissionGranted: Boolean = true,
        voiceImeAvailable: Boolean = true,
        permissionRefused: Boolean = false
    ): Press = DictationPolicy.onPress(
        mode = mode,
        selfTest = selfTest,
        listening = listening,
        recognizerAvailable = recognizerAvailable,
        permissionGranted = permissionGranted,
        voiceImeAvailable = voiceImeAvailable,
        permissionRefused = permissionRefused
    )

    @Test
    fun `the happy path listens`() {
        assertEquals(Press.START_LISTENING, press())
    }

    @Test
    fun `a second press stops the running session`() {
        // Even with the permission revoked and the recogniser gone: the session that exists has to
        // be the one this press ends.
        assertEquals(
            Press.STOP_LISTENING,
            press(listening = true, permissionGranted = false, recognizerAvailable = false)
        )
    }

    @Test
    fun `a missing permission is asked for, not worked around`() {
        assertEquals(Press.REQUEST_PERMISSION, press(permissionGranted = false))
    }

    @Test
    fun `no recogniser falls back to the voice ime instead of asking for a useless permission`() {
        assertEquals(
            Press.SWITCH_TO_VOICE_IME,
            press(recognizerAvailable = false, permissionGranted = false, voiceImeAvailable = true)
        )
    }

    /**
     * The denial path is a promise the UI makes: `voice_typing_permission_denied` tells the user the
     * mic key "switches to a voice keyboard instead of dictating here". Before this row existed the
     * key asked again instead, and once Android had stopped showing the system prompt (two denials)
     * that was a panel that could never succeed — the key was effectively dead while telling the
     * user it had done something else.
     */
    @Test
    fun `a permission refused once is not asked for again, it falls back to the voice ime`() {
        assertEquals(
            Press.SWITCH_TO_VOICE_IME,
            press(permissionGranted = false, permissionRefused = true, voiceImeAvailable = true)
        )
    }

    /**
     * With no voice IME to hand over to, the message must be about the permission — the recogniser
     * exists, and `REPORT_NO_VOICE_INPUT` would send the user looking for a missing app.
     */
    @Test
    fun `a refused permission with no voice ime reports the permission, not a missing recogniser`() {
        assertEquals(
            Press.REPORT_PERMISSION_REFUSED,
            press(permissionGranted = false, permissionRefused = true, voiceImeAvailable = false)
        )
    }

    /** A refusal is irrelevant once the permission is actually held (a later grant or a restore). */
    @Test
    fun `a granted permission listens even if it was refused earlier`() {
        assertEquals(
            Press.START_LISTENING,
            press(permissionGranted = true, permissionRefused = true)
        )
    }

    @Test
    fun `no recogniser and no voice ime says so rather than doing nothing`() {
        assertEquals(Press.REPORT_NO_VOICE_INPUT, press(recognizerAvailable = false, voiceImeAvailable = false))
    }

    @Test
    fun `the legacy mode switches even when a recogniser is available`() {
        assertEquals(Press.SWITCH_TO_VOICE_IME, press(mode = Mode.SWITCH_IME))
    }

    @Test
    fun `the legacy mode with no voice ime reports instead of failing silently`() {
        assertEquals(Press.REPORT_NO_VOICE_INPUT, press(mode = Mode.SWITCH_IME, voiceImeAvailable = false))
    }

    @Test
    fun `self test commits without a recogniser or a permission`() {
        // This is the row CI runs on: the emulator image has no speech service at all.
        assertEquals(
            Press.COMMIT_SELF_TEST,
            press(selfTest = true, recognizerAvailable = false, permissionGranted = false)
        )
    }

    @Test
    fun `self test is ignored in legacy mode, which never commits text`() {
        assertEquals(Press.SWITCH_TO_VOICE_IME, press(mode = Mode.SWITCH_IME, selfTest = true))
    }

    @Test
    fun `a final transcript commits while the session is live`() {
        assertEquals(
            DictationPolicy.Delivery.COMMIT,
            DictationPolicy.onTranscript("hello there", isFinal = true, sessionActive = true)
        )
    }

    @Test
    fun `a late transcript is discarded after the session ended`() {
        assertEquals(
            DictationPolicy.Delivery.DISCARD,
            DictationPolicy.onTranscript("hello there", isFinal = true, sessionActive = false)
        )
    }

    @Test
    fun `partials never commit`() {
        assertEquals(
            DictationPolicy.Delivery.DISCARD,
            DictationPolicy.onTranscript("hel", isFinal = false, sessionActive = true)
        )
    }

    @Test
    fun `a blank final transcript never commits`() {
        assertEquals(
            DictationPolicy.Delivery.DISCARD,
            DictationPolicy.onTranscript("   ", isFinal = true, sessionActive = true)
        )
        assertEquals(
            DictationPolicy.Delivery.DISCARD,
            DictationPolicy.onTranscript(null, isFinal = true, sessionActive = true)
        )
    }

    @Test
    fun `the joining space appears only after real text`() {
        assertEquals("", DictationPolicy.commitPrefix(null))
        assertEquals("", DictationPolicy.commitPrefix(""))
        assertEquals("", DictationPolicy.commitPrefix("hello "))
        assertEquals(" ", DictationPolicy.commitPrefix("hello"))
        assertEquals(" ", DictationPolicy.commitPrefix("hello."))
    }

    @Test
    fun `recogniser errors map onto something the user can be told`() {
        assertEquals(
            DictationPolicy.Failure.NOTHING_HEARD,
            DictationPolicy.onFailure(DictationPolicy.ERROR_SPEECH_TIMEOUT)
        )
        assertEquals(DictationPolicy.Failure.NOTHING_HEARD, DictationPolicy.onFailure(DictationPolicy.ERROR_NO_MATCH))
        assertEquals(
            DictationPolicy.Failure.PERMISSION,
            DictationPolicy.onFailure(DictationPolicy.ERROR_INSUFFICIENT_PERMISSIONS)
        )
        assertEquals(DictationPolicy.Failure.BUSY, DictationPolicy.onFailure(DictationPolicy.ERROR_RECOGNIZER_BUSY))
        // Offline is not "no recogniser installed": these three are the connection, and the user is
        // told so. Mistaking one for the other sent an offline user looking for a missing app.
        assertEquals(
            DictationPolicy.Failure.SERVICE_UNREACHABLE,
            DictationPolicy.onFailure(DictationPolicy.ERROR_NETWORK)
        )
        assertEquals(
            DictationPolicy.Failure.SERVICE_UNREACHABLE,
            DictationPolicy.onFailure(DictationPolicy.ERROR_NETWORK_TIMEOUT)
        )
        assertEquals(
            DictationPolicy.Failure.SERVICE_UNREACHABLE,
            DictationPolicy.onFailure(DictationPolicy.ERROR_SERVER)
        )
        assertEquals(DictationPolicy.Failure.MICROPHONE, DictationPolicy.onFailure(DictationPolicy.ERROR_AUDIO))
        assertEquals(DictationPolicy.Failure.UNKNOWN, DictationPolicy.onFailure(DictationPolicy.ERROR_CLIENT))
        assertEquals(DictationPolicy.Failure.UNKNOWN, DictationPolicy.onFailure(999))
    }
}
