package tribixbite.cleverkeys.voice

/**
 * Which language to ask the speech recogniser for, and whether a failed attempt is worth one
 * automatic retry. Pure Kotlin so both answers are JVM-tested.
 *
 * Why this exists (2026-11-04, owner report): the mic key listened, the owner spoke, and the
 * suggestion bar said "Couldn't catch that". `DictationController` had been asking for the
 * keyboard's own locale verbatim — `context.resources.configuration.locales[0]`, which on a phone
 * set to English (Ghana) is `en-GH`. A recogniser that has no model for a tag does not always say
 * so: several of them accept the session, hear audio, decode nothing, and report ERROR_NO_MATCH.
 * That is indistinguishable from a microphone problem, and it is exactly the symptom.
 *
 * Two defences:
 *  1. [pick] asks the recogniser which tags it advertises (`ACTION_GET_LANGUAGE_DETAILS`) and
 *     only ever asks for one of those. An unsupported region is resolved to the closest supported
 *     variant of the same language (en-GH -> en-GB, en-US, or whatever the service prefers), and a
 *     language it does not know at all is left to the service instead of being forced.
 *  2. [shouldRetryWithDefault] allows ONE silent second attempt with no language at all when the
 *     first attempt failed after a forced tag — the service's own preference is, by definition,
 *     a language it can handle.
 */
object VoiceLanguagePolicy {

    /** `en_GH` -> `en-GH`, trims, drops an empty tag. Case of the language part is kept. */
    fun normalise(tag: String?): String? {
        val t = tag?.trim()?.replace('_', '-')?.takeIf { it.isNotEmpty() } ?: return null
        return if (t.length >= 2) t else null
    }

    private fun languageOf(tag: String): String =
        tag.substringBefore('-').lowercase()

    private fun regionOf(tag: String): String? =
        tag.split('-').drop(1).firstOrNull { it.length == 2 }?.uppercase()

    /**
     * What language the user is *typing* right now, which is what they want to dictate.
     *
     * This used to be the device locale (`context.resources.configuration.locales[0]`). That is
     * the wrong source: the keyboard has its own per-layout active language, so a phone set to
     * English whose keyboard is on a Spanish layout dictated English. The 2026-11-04 fix (this
     * class) made that tag *negotiable*, which stopped `en-GH` from producing NO_MATCH, but it
     * still asked the wrong question first -- it negotiated a language the user was not typing in.
     *
     * The keyboard's active language wins when it is set; the device locale is only the fallback
     * for the case where there is no keyboard language yet (first run, or Config not initialised).
     *
     * @param keyboardLanguage the active language of the current layout, e.g. `es`.
     * @param deviceLocaleTag the device locale as a BCP-47 tag, e.g. `en-GH`.
     * @return a normalised tag, or null when neither is usable.
     */
    fun wanted(keyboardLanguage: String?, deviceLocaleTag: String?): String? =
        normalise(keyboardLanguage) ?: normalise(deviceLocaleTag)

    /**
     * The tag to pass as `RecognizerIntent.EXTRA_LANGUAGE`.
     *
     * @param wanted the keyboard's locale, e.g. `en-GH`.
     * @param supported the tags the recogniser advertised; empty means "it would not say", in
     *   which case [wanted] is passed through unchanged — the pre-2026-11-04 behaviour, which is
     *   still the best available answer when there is nothing to negotiate against.
     * @param preference the service's own preferred tag, from the same query.
     * @return a tag, or null to let the service decide (no EXTRA_LANGUAGE at all).
     */
    fun pick(wanted: String?, supported: List<String>, preference: String?): String? {
        val want = normalise(wanted)
        val pref = normalise(preference)
        val offered = supported.mapNotNull { normalise(it) }
        if (offered.isEmpty()) return want ?: pref
        if (want == null) return offered.firstOrNull { it.equals(pref, ignoreCase = true) } ?: pref
        offered.firstOrNull { it.equals(want, ignoreCase = true) }?.let { return it }
        val lang = languageOf(want)
        val sameLanguage = offered.filter { languageOf(it) == lang }
        if (sameLanguage.isEmpty()) {
            // The recogniser does not know this language at all. Forcing the tag is what turns a
            // good attempt into NO_MATCH, so hand the choice back to the service.
            return pref?.takeIf { p -> offered.any { it.equals(p, ignoreCase = true) } }
        }
        val region = regionOf(want)
        if (region != null) {
            sameLanguage.firstOrNull { regionOf(it) == region }?.let { return it }
        }
        if (pref != null) {
            sameLanguage.firstOrNull { it.equals(pref, ignoreCase = true) }?.let { return it }
        }
        // Same language, different region: prefer the generic tag ("en"), then whatever the
        // service listed first — its list is ordered most-relevant-first, so that is the variant
        // it actually ships a model for, rather than a region we would be guessing at.
        sameLanguage.firstOrNull { regionOf(it) == null }?.let { return it }
        return sameLanguage.first()
    }

    /**
     * Whether to retry the session once, silently, with no language override.
     *
     * Only when a language was forced (otherwise the second attempt would be identical), only
     * once per press, and only for the failures a language mismatch actually produces: the
     * recogniser heard something and could not decode it, or the service rejected the request.
     * A microphone or permission failure would fail the same way twice, so it is reported at once.
     */
    fun shouldRetryWithDefault(
        errorCode: Int,
        languageWasForced: Boolean,
        alreadyRetried: Boolean
    ): Boolean {
        if (!languageWasForced || alreadyRetried) return false
        // The error codes live in DictationPolicy as literals, so this file stays free of
        // android.* and the table is testable off-device (same reason that file spells them out).
        return when (errorCode) {
            DictationPolicy.ERROR_NO_MATCH,
            DictationPolicy.ERROR_SPEECH_TIMEOUT,
            DictationPolicy.ERROR_SERVER,
            DictationPolicy.ERROR_NETWORK,
            DictationPolicy.ERROR_NETWORK_TIMEOUT -> true
            else -> false
        }
    }
}
