package tribixbite.cleverkeys

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins when the suggestion strip earns its band (phase 4 part 2).
 *
 * The stable default reserves the row while suggestions change. The optional compact mode still
 * collapses it, and each of the three kinds of content expands it when that mode is selected.
 */
class SuggestionStripPolicyTest {

    @Test
    fun `an empty strip is not occupied`() {
        assertEquals(
            false,
            SuggestionStripPolicy.occupied(hasSuggestions = false, hasMessage = false, hasInlineView = false)
        )
    }

    @Test
    fun `each of the three contents occupies the strip on its own`() {
        assertEquals(true, SuggestionStripPolicy.occupied(true, false, false))
        assertEquals(true, SuggestionStripPolicy.occupied(false, true, false))
        assertEquals(true, SuggestionStripPolicy.occupied(false, false, true))
    }

    @Test
    fun `the band collapses only when empty and not pinned visible`() {
        assertEquals(0, SuggestionStripPolicy.heightPx(occupied = false, alwaysVisible = false, stripHeightPx = 110))
        assertEquals(110, SuggestionStripPolicy.heightPx(occupied = true, alwaysVisible = false, stripHeightPx = 110))
        assertEquals(110, SuggestionStripPolicy.heightPx(occupied = false, alwaysVisible = true, stripHeightPx = 110))
        assertEquals(110, SuggestionStripPolicy.heightPx(occupied = true, alwaysVisible = true, stripHeightPx = 110))
    }

    @Test
    fun `a keyboard built without a strip stays without one`() {
        // Predictions and swipe typing both off: there is no topPane at all, and no preference
        // may conjure a band for a bar that does not exist.
        assertEquals(0, SuggestionStripPolicy.heightPx(occupied = false, alwaysVisible = true, stripHeightPx = 0))
        assertEquals(0, SuggestionStripPolicy.heightPx(occupied = true, alwaysVisible = true, stripHeightPx = 0))
    }
}
