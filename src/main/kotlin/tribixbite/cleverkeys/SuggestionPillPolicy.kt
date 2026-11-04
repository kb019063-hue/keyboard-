package tribixbite.cleverkeys

/**
 * How the strip's "commit" suggestion is drawn (2026-11-04).
 *
 * Gboard does not merely embolden its most likely word: it draws it on a rounded pill filled
 * with the theme's **return-key accent** (the same blue as the enter key) and sets the word in
 * white on top. This keyboard tinted the text instead, so on the Gboard themes — where the
 * borrowed highlight colour is the same ink as everything else — the "highlight" was invisible:
 * the owner's screenshot shows four identical grey words.
 *
 * Pure Kotlin so the decision is testable off-device: the view code asks this object for a fill
 * and a text colour and paints what it gets.
 */
object SuggestionPillPolicy {

    /** A pill must differ from the bar behind it by at least this much (WCAG contrast) to be
     *  drawn at all. 1.2 is "visible at a glance"; anything less reads as a smudge. */
    const val MIN_FILL_CONTRAST = 1.2

    /** Text on the pill must reach this contrast against the fill (WCAG 1.4.11 for large text). */
    const val MIN_TEXT_CONTRAST = 3.0

    /**
     * What to paint for the highlighted suggestion.
     *
     * @param accent the theme's return-key accent fill (0 = the theme has none),
     * @param barBackground the colour actually behind the strip (0 = unknown),
     * @param fallbackText the theme's own highlight colour, used when no pill can be drawn,
     * @param barText the theme's normal suggestion ink, used when the pill would be unreadable.
     * @return [Look.drawPill] false means "bold text only", exactly the old behaviour.
     */
    data class Look(val drawPill: Boolean, val fill: Int, val text: Int)

    fun look(accent: Int, barBackground: Int, fallbackText: Int, barText: Int): Look {
        // No accent, or no idea what it sits on: keep the old bold-only highlight rather than
        // guess a colour that might be invisible.
        if (accent == 0 || barBackground == 0) return Look(false, 0, fallbackText)
        if (SuggestionBarPalette.contrastRatio(accent, barBackground) < MIN_FILL_CONTRAST) {
            return Look(false, 0, fallbackText)
        }
        val white = 0xFFFFFFFF.toInt()
        val black = 0xFF000000.toInt()
        val onPill = if (SuggestionBarPalette.contrastRatio(white, accent) >=
            SuggestionBarPalette.contrastRatio(black, accent)
        ) {
            white
        } else {
            black
        }
        // A white-on-blue pill is what Gboard shows; if the accent is pale enough that neither
        // ink reaches the threshold, fall back to the bar's own text colour rather than ship an
        // unreadable pill.
        val readable = SuggestionBarPalette.contrastRatio(onPill, accent) >= MIN_TEXT_CONTRAST
        return if (readable) Look(true, accent, onPill) else Look(true, accent, barText)
    }
}
