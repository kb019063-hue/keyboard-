package tribixbite.cleverkeys

/**
 * Where the emoji long-press tooltip goes, given the space it has.
 *
 * ## Why this exists
 *
 * `EmojiTooltipManager` always drew its bubble **above** the pressed cell and horizontally
 * centred on it. On the top row of the emoji grid there is no "above" — the pane's search bar is
 * there — so the tooltip left the keyboard surface and clipped. Two of the nine cells at each end
 * of a row also pushed it past the side edge, because centring a 200 dp-max bubble on a 45 dp cell
 * overhangs by roughly the difference.
 *
 * Gboard solves this by keeping the bubble inside the panel rather than by removing it: it flips
 * to the other side when one side does not fit, and slides along the edge instead of hanging off
 * it. That is the whole rule, and it is arithmetic on rectangles — so it lives here, pure, where
 * a JVM test can pin every case. The view code only measures, calls [place], and applies the
 * numbers it gets back.
 *
 * ## Coordinates
 *
 * `anchor*` and `bounds*` are in the same space; on Android that is window coordinates from
 * `getLocationInWindow`. The returned rectangle is in that same space, and its offset relative to
 * the anchor is what `PopupWindow.showAsDropDown` wants.
 *
 * Pure Kotlin: no resource ids, no Android types — like [ToolbarCompactPolicy] and
 * [MoreToolsPanel].
 */
object EmojiTooltipPlacement {

    /** Gap between the bubble and the cell it describes. */
    const val GAP_PX = 8

    /** Minimum distance the bubble keeps from the sides of its bounds. */
    const val EDGE_MARGIN_PX = 4

    /**
     * Result of [place]: where the bubble goes, and whether it ended up **below** the cell.
     *
     * [below] is returned rather than inferred because the caller passes it straight to
     * `showAsDropDown`'s offset convention, where "below" is a positive y offset and "above" is
     * negative — getting that sign wrong puts the tooltip on the wrong side of the screen.
     */
    data class Result(val x: Int, val y: Int, val below: Boolean)

    /**
     * Place a [width] × [height] bubble for the cell at ([anchorX], [anchorY]) with size
     * [anchorWidth] × [anchorHeight], inside the rectangle ([boundsLeft], [boundsTop]) to
     * ([boundsRight], [boundsBottom]).
     *
     * Preference order, straight from Gboard: **above** the cell, then **below** it, then —
     * if neither side fits — below anyway, clamped to the bounds, because a clipped bubble that
     * is visible beats no bubble at all.
     *
     * Horizontally the bubble is centred on the cell, then slid back inside the bounds. The
     * bounds win over the centring: a bubble that is visibly attached to its cell but one pixel
     * from the edge is worse than one nudged inward.
     */
    fun place(
        anchorX: Int,
        anchorY: Int,
        anchorWidth: Int,
        anchorHeight: Int,
        width: Int,
        height: Int,
        boundsLeft: Int,
        boundsTop: Int,
        boundsRight: Int,
        boundsBottom: Int,
    ): Result {
        // Horizontal: centre on the cell, then clamp. Clamp last so the edge always wins.
        val centredX = anchorX + (anchorWidth - width) / 2
        val maxX = boundsRight - width - EDGE_MARGIN_PX
        val minX = boundsLeft + EDGE_MARGIN_PX
        // A bubble wider than its bounds collapses to the left inset rather than inverting the
        // clamp (maxX < minX), which would push it off the right edge.
        val x = if (maxX < minX) minX else centredX.coerceIn(minX, maxX)

        // Vertical: above if it fits, otherwise below.
        val aboveY = anchorY - height - GAP_PX
        val fitsAbove = aboveY >= boundsTop + EDGE_MARGIN_PX
        if (fitsAbove) {
            return Result(x, aboveY, below = false)
        }

        val belowY = anchorY + anchorHeight + GAP_PX
        val maxY = boundsBottom - height - EDGE_MARGIN_PX
        val y = if (maxY < boundsTop + EDGE_MARGIN_PX) belowY else belowY.coerceAtMost(maxY)
        return Result(x, y, below = true)
    }

    /**
     * The y offset to hand `PopupWindow.showAsDropDown`, whose origin is the anchor's **bottom**
     * left corner and which draws downward for positive values.
     *
     * Kept next to [place] because it is the same fact expressed twice: [Result.below] says which
     * side, this converts the absolute y into the relative one the platform expects.
     */
    fun dropDownOffsetY(anchorY: Int, anchorHeight: Int, placedY: Int): Int =
        placedY - (anchorY + anchorHeight)
}
