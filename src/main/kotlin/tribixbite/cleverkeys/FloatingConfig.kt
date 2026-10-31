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
 * [maxYFraction] at the lowest value that still leaves the whole window on screen, so the keyboard
 * can never be dragged until only its grip shows.
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
     * [MAX_Y_FRACTION] alone is a fraction of the screen, not of the space the keyboard leaves
     * over, so on its own it lets a 38 %-tall keyboard sit with its bottom row (space, period,
     * enter) below the display: `y = 0.9 * 2400` puts the bottom edge at 3070 px on a 2400 px
     * screen. This caps by the window's own measured height instead, which is what the grip's
     * documented promise ("can never be dragged until only its grip shows") actually means.
     *
     * A [windowHeightPx] of 0 (before the first layout pass) keeps the old cap, so a caller that
     * has not measured yet can never park the window somewhere layout would have to undo.
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
