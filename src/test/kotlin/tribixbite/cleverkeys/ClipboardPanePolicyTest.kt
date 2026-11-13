package tribixbite.cleverkeys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the clipboard pane's height and what it leaves of the keyboard (2026-11-13).
 *
 * The owner's report: *"The clipboard: it's arrangements, order, I can't even go back to normal
 * keyboard typing because there's no back to press. Also… the keyboard is still active there (the
 * letter keyboard is covering off the clipboard)."* The pane was 30 % of the screen with the
 * letter keyboard underneath it, and its close control was a small down-arrow inside the search
 * row. These cases pin the two decisions that fix it — the pane takes the keyboard's whole height,
 * and only the clipboard does that.
 */
class ClipboardPanePolicyTest {

    @Test
    fun `the clipboard takes the keyboard's own height`() {
        // A 1080x2280 phone, keyboard ~1000 px tall: the pane is 1000 px, not 30 % of 2280 (684).
        assertEquals(1000, ClipboardPanePolicy.paneHeightPx(keyboardHeightPx = 1000, screenHeightPx = 2280))
    }

    @Test
    fun `a very short keyboard still gives a list worth scrolling`() {
        // Split screen or a short docked keyboard: never less than a third of the screen.
        assertEquals(
            (2280 * ClipboardPanePolicy.MINIMUM_SCREEN_FRACTION).toInt(),
            ClipboardPanePolicy.paneHeightPx(keyboardHeightPx = 300, screenHeightPx = 2280)
        )
    }

    @Test
    fun `no keyboard measured yet means no pane height to claim`() {
        assertEquals(0, ClipboardPanePolicy.paneHeightPx(keyboardHeightPx = 0, screenHeightPx = 2280))
        assertEquals(0, ClipboardPanePolicy.paneHeightPx(keyboardHeightPx = -5, screenHeightPx = 2280))
    }

    @Test
    fun `only the clipboard hides the keys`() {
        assertFalse(ClipboardPanePolicy.keysDrawnFor(ClipboardPanePolicy.PaneKind.CLIPBOARD))
        for (other in ClipboardPanePolicy.PaneKind.values().filter {
            it != ClipboardPanePolicy.PaneKind.CLIPBOARD
        }) {
            assertTrue("$other must keep the keys visible", ClipboardPanePolicy.keysDrawnFor(other))
        }
    }

    @Test
    fun `the toolbar stays usable above the clipboard`() {
        // Settings, emoji and the microphone must stay one tap away while the list is open.
        assertTrue(ClipboardPanePolicy.toolbarUsableFor(ClipboardPanePolicy.PaneKind.CLIPBOARD))
    }

    @Test
    fun `a tap pastes`() {
        assertTrue(ClipboardPanePolicy.TAP_PASTES)
    }
}
