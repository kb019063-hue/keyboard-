package tribixbite.cleverkeys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the floating-mode maths that a device cannot tell you is wrong until the window is already
 * stranded: the width clamps, the integer width conversion, and the travel-relative x so a
 * narrowed window recentres instead of drifting off an edge.
 */
class FloatingConfigTest {
    @Test
    fun `width percent clamps to the supported range`() {
        assertEquals(FloatingConfig.MIN_WIDTH_PERCENT, FloatingConfig.clampWidthPercent(10))
        assertEquals(FloatingConfig.MAX_WIDTH_PERCENT, FloatingConfig.clampWidthPercent(250))
        assertEquals(80, FloatingConfig.clampWidthPercent(80))
    }

    @Test
    fun `width pixels use integer maths on a 1080 screen`() {
        assertEquals(864, FloatingConfig.widthPixels(1080, 80))
        assertEquals(1080, FloatingConfig.widthPixels(1080, 100))
        assertEquals(540, FloatingConfig.widthPixels(1080, 50))
    }

    @Test
    fun `x fraction is relative to the travel so centring survives a width change`() {
        // 80% of 1080 leaves 216px of travel; centred is half of that.
        assertEquals(108, FloatingConfig.xPixels(1080, 864, 0.5f))
        assertEquals(0, FloatingConfig.xPixels(1080, 864, 0f))
        assertEquals(216, FloatingConfig.xPixels(1080, 864, 1f))
        // At full width there is no travel at all: every fraction is flush left, not off-screen.
        assertEquals(0, FloatingConfig.xPixels(1080, 1080, 0.5f))
    }

    @Test
    fun `y fraction places the top edge and cannot drag the window out of reach`() {
        assertEquals(1488, FloatingConfig.yPixels(2400, 0.62f))
        assertEquals((2400 * FloatingConfig.MAX_Y_FRACTION).toInt(), FloatingConfig.yPixels(2400, 5f))
        assertEquals(0, FloatingConfig.yPixels(2400, -1f))
    }

    @Test
    fun `window resolves the whole geometry in one call`() {
        val w = FloatingConfig.window(1080, 2400, 80, 0.5f, 0.62f)
        assertEquals(FloatingWindow(864, 108, 1488), w)
    }

    @Test
    fun `the y cap follows the window height, not just the screen`() {
        // 38% of a 2400px screen is the keyboard; the lowest top edge that keeps it on screen is
        // (2400 - 912) / 2400 = 0.62, not the screen-only 0.9 that left the bottom row 670px
        // below the display.
        assertEquals(0.62f, FloatingConfig.maxYFraction(2400, 912), 1e-6f)
        val bottomEdge = FloatingConfig.yPixels(2400, 0.9f, windowHeightPx = 912) + 912
        assertTrue("bottom edge ${bottomEdge}px on a 2400px screen", bottomEdge <= 2400)
        assertTrue(
            "window still sits low, not snapped to the top",
            FloatingConfig.yPixels(2400, 0.9f, windowHeightPx = 912) >= 1480
        )
        // A short keyboard can still be dragged low: 1200px leaves half the screen of travel.
        assertEquals(0.5f, FloatingConfig.maxYFraction(2400, 1200), 1e-6f)
        // Taller than the screen: the top edge goes to 0 rather than negative.
        assertEquals(0f, FloatingConfig.maxYFraction(2400, 3000), 1e-6f)
    }

    @Test
    fun `an unmeasured window keeps the old screen-only cap`() {
        // Before layout the caller has no height to measure; behaviour must be the pre-fix cap so
        // a first pass cannot park the window somewhere the next layout has to undo.
        assertEquals(FloatingConfig.MAX_Y_FRACTION, FloatingConfig.maxYFraction(2400, 0), 1e-6f)
        assertEquals(FloatingConfig.MAX_Y_FRACTION, FloatingConfig.maxYFraction(0, 912), 1e-6f)
        assertEquals(
            FloatingConfig.yPixels(2400, 5f),
            FloatingConfig.yPixels(2400, 5f, windowHeightPx = 0)
        )
    }

    @Test
    fun `the clamp used on drag matches the clamp used on layout`() {
        // Drag writes the stored fraction, layout reads it back: same helper, so a drag can never
        // bank a value the geometry would reject.
        val clamped = FloatingConfig.clampYFraction(0.95f, 2400, 912)
        assertEquals(0.62f, clamped, 1e-6f)
        assertEquals(clamped, FloatingConfig.window(1080, 2400, 80, 0.5f, clamped, 912).y / 2400f, 1e-3f)
        assertEquals(0f, FloatingConfig.clampYFraction(-2f, 2400, 912), 1e-6f)
    }
}
