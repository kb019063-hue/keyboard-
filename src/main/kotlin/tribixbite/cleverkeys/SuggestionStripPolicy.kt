package tribixbite.cleverkeys

/**
 * Whether the suggestion strip occupies its band, and how tall that band is (phase 4 part 2).
 *
 * On the owner's phone the keyboard showed a ~40dp purple band between the toolbar and the keys
 * with nothing in it: the strip is hardcoded always-visible, so an empty field on an empty
 * predictor reserves the space and reads as a broken row rather than a suggestion bar. Gboard
 * earns its strip by filling it; an empty one should give the band back to the keys.
 *
 * Three things make the strip worth its space: suggestions to tap, a temporary message (the IME
 * cannot toast on Android 13+, so "Added to dictionary" and dictation status live here), and an
 * inline autofill chip. Anything else and the band collapses to zero until one of them arrives.
 * The `always visible` preference restores the reserved band for anyone who prefers a keyboard
 * whose height never moves.
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
