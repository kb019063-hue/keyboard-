package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * Wave 21 (2026-11-15): the floating keyboard's handles belong BELOW the content, like Gboard's.
 *
 * [FloatingRoot] is built in code, so nothing else in the suite can see it — the same blind spot
 * that hid the tools panel's unlabelled close button. That matters here because the arrangement is
 * load-bearing in a way that is invisible at a glance:
 *
 *  - a grip **above** the content is a band inside the input-method window, which pushes the
 *    toolbar 26 dp down along with every y coordinate in the window. That is exactly how the smoke
 *    test's computed toolbar tap came to land in the grip, leaving 7g reporting `silent` and
 *    blaming the product's toolbar layout for months (PART-18 P5);
 *  - a grip **overlapping the knob** makes the resize affordance disappear: both handles are the
 *    same translucent grey, so a knob drawn on top of the grip is invisible while still being the
 *    thing you must hit.
 *
 * Neither failure raises an error. The keyboard still draws, still types, still drags — it just
 * does the wrong thing quietly. So both are pinned here by reading the source, because the classes
 * involved import `android.view.*` and cannot be instantiated in the pure-JVM runner.
 */
class FloatingRootHandlePositionDriftTest {

    private val source by lazy { File(ROOT).readText() }

    /** The `column` LinearLayout that stacks the content and the handle. */
    private fun columnBlock(): String {
        val start = source.indexOf("private val column = LinearLayout(context).apply {")
        assertWithMessage("FloatingRoot's `column` was renamed or removed — update this test")
            .that(start).isAtLeast(0)
        val end = source.indexOf("\n    }", start)
        assertWithMessage("FloatingRoot's `column` block could not be delimited")
            .that(end).isGreaterThan(start)
        return source.substring(start, end)
    }

    @Test
    fun `the content is added before the grip, so the toolbar sits at the window's top`() {
        val order = Regex("""addView\((\w+)\)""")
            .findAll(columnBlock())
            .map { it.groupValues[1] }
            .toList()
        assertWithMessage("`column` should add exactly the content holder and the grip, in that order")
            .that(order).isEqualTo(listOf("contentHolder", "grip"))
    }

    @Test
    fun `the grip stops short of the knob so the resize handle stays visible`() {
        val grip = source.indexOf("private val grip = View(context).apply {")
        assertWithMessage("the grip's construction was renamed or removed")
            .that(grip).isAtLeast(0)
        val block = source.substring(grip, source.indexOf("\n    }", grip))
        assertWithMessage(
            "the grip must reserve the knob's width (rightMargin = KNOB_SIZE_DP); without it the " +
                "knob is drawn on top of an identical grey band and the resize affordance vanishes"
        ).that(block).contains("rightMargin = dp(KNOB_SIZE_DP)")
    }

    @Test
    fun `both handle sizes are still named, because the smoke test mirrors the grip height`() {
        // scripts/ci/emulator-smoke.sh hardcodes 26 with a comment naming GRIP_HEIGHT_DP. If the
        // constant is renamed the comment silently stops being checkable; if the value changes the
        // script's guard warning is what tells CI. Pin the names so the first cannot happen.
        assertWithMessage("GRIP_HEIGHT_DP is referenced by the smoke test's guard")
            .that(source).contains("const val GRIP_HEIGHT_DP")
        assertWithMessage("KNOB_SIZE_DP is referenced by the grip's clearance")
            .that(source).contains("const val KNOB_SIZE_DP")
    }

    private companion object {
        const val ROOT = "src/main/kotlin/tribixbite/cleverkeys/FloatingRoot.kt"
    }
}
