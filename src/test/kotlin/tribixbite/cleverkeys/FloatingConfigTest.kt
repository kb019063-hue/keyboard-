package tribixbite.cleverkeys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    // ─────────────────────────────────────────────── the resize knob (2026-11-11, hunt part 2)

    /** Feed [events] deltas of [dxPx] through the step the service uses, as one gesture. */
    private fun drag(startPercent: Int, dxPx: Int, events: Int, screenWidth: Int = 1080): ResizeStep {
        var step = ResizeStep(startPercent, 0)
        repeat(events) {
            step = FloatingConfig.resizeStep(step.widthPercent, step.carry, dxPx, screenWidth)
        }
        return step
    }

    @Test
    fun `a slow knob drag still resizes`() {
        // 4 px per event on a 1080 px screen. Each event alone is 0.37 % and used to round to
        // nothing, so at an ordinary drag speed the knob was dead. 27 events are 108 px: 10 %.
        assertEquals(ResizeStep(70, 0), drag(80, dxPx = -4, events = 27))
        // The per-event arithmetic this replaces, for the record: it never left 80.
        var old = 80
        repeat(27) { old = FloatingConfig.clampWidthPercent(old + -4 * 100 / 1080) }
        assertEquals(80, old)
    }

    @Test
    fun `the width follows the distance, however the drag is chopped into events`() {
        // 216 px to the right from 60 %: one flick, 216 one-pixel events, 72 three-pixel events.
        val flick = FloatingConfig.resizeStep(60, 0, 216, 1080)
        assertEquals(ResizeStep(80, 0), flick)
        assertEquals(flick, drag(60, dxPx = 1, events = 216))
        assertEquals(flick, drag(60, dxPx = 3, events = 72))
    }

    @Test
    fun `pixels short of a percent are carried, and a reversal spends them first`() {
        // 5 px is less than one percent of 1080: no step yet, but nothing is lost either.
        val out = FloatingConfig.resizeStep(80, 0, 5, 1080)
        assertEquals(ResizeStep(80, 500), out)
        // Coming straight back is no movement at all.
        assertEquals(ResizeStep(80, 0), FloatingConfig.resizeStep(out.widthPercent, out.carry, -5, 1080))
        // Going on is: 5 + 6 px crosses the 10.8 px that one percent takes.
        assertEquals(ResizeStep(81, 20), FloatingConfig.resizeStep(out.widthPercent, out.carry, 6, 1080))
    }

    @Test
    fun `dragging past either end is not banked`() {
        // 300 px to the left from 52 %: only 2 % of it can be used.
        val floor = FloatingConfig.resizeStep(52, 0, -300, 1080)
        assertEquals(ResizeStep(FloatingConfig.MIN_WIDTH_PERCENT, 0), floor)
        // Turning round widens at once: 11 px is a percent, with nothing to pay back first.
        assertEquals(51, FloatingConfig.resizeStep(floor.widthPercent, floor.carry, 11, 1080).widthPercent)
        // Leaning on the end keeps the carry at zero however long it lasts.
        assertEquals(ResizeStep(FloatingConfig.MIN_WIDTH_PERCENT, 0), drag(50, dxPx = -3, events = 400))

        val ceiling = FloatingConfig.resizeStep(99, 0, 400, 1080)
        assertEquals(ResizeStep(FloatingConfig.MAX_WIDTH_PERCENT, 0), ceiling)
        assertEquals(ResizeStep(FloatingConfig.MAX_WIDTH_PERCENT, 0), drag(100, dxPx = 7, events = 400))
        assertEquals(99, FloatingConfig.resizeStep(ceiling.widthPercent, ceiling.carry, -11, 1080).widthPercent)
    }

    @Test
    fun `an unmeasured screen or an out-of-range stored width cannot produce a bad width`() {
        assertEquals(ResizeStep(80, 0), FloatingConfig.resizeStep(80, 37, 50, 0))
        assertEquals(ResizeStep(80, 0), FloatingConfig.resizeStep(80, 37, 50, -1))
        // A stored 20 % (hand-edited backup) is brought into range before the drag applies.
        assertEquals(FloatingConfig.MIN_WIDTH_PERCENT, FloatingConfig.resizeStep(20, 0, 0, 1080).widthPercent)
        assertEquals(FloatingConfig.MAX_WIDTH_PERCENT, FloatingConfig.resizeStep(400, 0, 0, 1080).widthPercent)
        // One absurd delta cannot overflow its way to a bad width.
        assertEquals(
            FloatingConfig.MAX_WIDTH_PERCENT,
            FloatingConfig.resizeStep(80, 0, Int.MAX_VALUE, 1080).widthPercent
        )
        assertEquals(
            FloatingConfig.MIN_WIDTH_PERCENT,
            FloatingConfig.resizeStep(80, 0, Int.MIN_VALUE, 1080).widthPercent
        )
    }

    /**
     * The defect was in the service, not in this object: `resizeFloating` did the division itself.
     * A source fact, because the service cannot be constructed off-device.
     */
    @Test
    fun `the service resizes through the carrying step`() {
        val service = java.io.File("src/main/kotlin/tribixbite/cleverkeys/CleverKeysService.kt")
        assertTrue("CleverKeysService.kt must exist (run from the project root)", service.isFile)
        val text = service.readText()
        val body = text.substringAfter("private fun resizeFloating(dx: Int) {").substringBefore("\n    }\n")
        assertTrue("resizeFloating must go through FloatingConfig.resizeStep", body.contains("FloatingConfig.resizeStep("))
        assertTrue("the carry must be fed back in", body.contains("_floatingResizeCarry"))
        assertFalse("no per-event integer division in the service", body.contains("* 100 /"))
        // A new gesture must not inherit the last one's leftover pixels.
        val release = text.substringAfter("private fun persistFloatingWidth() {").substringBefore("\n    }\n")
        assertTrue("the carry is reset when the knob is released", release.contains("_floatingResizeCarry = 0"))
    }
}
