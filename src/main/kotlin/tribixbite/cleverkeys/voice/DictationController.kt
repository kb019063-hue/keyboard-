package tribixbite.cleverkeys.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import tribixbite.cleverkeys.R
import tribixbite.cleverkeys.VoicePermissionActivity

/**
 * In-process dictation for the mic key (phase 4 part 1).
 *
 * Before this, `voice_typing` only ever switched the active IME to a voice IME
 * ([tribixbite.cleverkeys.VoiceImeSwitcher]), which on a phone with no cooperating voice IME
 * produces a system toast and no text. This recognises speech inside the keyboard's own process
 * and commits the transcript through the InputConnection, so the key does the thing its label says.
 *
 * All branching lives in [DictationPolicy]; this class is only the Android plumbing around it --
 * the recogniser's lifecycle, the permission, and the three ways it talks back out through [Sink].
 *
 * Threading: `SpeechRecognizer` must be created and driven on the thread that owns the looper it
 * reports on, so every recogniser call is posted to the main looper. `sessionActive` is set
 * synchronously in [press] rather than inside the posted work, because that is what makes a fast
 * double-tap a start-then-stop instead of two starts.
 */
internal class DictationController(private val context: Context) {

    /** The three things dictation needs from the keyboard, so this class stays testable in shape. */
    interface Sink {
        /** Commit finished transcript text into the focused editor. */
        fun commitText(text: String)

        /** Text immediately before the cursor, used only to decide the joining space. */
        fun textBeforeCursor(): String?

        /** Short-lived status line in the suggestion bar; toasts are suppressed for IMEs on 13+. */
        fun showMessage(message: String)
    }

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var sink: Sink? = null

    /**
     * The microphone permission has been asked for at least once and has not been granted since.
     *
     * Android shows the system prompt twice and then answers `denied` immediately, so a key that
     * asks on every press becomes a panel that can never succeed — which is what the old code did
     * while its denial message promised the key had switched to a voice keyboard. Cleared as soon
     * as the permission is actually held, so a later revoke asks properly once more.
     */
    private var askedForPermission = false

    /**
     * Audio focus, held for the length of one session.
     *
     * Without it the recogniser can open the microphone and still receive silence: on OEM builds
     * that police background recording (EMUI among them) an IME is not the app the user sees in
     * Recents, so the capture is attenuated or dropped and every attempt ends in NO_MATCH --
     * which is exactly the reported symptom (a loud voice, several tries, "Didn't catch that").
     * Asking for transient focus puts this process in the queue the platform expects, and it is
     * the same thing a voice IME does before it listens.
     */
    private var audioManager: AudioManager? = null
    private var focusHeld = false

    @Suppress("DEPRECATION")
    private fun requestAudioFocus() {
        if (focusHeld) return
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        audioManager = am
        try {
            val result = am.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            )
            focusHeld = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            Log.i(TAG, "audio focus granted=$focusHeld code=$result")
        } catch (t: Throwable) {
            Log.w(TAG, "requestAudioFocus threw: $t")
        }
    }

    @Suppress("DEPRECATION")
    private fun abandonAudioFocus() {
        if (!focusHeld) return
        focusHeld = false
        try {
            audioManager?.abandonAudioFocus(null)
        } catch (t: Throwable) {
            Log.w(TAG, "abandonAudioFocus threw: $t")
        }
    }

    @Volatile
    private var sessionActive = false

    fun isListening(): Boolean = sessionActive

    fun recognizerAvailable(): Boolean = try {
        SpeechRecognizer.isRecognitionAvailable(context)
    } catch (t: Throwable) {
        Log.w(TAG, "isRecognitionAvailable threw: $t")
        false
    }

    fun permissionGranted(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * One press of the mic key. Returns the decision that was acted on so the caller -- and the CI
     * smoke run, through logcat -- can see which branch a given device took.
     */
    fun press(
        mode: VoiceTypingConfig.Mode,
        selfTest: Boolean,
        voiceImeAvailable: Boolean,
        sink: Sink
    ): DictationPolicy.Press {
        val recognizerAvailable = recognizerAvailable()
        val permissionGranted = permissionGranted()
        if (permissionGranted) askedForPermission = false
        val decision = DictationPolicy.onPress(
            mode = mode,
            selfTest = selfTest,
            listening = sessionActive,
            recognizerAvailable = recognizerAvailable,
            permissionGranted = permissionGranted,
            voiceImeAvailable = voiceImeAvailable,
            permissionRefused = askedForPermission
        )
        // The single most useful line this feature logs: it says why the key did what it did.
        Log.i(
            TAG,
            "press mode=$mode selfTest=$selfTest listening=$sessionActive " +
                "recognizer=$recognizerAvailable permission=$permissionGranted " +
                "askedBefore=$askedForPermission voiceIme=$voiceImeAvailable decision=$decision"
        )
        this.sink = sink
        when (decision) {
            DictationPolicy.Press.START_LISTENING -> {
                sessionActive = true
                main.post { startListening() }
            }
            DictationPolicy.Press.STOP_LISTENING -> main.post { stopListening() }
            DictationPolicy.Press.REQUEST_PERMISSION -> {
                askedForPermission = true
                requestPermission()
            }
            DictationPolicy.Press.COMMIT_SELF_TEST -> commitSelfTest()
            // Both handed back to the caller: it owns the voice-IME switch and the config flag.
            DictationPolicy.Press.SWITCH_TO_VOICE_IME,
            DictationPolicy.Press.REPORT_NO_VOICE_INPUT,
            DictationPolicy.Press.REPORT_PERMISSION_REFUSED -> Unit
        }
        return decision
    }

    /**
     * End a session without committing. Called when the editor goes away
     * (`CleverKeysService.onFinishInputView`), so a transcript can never land in the next field the
     * user focuses.
     */
    fun cancel() {
        // `focusHeld` is part of the condition: a failed start can leave transient audio focus
        // held after sessionActive has already been cleared. Releasing it unconditionally here
        // is safe (abandonAudioFocus is a no-op when no focus is held) and closes that leak.
        if (!sessionActive && recognizer == null && !focusHeld) return
        sessionActive = false
        abandonAudioFocus()
        main.post {
            try {
                recognizer?.cancel()
            } catch (t: Throwable) {
                Log.w(TAG, "cancel threw: $t")
            }
        }
    }

    /** Release the recogniser. Called when the service is destroyed. */
    fun destroy() {
        sessionActive = false
        sink = null
        abandonAudioFocus()
        main.post {
            try {
                recognizer?.destroy()
            } catch (t: Throwable) {
                Log.w(TAG, "destroy threw: $t")
            }
            recognizer = null
        }
    }

    private fun startListening() {
        val active = obtainRecognizer()
        if (active == null) {
            // Recogniser vanished between the availability check and here.
            sessionActive = false
            sink?.showMessage(context.getString(R.string.voice_typing_unavailable))
            return
        }
        // Focus BEFORE listening: see requestAudioFocus. Released in endSession/reportFailure/
        // cancel/destroy so it can never outlive the session.
        requestAudioFocus()
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            // Partials are still discarded by DictationPolicy (only a final result commits), but
            // asking for them is not free of side effects: several recognisers only start
            // decoding -- and only arm their endpointing -- when partials are requested. Asking
            // for them costs nothing here and makes the session behave the way a real dictation
            // session does.
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            // End the utterance promptly after the speaker stops instead of waiting for the
            // recogniser's default timeout, which is what turns a good attempt into NO_MATCH.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1000L)
            // Dictate in the language the user is typing, not the service default.
            localeLanguageTag()?.let { putExtra(RecognizerIntent.EXTRA_LANGUAGE, it) }
        }
        Log.i(TAG, "startListening language=${localeLanguageTag() ?: "default"}")
        try {
            active.startListening(intent)
            sink?.showMessage(context.getString(R.string.voice_typing_listening))
        } catch (t: Throwable) {
            Log.e(TAG, "startListening threw", t)
            sessionActive = false
            // No recognition callback will arrive from a start that threw, so this is the
            // only place that can release the focus requested above.
            abandonAudioFocus()
            sink?.showMessage(context.getString(R.string.voice_typing_failed))
        }
    }

    private fun stopListening() {
        try {
            recognizer?.stopListening()
        } catch (t: Throwable) {
            Log.w(TAG, "stopListening threw: $t")
            // Nothing will arrive now, so the session must not stay open forever.
            sessionActive = false
            abandonAudioFocus()
        }
    }

    private fun obtainRecognizer(): SpeechRecognizer? {
        recognizer?.let { return it }
        return try {
            SpeechRecognizer.createSpeechRecognizer(context).also { created ->
                created.setRecognitionListener(listener)
                recognizer = created
            }
        } catch (t: Throwable) {
            Log.e(TAG, "createSpeechRecognizer threw", t)
            null
        }
    }

    private fun requestPermission() {
        try {
            val intent = Intent(context, VoicePermissionActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(intent)
        } catch (t: Throwable) {
            // A keyboard cannot always start an activity (background-launch restrictions); tell the
            // user where the permission lives instead of failing quietly.
            Log.e(TAG, "could not start the permission activity", t)
            sink?.showMessage(context.getString(R.string.voice_typing_permission_needed))
        }
    }

    private fun commitSelfTest() {
        val text = context.getString(R.string.voice_typing_self_test_text)
        Log.i(TAG, "self-test commit length=${text.length}")
        commit(text)
    }

    private fun deliver(results: Bundle?, isFinal: Boolean) {
        val active = sessionActive
        val heard = results?.getStringArrayList(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        when (DictationPolicy.onTranscript(heard, isFinal, active)) {
            DictationPolicy.Delivery.COMMIT -> {
                commit(heard!!.trim())
                endSession()
            }
            DictationPolicy.Delivery.DISCARD -> {
                if (isFinal && active) {
                    endSession()
                    sink?.showMessage(context.getString(R.string.voice_typing_nothing_heard))
                }
            }
        }
    }

    private fun commit(text: String) {
        val target = sink ?: return
        val prefix = DictationPolicy.commitPrefix(target.textBeforeCursor())
        target.commitText(prefix + text)
        Log.i(TAG, "committed ${prefix.length + text.length} characters")
    }

    private fun endSession() {
        sessionActive = false
        abandonAudioFocus()
    }

    /** The language to dictate in: the keyboard's current locale as a BCP-47 tag. */
    private fun localeLanguageTag(): String? = try {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            context.resources.configuration.locales[0]?.toLanguageTag()
        } else {
            @Suppress("DEPRECATION")
            context.resources.configuration.locale?.toLanguageTag()
        }
    } catch (t: Throwable) {
        null
    }

    /**
     * The symbolic name of a recogniser error. The numeric code alone is useless in a bug
     * report, and the difference between them is the difference between "the microphone never
     * delivered audio" (SPEECH_TIMEOUT / NO_MATCH), "the OS refused us the mic"
     * (INSUFFICIENT_PERMISSIONS) and "another session owns the recogniser" (RECOGNIZER_BUSY).
     */
    private fun errorName(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "ERROR_AUDIO"
        SpeechRecognizer.ERROR_CLIENT -> "ERROR_CLIENT"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "ERROR_INSUFFICIENT_PERMISSIONS"
        SpeechRecognizer.ERROR_NETWORK -> "ERROR_NETWORK"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ERROR_NETWORK_TIMEOUT"
        SpeechRecognizer.ERROR_NO_MATCH -> "ERROR_NO_MATCH"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "ERROR_RECOGNIZER_BUSY"
        SpeechRecognizer.ERROR_SERVER -> "ERROR_SERVER"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "ERROR_SPEECH_TIMEOUT"
        else -> "ERROR_$code"
    }

    private fun reportFailure(errorCode: Int) {
        val failure = DictationPolicy.onFailure(errorCode)
        Log.w(TAG, "recognition error=$errorCode (${errorName(errorCode)}) failure=$failure")
        sessionActive = false
        abandonAudioFocus()
        val message = when (failure) {
            DictationPolicy.Failure.NOTHING_HEARD -> R.string.voice_typing_nothing_heard
            DictationPolicy.Failure.PERMISSION -> R.string.voice_typing_permission_needed
            DictationPolicy.Failure.BUSY -> R.string.voice_typing_busy
            DictationPolicy.Failure.SERVICE_UNREACHABLE -> R.string.voice_typing_service_unreachable
            DictationPolicy.Failure.MICROPHONE -> R.string.voice_typing_microphone
            DictationPolicy.Failure.UNKNOWN -> R.string.voice_typing_failed
        }
        sink?.showMessage(context.getString(message))
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            sink?.showMessage(context.getString(R.string.voice_typing_listening))
        }

        override fun onBeginningOfSpeech() = Unit

        override fun onRmsChanged(rmsdB: Float) = Unit

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() = Unit

        override fun onError(error: Int) = reportFailure(error)

        override fun onResults(results: Bundle?) = deliver(results, isFinal = true)

        override fun onPartialResults(partialResults: Bundle?) = deliver(partialResults, isFinal = false)

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private companion object {
        const val TAG = "DictationController"
    }
}
