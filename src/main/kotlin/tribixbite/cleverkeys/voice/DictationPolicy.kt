package tribixbite.cleverkeys.voice

/**
 * The mic key's whole decision table, as pure Kotlin (phase 4 part 1).
 *
 * [DictationController] owns the Android side -- `SpeechRecognizer`, the permission, the
 * InputConnection -- and holds no branching of its own, so every "what should the key do" answer
 * here is JVM-testable without a device or a microphone. That is the same split the GIF pipeline
 * uses ([tribixbite.cleverkeys.gif.GifInsertPolicy]) and the incognito gate
 * ([tribixbite.cleverkeys.IncognitoPolicy]).
 *
 * The order of the checks in [onPress] is the interesting part and is deliberate:
 *
 *  1. a session already running means this press is the STOP button, whatever else changed while
 *     it was listening -- otherwise a second tap starts a second recogniser and the first one's
 *     transcript lands in whatever field has focus by then;
 *  2. the self-test short-circuits before availability, because its whole purpose is to prove the
 *     commit path on a device that has no recogniser at all (the CI emulator is exactly that);
 *  3. no recogniser means asking for the microphone is pointless, so fall back to the legacy IME
 *     switch instead of stranding the key behind a permission dialog that leads nowhere;
 *  4. only then permission, and only then listening.
 */
object DictationPolicy {

    /** What one press of the mic key should do. */
    enum class Press {
        /** Begin a recognition session. */
        START_LISTENING,

        /** A session is running: stop it and accept whatever it heard. */
        STOP_LISTENING,

        /** RECORD_AUDIO is not granted: ask, through the translucent permission activity. */
        REQUEST_PERMISSION,

        /** No in-app recogniser: hand the field to a voice IME, the pre-phase-4 behaviour. */
        SWITCH_TO_VOICE_IME,

        /** Neither a recogniser nor a voice IME exists. The key must say so, not do nothing. */
        REPORT_NO_VOICE_INPUT,

        /** Self-test: commit the fixed transcript through the real commit path. */
        COMMIT_SELF_TEST
    }

    /** What a recognition callback should do with the text it was handed. */
    enum class Delivery {
        /** Commit into the focused editor, with [commitPrefix] in front of it. */
        COMMIT,

        /** Throw it away. */
        DISCARD
    }

    /** Why a session ended without usable text. The controller turns these into user-visible lines. */
    enum class Failure {
        /** Silence, or speech the recogniser could not match. Worth retrying, not worth alarming. */
        NOTHING_HEARD,

        /** The microphone permission went away mid-session, or was never really granted. */
        PERMISSION,

        /** The recogniser was already busy with someone else. A retry usually works. */
        BUSY,

        /** Network, server or audio plumbing. Retrying may not help. */
        UNAVAILABLE,

        /** Anything the table does not recognise; reported generically rather than swallowed. */
        UNKNOWN
    }

    // android.speech.SpeechRecognizer error codes, spelled out as literals so this file stays pure
    // Kotlin and the table below is testable off-device. Values are API-stable public constants.
    const val ERROR_NETWORK_TIMEOUT = 1
    const val ERROR_NETWORK = 2
    const val ERROR_AUDIO = 3
    const val ERROR_SERVER = 4
    const val ERROR_CLIENT = 5
    const val ERROR_SPEECH_TIMEOUT = 6
    const val ERROR_NO_MATCH = 7
    const val ERROR_RECOGNIZER_BUSY = 8
    const val ERROR_INSUFFICIENT_PERMISSIONS = 9

    fun onPress(
        mode: VoiceTypingConfig.Mode,
        selfTest: Boolean,
        listening: Boolean,
        recognizerAvailable: Boolean,
        permissionGranted: Boolean,
        voiceImeAvailable: Boolean
    ): Press {
        if (listening) return Press.STOP_LISTENING
        if (selfTest && mode == VoiceTypingConfig.Mode.DICTATION) return Press.COMMIT_SELF_TEST
        if (mode == VoiceTypingConfig.Mode.SWITCH_IME) return switchOrFail(voiceImeAvailable)
        if (!recognizerAvailable) return switchOrFail(voiceImeAvailable)
        if (!permissionGranted) return Press.REQUEST_PERMISSION
        return Press.START_LISTENING
    }

    private fun switchOrFail(voiceImeAvailable: Boolean): Press =
        if (voiceImeAvailable) Press.SWITCH_TO_VOICE_IME else Press.REPORT_NO_VOICE_INPUT

    /**
     * Recogniser callbacks are asynchronous and can outlive the session that started them, so the
     * session flag is part of the decision: a late result must never type into whatever field the
     * user has moved on to. Partials are discarded on purpose -- the suggestion strip belongs to
     * the prediction engine, and fighting it for a live transcript is a worse experience than one
     * clean commit at the end.
     */
    fun onTranscript(text: String?, isFinal: Boolean, sessionActive: Boolean): Delivery {
        if (!sessionActive) return Delivery.DISCARD
        if (!isFinal) return Delivery.DISCARD
        if (text.isNullOrBlank()) return Delivery.DISCARD
        return Delivery.COMMIT
    }

    /**
     * The space that joins dictated text to what was already there. A blank field gets none; a
     * field ending in whitespace gets none; anything else gets exactly one, so two dictations in a
     * row read as two words and never as "hellohello" or "hello  world".
     */
    fun commitPrefix(textBeforeCursor: String?): String {
        val last = textBeforeCursor?.lastOrNull() ?: return ""
        return if (last.isWhitespace()) "" else " "
    }

    fun onFailure(errorCode: Int): Failure = when (errorCode) {
        ERROR_SPEECH_TIMEOUT, ERROR_NO_MATCH -> Failure.NOTHING_HEARD
        ERROR_INSUFFICIENT_PERMISSIONS -> Failure.PERMISSION
        ERROR_RECOGNIZER_BUSY -> Failure.BUSY
        ERROR_NETWORK_TIMEOUT, ERROR_NETWORK, ERROR_AUDIO, ERROR_SERVER, ERROR_CLIENT ->
            Failure.UNAVAILABLE
        else -> Failure.UNKNOWN
    }
}
