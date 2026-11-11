package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * Audit E-10 (2026-09-06) — Toast is not a feedback surface inside the IME.
 *
 * Toasts from an IME context are suppressed on Android 13+ (the project's own #156
 * pattern; KeyboardReceiver.insertGif documents it inline and uses
 * `showSuggestionBarMessage`). The GIF long-press popup's "URL copied"/"GIF copied"/
 * "Keywords copied" actions still used Toast — invisible feedback on modern Android.
 *
 * Ratchet: no `Toast` usage in the IME-context files this wave owns. (Cross-cutting
 * ratchet #4 from the audit; the clipboard-pane files are W4's half and can be added to
 * [BANNED_FILES] when converted.)
 *
 * RED (2026-09-06, pre-fix): KeyboardReceiver.kt matched 3 Toast.makeText call sites
 * (showGifPopup's copy actions).
 */
class ImeToastBanDriftTest {

    @Test
    fun imeContextFilesNeverUseToast() {
        val offenders = BANNED_FILES.flatMap { path ->
            val file = File(path)
            check(file.isFile) { "Drift test must run from the project root ($path missing)" }
            file.readLines().withIndex()
                .filter { (_, line) -> TOAST_USE.containsMatchIn(line) }
                .map { (i, _) -> "$path:${i + 1}" }
        }
        assertWithMessage(
            "Toast is IME-suppressed on Android 13+ — feedback from IME-context code goes " +
                "through the suggestion bar (showSuggestionBarMessage, #156 pattern / audit E-10)."
        ).that(offenders).isEmpty()
    }

    /**
     * 2026-11-11 hunt, part 2 (F5). A toolbar button names itself on long-press. It did that
     * with a Toast from a long-click listener that returned true, which on Android 13+ shows
     * nothing twice over: the toast is dropped, and a claimed long-press never reaches the
     * view's own `tooltipText`. The toolbar keeps exactly one Toast, for API 24-25 (no tooltip
     * there, and toasts still work), and from API 26 leaves the long-press to the tooltip.
     */
    @Test
    fun toolbarLongPressIsLeftToTheTooltipFromApi26() {
        val file = File(TOOLBAR)
        check(file.isFile) { "Drift test must run from the project root ($TOOLBAR missing)" }
        val text = file.readText()
        val helper = text.substringAfter("private fun nameOnLongPress(", "").substringBefore("\n    }\n")
        assertWithMessage("KeyboardToolbar.nameOnLongPress is where a button gets its long-press label")
            .that(helper).isNotEmpty()

        val tooltip = helper.indexOf("if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {")
        val fallback = helper.indexOf("} else {")
        val listener = helper.indexOf("setOnLongClickListener")
        val toast = helper.indexOf("Toast.makeText")
        assertWithMessage("from API 26 the label is the view's tooltip")
            .that(tooltip in 0 until fallback && helper.indexOf("view.tooltipText = label") in tooltip until fallback)
            .isTrue()
        assertWithMessage("the long-click listener and its Toast belong to the pre-26 branch only")
            .that(listener > fallback && toast > listener).isTrue()
        assertWithMessage("no other long-click listener in the toolbar may claim the press")
            .that(Regex("setOnLongClickListener").findAll(text).count()).isEqualTo(1)
        assertWithMessage("and no other Toast")
            .that(Regex("""Toast\.makeText""").findAll(text).count()).isEqualTo(1)
    }

    private companion object {
        /** IME-context sources owned by the pane/routing wave. Extend as files convert. */
        val BANNED_FILES = listOf(
            "src/main/kotlin/tribixbite/cleverkeys/KeyboardReceiver.kt",
            "src/main/kotlin/tribixbite/cleverkeys/KeyEventHandler.kt",
            // 2026-11-11 hunt, part 2 (F5): the split, floating and incognito confirmations.
            "src/main/kotlin/tribixbite/cleverkeys/CleverKeysService.kt",
        )

        const val TOOLBAR = "src/main/kotlin/tribixbite/cleverkeys/KeyboardToolbar.kt"

        /** Both the call and the import — either one is how the pattern creeps back. */
        val TOAST_USE = Regex("""Toast\.makeText|import\s+android\.widget\.Toast""")
    }
}
