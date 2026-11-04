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

    /**
     * What the recogniser says it can handle, asked once per process
     * (`RecognizerIntent.ACTION_GET_LANGUAGE_DETAILS`). Empty until the answer arrives; empty
     * forever on a recogniser that does not answer at all, which is why [VoiceLanguagePolicy.pick]
     * falls back to the wanted tag in that case.
     */
    private var supportedLanguages: List<String> = emptyList()
    private var serviceLanguagePreference: String? = null
    private var languagesQueried = false

    /** The tag this session put in EXTRA_LANGUAGE, or null when the service chose for itself. */
    private var forcedLanguage: String? = null
    private var retriedWithDefault = false

    /**
     * Sticky, per process: once even the service-default retry has failed, stop forcing a
     * language on every later press. A recogniser that cannot decode the tag we negotiate
     * (or that ignores negotiation entirely) is better served by its own preference than by
     * repeating the same failing request every time the user taps the mic.
     */
    private var languageOverrideEnabled = true

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
                retriedWithDefault = false
                forcedLanguage = null
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
        // The first press of a process asks the recogniser what it supports before listening, so
        // the very first session already asks in a language the service can decode. Every later
        // press (and the retry path) goes straight through.
        queryLanguagesOnce { startListeningWithNegotiatedLanguage() }
    }

    /**
     * @param forceDefault skip language negotiation and let the service use its own preference.
     *   Used by the one automatic retry: the service's preferred language is, by definition, one
     *   it has a model for.
     */
    private fun startListeningWithNegotiatedLanguage(forceDefault: Boolean = false) {
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
            // 2026-11-04: the three *_SPEECH_INPUT_*_MILLIS extras are GONE. They asked the
            // recogniser to end the utterance after 1.0 s of speech and 1.5 s of silence, which
            // is shorter than a normal dictated sentence takes; a session cut off mid-sentence
            // ends in ERROR_NO_MATCH, i.e. "couldn't catch that" while the speaker was talking.
            // The recogniser's own endpointing is the behaviour Gboard gets, so use it.
            //
            // Dictate in the language the user is typing -- negotiated against what the service
            // advertises, so an unsupported region (en-GH) becomes a tag it can actually decode.
            val language =
                if (forceDefault || !languageOverrideEnabled) null else negotiateLanguage()
            forcedLanguage = language
            language?.let { putExtra(RecognizerIntent.EXTRA_LANGUAGE, it) }
        }
        Log.i(
            TAG,
            "startListening language=${forcedLanguage ?: "service default"} " +
                "retriedWithDefault=$retriedWithDefault"
        )
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

    /**
     * Ask the recogniser which language tags it advertises, once.
     *
     * `ACTION_GET_LANGUAGE_DETAILS` is an ordered broadcast the recognition service answers with
     * `EXTRA_SUPPORTED_LANGUAGES` and `EXTRA_LANGUAGE_PREFERENCE`. It is best-effort by design: a
     * service that ignores it leaves both fields empty, and [VoiceLanguagePolicy.pick] then passes
     * the wanted tag straight through, exactly as before this fix.
     */
    private fun queryLanguagesOnce(onDone: () -> Unit) {
        if (languagesQueried) {
            onDone()
            return
        }
        languagesQueried = true
        try {
            val intent = Intent(RecognizerIntent.ACTION_GET_LANGUAGE_DETAILS).apply {
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            }
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(ctx: Context?, broadcast: Intent?) {
                    try {
                        context.unregisterReceiver(this)
                    } catch (t: Throwable) {
                        Log.w(TAG, "unregister language receiver threw: $t")
                    }
                    val list = broadcast?.getStringArrayListExtra(
                        RecognizerIntent.EXTRA_SUPPORTED_LANGUAGES
                    )
                    supportedLanguages = list?.mapNotNull { VoiceLanguagePolicy.normalise(it) }
                        ?: emptyList()
                    serviceLanguagePreference =
                        broadcast?.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE)
                    Log.i(
                        TAG,
                        "recogniser advertises ${supportedLanguages.size} languages, prefers " +
                            "${serviceLanguagePreference ?: "none"}"
                    )
                    onDone()
                }
            }
            context.sendOrderedBroadcast(intent, null, receiver, main, 0, null, null)
        } catch (t: Throwable) {
            Log.w(TAG, "language query threw: $t")
            onDone()
        }
    }

    /** The tag to ask for right now: negotiated against what the service advertises. */
    private fun negotiateLanguage(): String? = VoiceLanguagePolicy.pick(
        wanted = localeLanguageTag(),
        supported = supportedLanguages,
        preference = serviceLanguagePreference
    )

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
        // One silent retry, only when a language tag was forced AND the failure is one a language
        // mismatch produces. The microphone stays open from the user's point of view: the bar
        // keeps saying "Listening…" rather than failing a session they can see.
        if (VoiceLanguagePolicy.shouldRetryWithDefault(
                errorCode, languageWasForced = forcedLanguage != null, alreadyRetried = retriedWithDefault
            )
        ) {
            retriedWithDefault = true
            Log.i(TAG, "first attempt failed with a forced language=$forcedLanguage: retrying " +
                "once with the service default")
            abandonAudioFocus()
            main.post { startListeningWithNegotiatedLanguage(forceDefault = true) }
            return
        }
        if (retriedWithDefault && languageOverrideEnabled) {
            // The service-default retry failed too: the language override is not the answer on
            // this device. Stop sending one for the rest of the process.
            languageOverrideEnabled = false
            Log.w(TAG, "service-default retry failed as well; language override disabled " +
                "for this session's lifetime")
        }
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
        // The reason travels with the message: "Didn't catch that" alone cannot tell a language
        // mismatch from a dead microphone, and the owner had exactly that report to make twice.
        sink?.showMessage(context.getString(message) + " (" + reasonName(errorCode) + ")")
    }

    /** Plain words for the status line, matching [errorName] one for one. */
    private fun reasonName(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "microphone"
        SpeechRecognizer.ERROR_CLIENT -> "client error"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "permission"
        SpeechRecognizer.ERROR_NETWORK -> "network"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network timeout"
        SpeechRecognizer.ERROR_NO_MATCH -> "no match"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "busy"
        SpeechRecognizer.ERROR_SERVER -> "service error"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "nothing heard"
        else -> "error $code"
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
