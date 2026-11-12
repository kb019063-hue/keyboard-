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
     * @param heldForField the strip has already shown something in this field, so the band
     *   stays until the next field -- see [StripBandLatch].
     */
    fun heightPx(
        occupied: Boolean,
        alwaysVisible: Boolean,
        stripHeightPx: Int,
        heldForField: Boolean = false
    ): Int {
        if (stripHeightPx <= 0) return 0
        return if (occupied || alwaysVisible || heldForField) stripHeightPx else 0
    }
}

/**
 * While a field is being typed in, the strip's band stays open; an empty field gives it back.
 *
 * The band used to follow occupancy moment by moment, and while words are being typed occupancy
 * keeps changing: suggestions arrive, a commit clears them, the next word brings them back. On
 * the CI emulator the band changed height five times while one six-word sentence was typed (run
 * 43142217574, `resize=5`). Each change is a change in the height of the keyboard, and a
 * keyboard's height is not private: the platform tells the app behind it, and the app lays
 * itself out again -- its whole message list, if it is a chat -- on the same UI thread that is
 * trying to show the letter just typed. The toolbar above the band jumps with it. That is work
 * the keyboard causes in somebody else's process, which is why passes that looked only at this
 * process did not find it; how much it costs on a phone depends on the app and has not been
 * measured.
 *
 * The rule now, in the order it is decided:
 *  1. The band opens when the strip has something to show. Unchanged.
 *  2. Once WORD SUGGESTIONS have shown while there is text before the caret, the band stays open
 *     through the moments the strip is empty. Only suggestions count: they are the one kind of
 *     content that comes and goes with every word. A message ("Added to dictionary") or an
 *     autofill chip in a field nobody has typed in is a single event, and its band closes behind
 *     it exactly as before.
 *  3. The band is let go when input starts in a different field, and when the caret is back at
 *     position 0 with nothing selected: the field has been emptied (a message was sent, the text
 *     was deleted) or the caret stands at its very start. Both are moments of rest, and the
 *     first is one in which the app is rearranging itself anyway.
 *
 * So what the owner asked for on 2026-10-23 is kept: a field at rest shows no empty band. The
 * caret position costs nothing to know -- the app reports it after every edit
 * (`onUpdateSelection`) and at the start of input (`EditorInfo.initialSelStart`); no call is
 * made to the app for it. An app that never reports its caret simply keeps the old behaviour.
 * Anyone who wants no movement at all has the always-visible switch.
 */
class StripBandLatch {
    /** Word suggestions have shown, with text before the caret, since the band was last let go. */
    private var seen = false

    /** The caret is at position 0 with nothing selected: an empty field, or as good as. */
    private var atStart = false

    /** True while the band is kept open whether or not the strip has anything in it. */
    val held: Boolean get() = seen && !atStart

    /** Input started in a different field: the band has to be earned again. */
    fun onNewField() {
        seen = false
        atStart = false
    }

    /**
     * The caret moved, or input (re)started with the caret here.
     *
     * @param suggesting word suggestions are showing right now. They may have arrived before
     *   this report did (the first letter of a field does both, in either order), and must count
     *   whichever came first.
     * @return true when [held] changed, i.e. the band has to be sized again. False on every
     *   ordinary keystroke, so the typing path pays two comparisons for this.
     */
    fun onCaret(selStart: Int, selEnd: Int, suggesting: Boolean): Boolean {
        val before = held
        atStart = selStart == 0 && selEnd == 0
        seen = !atStart && (seen || suggesting)
        return held != before
    }

    /**
     * The band's height now.
     *
     * @param occupied the strip is showing anything at all (suggestions, a message, a chip)
     * @param suggesting the strip is showing word suggestions
     */
    fun heightPx(occupied: Boolean, suggesting: Boolean, alwaysVisible: Boolean, stripHeightPx: Int): Int {
        // Not while the caret is at the start: what shows then is left over from text that is
        // gone (the suggestions of a message just sent), and must not hold the band open.
        if (suggesting && stripHeightPx > 0 && !atStart) seen = true
        return SuggestionStripPolicy.heightPx(occupied, alwaysVisible, stripHeightPx, held)
    }
}
