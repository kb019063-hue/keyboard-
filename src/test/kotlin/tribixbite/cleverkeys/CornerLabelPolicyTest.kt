package tribixbite.cleverkeys

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the calm-default corner-label table (phase 4 part 2).
 *
 * The rows that matter are the ones the owner's screenshot showed: Q carries a digit, a tilde, a
 * backtick and a materialised `loc esc`, and calm mode must keep exactly the digit; shift carries
 * home/end/tab/capslock and calm mode must keep none of them, because shift does something rather
 * than typing something. And the "print everything" preference must return the present mask bit
 * for bit, so the toggle is a faithful way back to the rendering people already learned.
 */
class CornerLabelPolicyTest {

    private fun bits(vararg slots: Int): Int = slots.fold(0) { acc, s -> acc or (1 shl s) }

    @Test
    fun `print everything returns the present mask unchanged`() {
        val present = bits(1, 2, 3, 4, 7)
        assertEquals(present, CornerLabelPolicy.drawn(true, mainIsCharacter = true, present = present))
        assertEquals(present, CornerLabelPolicy.drawn(true, mainIsCharacter = false, present = present))
    }

    @Test
    fun `calm mode keeps the digit corner on a letter key`() {
        // Q as latn_qwerty_us.xml defines it: ne=1, nw=~, sw=`, se=loc esc.
        val present = bits(
            CornerLabelPolicy.NORTH_EAST,
            CornerLabelPolicy.NORTH_WEST,
            CornerLabelPolicy.SOUTH_WEST,
            CornerLabelPolicy.SOUTH_EAST
        )
        assertEquals(
            1 shl CornerLabelPolicy.NORTH_EAST,
            CornerLabelPolicy.drawn(false, mainIsCharacter = true, present = present)
        )
    }

    @Test
    fun `calm mode keeps nothing on a key that does something`() {
        // shift as laid out: nw=home, ne=end, se=loc tab, sw=loc capslock.
        val present = bits(
            CornerLabelPolicy.NORTH_WEST,
            CornerLabelPolicy.NORTH_EAST,
            CornerLabelPolicy.SOUTH_EAST,
            CornerLabelPolicy.SOUTH_WEST
        )
        assertEquals(0, CornerLabelPolicy.drawn(false, mainIsCharacter = false, present = present))
    }

    @Test
    fun `calm mode falls through the priority order when the favourite corner is empty`() {
        assertEquals(
            1 shl CornerLabelPolicy.NORTH_WEST,
            CornerLabelPolicy.drawn(
                false,
                mainIsCharacter = true,
                present = bits(CornerLabelPolicy.NORTH_WEST, CornerLabelPolicy.SOUTH_WEST)
            )
        )
        assertEquals(
            1 shl CornerLabelPolicy.SOUTH_EAST,
            CornerLabelPolicy.drawn(false, mainIsCharacter = true, present = bits(CornerLabelPolicy.SOUTH_EAST))
        )
        assertEquals(
            1 shl CornerLabelPolicy.WEST,
            CornerLabelPolicy.drawn(
                false,
                mainIsCharacter = true,
                present = bits(CornerLabelPolicy.WEST, CornerLabelPolicy.SOUTH)
            )
        )
    }

    @Test
    fun `a key with no corner labels draws none in either mode`() {
        assertEquals(0, CornerLabelPolicy.drawn(false, mainIsCharacter = true, present = 0))
        assertEquals(0, CornerLabelPolicy.drawn(true, mainIsCharacter = true, present = 0))
    }

    @Test
    fun `only the kinds that put a glyph in the field count as characters`() {
        assertEquals(true, CornerLabelPolicy.isCharacterKind(KeyValue.Kind.Char))
        assertEquals(true, CornerLabelPolicy.isCharacterKind(KeyValue.Kind.Compose_pending))
        assertEquals(true, CornerLabelPolicy.isCharacterKind(KeyValue.Kind.Hangul_initial))
        assertEquals(false, CornerLabelPolicy.isCharacterKind(KeyValue.Kind.Event))
        assertEquals(false, CornerLabelPolicy.isCharacterKind(KeyValue.Kind.Modifier))
        assertEquals(false, CornerLabelPolicy.isCharacterKind(KeyValue.Kind.Editing))
        assertEquals(false, CornerLabelPolicy.isCharacterKind(null))
    }
}
