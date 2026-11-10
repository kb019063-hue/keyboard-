package tribixbite.cleverkeys.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which language dictation asks the speech recogniser for (2026-11-09).
 *
 * The defect this pins: `DictationController` read the language from
 * `context.resources.configuration.locales[0]` -- the *device* locale. The keyboard carries its
 * own per-layout active language (`Config.activeLanguages().primary`), so the two disagree
 * whenever the user types in a language the phone is not set to. On a phone set to English with a
 * Spanish layout, the mic key dictated English: the recogniser was asked for `en`, decoded the
 * Spanish audio it heard as English, and either returned gibberish or `ERROR_NO_MATCH`, which the
 * bar reports as "Didn't catch that".
 *
 * The 2026-11-04 round already made the tag *negotiable* (see [VoiceLanguagePolicy.pick]) so an
 * unsupported region could not produce NO_MATCH. This adds the missing half: asking the right
 * question in the first place.
 */
class DictationLanguageSourceTest {

    @Test
    fun `the keyboard language wins over the device locale`() {
        // The whole bug, in one line: phone in English, keyboard on a Spanish layout.
        assertEquals("es", VoiceLanguagePolicy.wanted("es", "en-GH"))
    }

    @Test
    fun `a region on the keyboard language is kept`() {
        // Layouts may name a region (`en-GB`); it must survive to the negotiator, which is the
        // layer that knows whether the recogniser has a model for it.
        assertEquals("en-GB", VoiceLanguagePolicy.wanted("en-GB", "en-US"))
    }

    @Test
    fun `an underscore tag is normalised the same way everywhere`() {
        assertEquals("pt-BR", VoiceLanguagePolicy.wanted("pt_BR", "en-US"))
    }

    @Test
    fun `the device locale is the fallback when the keyboard has no language`() {
        assertEquals("en-GH", VoiceLanguagePolicy.wanted(null, "en-GH"))
        assertEquals("en-GH", VoiceLanguagePolicy.wanted("", "en-GH"))
        assertEquals("en-GH", VoiceLanguagePolicy.wanted("   ", "en-GH"))
    }

    @Test
    fun `no language at all is null, not an empty tag`() {
        // null is meaningful: it makes the controller omit EXTRA_LANGUAGE and let the service
        // pick. An empty string would be sent as a tag and rejected.
        assertNull(VoiceLanguagePolicy.wanted(null, null))
        assertNull(VoiceLanguagePolicy.wanted("", ""))
        assertNull(VoiceLanguagePolicy.wanted("   ", null))
    }

    @Test
    fun `a one character tag is not a language`() {
        // Same rule as normalise: a single character is a malformed preference (a hand-edited or
        // imported value), and must fall through rather than be forced on the recogniser.
        assertEquals("en", VoiceLanguagePolicy.wanted("x", "en"))
    }

    @Test
    fun `the negotiated tag is one the service offered, not the raw keyboard tag`() {
        // End to end through both layers: keyboard says es, the service only ships es-ES.
        val wanted = VoiceLanguagePolicy.wanted("es", "en-GH")
        assertEquals(
            "es-ES",
            VoiceLanguagePolicy.pick(wanted, listOf("en-US", "es-ES"), "en-US")
        )
    }

    @Test
    fun `a keyboard language the service cannot decode falls back to its preference`() {
        // Keyboard on a layout the recogniser has no model for: hand the choice back rather than
        // force a tag that turns a good attempt into NO_MATCH.
        val wanted = VoiceLanguagePolicy.wanted("yo", "en-GH")
        val picked = VoiceLanguagePolicy.pick(wanted, listOf("en-US", "en-GB"), "en-US")
        assertEquals("en-US", picked)
    }

    @Test
    fun `dictating in the keyboard language differs from the old device locale answer`() {
        // The regression guard: these two MUST differ for this fix to mean anything. If a future
        // change makes `wanted` return the device tag here, the bug is back.
        assertNotEquals("en-GH", VoiceLanguagePolicy.wanted("es", "en-GH"))
    }
}
