package tribixbite.cleverkeys

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins when the suggestion strip earns its band (phase 4 part 2).
 *
 * The regression this file guards is the owner's screenshot: an empty purple band between the
 * toolbar and the keys, because the strip reserved its space whether or not it had anything to
 * show. So the empty row must collapse, each of the three things that fill the strip must expand
 * it on its own, and the always-visible preference must restore the reserved band exactly.
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
        assertEquals(0, SuggestionStripPolicy.heightPx(occupied = false, alwaysVisible = false, stripHeightPx = 0, heldForField = true))
    }

    // ── 2026-11-12: the band stops following every word ───────────────────────────────────────
    // Each change of the band's height changes the keyboard's height, and the platform makes the
    // app behind the keyboard lay itself out again to match. While words are typed, suggestions
    // keep arriving and being cleared; on the CI emulator the band moved five times in one
    // six-word sentence (run 43142217574). These cases replay that kind of sequence.

    private val strip = 110

    /** One band evaluation, the way the service makes it. */
    private fun StripBandLatch.band(occupied: Boolean, suggesting: Boolean = occupied): Int =
        heightPx(occupied, suggesting, alwaysVisible = false, stripHeightPx = strip)

    /** A fresh latch in a new, empty field, as onStartInputView leaves it. */
    private fun emptyField(): StripBandLatch = StripBandLatch().apply {
        onNewField()
        onCaret(0, 0, suggesting = false)
    }

    /**
     * Types [words] one letter at a time into [latch], starting with the caret at [caret], and
     * returns every height the band took, without repeats. A letter brings suggestions, the
     * space after a word clears them: the per-word cycle. [reportFirst] chooses which of the two
     * events a keystroke causes arrives first -- the app's caret report or the suggestions --
     * because the keyboard does not control that order.
     */
    private fun type(latch: StripBandLatch, words: List<String>, caret: Int = 0, reportFirst: Boolean): List<Int> {
        val seen = mutableListOf(latch.band(occupied = false))
        var pos = caret
        fun note(h: Int) { if (seen.last() != h) seen.add(h) }
        fun key(suggestingAfter: Boolean, suggestingBefore: Boolean) {
            pos++
            if (reportFirst) {
                if (latch.onCaret(pos, pos, suggestingBefore)) note(latch.band(suggestingBefore))
                note(latch.band(suggestingAfter))
            } else {
                note(latch.band(suggestingAfter))
                if (latch.onCaret(pos, pos, suggestingAfter)) note(latch.band(suggestingAfter))
            }
        }
        var showing = false
        for (w in words) {
            for (c in w) { key(suggestingAfter = true, suggestingBefore = showing); showing = true }
            key(suggestingAfter = false, suggestingBefore = showing); showing = false
        }
        return seen
    }

    @Test
    fun `a field at rest still shows no empty band`() {
        // The owner's screenshot stays fixed: nothing typed, nothing reserved.
        val latch = emptyField()
        assertEquals(0, latch.band(occupied = false))
        assertEquals(false, latch.held)
    }

    @Test
    fun `typing a sentence moves the band once, whichever event arrives first`() {
        val sentence = listOf("a", "sad", "lad", "had", "a", "salad")
        for (reportFirst in listOf(true, false)) {
            assertEquals(
                "reportFirst=$reportFirst: closed at rest, opened by the first letter, never closed again",
                listOf(0, strip),
                type(emptyField(), sentence, reportFirst = reportFirst)
            )
        }
    }

    @Test
    fun `the old rule, for comparison, moved it on every word`() {
        // The same per-word cycle through the bare policy: what the app behind was put through.
        val seen = mutableListOf<Int>()
        for (o in listOf(false, true, false, true, true, true, false, true, true, true, false)) {
            val h = SuggestionStripPolicy.heightPx(o, alwaysVisible = false, stripHeightPx = strip)
            if (seen.lastOrNull() != h) seen.add(h)
        }
        assertEquals(listOf(0, strip, 0, strip, 0, strip, 0), seen)
    }

    @Test
    fun `sending the message gives the band back`() {
        // The app empties its field. The suggestions of the last word may still be on the strip
        // at that moment, and are cleared a little later; neither order may leave a band behind.
        val latch = emptyField()
        type(latch, listOf("see", "you"), reportFirst = true)
        assertEquals(true, latch.held)

        // caret report first, with the old suggestions still showing ...
        assertEquals("the hold ends the moment the field is empty", true, latch.onCaret(0, 0, suggesting = true))
        assertEquals("still open: there is something on the strip", strip, latch.band(occupied = true))
        // ... then they are cleared
        assertEquals(0, latch.band(occupied = false))
        assertEquals(false, latch.held)
    }

    @Test
    fun `an app that empties its field by restarting input gives the band back too`() {
        // setText("") restarts input instead of reporting a caret move; onStartInputView passes
        // EditorInfo.initialSelStart/End through the same door, WITHOUT onNewField (restarting).
        val latch = emptyField()
        type(latch, listOf("ok"), reportFirst = false)
        latch.onCaret(0, 0, suggesting = false)
        assertEquals(0, latch.band(occupied = false))
    }

    @Test
    fun `the next message holds the band again`() {
        val latch = emptyField()
        type(latch, listOf("one"), reportFirst = true)
        latch.onCaret(0, 0, suggesting = false)
        assertEquals(0, latch.band(occupied = false))
        assertEquals(listOf(0, strip), type(latch, listOf("two", "three"), reportFirst = false))
    }

    @Test
    fun `text that arrives without suggestions does not open an empty band`() {
        // A paste, or a caret tapped into the middle of existing text: the caret leaves 0, but
        // nothing has been suggested, so there is nothing to hold a band open for.
        val latch = emptyField()
        assertEquals(false, latch.onCaret(20, 20, suggesting = false))
        assertEquals(0, latch.band(occupied = false))
    }

    @Test
    fun `a selection that starts at 0 is not an empty field`() {
        val latch = emptyField()
        type(latch, listOf("word"), reportFirst = true)
        assertEquals("select-all must not let the band go", false, latch.onCaret(0, 5, suggesting = false))
        assertEquals(strip, latch.band(occupied = false))
    }

    @Test
    fun `a message in a field nobody typed in closes its band behind it`() {
        // "Added to dictionary", the incognito notice, dictation status, an autofill chip: one
        // event, not a per-word cycle, and an empty band left behind it is the owner's
        // original complaint.
        val latch = emptyField()
        assertEquals(strip, latch.band(occupied = true, suggesting = false))
        assertEquals(false, latch.held)
        assertEquals(0, latch.band(occupied = false))
    }

    @Test
    fun `a message shown while typing leaves the band where typing put it`() {
        val latch = emptyField()
        type(latch, listOf("hi"), reportFirst = true)
        // The message replaces the suggestions, then expires and leaves the strip empty.
        assertEquals(strip, latch.band(occupied = true, suggesting = false))
        assertEquals(strip, latch.band(occupied = false))
    }

    @Test
    fun `a new field has to earn the band again`() {
        val latch = emptyField()
        type(latch, listOf("hi"), reportFirst = true)
        assertEquals(strip, latch.band(occupied = false))
        // A draft reopened with its caret at the end: text before the caret, nothing suggested.
        latch.onNewField()
        latch.onCaret(12, 12, suggesting = false)
        assertEquals(0, latch.band(occupied = false))
    }

    @Test
    fun `an app that never reports its caret keeps the old behaviour`() {
        // initialSelStart says 0 and no report ever corrects it: the band just follows
        // occupancy, as it did before. Nothing gets worse for such an app.
        val latch = emptyField()
        assertEquals(strip, latch.band(occupied = true))
        assertEquals(0, latch.band(occupied = false))
        assertEquals(strip, latch.band(occupied = true))
        assertEquals(0, latch.band(occupied = false))
    }

    @Test
    fun `an ordinary keystroke asks for no resize`() {
        // onCaret's answer is what keeps the typing path free of layout work.
        val latch = emptyField()
        type(latch, listOf("abc"), reportFirst = true)
        assertEquals(false, latch.onCaret(5, 5, suggesting = true))
        assertEquals(false, latch.onCaret(6, 6, suggesting = false))
    }

    @Test
    fun `a keyboard without a strip is never latched open`() {
        val latch = StripBandLatch()
        latch.onCaret(3, 3, suggesting = false)
        assertEquals(0, latch.heightPx(occupied = true, suggesting = true, alwaysVisible = false, stripHeightPx = 0))
        assertEquals(false, latch.held)
    }

    @Test
    fun `the service feeds the latch from the three places that know`() {
        val src = java.io.File("src/main/kotlin/tribixbite/cleverkeys/CleverKeysService.kt").readText()
        fun body(signature: String): String {
            val start = src.indexOf(signature)
            assertEquals("$signature not found", true, start >= 0)
            val end = src.indexOf("\n    override fun ", start + 10).let { if (it < 0) src.length else it }
            return src.substring(start, end)
        }
        val startView = body("override fun onStartInputView(info: EditorInfo, restarting: Boolean)")
        // `restarting` means the SAME field; plenty of editors restart input while being typed
        // in, and resetting there would bring the per-word resize straight back.
        assertEquals(true, startView.contains("if (!restarting) _stripBand.onNewField()"))
        assertEquals(
            "a restart that empties the field must still be able to let the band go",
            true,
            startView.contains("_stripBand.onCaret(info.initialSelStart, info.initialSelEnd,")
        )
        val selection = body("override fun onUpdateSelection(")
        assertEquals(true, selection.contains("if (_stripBand.onCaret(newSelStart, newSelEnd,"))
        // 2026-11-13: the row keeps its full height all the time (the owner's choice, to stop
        // the keyboard moving while typing), so sizing it no longer consults the latch. What the
        // latch still decides is which fields count as typed in; that is asserted above.
        assertEquals(
            "suggestionStripHeight is the row's one height now",
            true,
            src.contains("fun suggestionStripHeight(fullHeightPx: Int): Int = if (fullHeightPx > 0) fullHeightPx else 0")
        )
    }
}
