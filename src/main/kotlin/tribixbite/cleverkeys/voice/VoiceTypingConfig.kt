package tribixbite.cleverkeys.voice

/**
 * Preference keys, defaults and value parsing for voice typing (phase 4 part 1).
 *
 * Pure Kotlin on purpose, the same arrangement as [tribixbite.cleverkeys.FloatingConfig] and
 * [tribixbite.cleverkeys.SplitConfig]: no `android.*` import, so the mode parsing is JVM-testable
 * and the service code that cannot be tested offline holds no parsing of its own.
 *
 * Two modes, because the mic key has always had exactly one job in this codebase: hand the field to
 * a voice IME ([tribixbite.cleverkeys.VoiceImeSwitcher]). That job silently does nothing on a phone
 * where no voice IME is enabled, which is what the owner's Huawei did: the key produced a system
 * toast and no dictation. Dictation -- recognising speech inside this process and committing the
 * transcript through the InputConnection -- is now the default, and the IME switch stays one setting
 * away for anyone who prefers their own voice keyboard.
 */
object VoiceTypingConfig {
    const val PREF_MODE = "voice_typing_mode"
    const val PREF_SELF_TEST = "voice_typing_self_test"

    const val MODE_DICTATION = "dictation"
    const val MODE_SWITCH_IME = "switch_ime"
    const val DEFAULT_MODE = MODE_DICTATION
    const val DEFAULT_SELF_TEST = false

    /**
     * The modes in the order the settings dropdown lists them. [indexOf] and [rawOf] both go
     * through this one list, so the dropdown, the persisted string and the parsed enum can never
     * disagree about which entry is which.
     */
    fun modes(): List<Mode> = listOf(Mode.DICTATION, Mode.SWITCH_IME)

    /** An unknown or absent value parses to the default, never to a crash or a blank mode. */
    fun modeOf(raw: String?): Mode =
        if (raw == MODE_SWITCH_IME) Mode.SWITCH_IME else Mode.DICTATION

    fun rawOf(mode: Mode): String = when (mode) {
        Mode.DICTATION -> MODE_DICTATION
        Mode.SWITCH_IME -> MODE_SWITCH_IME
    }

    fun indexOf(mode: Mode): Int = modes().indexOf(mode).coerceAtLeast(0)

    enum class Mode { DICTATION, SWITCH_IME }
}
