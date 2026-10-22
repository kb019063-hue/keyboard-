package tribixbite.cleverkeys

/**
 * Preference keys, defaults and maths for split-keyboard mode (phase 3 part 4).
 *
 * A split keyboard is two halves with a gap between them, for thumb typing on a wide screen. The
 * gap cannot simply be inserted: every row's keys sum to the full content width, so pushing the
 * right half over by the gap would run it off the edge. The halves therefore also shrink, by
 * [keyWidthScale], so that left half + gap + right half fills exactly the width the unsplit
 * keyboard filled. [KeyboardGeometry] applies both halves of that bargain in one place, which is
 * why drawing, tap hit-testing and accessibility cannot disagree about where a key is.
 *
 * Pure Kotlin, no `android.*`, JVM-tested -- the same arrangement as [OneHandedConfig] and
 * [FloatingConfig].
 */
object SplitConfig {
    const val PREF_ENABLED = "split_enabled"
    const val PREF_GAP_PERCENT = "split_gap_percent"

    const val DEFAULT_ENABLED = false
    /** 10% of the content width: unmistakably split, still two usable halves. */
    const val DEFAULT_GAP_PERCENT = 10
    const val MIN_GAP_PERCENT = 4
    const val MAX_GAP_PERCENT = 25

    fun clampGapPercent(percent: Int): Int = percent.coerceIn(MIN_GAP_PERCENT, MAX_GAP_PERCENT)

    /** Integer maths, like the floating and one-handed widths: float truncation bites. */
    fun gapPixels(contentWidth: Int, percent: Int): Int =
        contentWidth * clampGapPercent(percent) / 100

    /** The key-width multiplier that makes halves plus gap fill the content width exactly. */
    fun keyWidthScale(contentWidth: Int, gapPixels: Int): Float =
        if (contentWidth <= 0 || gapPixels <= 0) 1f
        else (contentWidth - gapPixels).toFloat() / contentWidth.toFloat()

    /**
     * Whether a key belongs to the right half: its left edge, in row-width units, has reached the
     * row's midpoint. Decided in units rather than pixels so the answer is independent of the
     * scale the gap compensation applies.
     */
    fun isRightHalf(rowTotalUnits: Float, keyLeftUnits: Float): Boolean =
        rowTotalUnits > 0f && keyLeftUnits >= rowTotalUnits / 2f
}
