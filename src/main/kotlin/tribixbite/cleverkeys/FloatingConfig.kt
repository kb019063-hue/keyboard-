package tribixbite.cleverkeys

/**
 * Preference keys, defaults and pixel maths for floating-keyboard mode (phase 3 part 3).
 *
 * Pure Kotlin on purpose, the same arrangement as [OneHandedConfig]: no `android.*` import, so
 * every clamp and every fraction-to-pixel conversion is JVM-testable without a device, and the
 * view/service code that cannot be tested offline holds no arithmetic of its own.
 *
 * Position is stored as FRACTIONS of the screen, not pixels, for two reasons: a persisted pixel
 * offset from a portrait session would strand the window off a landscape screen, and a width
 * change must never push the window past an edge. The x fraction is a fraction of the window's
 * available horizontal TRAVEL (screen width minus window width), so 0 is flush left, 1 flush
 * right and 0.5 centred at any width; the y fraction places the window's top edge, capped by
 * [maxYFraction] at the lowest value that still leaves the whole window on screen.
 *
 * (That cap used to be described as "the keyboard can never be dragged until only its grip shows",
 * which was written when the grip was across the top and the degenerate case was the content
 * sliding off above it. The grip moved to the bottom on 2026-11-15 — see [FloatingRoot] — so the
 * analogous case is the grip sliding off below. The cap was always about the whole window staying
 * on screen, so it forbids both without changing: only this sentence was ever grip-specific.)
 */
object FloatingConfig {
    const val PREF_ENABLED = "floating_enabled"
    const val PREF_WIDTH_PERCENT = "floating_width_percent"
    const val PREF_X_FRACTION = "floating_x_fraction"
    const val PREF_Y_FRACTION = "floating_y_fraction"

    const val DEFAULT_ENABLED = false
    /** 80%: clearly smaller than the full keyboard, still wide enough to type on. */
    const val DEFAULT_WIDTH_PERCENT = 80
    const val MIN_WIDTH_PERCENT = 50
    const val MAX_WIDTH_PERCENT = 100
    const val DEFAULT_X_FRACTION = 0.5f
    /** 0.62: the window's top edge a little below mid-screen, i.e. a keyboard that floats. */
    const val DEFAULT_Y_FRACTION = 0.62f
    const val MAX_Y_FRACTION = 0.9f

    fun clampWidthPercent(percent: Int): Int = percent.coerceIn(MIN_WIDTH_PERCENT, MAX_WIDTH_PERCENT)

    /** Integer maths, like [OneHandedConfig.gapWidth]: float truncation surprised us in phase 2. */
    fun widthPixels(screenWidth: Int, percent: Int): Int =
        screenWidth * clampWidthPercent(percent) / 100

    fun clampXFraction(f: Float): Float = f.coerceIn(0f, 1f)

    fun clampYFraction(f: Float): Float = f.coerceIn(0f, MAX_Y_FRACTION)

    /**
     * The largest top-edge fraction that still leaves the WHOLE window on screen.
     *
     * [MAX_Y_FRACTION] alone caps by a flat fraction of the height and so knows nothing about how
     * tall the window is, which on its own lets a tall keyboard sit with its bottom row (space,
     * period, enter) below the display. This caps by the window's own measured height instead,
     * which is what the promise "the whole window stays on screen" actually means.
     *
     * ## What `screenHeight` really is (corrected 2026-11-15, part 19)
     *
     * The caller passes `resources.displayMetrics.heightPixels`, and the window is positioned with
     * `Gravity.TOP` in the input method's own area — so on a device with a navigation bar this is
     * the IME's AVAILABLE height, not the physical screen. Measured in CI run 45307511686 on a
     * 1080x2400 device at density 2.625: an 80%-wide window is 972px tall, the layout resolves to
     * `(2274 - 972) / 2274 = 0.5726`, and `0.5726 * 2274 = 1302` matches the frame top `dumpsys`
     * reported to the pixel. The 126px difference from 2400 is a 48dp navigation bar.
     *
     * The previous version of this comment worked its example in physical-screen pixels
     * (`y = 0.9 * 2400` puts the bottom edge at 3070px on a 2400px screen). The arithmetic was
     * fine and the units were not: 0.9 is unreachable in either space, because the cap for any
     * window taller than 38% of this height is below 0.62 — the shipped default. See PART-19's
     * P8-P10; the practical consequence is that the window cannot be dragged *down* at all.
     *
     * A [windowHeightPx] of 0 (before the first layout pass) keeps the old cap, so a caller that
     * has not measured yet can never park the window somewhere layout would have to undo.
     *
     * [windowHeightPx] must be the WHOLE window, grip included — it is measured from the wrapper
     * root, so it already is. That matters more since the grip moved below the content: the grip
     * is now the part that would be last to leave the screen.
     */
    fun maxYFraction(screenHeight: Int, windowHeightPx: Int): Float {
        if (screenHeight <= 0 || windowHeightPx <= 0) return MAX_Y_FRACTION
        val travel = (screenHeight - windowHeightPx).toFloat() / screenHeight.toFloat()
        return travel.coerceIn(0f, MAX_Y_FRACTION)
    }

    /**
     * Top-edge fraction clamped so [windowHeightPx] fits below it. Shared by the geometry the
     * window manager is given and by the drag handler that writes the stored fraction, so a drag
     * cannot bank a value the next layout would have to reject.
     */
    fun clampYFraction(f: Float, screenHeight: Int, windowHeightPx: Int): Float =
        f.coerceIn(0f, maxYFraction(screenHeight, windowHeightPx))

    fun xPixels(screenWidth: Int, widthPixels: Int, xFraction: Float): Int =
        ((screenWidth - widthPixels) * clampXFraction(xFraction)).toInt()

    fun yPixels(screenHeight: Int, yFraction: Float, windowHeightPx: Int = 0): Int {
        val max = maxYFraction(screenHeight, windowHeightPx)
        return (screenHeight * yFraction.coerceIn(0f, max)).toInt()
    }

    /**
     * One event of a width drag: the knob moved [dxPx] since it last reported.
     *
     * A touch stream reports a few pixels per event, and one percent of a 1080 px screen is
     * 10.8 px. Converting each event on its own (`dx * 100 / screenWidth`, in integers) rounds
     * every event shorter than that to zero and throws its pixels away, so at an ordinary drag
     * speed the knob did nothing at all; only a flick fast enough to cover a whole percent
     * between two events moved the width, and then by less than the finger had travelled.
     * The pixels that have not yet added up to a percent are carried to the next event instead.
     *
     * [carry] is `pixels * 100` (hundredths of a percent-pixel), which keeps the arithmetic exact
     * in integers at any screen width. A gesture starts with 0 and feeds each result's
     * [ResizeStep.carry] into the next call, so the width depends on how far the finger moved
     * and not on how the system chopped that movement into events.
     *
     * A carry that points past the end the width is resting on is dropped, not banked: dragging
     * 300 px beyond the minimum must not have to be paid back before the keyboard widens again.
     */
    fun resizeStep(widthPercent: Int, carry: Int, dxPx: Int, screenWidth: Int): ResizeStep {
        val current = clampWidthPercent(widthPercent)
        if (screenWidth <= 0) return ResizeStep(current, 0)
        val total = carry.toLong() + dxPx.toLong() * 100L
        // Truncates toward zero, so `steps` and `rest` both carry the sign of `total`.
        val steps = total / screenWidth
        val rest = (total - steps * screenWidth).toInt()
        val next = clampWidthPercent((current + steps).coerceIn(0L, 1000L).toInt())
        val keep = when {
            next <= MIN_WIDTH_PERCENT && rest < 0 -> 0
            next >= MAX_WIDTH_PERCENT && rest > 0 -> 0
            else -> rest
        }
        return ResizeStep(next, keep)
    }

    /** The whole window geometry in one call, so callers cannot apply half of it. */
    fun window(
        screenWidth: Int,
        screenHeight: Int,
        widthPercent: Int,
        xFraction: Float,
        yFraction: Float,
        windowHeightPx: Int = 0
    ): FloatingWindow {
        val w = widthPixels(screenWidth, widthPercent)
        return FloatingWindow(
            w,
            xPixels(screenWidth, w, xFraction),
            yPixels(screenHeight, yFraction, windowHeightPx)
        )
    }
}

/** A resolved floating window geometry, in pixels, ready for the window manager. */
data class FloatingWindow(val widthPx: Int, val x: Int, val y: Int)

/** Where one width-drag event leaves the keyboard; see [FloatingConfig.resizeStep]. */
data class ResizeStep(val widthPercent: Int, val carry: Int)
