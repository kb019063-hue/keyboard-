package tribixbite.cleverkeys.popover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tribixbite.cleverkeys.customization.SwipeDirection

/** [SubkeyPopoverGeometry]: neutral zone, sector hit-testing, clamping, arming. */
class SubkeyPopoverGeometryTest {

    // Cells 100 × 150 (a key taller than wide); neutral 60 % × 60 % = ±30 × ±45 px.
    private fun slot(dx: Float, dy: Float, nw: Float = 0.6f, nh: Float = 0.6f) =
        SubkeyPopoverGeometry.slotAt(dx, dy, 100f, 150f, nw, nh)

    @Test
    fun insideTheNeutralRectangle_selectsNothing() {
        assertNull(slot(0f, 0f))
        assertNull(slot(29f, 44f))
        assertNull(slot(-29f, -44f))
    }

    @Test
    fun theNeutralZoneIsARectangle_notACircle() {
        // 40 px right is outside the ±30 width even though 40 < 45 (the height half-extent).
        assertEquals(SwipeDirection.E, slot(40f, 0f))
        // 40 px down stays neutral: inside ±45.
        assertNull(slot(0f, 40f))
    }

    @Test
    fun aWiderNeutralSetting_widensTheCancelArea() {
        assertEquals(SwipeDirection.E, slot(40f, 0f, nw = 0.6f))
        assertNull(slot(40f, 0f, nw = 1.0f))
    }

    @Test
    fun cellCentres_selectTheirOwnDirection() {
        for (dir in SwipeDirection.entries) {
            val dx = SubkeyPopoverGeometry.cellColumn(dir) * 100f
            val dy = SubkeyPopoverGeometry.cellRow(dir) * 150f
            assertEquals("centre of the $dir cell", dir, slot(dx, dy))
        }
    }

    @Test
    fun sectorsFollowTheGridDiagonals_forTallKeys() {
        // Half a cell right, one cell up: raw px angle 71.6° would be N; normalised by the
        // 100 × 150 cell it is 63.4°, inside NE's 22.5°–67.5° sector — the drawn NE cell.
        assertEquals(SwipeDirection.NE, slot(50f, -150f))
    }

    @Test
    fun screenYGrowsDown() {
        assertEquals(SwipeDirection.S, slot(0f, 150f))
        assertEquals(SwipeDirection.N, slot(0f, -150f))
    }

    @Test
    fun gridCentre_staysOnTheFinger_whenTheGridFits() {
        assertEquals(500f, SubkeyPopoverGeometry.gridCentre(500f, 100f, 1000f))
    }

    @Test
    fun gridCentre_clampsAtBothEdges() {
        assertEquals(150f, SubkeyPopoverGeometry.gridCentre(40f, 100f, 1000f))
        assertEquals(850f, SubkeyPopoverGeometry.gridCentre(990f, 100f, 1000f))
    }

    @Test
    fun gridCentre_centresOnAViewSmallerThanTheGrid() {
        assertEquals(125f, SubkeyPopoverGeometry.gridCentre(10f, 100f, 250f))
    }

    @Test
    fun arming_needsMovementPastAFractionOfTheSmallerCellSide() {
        // 15 % of min(100, 150) = 15 px.
        assertFalse(SubkeyPopoverGeometry.isArmed(0f, 0f, 10f, 10f, 100f, 150f))  // 14.1 px
        assertTrue(SubkeyPopoverGeometry.isArmed(0f, 0f, 12f, 12f, 100f, 150f))   // 17.0 px
    }

    // ---------------------------------------------------------------------
    // Selection vs. the cell DRAWN under the finger (audit 2026-11-15, P1/P2).
    // ---------------------------------------------------------------------

    /**
     * A finger inside a cell's drawn square, but off its centre, can select a NEIGHBOUR.
     *
     * Measured on a 100 × 150 px cell at the shipped 60 % neutral default: 20.7 px inside the
     * drawn N cell (41 % of the way to its centre) the selection is NE, not N. That is inherent
     * to the design — the slot is a 45° SECTOR from the grid centre while the cell is a SQUARE
     * on screen, and a wedge cannot contain a square. Worst penetration per cell is 41 % for the
     * four cardinals (their square's corners stick out furthest) and 17 % for the diagonals.
     *
     * Pinned, not fixed: closing it means changing the rule from "sector" to "nearest drawn
     * cell", which changes the selection of every swipe in the corners. The module's own doc
     * claims "what the user sees under the finger is what gets selected", so this test is the
     * counter-example that has to be resolved before that sentence can stand — see
     * docs/BUG-HUNT-2026-11-15-PART-17.md (P1).
     */
    @Test
    fun aFingerInsideTheDrawnNCellsLeftSide_selectsItsNeighbour() {
        // Inside the drawn N cell (|dx| < 50, |dy + 150| < 75) but 20 px left of its centre.
        assertEquals(SwipeDirection.NW, slot(-55f, -150f))
        assertEquals(SwipeDirection.NW, slot(-45f, -150f))
        // Comfortably off-centre but still N: the boundary sits at 30 px for this cell shape.
        assertEquals(SwipeDirection.N, slot(-35f, -150f))
        assertEquals(SwipeDirection.N, slot(0f, -150f))
    }

    @Test
    fun everyCellsCentreStillSelectsItself() {
        // The property the sector rule DOES guarantee, and the reason P1 is a corner-case
        // finding rather than a broken control: each cell's centre and a generous core around
        // it are unambiguous. Guards against a future "fix" that moves the boundaries inward.
        for (dir in SwipeDirection.entries) {
            val dx = SubkeyPopoverGeometry.cellColumn(dir) * 100f
            val dy = SubkeyPopoverGeometry.cellRow(dir) * 150f
            assertEquals("centre of $dir", dir, slot(dx, dy))
            // 1/4 of a cell off-centre, on both axes, is still the same cell.
            assertEquals("$dir, quarter-cell off-centre", dir, slot(dx * 0.75f, dy * 0.75f))
        }
    }

    @Test
    fun theNeutralZoneIsTheSameFractionOfEachAxis_regardlessOfCellAspect() {
        // The neutral zone is a RECTANGLE in px but a SQUARE in cell units (both halves come
        // from the cell size), so the cancel region has the same shape relative to the cells on
        // a wide key as on a tall one. Pinned because making the two axes independent is the
        // obvious-looking "fix" that would make a release inside a drawn cell cancel.
        val tall = SubkeyPopoverGeometry.slotAt(0f, -100f, 100f, 150f, 0.6f, 0.6f)
        val wide = SubkeyPopoverGeometry.slotAt(0f, -100f, 150f, 100f, 0.6f, 0.6f)
        // 100 px up is 0.67 cells on the tall key (outside ±0.30) and 1.0 cells on the wide one.
        assertEquals(SwipeDirection.N, tall)
        assertEquals(SwipeDirection.N, wide)
        // The cancel half-extent in cells is 0.30 either way.
        assertNull(SubkeyPopoverGeometry.slotAt(0f, -45f, 100f, 150f, 0.6f, 0.6f))   // 0.30 cells
        assertNull(SubkeyPopoverGeometry.slotAt(0f, -30f, 150f, 100f, 0.6f, 0.6f))   // 0.30 cells
    }

    @Test
    fun aNeutralWidthLargerThanTheCell_swallowsTheHorizontalSlots_reportedNotFixed() {
        // Both sliders accept 20 %…150 % (Config.SUBKEY_POPOVER_NEUTRAL_MAX). At the top of that
        // range the cancel region is 0.75 cells wide, which reaches past the inner edge of the
        // drawn W/E cells: 50 px right of centre is inside the drawn E cell and cancels.
        //
        // This is a live interaction between two independent settings, not a boundary artefact,
        // and it is REPORTED rather than fixed (P2): clamping the neutral zone to what the grid
        // can spare would silently move a slider the user set. Pinned so the range and the
        // consequence stay tied together.
        assertNull(SubkeyPopoverGeometry.slotAt(50f, 0f, 100f, 150f, 1.5f, 0.6f))
        assertEquals(SwipeDirection.E, SubkeyPopoverGeometry.slotAt(50f, 0f, 100f, 150f, 0.6f, 0.6f))
        // Hitting exactly one maximum is enough; the smaller axis does not rescue the larger.
        assertNull(SubkeyPopoverGeometry.slotAt(0f, 110f, 100f, 150f, 0.6f, 1.5f))
        assertEquals(SwipeDirection.S, SubkeyPopoverGeometry.slotAt(0f, 110f, 100f, 150f, 0.6f, 0.6f))
    }

    @Test
    fun degenerateCells_selectNothing() {
        assertNull(SubkeyPopoverGeometry.slotAt(50f, 50f, 0f, 150f, 0.6f, 0.6f))
    }
}
