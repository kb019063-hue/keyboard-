package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test

/**
 * One pitch for every key row, as on Gboard (2026-11-28).
 *
 * Gboard does not distinguish "digit rows" from "letter rows" or "the bottom row" by height: with
 * its number row switched on it is taller by exactly one ordinary row, and every key in every row
 * is the same size. This keyboard's rows were not: the Gboard-style layout gave its digit row
 * `height="0.75"` and its bottom row `height="0.95"` (HeliBoard's compact conventions), so the
 * digits read as a squashed strip and the space bar as a thin band under a normal keyboard — the
 * one part of the default layout that did not match the screenshots it was built from.
 *
 * The rows that are deliberately NOT ordinary key rows keep their own heights: a numpad pane
 * (`numeric.xml`, `numpad.xml`) is a different animal, and the iOS/SwiftKey style layouts are
 * copies of other keyboards' proportions rather than Gboard's.
 */
class KeyRowPitchTest {

    private fun read(path: String): String {
        val f = File(path)
        assertWithMessage("$path must exist for this pin to mean anything").that(f.isFile).isTrue()
        return f.readText()
    }

    /** Every `<row …>` open tag in [xml], without the comments (which mention heights). */
    private fun rowTags(xml: String): List<String> {
        val withoutComments = xml.replace(
            Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL),
            ""
        )
        return Regex("<row\\b[^>]*>").findAll(withoutComments).map { it.value }.toList()
    }

    @Test
    fun theGboardLayoutGivesEveryRowTheSameHeight() {
        val xml = read("src/main/layouts/latn_qwerty_us_gboard_style.xml")
        val tags = rowTags(xml)
        assertWithMessage("the Gboard-style layout must still have its five rows")
            .that(tags.size).isEqualTo(5)
        val odd = tags.filter { it.contains("height=") }
        assertWithMessage(
            "every row of the Gboard-style layout must be one key row tall: Gboard's digit row " +
                "and space-bar row are the same height as its letter rows. Rows that disagree: $odd"
        ).that(odd).isEmpty()
    }

    @Test
    fun theSharedNumberRowIsAWholeKeyRow() {
        for (path in listOf("res/xml/number_row.xml", "res/xml/number_row_no_symbols.xml")) {
            val tags = rowTags(read(path))
            assertWithMessage("$path must declare exactly one row").that(tags.size).isEqualTo(1)
            assertWithMessage(
                "$path is appended above the letter rows of every layout that borrows it, so it " +
                    "must be a whole key row (no `height`) — a compact 0.75-row digit strip reads " +
                    "as a second, squashed keyboard"
            ).that(tags.first().contains("height=")).isFalse()
        }
    }

    @Test
    fun theGboardLayoutsDigitRowIsStillTenKeys() {
        // The pitch change is a height change only: the row must still be the ten digits, or this
        // pin would happily accept a layout that lost its number row altogether.
        val xml = read("src/main/layouts/latn_qwerty_us_gboard_style.xml")
        val firstRow = Regex("<row\\b[^>]*>(.*?)</row>", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.get(1).orEmpty()
        val keys = Regex("<key\\b[^>]*>").findAll(firstRow).map { it.value }.toList()
        assertWithMessage("the digit row must still be ten keys, one unit each")
            .that(keys.size).isEqualTo(10)
        for (digit in 0..9) {
            assertWithMessage("the digit row must still carry $digit")
                .that(firstRow.contains("c=\"$digit\"")).isTrue()
        }
    }
}
