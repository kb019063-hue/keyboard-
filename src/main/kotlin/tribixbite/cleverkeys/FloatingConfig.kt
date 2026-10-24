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
 * right and 0.5 centred at any width; the y fraction places the window's top edge, capped at
 * [MAX_Y_FRACTION] so the keyboard can never be dragged until only its grip shows.
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

    fun xPixels(screenWidth: Int, widthPixels: Int, xFraction: Float): Int =
        ((screenWidth - widthPixels) * clampXFraction(xFraction)).toInt()

    fun yPixels(screenHeight: Int, yFraction: Float): Int =
        (screenHeight * clampYFraction(yFraction)).toInt()

    /** The whole window geometry in one call, so callers cannot apply half of it. */
    fun window(
        screenWidth: Int,
        screenHeight: Int,
        widthPercent: Int,
        xFraction: Float,
        yFraction: Float
    ): FloatingWindow {
        val w = widthPixels(screenWidth, widthPercent)
        return FloatingWindow(w, xPixels(screenWidth, w, xFraction), yPixels(screenHeight, yFraction))
    }
}

/** A resolved floating window geometry, in pixels, ready for the window manager. */
data class FloatingWindow(val widthPx: Int, val x: Int, val y: Int)
