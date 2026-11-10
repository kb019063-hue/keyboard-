package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test

/**
 * Pins the emoji tooltip's flip-or-slide rule.
 *
 * The defect this guards: the tooltip was always drawn **above** and **centred** on the pressed
 * cell. On the grid's top row "above" is the pane's search bar, so the bubble left the keyboard
 * surface; on the first and last cell of every row, centring a bubble wider than the cell pushed
 * it past the side edge. Both clipped — and a clipped tooltip looks like a rendering bug rather
 * than a missing feature.
 *
 * All rectangles below are ordinary integers, so every case runs without a device.
 */
class EmojiTooltipPlacementTest {

    // A keyboard-sized surface, the region the bubble must stay inside.
    private val left = 0
    private val top = 1400
    private val right = 1080
    private val bottom = 2400

    private val bubbleW = 200
    private val bubbleH = 80
    private val cell = 90

    private fun place(ax: Int, ay: Int) = EmojiTooltipPlacement.place(
        anchorX = ax, anchorY = ay, anchorWidth = cell, anchorHeight = cell,
        width = bubbleW, height = bubbleH,
        boundsLeft = left, boundsTop = top, boundsRight = right, boundsBottom = bottom,
    )

    @Test
    fun `a cell in the middle gets its bubble above, centred`() {
        val result = place(ax = 500, ay = 1800)

        assertThat(result.below).isFalse()
        // Centred: cell centre sits on the bubble centre.
        assertThat(result.x).isEqualTo(500 + cell / 2 - bubbleW / 2)
        // Above, with the documented gap.
        assertThat(result.y)
            .isEqualTo(1800 - bubbleH - EmojiTooltipPlacement.GAP_PX)
    }

    @Test
    fun `a top-row cell flips the bubble below instead of into the search bar`() {
        // Sits at the top edge of the surface: there is no room above.
        val result = place(ax = 500, ay = top + 2)

        assertThat(result.below).isTrue()
        assertThat(result.y).isEqualTo(top + 2 + cell + EmojiTooltipPlacement.GAP_PX)
        assertThat(result.y).isGreaterThan(top)
    }

    @Test
    fun `a bubble that fits neither side still shows, clamped inside`() {
        // Taller than the surface: no placement fits. It must not be pushed off the top.
        val huge = EmojiTooltipPlacement.place(
            anchorX = 500, anchorY = top + 10, anchorWidth = cell, anchorHeight = cell,
            width = bubbleW, height = bottom - top + 500,
            boundsLeft = left, boundsTop = top, boundsRight = right, boundsBottom = bottom,
        )
        assertWithMessage("a bubble with no room keeps its top inset rather than inverting the clamp")
            .that(huge.y).isAtLeast(top)
    }

    @Test
    fun `the first cell of a row slides right instead of overhanging the left edge`() {
        val result = place(ax = 0, ay = 1800)
        assertThat(result.x).isEqualTo(left + EmojiTooltipPlacement.EDGE_MARGIN_PX)
        assertThat(result.x).isAtLeast(left)
    }

    @Test
    fun `the last cell of a row slides left instead of overhanging the right edge`() {
        val result = place(ax = right - cell, ay = 1800)
        assertThat(result.x + bubbleW).isAtMost(right - EmojiTooltipPlacement.EDGE_MARGIN_PX + 1)
        assertThat(result.x + bubbleW).isAtMost(right)
    }

    @Test
    fun `the bubble is always fully inside the bounds horizontally`() {
        // Sweep every cell position across the row: none may escape.
        var ax = left
        while (ax < right) {
            val result = place(ax = ax, ay = 1800)
            assertWithMessage("x at anchor $ax")
                .that(result.x).isAtLeast(left)
            assertWithMessage("right edge at anchor $ax")
                .that(result.x + bubbleW).isAtMost(right)
            ax += cell / 2
        }
    }

    @Test
    fun `dropDownOffsetY converts an above placement to a negative offset`() {
        val anchorY = 1800
        val aboveY = anchorY - bubbleH - EmojiTooltipPlacement.GAP_PX
        // showAsDropDown's origin is the anchor's bottom edge; above is negative.
        assertThat(EmojiTooltipPlacement.dropDownOffsetY(anchorY, cell, aboveY))
            .isEqualTo(aboveY - (anchorY + cell))
        assertThat(EmojiTooltipPlacement.dropDownOffsetY(anchorY, cell, aboveY)).isLessThan(0)
    }

    @Test
    fun `dropDownOffsetY converts a below placement to a positive offset`() {
        val anchorY = top + 2
        val belowY = anchorY + cell + EmojiTooltipPlacement.GAP_PX
        assertThat(EmojiTooltipPlacement.dropDownOffsetY(anchorY, cell, belowY)).isGreaterThan(0)
    }

    /**
     * Drift guard: the manager must ask the policy, not go back to a fixed "above, centred".
     *
     * The behavioural tests above stay green even if `EmojiTooltipManager` stops calling [place],
     * because the policy is correct on its own — only the caller's source can prove the fix is
     * wired in. Same convention as CoreImeHygieneDriftTest.
     */
    @Test
    fun `the tooltip manager still routes through the placement policy`() {
        val manager = File(
            "src/main/kotlin/tribixbite/cleverkeys/emoji/EmojiTooltipManager.kt"
        )
        assertThat(manager.isFile).isTrue()
        val body = manager.readText()

        assertWithMessage(
            "EmojiTooltipManager must call EmojiTooltipPlacement. Dropping it restores the " +
                "fixed above-and-centred bubble that clipped on the top row and at row ends."
        ).that(body).contains("EmojiTooltipPlacement.place(")
    }

    /**
     * Drift guard for the recents write.
     *
     * `scheduleSaveLastUsed` used the View's own `postDelayed`. A detached View drops its queue,
     * and this pane detaches on every switch back to the keyboard — so closing the grid within
     * the 500 ms window silently discarded the selection the user had just made. The fix puts the
     * deadline on a process-wide handler and snapshots the payload, so the queued write cannot
     * hold the view alive either.
     *
     * Both halves are source facts: a behavioural test would need a device, and the failure mode
     * (nothing saved) is invisible.
     */
    @Test
    fun `the recents debounce survives detach and does not retain the view`() {
        val grid = File("src/main/kotlin/tribixbite/cleverkeys/emoji/EmojiGridView.kt")
        assertThat(grid.isFile).isTrue()
        val body = grid.readText()

        assertWithMessage(
            "the debounce must not be a bare View postDelayed that calls the same runnable — " +
                "a detached View discards its queue, which is how the last emoji used to be lost."
        ).that(body).doesNotContain("        postDelayed({")

        assertWithMessage("the debounce must be scheduled on a non-View handler")
            .that(body).contains("MAIN_HANDLER.postDelayed(saveLastUsedRunnable")

        assertWithMessage(
            "detaching must not cancel the recents write — that write is the user's last " +
                "selection. Only the tooltip's decoration timers are dropped."
        ).that(body).doesNotContain("MAIN_HANDLER.removeCallbacksAndMessages")

        assertWithMessage(
            "the queued write must carry a snapshot, not a reference to this view"
        ).that(body).contains("pendingSaveSet")
    }
}
