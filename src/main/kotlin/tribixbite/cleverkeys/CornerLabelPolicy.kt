package tribixbite.cleverkeys

/**
 * Which corner labels a key draws (phase 4 part 2).
 *
 * The layout files this keyboard inherits print every symbol a key can produce, in up to four
 * corners: `latn_qwerty_us.xml` gives Q a digit in the north-east, a tilde in the north-west, a
 * backtick in the south-west AND a hidden `loc esc` in the south-east, and the extra-keys defaults
 * materialise most of those `loc` placeholders on top. That is Unexpected Keyboard's philosophy --
 * everything visible, everything reachable -- and on the owner's phone it read as chaos next to
 * Gboard, which prints one hint per key and hides the rest behind long-press.
 *
 * So the default here is calm: one hint per key that types something, and none at all on keys that
 * do something (shift, backspace, ctrl, fn, enter, the space bar's cursor arrows, the arrows key).
 * Nothing is removed -- every hidden symbol stays reachable exactly as before by long-press and
 * swipe, and the "print every symbol on keys" setting restores the dense rendering in one tap.
 *
 * This is a RENDERING decision only. The `KeySpec` still carries all nine slots, so hit-testing,
 * the accessibility tree and the subkey popover are untouched; only [Keyboard2View]'s draw loop
 * consults this, which is why the policy is pure Kotlin and the whole table is JVM-tested.
 *
 * Slot indices match the draw loop's `for (i in 1..8)` and the layout's corner names, in the order
 * [Pointers] lists them: centre, then nw, ne, sw, se, w, e, n, s.
 */
object CornerLabelPolicy {
    const val NORTH_WEST = 1
    const val NORTH_EAST = 2
    const val SOUTH_WEST = 3
    const val SOUTH_EAST = 4
    const val WEST = 5
    const val EAST = 6
    const val NORTH = 7
    const val SOUTH = 8

    /**
     * The one hint calm mode keeps on a character key, best first. North-east leads because that
     * is where the layouts put the digits on the top row -- the hint people actually look for --
     * and where most punctuation sits elsewhere; the southern corners hold the rarer and the
     * `loc`-materialised symbols, so they are the first to go quiet.
     */
    private val PRIORITY = intArrayOf(
        NORTH_EAST, NORTH_WEST, SOUTH_EAST, SOUTH_WEST, NORTH, SOUTH, EAST, WEST
    )

    /**
     * Whether a key's centre label types something, as opposed to doing something. Compose-pending
     * and the Hangul jamo count as characters: they put a glyph in the field, which is what a
     * corner hint is explaining.
     */
    fun isCharacterKind(kind: KeyValue.Kind?): Boolean =
        kind == KeyValue.Kind.Char ||
            kind == KeyValue.Kind.Compose_pending ||
            kind == KeyValue.Kind.Hangul_initial ||
            kind == KeyValue.Kind.Hangul_medial

    /**
     * The set of corner slots to draw, as a bit mask over the slot indices above.
     *
     * @param printAll the "print every symbol on keys" preference. True is the pre-phase-4
     *   rendering, unchanged, bit for bit.
     * @param mainIsCharacter [isCharacterKind] of the key's centre label.
     * @param present bit [i] set when the key carries a label in slot [i].
     */
    fun drawn(printAll: Boolean, mainIsCharacter: Boolean, present: Int): Int {
        if (printAll) return present
        if (!mainIsCharacter) return 0
        for (slot in PRIORITY) {
            if (present and (1 shl slot) != 0) return 1 shl slot
        }
        return 0
    }
}
