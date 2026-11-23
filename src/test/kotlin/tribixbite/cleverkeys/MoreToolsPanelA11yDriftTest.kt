package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File

/**
 * Wave 21 (2026-11-15): the tools panel's controls must be announceable.
 *
 * [MoreToolsPanelView] is built in code, not inflated from a layout, so the project's XML-side
 * a11y drift tests ([tribixbite.cleverkeys.clipboard.ClipboardEntryButtonA11yDriftTest] and
 * friends) cannot see it. That blind spot is how the panel shipped with a correct
 * `contentDescription` on all nine tiles and **none on its own close button**: the wrapper is
 * `clickable` + `focusable` (correct — it is the 48 dp hit target), so TalkBack focuses it and
 * announces an empty node. The other panes all label their dismiss affordance
 * (`clipboard_close`, `emoji_close`, `gif_close`); the tools panel was the one that did not.
 *
 * This test reads the source off disk rather than instantiating the view — the class imports
 * `android.view.*`/`android.widget.*` and so cannot run in the pure-JVM runner — and pins the
 * three things that together make the button usable with a screen reader:
 *
 *  1. the close wrapper carries a `contentDescription`;
 *  2. it is drawn from a string resource, never an English literal;
 *  3. that resource exists in the default `values/strings.xml` AND in every shipped locale file
 *     (21 of them), so the label is not silently English-only.
 *
 * It is deliberately a *source* scan, not a regex on one exact line: the check is "the block
 * that makes the wrapper interactive also names it", which survives formatting and renames.
 */
class MoreToolsPanelA11yDriftTest {

    private val viewSource by lazy { File(VIEW).readText() }

    @Test
    fun `the tools panel close button announces itself`() {
        val wrapper = wrapperBlock()
        assertWithMessage("the close wrapper's construction block must be findable")
            .that(wrapper).isNotEmpty()
        assertWithMessage("the close button is clickable+focusable and had NO label (wave 21)")
            .that(wrapper).contains("contentDescription")
    }

    @Test
    fun `the close label comes from a string resource, not an English literal`() {
        val wrapper = wrapperBlock()
        assertWithMessage("the close label must be a localized resource")
            .that(LOCALIZED_LABEL.matches(wrapper)).isTrue()
    }

    @Test
    fun `the close label exists in the default and in every shipped locale`() {
        val name = closeStringName()
        val files = localeStringFiles()
        assertWithMessage("expected the base strings file plus the shipped locales")
            .that(files.size).isAtLeast(2)
        for (file in files) {
            assertWithMessage("${file.path} is missing the panel close label `$name`")
                .that(file.readText()).contains("name=\"$name\"")
        }
    }

    /** The `val wrapper = FrameLayout(context).apply { … }` block in `buildHeader`. */
    private fun wrapperBlock(): String {
        val start = viewSource.indexOf("val wrapper = FrameLayout(context).apply {")
        assertWithMessage("the close wrapper's construction was renamed or removed")
            .that(start).isAtLeast(0)
        // The block ends at the first line that closes the apply at the wrapper's own indent.
        val end = viewSource.indexOf("\n        }", start)
        assertWithMessage("the close wrapper's block could not be delimited")
            .that(end).isGreaterThan(start)
        return viewSource.substring(start, end)
    }

    /** The `R.string.…` the contentDescription uses. */
    private fun closeStringName(): String {
        val match = Regex("""contentDescription\s*=\s*context\.getString\(R\.string\.([a-z0-9_]+)\)""")
            .find(wrapperBlock())
        assertNotNull("no `R.string.…` close label found — see the sibling test", match)
        return match!!.groupValues[1]
    }

    private fun localeStringFiles(): List<File> {
        val base = File("res/values/strings.xml")
        val locales = File("res").listFiles { f ->
            f.isDirectory && f.name.startsWith("values-") && File(f, "strings.xml").exists()
        }?.sortedBy { it.name } ?: emptyList()
        return listOf(base) + locales.map { File(it, "strings.xml") }
    }

    private companion object {
        const val VIEW = "src/main/kotlin/tribixbite/cleverkeys/MoreToolsPanelView.kt"

        /**
         * `contentDescription = context.getString(R.string.<lower_snake>)` — the only shape this
         * project accepts for a code-built control's label. Kept as a compiled [Regex] rather than
         * a Truth `containsPattern` so the assertion uses APIs this project already exercises.
         */
        val LOCALIZED_LABEL = Regex(
            """contentDescription\s*=\s*context\.getString\(R\.string\.[a-z0-9_]+\)"""
        )
    }
}
