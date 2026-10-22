package tribixbite.cleverkeys

import org.junit.Assert.assertEquals
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
}
