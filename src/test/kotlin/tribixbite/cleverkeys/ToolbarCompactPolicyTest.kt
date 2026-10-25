package tribixbite.cleverkeys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins compact-toolbar layout before a custom toolbar can empty the whole row. */
class ToolbarCompactPolicyTest {

    @Test
    fun normalModeShowsEveryButtonAndSharesTheWidth() {
        for (count in 0..7) {
            for (index in 0 until count) {
                assertTrue(
                    "button $index of $count must be visible in normal mode",
                    ToolbarCompactPolicy.buttonVisible(index, count, compact = false)
                )
            }
            assertEquals(1f, ToolbarCompactPolicy.buttonWeight(count, compact = false), 0f)
            assertFalse(ToolbarCompactPolicy.spacerVisible(count, compact = false))
        }
    }

    @Test
    fun compactModeKeepsFirstAndLastRegardlessOfCommandNames() {
        val count = 7
        val visible = (0 until count).map {
            ToolbarCompactPolicy.buttonVisible(it, count, compact = true)
        }
        assertEquals(listOf(true, false, false, false, false, false, true), visible)
        assertEquals(0f, ToolbarCompactPolicy.buttonWeight(count, compact = true), 0f)
        assertTrue(ToolbarCompactPolicy.spacerVisible(count, compact = true))
    }

    @Test
    fun compactModeHandlesTinyCustomToolbars() {
        assertTrue(ToolbarCompactPolicy.buttonVisible(0, 0, compact = true).not())
        assertTrue(ToolbarCompactPolicy.buttonVisible(0, 1, compact = true))
        assertEquals(1f, ToolbarCompactPolicy.buttonWeight(1, compact = true), 0f)
        assertFalse(ToolbarCompactPolicy.spacerVisible(1, compact = true))
        assertTrue(ToolbarCompactPolicy.buttonVisible(0, 2, compact = true))
        assertTrue(ToolbarCompactPolicy.buttonVisible(1, 2, compact = true))
        assertTrue(ToolbarCompactPolicy.spacerVisible(2, compact = true))
    }
}
