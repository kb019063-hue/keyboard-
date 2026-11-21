package tribixbite.cleverkeys.gif

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Guards the two visual defects fixed in the 2026-11-21 `gif`/look pass.
 *
 * Both were the kind that no assertion had ever described: the grid's cell height was a literal
 * 80 dp while the column count was configurable, and the dp→px conversion used the SYSTEM density
 * instead of the pane's own. Neither produced an exception and neither is visible in a screenshot
 * at the default configuration.
 */
class GifGridGeometryTest {

    // 1080x2400 phone at 2.75 density; the grid's own padding is 2dp each side.
    private val density = 2.75f
    private val panePx = 1080
    private val gridPadPx = 10

    @Test
    fun `cell is square at every supported column count`() {
        // The user-configurable range (Config.GIF_THUMBNAIL_COLUMNS). Before the fix the height
        // was pinned at 80dp, so the shape swung from 2.42:1 at two columns to 0.97:1 at five.
        for (columns in 2..5) {
            val side = GifGridGeometry.cellSizePx(panePx, gridPadPx, columns, density)
            val dp = side / density
            // Square by construction: width == height, so there is only one number to check.
            assertThat(dp).isAtLeast(GifGridGeometry.MIN_CELL_DP.toFloat())
            // A row must never overflow the pane, or the last column is clipped by the adapter.
            assertThat(side * columns).isAtMost(panePx - gridPadPx)
        }
    }

    @Test
    fun `more columns means smaller cells and the row still fits`() {
        val widths = (2..5).map { GifGridGeometry.cellSizePx(panePx, gridPadPx, it, density) }
        // Strictly decreasing: each extra column divides the same usable width further.
        assertThat(widths).isInOrder()
        assertThat(widths.toSet()).hasSize(widths.size)
        // Two columns on this pane: half of 1070px usable is 535px.
        assertThat(widths.first()).isEqualTo((panePx - gridPadPx) / 2)
    }

    @Test
    fun `the 56dp floor only fires on panes too narrow for the column count`() {
        // A 320dp fold cover screen at 5 columns: 880px / 5 = 176px, still above the 154px floor.
        val narrowPx = (320 * density).toInt()
        assertThat(GifGridGeometry.cellSizePx(narrowPx, gridPadPx, 5, density))
            .isEqualTo((narrowPx - gridPadPx) / 5)

        // Force the floor: a pane far narrower than any real phone at five columns.
        val floorPx = (GifGridGeometry.MIN_CELL_DP * density).toInt()
        assertThat(GifGridGeometry.cellSizePx(120, 0, 5, density)).isEqualTo(floorPx)
    }

    @Test
    fun `density comes from the caller so an overridden display does not resize the grid`() {
        // The bug: GifGridView used Resources.getSystem().displayMetrics.density while
        // GifGroupButtonsBar used context.resources. Under a display override the two disagreed and
        // tiles and category buttons scaled differently in the same pane. A pure function has only
        // the density it is handed, so the two call sites cannot diverge here.
        val at1x = GifGridGeometry.cellSizePx(panePx, gridPadPx, 3, 1.0f)
        val at2x = GifGridGeometry.cellSizePx(panePx, gridPadPx, 3, 2.0f)
        // Same pixel pane, higher density: the cells are the same PIXELS (the width drives them),
        // but the floor is measured in dp and therefore doubles.
        assertThat(at2x).isEqualTo(at1x)
        assertThat(GifGridGeometry.cellSizePx(90, 0, 5, 1.0f))
            .isEqualTo(GifGridGeometry.MIN_CELL_DP)
        assertThat(GifGridGeometry.cellSizePx(90, 0, 5, 2.0f))
            .isEqualTo(GifGridGeometry.MIN_CELL_DP * 2)
    }

    @Test
    fun `a not-yet-laid-out pane returns the floor instead of dividing by zero`() {
        // onCreateViewHolder can be asked for a cell before the first layout pass reports a width.
        // The old code had no such branch; a zero width produced a zero-height cell and the grid
        // appeared empty.
        val floorPx = (GifGridGeometry.MIN_CELL_DP * density).toInt()
        assertThat(GifGridGeometry.cellSizePx(0, 0, 3, density)).isEqualTo(floorPx)
        assertThat(GifGridGeometry.cellSizePx(10, 10, 3, density)).isEqualTo(floorPx)
        // Never zero, never negative - a negative side would throw in LayoutParams.
        assertThat(GifGridGeometry.cellSizePx(-5, 0, 3, density)).isEqualTo(floorPx)
    }

    @Test
    fun `a zero column count cannot divide by zero`() {
        // Config clamps gif_thumbnail_columns to 2..5, but safeGetInt can hand back a stale 0 from a
        // hand-edited prefs file, and that must not crash the picker.
        assertThat(GifGridGeometry.cellSizePx(panePx, gridPadPx, 0, density))
            .isEqualTo(panePx - gridPadPx)
    }
}
