package tribixbite.cleverkeys

/**
 * What the empty suggestion row shows while it keeps its space (2026-11-13).
 *
 * The owner's report: *"It still moves when typing. Moving up and down when I start typing."*
 * The cause is the row above the keys: it was sized to zero while it had nothing to show and
 * opened the moment words arrived, so the keys — and the app behind them — moved once per field,
 * at the first letter. The owner chose, by name, that the row keeps its space all the time
 * ("always keep the row's space"), which removes that movement entirely: the row's height never
 * changes, so nothing above or below it ever re-lays-out because the keyboard grew.
 *
 * A row that keeps its space and then shows nothing is the empty purple band the owner already
 * complained about on 2026-10-23 ([SuggestionStripPolicy]). So it must *earn* the space with
 * something readable. What it shows, in order:
 *
 *  1. **Nothing at all** — when the field asks for no suggestions (password fields, which also
 *     must never show a learned word), or when word prediction and swipe typing are both off.
 *     Those keyboards are built without the row.
 *  2. **The last word** — Gboard's own idiom for an empty strip: the word before the caret,
 *     shown in the dimmed sub-label colour, tappable to put the cursor back at the end of it.
 *     Not claimed: whether the Gboard the owner compares against does this; it is a decision
 *     taken here so the row is never a blank band, and it is listed in `docs/OWNER-DECISIONS.md`.
 *  3. **"Swipe to type"** — the hint this keyboard already shows in an empty suggestion bar
 *     ([EMPTY_HINT]). Kept as the fallback for a field with no text before the caret.
 *
 * Pure Kotlin, like [SuggestionStripPolicy]: the view applies it, this object decides it, and
 * the decision is unit-tested off-device.
 */
object SuggestionStripBandPolicy {

    /** What the row shows when it has no suggestions and no message of its own. */
    sealed class Placeholder {
        /** The row is not drawn at all (password field, predictions off). */
        object None : Placeholder()

        /** The last word before the caret, dimmed; tapping it returns the caret to its end. */
        data class LastWord(val word: String) : Placeholder()

        /** `Swipe to type`, for a field with nothing before the caret. */
        object Hint : Placeholder()
    }

    /** The longest "last word" the placeholder will show; longer text is a pasted paragraph, not a word. */
    const val MAX_WORD_CHARS = 24

    /**
     * The placeholder for the current field.
     *
     * @param passwordField the strip is in password mode, where no learned word may be shown.
     * @param textBeforeCursor the text between the start of the field (or a line break / space)
     *   and the caret, already read by the caller. Null when the app gave us nothing, which is
     *   every app that does not report its caret.
     */
    fun placeholder(passwordField: Boolean, textBeforeCursor: CharSequence?): Placeholder {
        if (passwordField) return Placeholder.None
        val word = lastWord(textBeforeCursor) ?: return Placeholder.Hint
        return Placeholder.LastWord(word)
    }

    /**
     * The word being typed, taken from the tail of [textBeforeCursor]: everything after the last
     * space or line break, with surrounding whitespace and punctuation removed from its edges.
     * Null when there is nothing word-like, or when it is too long to be a word.
     */
    fun lastWord(textBeforeCursor: CharSequence?): String? {
        if (textBeforeCursor.isNullOrEmpty()) return null
        // A caret just after "word " or "word, " still means "word": trailing separators and
        // punctuation belong to nothing, so they are dropped before the walk back.
        val text = textBeforeCursor.toString().trimEnd { !it.isLetterOrDigit() }
        if (text.isEmpty()) return null
        // Walk back to the separator before the word that ends at the (trimmed) caret.
        var start = text.length
        while (start > 0 && !isBoundary(text[start - 1])) start--
        val word = text.substring(start).trim { !it.isLetterOrDigit() }
        if (word.isEmpty() || word.length > MAX_WORD_CHARS) return null
        return word
    }

    private fun isBoundary(c: Char): Boolean = c.isWhitespace()
}
