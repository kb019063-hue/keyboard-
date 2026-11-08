package tribixbite.cleverkeys

/**
 * Whether the suggestion strip occupies its band, and how tall that band is (phase 4 part 2).
 *
 * Suggestions, temporary messages and inline autofill chips occupy the strip. The shared
 * toolbar provides the typing row when it is enabled; when it is disabled the strip is reserved
 * by default to avoid resizing the IME. The setting can opt into collapsing that empty strip.
 *
 * Pure Kotlin, like [CornerLabelPolicy]: the view code that cannot be tested off-device holds no
 * decision of its own, only the two numbers this table produces.
 */
object SuggestionStripPolicy {

    fun occupied(hasSuggestions: Boolean, hasMessage: Boolean, hasInlineView: Boolean): Boolean =
        hasSuggestions || hasMessage || hasInlineView

    /**
     * The band's height in pixels for the current occupancy.
     *
     * @param stripHeightPx the height the hierarchy was built with; 0 means "no strip at all",
     *   which is what a predictions-off keyboard has, and stays what it has.
     */
    fun heightPx(occupied: Boolean, alwaysVisible: Boolean, stripHeightPx: Int): Int {
        if (stripHeightPx <= 0) return 0
        return if (occupied || alwaysVisible) stripHeightPx else 0
    }
}
