package tribixbite.cleverkeys.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the language negotiation that the 2026-11-04 "couldn't catch that" report produced.
 *
 * The bug: `DictationController` asked for the keyboard's locale verbatim, which on the owner's
 * phone is `en-GH`. A recogniser without an en-GH model accepts the session, hears audio, decodes
 * nothing and reports ERROR_NO_MATCH — indistinguishable from a broken microphone. Everything here
 * is about never forcing a tag the service has not advertised, and retrying once without one.
 */
class VoiceLanguagePolicyTest {

    @Test
    fun `tags are normalised to the form RecognizerIntent wants`() {
        assertEquals("en-GH", VoiceLanguagePolicy.normalise("en_GH"))
        assertEquals("en-GB", VoiceLanguagePolicy.normalise(" en-GB "))
        assertNull(VoiceLanguagePolicy.normalise(null))
        assertNull(VoiceLanguagePolicy.normalise(""))
        assertNull(VoiceLanguagePolicy.normalise("   "))
    }

    @Test
    fun `a service that will not say keeps the old behaviour`() {
        // No advertised list: passing the wanted locale through is still the best guess available.
        assertEquals("en-GH", VoiceLanguagePolicy.pick("en-GH", emptyList(), null))
        assertEquals("en-GB", VoiceLanguagePolicy.pick(null, emptyList(), "en-GB"))
        assertNull(VoiceLanguagePolicy.pick(null, emptyList(), null))
    }

    @Test
    fun `an advertised exact match is used verbatim, including the service's spelling`() {
        assertEquals("en-GB",
            VoiceLanguagePolicy.pick("en-GB", listOf("en-US", "en-GB"), null))
        assertEquals("en-GH",
            VoiceLanguagePolicy.pick("en_gh", listOf("en-GH", "en-US"), null))
    }

    @Test
    fun `an unsupported region falls back inside the same language`() {
        // The owner's case: en-GH is not offered, English is.
        val picked = VoiceLanguagePolicy.pick("en-GH", listOf("en-US", "en-GB", "fr-FR"), null)
        assertTrue("picked=$picked", picked in setOf("en-US", "en-GB"))
    }

    @Test
    fun `the service preference wins among same-language variants`() {
        assertEquals("en-AU",
            VoiceLanguagePolicy.pick("en-GH", listOf("en-US", "en-AU", "en-GB"), "en-AU"))
    }

    @Test
    fun `a generic language tag is preferred over a region`() {
        assertEquals("en",
            VoiceLanguagePolicy.pick("en-GH", listOf("en-US", "en"), null))
    }

    @Test
    fun `a language the service does not know is left to the service`() {
        // Never force a tag the recogniser did not advertise: that is what produced NO_MATCH.
        assertNull(VoiceLanguagePolicy.pick("tw-GH", listOf("en-US", "fr-FR"), null))
        assertEquals("en-US", VoiceLanguagePolicy.pick("tw-GH", listOf("en-US"), "en-US"))
    }

    @Test
    fun `a missing locale is resolved from the service preference`() {
        assertEquals("fr-FR", VoiceLanguagePolicy.pick(null, listOf("en-US", "fr-FR"), "fr-FR"))
    }

    @Test
    fun `one silent retry with the service default, only after a forced language`() {
        assertTrue(
            VoiceLanguagePolicy.shouldRetryWithDefault(
                DictationPolicy.ERROR_NO_MATCH, languageWasForced = true, alreadyRetried = false
            )
        )
        assertFalse(
            "no language was forced, a second identical attempt would fail the same way",
            VoiceLanguagePolicy.shouldRetryWithDefault(
                DictationPolicy.ERROR_NO_MATCH, languageWasForced = false, alreadyRetried = false
            )
        )
        assertFalse(
            "one retry per press",
            VoiceLanguagePolicy.shouldRetryWithDefault(
                DictationPolicy.ERROR_NO_MATCH, languageWasForced = true, alreadyRetried = true
            )
        )
        assertFalse(
            "a dead microphone fails identically twice, so report it at once",
            VoiceLanguagePolicy.shouldRetryWithDefault(
                DictationPolicy.ERROR_AUDIO, languageWasForced = true, alreadyRetried = false
            )
        )
        assertFalse(
            "the recogniser was busy: retrying immediately cannot help",
            VoiceLanguagePolicy.shouldRetryWithDefault(
                DictationPolicy.ERROR_RECOGNIZER_BUSY, languageWasForced = true, alreadyRetried = false
            )
        )
    }
}
