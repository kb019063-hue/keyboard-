package tribixbite.cleverkeys

/**
 * Which toolbar buttons survive while the suggestion strip is occupied (phase 4 part 7).
 *
 * The shared header keeps the two END buttons and puts a flexible spacer between them.
 * Deciding that from command names was wrong: a user
 * who removed `more_tools` or `voice_typing` from a custom toolbar lost every button as
 * soon as suggestions appeared. Positions, not names, are the contract.
 *
 * Pure Kotlin: the view owns Android layout params, this object owns the arithmetic.
 */
object ToolbarCompactPolicy {

    /** Whether button [index] of [count] is visible. */
    fun buttonVisible(index: Int, count: Int, compact: Boolean): Boolean {
        if (index !in 0 until count) return false
        return !compact || count <= 1 || index == 0 || index == count - 1
    }

    /**
     * Weight of one button. Normal mode divides the row evenly; compact mode pins both
     * ends to their intrinsic width so the spacer — not the buttons — owns the middle.
     * A one-button toolbar is pinned to the start in compact mode.
     */
    fun buttonWeight(count: Int, compact: Boolean): Float =
        if (compact && count > 0) 0f else 1f

    /** A spacer holds the middle for suggestions, including with a single end button. */
    fun spacerVisible(count: Int, compact: Boolean): Boolean = compact && count > 0
}
