package tribixbite.cleverkeys

import android.os.Handler
import android.text.InputType
import android.text.TextUtils
import android.util.Log
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection

class Autocapitalisation(
    private val handler: Handler,
    private val callback: Callback
) {
    private var enabled = false
    private var shouldEnableShift = false
    private var shouldDisableShift = false
    private var shouldUpdateCapsMode = false

    /**
     * The field declared `TYPE_TEXT_FLAG_CAP_CHARACTERS` ("all caps": codes, serials, some search
     * boxes). Such a field wants the CAPS LOCK, not sentence capitals, for as long as it has focus.
     */
    private var capsLockField = false

    private var ic: InputConnection? = null
    private var capsMode = 0

    /** Keep track of the cursor to recognize cursor movements from typing. */
    private var cursor = 0

    /** Verbose-only debug log; message lambda is not evaluated unless verbose logging is enabled. */
    private inline fun vlog(message: () -> String) { if (BuildConfig.ENABLE_VERBOSE_LOGGING) Log.d(TAG, message()) }

    /**
     * The events are: started, typed, event sent, selection updated
     * [started] does initialisation work and must be called before any other
     * event.
     */
    fun started(info: EditorInfo, ic: InputConnection) {
        this.ic = ic
        // Check inputType for CAP_MODE flags
        capsMode = info.inputType and SUPPORTED_CAPS_MODES
        val autocapEnabled = Config.globalConfig().autocapEffective
        // A field that asks for all caps (codes, serials, some search boxes) wants the CAPS LOCK.
        // The flag was simply not read before, so such a field opened with a lowercase keyboard
        // and the editor had to re-case every letter after the fact — or, in fields that ignore
        // case on input, ended up with lowercase text the user had to fix by hand.
        capsLockField = (info.inputType and InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS) != 0

        vlog { "AUTOCAP started: setting=$autocapEnabled, capsMode=$capsMode, lock=$capsLockField, inputType=0x${info.inputType.toString(16)}" }

        // An all-caps field is honoured even with auto-capitalisation switched off: it is the
        // FIELD asking, not a guess about the text.
        if ((!autocapEnabled || capsMode == 0) && !capsLockField) {
            enabled = false
            shouldEnableShift = false
            shouldUpdateCapsMode = false
            vlog { "AUTOCAP: Disabled (setting=$autocapEnabled, capsMode=$capsMode)" }
            // Publish the decision even though there is nothing to capitalise. Returning without
            // a callback left the PREVIOUS field's shift state standing: end a sentence in a
            // message, tap a URL/search field and the keyboard came up shifted, so the first
            // letter of an address or a query was typed in capitals. Every field opens unshifted
            // unless it — or the text before the caret — asks otherwise.
            callback_now(true)
            return
        }

        enabled = true
        shouldEnableShift = capsLockField || info.initialCapsMode != 0
        // A locked field never asks the editor: the lock outranks whatever it would answer.
        shouldUpdateCapsMode = !capsLockField && autocapEnabled && capsMode != 0 &&
            started_should_update_state(info.inputType)
        vlog { "AUTOCAP: Enabled, shouldEnableShift=$shouldEnableShift, shouldUpdateCapsMode=$shouldUpdateCapsMode" }
        callback_now(true)
    }

    fun typed(c: CharSequence) {
        for (i in c.indices) {
            type_one_char(c[i])
        }
        callback(false)
    }

    fun event_sent(code: Int, meta: Int) {
        if (meta != 0) {
            // A key sent with a modifier (Ctrl/Alt, or a Shift+key combination this keyboard
            // synthesised): never re-arm shift for it -- the next character is not a new
            // sentence just because a modified key went by -- but DO publish that decision.
            // Returning here without [callback] left both flags set for a callback that never
            // ran, so the visual shift state stayed exactly as it was until the next unmodified
            // key, i.e. one key too long.
            shouldEnableShift = false
            shouldUpdateCapsMode = false
            callback(true)
            return
        }
        when (code) {
            KeyEvent.KEYCODE_DEL -> {
                if (cursor > 0) cursor--
                shouldUpdateCapsMode = true
            }
            KeyEvent.KEYCODE_ENTER -> {
                shouldUpdateCapsMode = true
            }
        }
        callback(true)
    }

    fun stop() {
        shouldEnableShift = false
        shouldUpdateCapsMode = false
        callback_now(true)
    }

    /** Pause auto capitalisation until [unpause] is called. */
    fun pause(): Boolean {
        val wasEnabled = enabled
        stop()
        enabled = false
        return wasEnabled
    }

    /**
     * Continue auto capitalisation after [pause] was called. Argument is the
     * output of [pause].
     */
    fun unpause(wasEnabled: Boolean) {
        enabled = wasEnabled
        shouldUpdateCapsMode = true
        callback_now(true)
    }

    fun interface Callback {
        /**
         * @param should_enable latched shift on.
         * @param should_disable latched shift off (a decision, not just "don't touch it").
         * @param lock CAPS LOCK rather than a one-shot shift — the field asked for all caps.
         */
        fun update_shift_state(should_enable: Boolean, should_disable: Boolean, lock: Boolean)
    }

    /** Returns [true] if shift might be disabled. */
    fun selection_updated(old_cursor: Int, new_cursor: Int) {
        if (new_cursor == cursor) { // Just typing
            return
        }
        // A caret move — a tap in the field, an arrow key, a suggestion replacing a word — can
        // land at a sentence start, where the editor asks for a capital again. The question used
        // to be asked only when the field had JUST been cleared (cursor 0 with nothing after it),
        // so placing the caret after a full stop left shift off until the next key was typed and
        // the capital was then lost. Ask on every real caret move; the 50 ms delay coalesces them.
        shouldUpdateCapsMode = true
        cursor = new_cursor
        shouldEnableShift = false
        callback(true)
    }

    private val delayed_callback = Runnable {
        if (shouldUpdateCapsMode && ic != null) {
            val cursorCapsMode = ic?.getCursorCapsMode(capsMode) ?: 0
            shouldEnableShift = enabled && (cursorCapsMode != 0)
            vlog { "AUTOCAP callback: enabled=$enabled, cursorCapsMode=$cursorCapsMode, shouldEnableShift=$shouldEnableShift" }
            shouldUpdateCapsMode = false
        }
        // An all-caps field keeps the lock through everything typed into it: the editor's caps
        // mode is not consulted for it, and a letter typed into it must not drop the lock.
        if (enabled && capsLockField) {
            shouldEnableShift = true
            shouldUpdateCapsMode = false
        }
        vlog { "AUTOCAP update_shift_state: enable=$shouldEnableShift, disable=$shouldDisableShift, lock=$capsLockField" }
        callback.update_shift_state(shouldEnableShift, shouldDisableShift, enabled && capsLockField)
    }

    /**
     * Update the shift state if [shouldUpdateCapsMode] is true, then call
     * [callback.update_shift_state]. This is done after a short delay to wait
     * for the editor to handle the events, as this might be called before the
     * corresponding event is sent.
     */
    private fun callback(might_disable: Boolean) {
        shouldDisableShift = might_disable
        // The callback must be delayed because [getCursorCapsMode] would sometimes
        // be called before the editor finished handling the previous event.
        // Remove any stale queued callback first so rapid events don't stack up
        // and fire with outdated shift state.
        handler.removeCallbacks(delayed_callback)
        handler.postDelayed(delayed_callback, 50)
    }

    /** Like [callback] but runs immediately. */
    private fun callback_now(might_disable: Boolean) {
        shouldDisableShift = might_disable
        // Cancel any queued delayed callback so it can't fire after this immediate run.
        handler.removeCallbacks(delayed_callback)
        delayed_callback.run()
    }

    private fun type_one_char(c: Char) {
        cursor++
        if (is_trigger_character(c)) {
            shouldUpdateCapsMode = true
            vlog { "AUTOCAP: Trigger char typed, will update caps mode" }
        } else {
            shouldEnableShift = false
        }
    }

    private fun is_trigger_character(c: Char): Boolean {
        return when (c) {
            ' ', '.', '!', '?', '\n' -> true
            else -> false
        }
    }

    /**
     * Whether the caps state should be updated when input starts. [inputType]
     * is the field from the editor info object.
     */
    private fun started_should_update_state(inputType: Int): Boolean {
        val class_ = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        if (class_ != InputType.TYPE_CLASS_TEXT) {
            return false
        }
        return when (variation) {
            InputType.TYPE_TEXT_VARIATION_LONG_MESSAGE,
            InputType.TYPE_TEXT_VARIATION_NORMAL,
            InputType.TYPE_TEXT_VARIATION_PERSON_NAME,
            InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE,
            InputType.TYPE_TEXT_VARIATION_EMAIL_SUBJECT,
            InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT -> true
            else -> false
        }
    }

    companion object {
        private const val TAG = "Autocapitalisation"

        @JvmField
        val SUPPORTED_CAPS_MODES =
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
            InputType.TYPE_TEXT_FLAG_CAP_WORDS

        /**
         * THE auto-cap decision, evaluated at a single instant: would tap typing capitalize
         * the next letter at the current cursor?
         *
         * Same inputs as [started]/[delayed_callback]: the user setting, the field's declared
         * caps modes (a field with none disables the feature outright — a misbehaving
         * editor's [InputConnection.getCursorCapsMode] is then never consulted), and the
         * editor's live caps mode at the cursor.
         *
         * Added for the swipe commit path (SuggestionHandler): the tap path reaches this
         * decision through the latched-shift fake pointer, but the swipe path's shift
         * snapshot (taken at gesture start) goes stale across autocap's 50ms delayed latch,
         * suggestion commits the cursor tracker never saw, and selection-update races —
         * device-confirmed as a swiped "bowie" committing lowercase at a sentence start.
         * Pinned by SwipeAutocapCommitTest.
         */
        @JvmStatic
        fun shouldCapitalizeAtCursor(
            ic: InputConnection?,
            info: EditorInfo?,
            autocapEnabled: Boolean
        ): Boolean {
            if (!autocapEnabled || ic == null || info == null) return false
            val capsMode = info.inputType and SUPPORTED_CAPS_MODES
            if (capsMode == 0) return false
            return try {
                ic.getCursorCapsMode(capsMode) != 0
            } catch (e: Exception) {
                false
            }
        }
    }
}
