package tribixbite.cleverkeys.gif

/**
 * Pure geometry for the GIF picker grid.
 *
 * Extracted from [GifGridManager] on 2026-11-21 (`gif`/look pass) because both bugs it closes were
 * invisible in a screenshot and impossible to assert against a view class in a JVM test.
 *
 * Why a separate object at all: the grid used to hard-code its cell height as `dpToPx(80)` while
 * only the COLUMN COUNT is user-configurable (`gif_thumbnail_columns`, allowed 2..5, default 3).
 * On a 392dp-wide pane that produced a 194x80dp cell at two columns and a 78x80dp cell at five - a
 * 2.4x swing in aspect ratio, so the same thumbnail was cropped through its sides at two columns
 * and was nearly square at five. A cell whose side derives from the width keeps one shape at every
 * column count, which is what the packs' colour coding and the Coil `Scale.FILL` request assume.
 */
internal object GifGridGeometry {

    /** Smallest cell side, in dp. A five-column pane on a fold's cover screen must stay legible. */
    const val MIN_CELL_DP = 56

    /**
     * Side of one square grid cell, in pixels.
     *
     * @param paneWidthPx    width of the RecyclerView, paddings included
     * @param horizontalPadPx total horizontal padding of the RecyclerView (both sides)
     * @param columns        user-configured column count, already clamped to `2..5`
     * @param density        the VIEW's density, never `Resources.getSystem()`
     */
    fun cellSizePx(
        paneWidthPx: Int,
        horizontalPadPx: Int,
        columns: Int,
        density: Float
    ): Int {
        val safeColumns = columns.coerceAtLeast(1)
        val usable = paneWidthPx - horizontalPadPx
        val floorPx = (MIN_CELL_DP * density).toInt()
        // A zero width means the first layout pass has not happened; the caller falls back to the
        // screen width rather than dividing by nothing, so this must not silently return the floor.
        if (usable <= 0) return floorPx
        return (usable / safeColumns).coerceAtLeast(floorPx)
    }
}
