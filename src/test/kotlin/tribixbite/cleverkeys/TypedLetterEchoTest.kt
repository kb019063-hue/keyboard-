package tribixbite.cleverkeys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [TypedLetterEcho] and [CursorSyncPace]: which caret reports are only the answer to a letter
 * the keyboard typed, and how long the re-read of the editor that follows a report may wait.
 *
 * Before 2026-11-12 every typed letter was followed, a tenth of a second later, by two round
 * trips to the app, a second prediction and a second redraw of the suggestion strip, all to
 * learn the word the keyboard had just typed. The emulator showed the count (33 strip rebinds
 * for a 21-key sentence); it cannot show the cost, because its test field lives in the
 * keyboard's own process and answers at once -- on a phone each read waits for another app.
 *
 * The rule here is narrow on purpose, and these cases are the fence around it: only a letter
 * that extends a word, only when the caret lands exactly where it put it, only when the word was
 * already checked against the editor. Everything else keeps the prompt re-read it always had.
 */
class TypedLetterEchoTest {

    private fun echoAt(caret: Int) = TypedLetterEcho().apply { reset(caret, caret) }

    // ── which reports are echoes ────────────────────────────────────────────────────────────────

    @Test
    fun theCaretArrivingWhereALetterPutIt_isAnEcho() {
        val echo = echoAt(10)
        echo.onTyped(extendsWord = true)
        assertTrue(echo.onSelection(11, 11))
    }

    @Test
    fun aReportNobodyTypedFor_isNotAnEcho() {
        val echo = echoAt(10)
        assertFalse("a tap one character to the right", echo.onSelection(11, 11))
        assertFalse("and back", echo.onSelection(10, 10))
    }

    @Test
    fun anEchoIsOwedOnce() {
        val echo = echoAt(10)
        echo.onTyped(extendsWord = true)
        assertTrue(echo.onSelection(11, 11))
        assertFalse("the next move is the user's", echo.onSelection(12, 12))
    }

    @Test
    fun theFirstLetterOfAWord_aSpace_orPunctuation_isNotOwedAnEcho() {
        val echo = echoAt(10)
        echo.onTyped(extendsWord = false)
        assertFalse(echo.onSelection(11, 11))
    }

    @Test
    fun lettersTypedFasterThanTheEditorReports_areEachOwedOne() {
        val echo = echoAt(10)
        repeat(3) { echo.onTyped(extendsWord = true) }
        assertTrue(echo.onSelection(11, 11))
        assertTrue(echo.onSelection(12, 12))
        assertTrue(echo.onSelection(13, 13))
        assertFalse(echo.onSelection(14, 14))
    }

    @Test
    fun anEditorThatReportsSeveralLettersAtOnce_isStillAnEcho() {
        val echo = echoAt(10)
        repeat(3) { echo.onTyped(extendsWord = true) }
        assertTrue(echo.onSelection(13, 13))
        assertFalse(echo.onSelection(14, 14))
    }

    @Test
    fun theCaretLandingAnywhereElse_isAMove_andCancelsWhatWasOwed() {
        val echo = echoAt(10)
        echo.onTyped(extendsWord = true)
        assertFalse("the app put the caret somewhere else", echo.onSelection(15, 15))
        assertFalse("and the letter's claim is spent", echo.onSelection(16, 16))

        val back = echoAt(10)
        back.onTyped(extendsWord = true)
        assertFalse("the caret went backwards", back.onSelection(9, 9))
    }

    @Test
    fun aRangeIsNeverAnEcho_andLeavesTheCaretUnknown() {
        val echo = echoAt(10)
        echo.onTyped(extendsWord = true)
        assertFalse(echo.onSelection(8, 11))
        // The caret is unknown now, so the next letter cannot be matched to a position...
        echo.onTyped(extendsWord = true)
        assertFalse(echo.onSelection(9, 9))
        // ...but the report that followed it says where the caret is, and the one after counts.
        echo.onTyped(extendsWord = true)
        assertTrue(echo.onSelection(10, 10))
    }

    @Test
    fun aSpaceTypedBeforeTheLettersReportArrived_takesTheLettersClaimWithIt() {
        // "d" then " " faster than the editor answers: both reports are followed up promptly,
        // which is what the space needs (next-word suggestions, autocorrect's undo).
        val echo = echoAt(10)
        echo.onTyped(extendsWord = true)
        echo.onTyped(extendsWord = false)
        assertFalse(echo.onSelection(11, 11))
        assertFalse(echo.onSelection(12, 12))
        // The slate is clean afterwards.
        echo.onTyped(extendsWord = true)
        assertTrue(echo.onSelection(13, 13))
    }

    @Test
    fun aLetterTypedWhileSomethingElseIsStillUnanswered_isNotOwedAnEcho() {
        val echo = echoAt(10)
        echo.onTyped(extendsWord = false) // pasted text, say, whose report has not come
        echo.onTyped(extendsWord = true)
        assertFalse(echo.onSelection(14, 14))
        assertFalse(echo.onSelection(15, 15))
    }

    @Test
    fun beforeTheEditorHasSaidWhereTheCaretIs_nothingIsAnEcho() {
        val echo = TypedLetterEcho()
        echo.onTyped(extendsWord = true)
        assertFalse(echo.onSelection(1, 1))
        // That report was the first word on where the caret is; from here on letters count.
        echo.onTyped(extendsWord = true)
        assertTrue(echo.onSelection(2, 2))
    }

    @Test
    fun aNewInputSessionForgetsWhatWasOwed() {
        val echo = echoAt(10)
        echo.onTyped(extendsWord = true)
        echo.reset(0, 0) // the app emptied the field and restarted input
        assertFalse(echo.onSelection(1, 1))

        val ranged = echoAt(10)
        ranged.reset(3, 7) // a session that starts with a selection: the caret is not known
        ranged.onTyped(extendsWord = true)
        assertFalse(ranged.onSelection(4, 4))
    }

    // ── how long the re-read waits ──────────────────────────────────────────────────────────────

    @Test
    fun aCaretMove_isFollowedUpPromptly() {
        val pace = CursorSyncPace()
        assertEquals(CursorSyncPace.PROMPT_MS, pace.delayFor(afterTypedLetter = false))
    }

    @Test
    fun anEchoIsOnlyPatient_onceTheWordHasBeenCheckedAgainstTheEditor() {
        val pace = CursorSyncPace()
        // A field that already holds "hel": the keyboard knows nothing of it until it reads.
        assertEquals("nothing has been read yet", CursorSyncPace.PROMPT_MS, pace.delayFor(afterTypedLetter = true))
        pace.onSynced(wordAsTyped = "l", wordRead = "hell")
        assertEquals(CursorSyncPace.PATIENT_MS, pace.delayFor(afterTypedLetter = true))
        assertEquals("and stays so, letter after letter", CursorSyncPace.PATIENT_MS, pace.delayFor(afterTypedLetter = true))
    }

    @Test
    fun aLetterTypedWhileAPromptReReadIsPending_keepsItPrompt() {
        // First letter of a word (prompt), second letter before that re-read has run: the re-read
        // is what joins "don'" and "t", so it must not be pushed out to the next pause.
        val pace = CursorSyncPace().apply { onSynced(wordAsTyped = "can", wordRead = "can") }
        assertEquals(CursorSyncPace.PROMPT_MS, pace.delayFor(afterTypedLetter = false))
        assertFalse(pace.inStep)
        assertEquals(CursorSyncPace.PROMPT_MS, pace.delayFor(afterTypedLetter = true))
        pace.onSynced(wordAsTyped = "th", wordRead = "don'th")
        assertEquals(CursorSyncPace.PATIENT_MS, pace.delayFor(afterTypedLetter = true))
    }

    @Test
    fun aReReadThatFoundSomethingElse_leavesTheNextLettersPrompt() {
        // An editor that upper-cases what is typed: typing produced "h", the editor holds "H".
        // Every re-read disagrees, so every letter keeps the prompt re-read it always had.
        val pace = CursorSyncPace()
        var held = ""
        for (letter in "hello") {
            assertEquals(CursorSyncPace.PROMPT_MS, pace.delayFor(afterTypedLetter = true))
            val asTyped = held + letter
            held = asTyped.uppercase()           // what the re-read finds, and the tracker adopts
            pace.onSynced(wordAsTyped = asTyped, wordRead = held)
            assertFalse("\"$asTyped\" typed, \"$held\" read", pace.inStep)
        }
        // The same after one that agreed: a later disagreement takes the patience away again.
        pace.onSynced(wordAsTyped = "sa", wordRead = "sa")
        assertEquals(CursorSyncPace.PATIENT_MS, pace.delayFor(afterTypedLetter = true))
        pace.onSynced(wordAsTyped = "sad", wordRead = "")   // the read failed, or the text is gone
        assertEquals(CursorSyncPace.PROMPT_MS, pace.delayFor(afterTypedLetter = true))
    }

    @Test
    fun agreement_isTheTypedWordAtTheEndOfTheWordReadBack_andNeverNothing() {
        assertTrue("the same word", CursorSyncPace.agrees("sad", "sad"))
        assertTrue("text the field already held in front", CursorSyncPace.agrees("s", "cats"))
        assertTrue("a contraction's stem in front", CursorSyncPace.agrees("t", "don't"))
        assertFalse("an editor that upper-cases", CursorSyncPace.agrees("h", "H"))
        assertFalse("an editor that dropped the letter", CursorSyncPace.agrees("sad", "sa"))
        assertFalse("the caret went to another word", CursorSyncPace.agrees("sad", "lad"))
        assertFalse("a read that came back empty", CursorSyncPace.agrees("sad", ""))
        // Nothing typed was compared: a re-read after a tap in the text or after a space says
        // where the caret is, not whether this editor takes typed letters as typed.
        assertFalse(CursorSyncPace.agrees("", "hel"))
        assertFalse(CursorSyncPace.agrees("", ""))
    }

    @Test
    fun aCaretParkedOnAWord_earnsNoPatienceUntilALetterTypedThereHasBeenReadBack() {
        val pace = CursorSyncPace()
        // A tap at the end of "hel": a move, re-read promptly; the keyboard now holds "hel".
        assertEquals(CursorSyncPace.PROMPT_MS, pace.delayFor(afterTypedLetter = false))
        pace.onSynced(wordAsTyped = "", wordRead = "hel")
        // "l" typed onto it: the word is longer than one letter, so this IS an echo -- but no
        // typed letter has been compared with this editor yet.
        assertEquals(CursorSyncPace.PROMPT_MS, pace.delayFor(afterTypedLetter = true))
        pace.onSynced(wordAsTyped = "hell", wordRead = "hell")
        assertEquals(CursorSyncPace.PATIENT_MS, pace.delayFor(afterTypedLetter = true))
    }

    @Test
    fun aReadThroughAConnectionThatIsNoLongerTheLiveOne_provesNothing() {
        val pace = CursorSyncPace()
        pace.onSynced(wordAsTyped = "sad", wordRead = "sad", live = false)
        assertFalse(pace.inStep)
        assertEquals(CursorSyncPace.PROMPT_MS, pace.delayFor(afterTypedLetter = true))
    }

    @Test
    fun aPatientReReadThatFindsTheSameWord_isOnlyACheck() {
        // "sad" typed in step; the user pauses; the editor holds "sad": nothing further to do.
        assertTrue(CursorSyncPace.confirmsOnly(patient = true, live = true, wordAsTyped = "sad", wordRead = "sad", suffixLength = 0))
        // Anything new, and the full follow-up runs as it always did:
        assertFalse("the app put text in front (\"un\" + \"sad\"): the word is another word now",
            CursorSyncPace.confirmsOnly(true, true, "sad", "unsad", 0))
        assertFalse("the app changed the word",
            CursorSyncPace.confirmsOnly(true, true, "sad", "Sad", 0))
        assertFalse("text now follows the caret: the keyboard is inside a word",
            CursorSyncPace.confirmsOnly(true, true, "sad", "sad", 2))
        assertFalse("the read came back empty",
            CursorSyncPace.confirmsOnly(true, true, "sad", "", 0))
        assertFalse("read through a connection that is no longer the live one",
            CursorSyncPace.confirmsOnly(true, false, "sad", "sad", 0))
        assertFalse("no word in progress on either side is the caret parked: that is not a check",
            CursorSyncPace.confirmsOnly(true, true, "", "", 0))
        // A prompt re-read follows a caret that moved; it is never cut short.
        assertFalse(CursorSyncPace.confirmsOnly(patient = false, live = true, wordAsTyped = "sad", wordRead = "sad", suffixLength = 0))
    }

    @Test
    fun aNewInputSession_startsFromNothingKnown() {
        val pace = CursorSyncPace().apply { onSynced(wordAsTyped = "sa", wordRead = "sa") }
        assertTrue(pace.inStep)
        pace.reset()
        assertEquals(CursorSyncPace.PROMPT_MS, pace.delayFor(afterTypedLetter = true))
    }

    @Test
    fun thePatientWait_isLongerThanAnyTypingRhythm_andThePromptOneIsUnchanged() {
        // 100 ms is the debounce every caret move has had since v1.2.6. The patient wait must
        // fall in a pause: at a few hundred milliseconds it would land on the next key press.
        assertEquals(100L, CursorSyncPace.PROMPT_MS)
        assertTrue(CursorSyncPace.PATIENT_MS >= 1000L)
    }

    // ── a sentence, end to end ──────────────────────────────────────────────────────────────────

    /** Types [text] one key at a time with the editor answering each key, and returns the delays. */
    private fun type(text: String, startCaret: Int = 0): List<Long> {
        val echo = echoAt(startCaret)
        val pace = CursorSyncPace()
        val delays = ArrayList<Long>()
        var caret = startCaret
        var word = ""
        for (c in text) {
            if (c.isLetter()) { word += c; echo.onTyped(extendsWord = word.length > 1) }
            else { word = ""; echo.onTyped(extendsWord = false) }
            caret++
            val delay = pace.delayFor(echo.onSelection(caret, caret))
            delays.add(delay)
            // Keys are far enough apart for a prompt re-read to run before the next one; a
            // patient one is overtaken by the next key. The editor holds what was typed.
            if (delay == CursorSyncPace.PROMPT_MS) pace.onSynced(wordAsTyped = word, wordRead = word)
        }
        return delays
    }

    @Test
    fun inASentence_onlyTheFirstLetterOfEachWordAndEachSpace_areReReadPromptly() {
        val p = CursorSyncPace.PROMPT_MS
        val w = CursorSyncPace.PATIENT_MS
        assertEquals(listOf(p, p, p, w, w, p, p, w, w), type("a sad lad"))
        // The probe sentence of the emulator smoke test: 21 keys, 6 words, 5 spaces.
        val delays = type("a sad lad had a salad")
        assertEquals(21, delays.size)
        assertEquals("first letters and spaces", 11, delays.count { it == p })
        assertEquals("letters that extend a word", 10, delays.count { it == w })
    }

    // ── the wiring ──────────────────────────────────────────────────────────────────────────────

    private fun source(relative: String): String {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "src/main/kotlin").isDirectory }
        return File(root, "src/main/kotlin/tribixbite/cleverkeys/$relative").readText()
    }

    private fun body(text: String, signature: String): String {
        val start = text.indexOf(signature)
        assertTrue("$signature not found", start >= 0)
        val end = text.indexOf("\n    }\n", start)
        assertTrue("end of $signature not found", end > start)
        return text.substring(start, end)
    }

    @Test
    fun theHandlerSaysWhichLettersExtendAWord_andEverythingElseSaysItIsNot() {
        val handler = source("SuggestionHandler.kt")
        assertTrue("the one way to say \"not a typed letter\"",
            handler.contains("private fun noteNotATypedLetter() = contextTracker.noteTyped(extendsWord = false)"))
        val typing = body(handler, "fun handleRegularTyping(")
        val notALetter = typing.indexOf("if (text.length != 1 || !text[0].isLetter()) noteNotATypedLetter()")
        // The first return STATEMENT (the word also appears in comments).
        val firstReturn = Regex("(?m)^\\s*return\\b").find(typing)?.range?.first ?: typing.length
        assertTrue("a key that is not a letter must say so before any early return",
            notALetter in 0 until firstReturn)
        val append = typing.indexOf("contextTracker.appendToCurrentWord(text)")
        val extend = typing.indexOf("extendsWord = contextTracker.getCurrentWordLength() > 1 &&")
        assertTrue("a letter counts only once it has been added to the word, and only past the first",
            append in 0 until extend)
        assertTrue("and only at the END of the word: a letter typed into one keeps its prompt re-read",
            typing.substring(extend, extend + 200).contains("contextTracker.getCurrentWordSuffixLength() == 0"))
    }

    @Test
    fun everyOtherWayTheKeyboardWritesToTheEditor_givesUpTheClaimFirst() {
        // Backspace, Enter and the arrows, a suggestion, a swipe, delete-word: each moves the
        // caret, some by exactly one. If an editor dropped a letter's report, the report of one
        // of these must not be taken for it -- so each says "not a typed letter" before it does
        // anything else that could return.
        val handler = source("SuggestionHandler.kt")
        for (entry in listOf(
            "fun handleBackspace(", "fun handleNonTextInput(", "fun handleDeleteLastWord(",
            "fun handleSwipePredictionResults(", "fun onSuggestionSelected(",
        )) {
            val text = body(handler, entry)
            val note = text.indexOf("noteNotATypedLetter()")
            assertTrue("$entry must call noteNotATypedLetter()", note >= 0)
            // Nothing but a null/blank guard may return before it.
            val returnsBefore = Regex("(?m)^\\s*(if \\(.*\\) )?return\\b.*$").findAll(text.substring(0, note))
                .map { it.value.trim() }.toList()
            assertTrue("$entry returns before the note: $returnsBefore",
                returnsBefore.all { it == "if (word.isNullOrBlank()) return null" })
        }
    }

    @Test
    fun theServiceAsksAboutEveryReport_andOnlyTheAnswerReachesTheCoordinator() {
        val selection = body(source("CleverKeysService.kt"), "override fun onUpdateSelection(")
        val asked = selection.indexOf("_contextTracker.noteSelection(newSelStart, newSelEnd)")
        val gate = selection.indexOf("_suggestionHandler.onEditorSelectionChanged(")
        assertTrue("every report is seen, including the ones the owned-selection gate swallows",
            asked in 0 until gate)
        assertTrue(selection.contains("afterTypedLetter = afterTypedLetter"))

        val started = body(source("CleverKeysService.kt"), "override fun onStartInput(")
        assertTrue(started.contains("_contextTracker.noteInputStarted("))
        assertTrue(started.contains("_inputCoordinator.onInputStarted()"))
    }

    @Test
    fun theCoordinatorTakesItsDelayFromThePace_andMarksTheWordCheckedOnlyAfterReading() {
        val moved = body(source("InputCoordinator.kt"), "fun onCursorMoved(")
        val delay = moved.indexOf("syncPace.delayFor(afterTypedLetter)")
        val read = moved.indexOf("contextTracker.synchronizeWithCursor(ic, language, editorInfo)")
        val synced = moved.indexOf("syncPace.onSynced(")
        assertTrue(delay >= 0)
        assertTrue("checked means read: onSynced comes after the read", read in 0 until synced)
        val asTyped = moved.indexOf("val wordAsTyped = contextTracker.getCurrentWord()")
        assertTrue("what typing produced is taken BEFORE the read replaces it", asTyped in 0 until read)
        assertTrue("live means read through the connection that is still the current one",
            moved.contains("val live = ic != null && (provider == null || provider.current().first === ic)"))
        val wordRead = moved.indexOf("val wordRead = contextTracker.getCurrentWord()")
        assertTrue("what the editor held is taken AFTER the read", wordRead > read)
        assertTrue("and in step means the editor held what was typed",
            moved.contains("syncPace.onSynced(wordAsTyped = wordAsTyped, wordRead = wordRead, live = live)"))
        // A patient re-read that found nothing new stops before any prediction is asked for.
        val check = moved.indexOf("CursorSyncPace.confirmsOnly(")
        val predict = moved.indexOf("cursorSyncDelegate?.handleCursorSyncPrediction(")
        assertTrue("the check comes after the pace has been told, and before the prediction",
            check in (synced + 1) until predict)
        assertTrue("patient is the delay this report was given, fixed when it was scheduled",
            moved.contains("val patient = delayMs != CursorSyncPace.PROMPT_MS"))
        assertTrue(Regex("confirmsOnly\\(\\s*patient, live, wordAsTyped, wordRead, contextTracker\\.getCurrentWordSuffixLength\\(\\)\\s*\\)\\s*\\)\\s*\\{\\s*LatencyMeter\\.synced\\(System\\.nanoTime\\(\\) - syncBegan\\)\\s*return@Runnable")
            .containsMatchIn(moved))
        assertTrue(moved.contains("syncHandler.postDelayed(pendingSyncRunnable!!, delayMs)"))
        assertFalse("the fixed 100 ms is gone from the coordinator",
            source("InputCoordinator.kt").contains("CURSOR_SYNC_DEBOUNCE_MS"))
    }
}
