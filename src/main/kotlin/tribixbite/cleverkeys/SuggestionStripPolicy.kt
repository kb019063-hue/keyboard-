package tribixbite.cleverkeys

/**
 * Whether the suggestion strip occupies its band, and how tall that band is (phase 4 part 2).
 *
 * Suggestions, temporary messages and inline autofill chips occupy the strip. The band is
 * reserved by default even when none is present: collapsing it as words appear and disappear
 * resizes the IME and can move the editor or keys while typing. Users can explicitly opt into
 * the compact, occupancy-driven behaviour in settings.
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
