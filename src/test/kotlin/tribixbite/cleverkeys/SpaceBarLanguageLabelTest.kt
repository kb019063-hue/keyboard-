package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test

/**
 * The space bar prints the language only when there is more than one (2026-11-28).
 *
 * Two owner reports looked like they contradicted each other: a blank space bar "read as a missing
 * key" in a Gboard screenshot (2026-11-04), and the word "English" was not wanted on his space bar
 * (standing instruction). Both are true of Gboard, which prints the language name on the space bar
 * only while more than one language is in play — with a single language the bar is blank. So the
 * label follows the frame's second language, not the layout: `ConfigSnapshot.active_secondary_language`
 * is null with Multi-Language off (its default) and on a single-language layout, and non-null when a
 * second decoder is actually in use.
 *
 * Source pin (drift-test idiom): the drawing check is a private method on a `View`, so what can be
 * pinned off-device is that it returns early on a single language and that the caller hands it the
 * frame's own second-language field rather than reading live `Config` (ARC-072).
 */
class SpaceBarLanguageLabelTest {

    private val view = File("src/main/kotlin/tribixbite/cleverkeys/Keyboard2View.kt").readText()

    @Test
    fun aSingleLanguageLeavesTheSpaceBarBlank() {
        assertWithMessage(
            "drawSpaceLanguageLabel must return without drawing when the frame has no second " +
                "language — otherwise a one-language keyboard prints \"English\" on its space bar"
        ).that(view).contains("if (secondLanguageTag == null) return 0f")
    }

    @Test
    fun theCallerReadsTheFramesSecondLanguage() {
        assertWithMessage("the label must be gated on the frame's second language")
            .that(view).contains("snap.primary_language, snap.active_secondary_language")
        assertWithMessage("and never on live Config (ARC-072: draw paths read the snapshot)")
            .that(view.contains("drawSpaceLanguageLabel(canvas, x, y, keyW, keyH, tc_key, snap.primary_language)"))
            .isFalse()
    }
}
