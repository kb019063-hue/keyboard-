package tribixbite.cleverkeys

import android.content.res.Resources
import android.text.InputType
import android.util.Log
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.objenesis.ObjenesisStd

/**
 * Proper-noun casing on swipe (device-confirmed gap): a swiped "bowie" committed lowercase
 * even at a sentence start.
 *
 * Tap typing is capitalized there because [Autocapitalisation] latches the SHIFT fake pointer
 * and each tapped key is shifted. The swipe path instead snapshots the shift latch at swipe
 * START (Pointers.onTouchDown → recognizer) — a snapshot that goes stale whenever autocap's
 * delayed (50ms) latch, a suggestion-commit the autocap cursor tracker never saw, or a
 * selection-update race un-latches shift around the gesture. The fix applies the SAME decision
 * the tap path's Autocapitalisation uses, but at COMMIT time: when neither shift nor caps lock
 * was latched, `Autocapitalisation.shouldCapitalizeAtCursor` (autocap setting + the field's
 * CAP flags + `getCursorCapsMode`) decides whether the swiped slate is capitalized.
 *
 * Deliberately NOT proper-noun dictionary casing — that is a different feature; this only
 * mirrors what tap typing would have produced at the same cursor.
 *
 * Harness: mock tier, Objenesis-allocated SuggestionHandler with only the fields the swipe
 * path reads seeded — same pattern as [SuggestionTapAddAndIWordTest].
 */
class SwipeAutocapCommitTest {

    private val objenesis = ObjenesisStd()

    private lateinit var contextTracker: PredictionContextTracker
    private lateinit var coordinator: PredictionCoordinator
    private lateinit var dictionary: DictionaryManager
    private lateinit var predictor: WordPredictor
    private lateinit var bar: SuggestionBar
    private lateinit var ic: InputConnection
    private lateinit var resources: Resources
    private lateinit var config: Config
    private lateinit var inputCoordinator: InputCoordinator

    /** The words the handler last pushed to the bar; getTopSuggestion answers from it. */
    private var barWords: List<String> = emptyList()

    /**
     * Every text the handler asked the editor to insert, in order, flattened to plain characters.
     *
     * The production commit is a SuggestionSpan-carrying CharSequence (`withSuggestionSpan`), not a
     * String: mockk's `eq("Bowie ")` never matches it, whatever the characters are. Comparing
     * `toString()` is what the editor itself sees, and it keeps the span decoration from turning a
     * casing regression into a wall of `Verification failed` noise.
     */
    private val commits = mutableListOf<String>()

    /** Call site of each insertion, for when the value alone does not explain the failure. */
    private val commitSites = mutableListOf<String>()

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0

        mockkObject(Config.Companion)
        config = mockk(relaxed = true)
        config.autocapitalisation = true
        config.auto_space_after_suggestion = true
        // The commit path reads the incognito-folded getter, which a relaxed mock answers false.
        every { config.autocapEffective } answers { config.autocapitalisation }
        // The span decoration hands `commitText` an android.text.SpannableString. In a plain JVM
        // unit test that class comes from the mockable android.jar, where every method is a stub:
        // its characters cannot be read back, so a String assertion can never match it (the value
        // prints as null). The span itself is covered by SuggestionSpanPolicyTest and
        // SuggestionSpanCommitTest; these classes are about WHICH characters and casing get
        // committed, so the decoration is switched off here instead of weakening the assertion.
        mockkObject(SuggestionSpanPolicy)
        every { SuggestionSpanPolicy.plan(any(), any(), any()) } returns null
        every { Config.globalConfig() } returns config

        dictionary = mockk(relaxed = true)
        predictor = mockk(relaxed = true)
        every { predictor.applyUserWordCaseToList(any()) } answers { firstArg() }
        coordinator = mockk(relaxed = true)
        every { coordinator.getDictionaryManager() } returns dictionary
        every { coordinator.getWordPredictor() } returns predictor

        contextTracker = mockk(relaxed = true)
        every { contextTracker.getLastCommitSource() } returns PredictionSource.UNKNOWN
        every { contextTracker.getLastAutoInsertedWord() } returns null
        every { contextTracker.wasLastInputSwipe() } returns true
        every { contextTracker.getCurrentWordLength() } returns 0
        every { contextTracker.getCurrentWord() } returns ""
        every { contextTracker.shouldSyncForInputType(any()) } returns true

        barWords = emptyList()
        bar = mockk(relaxed = true)
        every { bar.getMetaForSuggestion(any()) } returns null
        every { bar.setSuggestionsWithScores(any(), any(), any(), any()) } answers {
            barWords = firstArg<List<String>>().toList()
        }
        every { bar.setSuggestionsWithScores(any(), any(), any()) } answers {
            barWords = firstArg<List<String>>().toList()
        }
        every { bar.getTopSuggestion() } answers { barWords.firstOrNull() }

        inputCoordinator = mockk(relaxed = true)
        every { inputCoordinator.getCurrentSwipeData() } returns null

        commits.clear()
        commitSites.clear()
        ic = mockk(relaxed = true)
        every { ic.commitText(any(), any()) } answers {
            // `null` reaches here: some caller hands the editor nothing. Keep the value verbatim
            // (never `toString()` blindly) and remember WHO asked, because the call site is the
            // only thing that explains it.
            val raw = args.getOrNull(0)
            commits += raw?.toString()
                ?: "<null arg=" + args.joinToString(",") { it?.javaClass?.simpleName ?: "null" } + ">"
            commitSites += "{" + Throwable().stackTrace
                    .filter { it.className.startsWith("tribixbite") && !it.className.contains("Test") }
                    .take(3)
                    .joinToString("<-") { "${it.fileName}:${it.lineNumber}" } + "}"
            true
        }
        every { ic.getTextBeforeCursor(any(), any()) } returns ""
        every { ic.getTextAfterCursor(any(), any()) } returns ""
        resources = mockk(relaxed = true)
    }

    @After
    fun teardown() = unmockkAll()

    // ------------------------------------------------------------------ fixtures

    private fun handler(): SuggestionHandler {
        val handler = objenesis.newInstance(SuggestionHandler::class.java)
        handler.setField("contextTracker", contextTracker)
        handler.setField("predictionCoordinator", coordinator)
        handler.setField("suggestionBar", bar)
        handler.setField("config", config)
        handler.setField("contractionManager", mockk<ContractionManager>(relaxed = true))
        handler.setField("keyeventhandler", mockk<KeyEventHandler>(relaxed = true))
        handler.setField("predictionTasks", mockk<PredictionTaskRunner>(relaxed = true))
        // Objenesis skips field initialisers: production's non-null defaults must exist.
        handler.setField("autocorrectMemo", tribixbite.cleverkeys.autocorrect.AutocorrectMemo())
        handler.setField("fieldAllowsPersonalizedLearning", true)
        handler.setField("mainHandler", mockk<android.os.Handler>(relaxed = true))
        return handler
    }

    /** A sentence-capitalizing text field (what a notes/message body declares). */
    private fun capSentencesField(): EditorInfo =
        objenesis.newInstance(EditorInfo::class.java).apply {
            packageName = "com.example.notes"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }

    /** A plain text field that declares NO capitalization behavior at all. */
    private fun noCapsField(): EditorInfo =
        objenesis.newInstance(EditorInfo::class.java).apply {
            packageName = "com.example.notes"
            inputType = InputType.TYPE_CLASS_TEXT
        }

    private fun swipe(
        editorInfo: EditorInfo,
        shiftActive: Boolean = false,
        shiftLocked: Boolean = false,
    ) {
        handler().handleSwipePredictionResults(
            listOf("bowie", "bowls"), listOf(100, 90), ic, editorInfo, resources,
            shiftActive, shiftLocked, inputCoordinator
        )
    }

    // ------------------------------------------------------------------ the gap

    @Test
    fun aSentenceStartSwipeCommitIsCapitalized() {
        // The editor reports "capitalize here" — exactly what autocap consults for taps.
        every { ic.getCursorCapsMode(any()) } returns InputType.TYPE_TEXT_FLAG_CAP_SENTENCES

        swipe(capSentencesField())

        assertCommitted("Bowie ")
        assertWithMessage("the bar must show the same casing the commit used")
            .that(barWords.first()).isEqualTo("Bowie")
    }

    @Test
    fun midSentenceTheSwipeCommitStaysLowercase() {
        every { ic.getCursorCapsMode(any()) } returns 0

        swipe(capSentencesField())

        assertCommitted("bowie ")
    }

    @Test
    fun withAutocapDisabledTheSentenceStartSwipeStaysLowercase() {
        config.autocapitalisation = false
        every { ic.getCursorCapsMode(any()) } returns InputType.TYPE_TEXT_FLAG_CAP_SENTENCES

        swipe(capSentencesField())

        assertCommitted("bowie ")
    }

    @Test
    fun aFieldWithoutCapFlagsIsNeverQueriedAndStaysLowercase() {
        // Mirrors Autocapitalisation.started(): capsMode == 0 disables the feature outright,
        // so a misbehaving editor's getCursorCapsMode can never flip the casing.
        swipe(noCapsField())

        verify(exactly = 0) { ic.getCursorCapsMode(any()) }
        assertCommitted("bowie ")
    }

    // ------------------------------------------------- explicit shift still wins

    @Test
    fun capsLockStillUppercasesTheWholeWord() {
        every { ic.getCursorCapsMode(any()) } returns 0

        swipe(capSentencesField(), shiftLocked = true)

        assertCommitted("BOWIE ")
    }

    @Test
    fun aLatchedShiftStillCapitalizesWithoutConsultingTheCursor() {
        swipe(capSentencesField(), shiftActive = true)

        assertCommitted("Bowie ")
    }

    /**
     * The editor was asked to insert exactly [expected].
     *
     * Failure message is short on purpose: CI annotations are truncated, and a trailing space is
     * invisible in `expected:<fix got > but was:<fix got>` — `·` makes it obvious.
     */
    private fun assertCommitted(expected: String) {
        val shown = commits.zip(commitSites)
            .joinToString(" | ") { (text, site) -> "<${text.replace(" ", "·")}>$site" }
        assertWithMessage("editor insertions (spaces shown as ·): $shown")
            .that(commits).containsExactly(expected)
    }

    // ------------------------------------------------------------------ reflection

    private fun Any.setField(name: String, value: Any?) {
        val field = javaClass.declaredFields.firstOrNull { it.name == name }
        assertWithMessage(
            "field '$name' not found on ${javaClass.simpleName} — it was renamed or removed; " +
                "declared: ${javaClass.declaredFields.map { it.name }}"
        ).that(field).isNotNull()
        field!!.isAccessible = true
        field.set(this, value)
    }
}
