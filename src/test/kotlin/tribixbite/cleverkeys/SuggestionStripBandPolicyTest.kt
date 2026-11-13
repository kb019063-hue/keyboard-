package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins what fills the suggestion row while it keeps its height (2026-11-13).
 *
 * The owner's report: *"It still moves when typing. Moving up and down when I start typing."*
 * The row used to be sized to nothing and opened when words arrived, so the keys moved once per
 * field. The owner chose that it keeps its space all the time, which removes the movement; a row
 * that keeps its space and then shows nothing is the blank band they complained about in October,
 * so the row has to say something. These cases pin what it says, and — most importantly — that it
 * says nothing at all in a password field.
 */
class SuggestionStripBandPolicyTest {

    @Test
    fun `an empty field gets the swipe hint`() {
        assertEquals(
            SuggestionStripBandPolicy.Placeholder.Hint,
            SuggestionStripBandPolicy.placeholder(passwordField = false, textBeforeCursor = "")
        )
        assertEquals(
            SuggestionStripBandPolicy.Placeholder.Hint,
            SuggestionStripBandPolicy.placeholder(passwordField = false, textBeforeCursor = null)
        )
    }

    @Test
    fun `a word being typed is echoed back, dimmed`() {
        assertEquals(
            SuggestionStripBandPolicy.Placeholder.LastWord("call"),
            SuggestionStripBandPolicy.placeholder(passwordField = false, textBeforeCursor = "Yoo, call")
        )
        assertEquals(
            SuggestionStripBandPolicy.Placeholder.LastWord("hello"),
            SuggestionStripBandPolicy.placeholder(passwordField = false, textBeforeCursor = "hello")
        )
    }

    @Test
    fun `the echo is the word at the caret, not the paragraph`() {
        assertEquals("line", SuggestionStripBandPolicy.lastWord("first line\nsecond line"))
        assertEquals("word", SuggestionStripBandPolicy.lastWord("some word "))
        assertEquals("word", SuggestionStripBandPolicy.lastWord("  word"))
        assertEquals("dont", SuggestionStripBandPolicy.lastWord("well dont"))
    }

    @Test
    fun `trailing punctuation is not part of the word`() {
        assertEquals("hello", SuggestionStripBandPolicy.lastWord("hello, "))
        assertEquals("end", SuggestionStripBandPolicy.lastWord("the end."))
        assertNull(SuggestionStripBandPolicy.lastWord("   "))
        assertNull(SuggestionStripBandPolicy.lastWord(null))
    }

    @Test
    fun `a pasted paragraph is not echoed as one enormous word`() {
        val long = "a".repeat(SuggestionStripBandPolicy.MAX_WORD_CHARS + 1)
        assertNull(SuggestionStripBandPolicy.lastWord(long))
        assertEquals(
            SuggestionStripBandPolicy.Placeholder.Hint,
            SuggestionStripBandPolicy.placeholder(passwordField = false, textBeforeCursor = long)
        )
    }

    @Test
    fun `a password field is never echoed`() {
        // The rule the whole password mode rests on: no learned or field text on the strip.
        assertEquals(
            SuggestionStripBandPolicy.Placeholder.None,
            SuggestionStripBandPolicy.placeholder(passwordField = true, textBeforeCursor = "hunter2")
        )
        assertEquals(
            SuggestionStripBandPolicy.Placeholder.None,
            SuggestionStripBandPolicy.placeholder(passwordField = true, textBeforeCursor = null)
        )
    }

    /** The resource the hint string names must exist, or the row would show a raw id. */
    @Test
    fun `the hint and the clipboard strings exist in the shipped resources`() {
        val strings = java.io.File("res/values/strings.xml").readText()
        for (name in listOf(
            "suggestion_strip_hint_swipe",
            "clipboard_back",
            "clipboard_action_paste",
            "clipboard_action_pin",
            "clipboard_action_unpin",
            "clipboard_action_edit",
            "clipboard_action_expand",
            "clipboard_action_delete",
            "clipboard_action_copy",
        )) {
            assertWithMessage("res/values/strings.xml must define $name")
                .that(strings.contains("name=\"$name\"")).isTrue()
        }
    }
}
