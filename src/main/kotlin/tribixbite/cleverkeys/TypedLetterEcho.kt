package tribixbite.cleverkeys

/**
 * Tells the caret report that merely answers a letter this keyboard typed from one that means the
 * caret went somewhere.
 *
 * Every time the caret moves, the editor reports it, and the keyboard re-reads the text around it
 * ([InputCoordinator.onCursorMoved]): two round trips to the app on the keyboard's main thread, a
 * second prediction for the word, a second redraw of the suggestion strip. That is the right
 * thing after a tap in the text. It is also what happened, a tenth of a second later, after every
 * letter the user typed -- because typing a letter moves the caret too -- and there the re-read
 * finds exactly the word the keyboard already holds and has already predicted for. Measured on the
 * emulator, 2026-11-12: a 21-key sentence rebound the strip 33 times, twice for each of its 16
 * letters.
 *
 * This class is the bookkeeping that tells the two apart. It is told when a letter is typed onto
 * the word in progress ([onTyped]) and sees every selection report ([onSelection]); a report that
 * puts a collapsed caret exactly where those letters put it is their echo. Anything else -- a
 * report elsewhere, a range, a key that is not such a letter -- is not, and the report after it is
 * treated as a move. What is done with the answer is [CursorSyncPace]'s business.
 *
 * Deliberately narrow. Only a single letter that EXTENDS a word counts: the first letter of a
 * word, a space, punctuation, Backspace, a suggestion, a swipe all keep the prompt re-read they
 * always had, because that is where the re-read does real work (joining "don'" and "t", noticing
 * the text the field already held, offering the next word).
 *
 * Pure Kotlin, main thread only. Positions are UTF-16 offsets as the editor reports them.
 */
class TypedLetterEcho {
    /** Where the editor last said the caret is; -1 after a range, or before any report. */
    private var caret = -1

    /** Letters typed at [caret] whose reports have not arrived yet. */
    private var owed = 0

    /** False once something other than such a letter was typed since the last report. */
    private var onlyLetters = true

    /** A new input session: [selStart]/[selEnd] are the selection the editor starts with. */
    fun reset(selStart: Int = -1, selEnd: Int = -1) {
        caret = if (selStart >= 0 && selStart == selEnd) selStart else -1
        owed = 0
        onlyLetters = true
    }

    /**
     * The keyboard committed typed text. [extendsWord] is true only for a single letter added to
     * a word that already had one: the one case whose caret report carries no news.
     */
    fun onTyped(extendsWord: Boolean) {
        if (extendsWord && onlyLetters && caret >= 0) {
            owed++
        } else {
            owed = 0
            onlyLetters = false
        }
    }

    /**
     * The editor reported its selection. True when this is the caret arriving where a letter
     * counted by [onTyped] put it, and nothing else has happened since the previous report.
     *
     * Letters typed faster than the editor reports are covered: after three letters the reports
     * `+1`, `+2`, `+3` are each an echo, and so is a single `+3` from an editor that batches.
     */
    fun onSelection(selStart: Int, selEnd: Int): Boolean {
        val collapsed = selStart >= 0 && selStart == selEnd
        val advance = if (collapsed && caret >= 0) selStart - caret else 0
        val echo = onlyLetters && owed > 0 && advance in 1..owed
        owed = if (echo) owed - advance else 0
        caret = if (collapsed) selStart else -1
        // Letters still owed keep their claim; anything else typed in between gave it up, and
        // the slate is clean again once the editor has reported past it.
        if (owed == 0) onlyLetters = true
        return echo
    }
}

/**
 * How long to wait before re-reading the editor after a caret report, and the one fact that
 * decides it: whether the keyboard's idea of the word is known to match the editor.
 *
 * - Ordinarily [PROMPT_MS], as it has been since v1.2.6: long enough to collapse a drag of the
 *   caret into one read, short enough to feel immediate.
 * - After the echo of a typed letter ([TypedLetterEcho]), and only while [inStep], [PATIENT_MS].
 *   The re-read still happens -- when the user pauses -- so anything the app did to the text in
 *   the meantime is still picked up; it just no longer runs between every two letters of a word.
 *   And when it finds exactly the word the keyboard already holds, it ends there
 *   ([confirmsOnly]): it was a check, and the strip already shows that word's predictions.
 *
 * [inStep] is what keeps that safe. It becomes true when a re-read has run AND found what
 * typing had produced, and false the moment a prompt one is asked for or a new input session
 * starts. So the patient wait is only ever given to a letter typed onto a word the keyboard has
 * already checked against the editor, in an editor that was just seen to take typed letters as
 * typed, with nothing unexplained in between; then "the word, plus this letter" is exactly what
 * a re-read would find. A letter typed while a prompt re-read is still pending keeps that
 * re-read prompt. In an editor that rewrites what is typed (upper-casing, formatting) no re-read
 * ever agrees, and every letter keeps the prompt re-read it had before this class existed.
 *
 * Why a second and a half: the patient re-read should fall in a real pause, not between two
 * keys. At 400 ms it would land on the next key press of anyone typing at about that pace, which
 * is worse than where it was (100 ms after a key is comfortably before the next one). A second
 * and a half is slower than typing; someone who presses a key that slowly gets the re-read where
 * it always was for them -- in the gap -- and nobody else gets it mid-word at all.
 *
 * Pure Kotlin, main thread only.
 */
class CursorSyncPace {
    /** True when a re-read has agreed with typing and nothing has asked for a prompt one since. */
    var inStep = false
        private set

    /** The delay for the re-read this caret report asks for. Call once per report. */
    fun delayFor(afterTypedLetter: Boolean): Long {
        if (afterTypedLetter && inStep) return PATIENT_MS
        inStep = false
        return PROMPT_MS
    }

    /**
     * A re-read ran. [wordAsTyped] is the word the keyboard held going in, [wordRead] the word
     * it holds now, and [live] says the read went through the connection that is still current.
     */
    fun onSynced(wordAsTyped: String, wordRead: String, live: Boolean = true) {
        inStep = live && agrees(wordAsTyped, wordRead)
    }

    /** A new input session, or the pending re-read was dropped: nothing is known. */
    fun reset() {
        inStep = false
    }

    companion object {
        /**
         * Did the editor hold what typing produced? It did when the word read back ends with
         * the word as typed: more in FRONT of it is agreement too (text the field already held,
         * the "don'" before a "t"). An empty [wordAsTyped] proves nothing -- nothing typed was
         * compared -- so the re-read after a space does not count, nor the one after a tap in
         * the text when no word was in progress: the first letter typed after it gets a prompt
         * re-read of its own, and that one decides. Case counts: an editor that turns a typed
         * "h" into "H" does not agree.
         */
        fun agrees(wordAsTyped: String, wordRead: String): Boolean =
            wordAsTyped.isNotEmpty() && wordRead.endsWith(wordAsTyped)

        /**
         * Did a re-read that waited for a pause ([patient]) find nothing new? It did when it
         * went through the [live] connection and came back with the very word the keyboard
         * held ([wordAsTyped] equal to [wordRead], not merely its end) and no text after the
         * caret ([suffixLength] 0). Then typing has already predicted for this word, and the
         * caller stops: predicting again would rebind the strip for nothing, at a moment the
         * user may be about to tap it. A prompt re-read never stops here -- it follows a caret
         * that moved, and what it found is news even when the word is the same.
         */
        fun confirmsOnly(
            patient: Boolean, live: Boolean, wordAsTyped: String, wordRead: String, suffixLength: Int
        ): Boolean = patient && live && suffixLength == 0 && wordAsTyped.isNotEmpty() && wordRead == wordAsTyped

        /** The debounce every caret move has had since v1.2.6. */
        const val PROMPT_MS = 100L

        /** The wait after the echo of a typed letter. See the class comment for the value. */
        const val PATIENT_MS = 1500L
    }
}
