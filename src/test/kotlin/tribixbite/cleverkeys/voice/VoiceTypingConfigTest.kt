package tribixbite.cleverkeys.voice

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the preference contract for voice typing (phase 4 part 1).
 *
 * The dropdown in settings, the persisted string and the parsed enum all go through
 * [VoiceTypingConfig.modes], and these three tests are what keeps them agreeing: a round trip
 * through the stored value, a stable index for the dropdown, and an absent or unknown value
 * resolving to dictation rather than to something that silently disables the mic key.
 */
class VoiceTypingConfigTest {

    @Test
    fun `every mode round trips through its stored value`() {
        VoiceTypingConfig.modes().forEach { mode ->
            assertEquals(mode, VoiceTypingConfig.modeOf(VoiceTypingConfig.rawOf(mode)))
        }
    }

    @Test
    fun `the dropdown index matches the mode list order`() {
        VoiceTypingConfig.modes().forEachIndexed { index, mode ->
            assertEquals(index, VoiceTypingConfig.indexOf(mode))
        }
        assertEquals(VoiceTypingConfig.modes().size, 2)
    }

    @Test
    fun `an absent or unknown value means dictation, never a dead key`() {
        assertEquals(VoiceTypingConfig.Mode.DICTATION, VoiceTypingConfig.modeOf(null))
        assertEquals(VoiceTypingConfig.Mode.DICTATION, VoiceTypingConfig.modeOf(""))
        assertEquals(VoiceTypingConfig.Mode.DICTATION, VoiceTypingConfig.modeOf("something_else"))
        assertEquals(
            VoiceTypingConfig.Mode.SWITCH_IME,
            VoiceTypingConfig.modeOf(VoiceTypingConfig.MODE_SWITCH_IME)
        )
    }

    @Test
    fun `the defaults are the ones the settings screen and reset list read`() {
        assertEquals(VoiceTypingConfig.MODE_DICTATION, VoiceTypingConfig.DEFAULT_MODE)
        assertEquals(false, VoiceTypingConfig.DEFAULT_SELF_TEST)
    }
}
