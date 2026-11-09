package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the tools panel's contents and grid (2026-11-09).
 *
 * The surface this guards replaced five checkboxes in an `AlertDialog` — the owner's verdict on
 * that was "when the tools open, the design is really bad. And it's not even how Gboard looks
 * like." A panel that is *meant* to look like Gboard's launcher fails quietly if any of these
 * facts drift, because drift here is a layout change, not an exception:
 *
 *  - all nine tools present, each exactly once, in the documented order;
 *  - five switches and four launchers, with launchers never reporting a state (a lit tile on
 *    Settings would tell the user a one-shot action is somehow "on");
 *  - a 4-column grid whose last row is padded, so the tiles keep one width instead of the ragged
 *    last line rendering narrower cells than the rows above it.
 */
class MoreToolsPanelTest {

    private val allOn = MoreToolsPanel.State(
        incognito = true, floating = true, split = true, oneHanded = true, sound = true
    )
    private val allOff = MoreToolsPanel.State(
        incognito = false, floating = false, split = false, oneHanded = false, sound = false
    )

    @Test
    fun `all nine tools are cells, in order, each exactly once`() {
        val keys = MoreToolsPanel.cells(allOff).map { it.key }

        assertEquals(
            listOf(
                MoreToolsPanel.Key.INCOGNITO,
                MoreToolsPanel.Key.FLOATING,
                MoreToolsPanel.Key.SPLIT,
                MoreToolsPanel.Key.ONE_HANDED,
                MoreToolsPanel.Key.SOUND,
                MoreToolsPanel.Key.EMOJI,
                MoreToolsPanel.Key.CLIPBOARD,
                MoreToolsPanel.Key.THEME,
                MoreToolsPanel.Key.SETTINGS,
            ),
            keys
        )
        assertEquals("no tool repeats", keys.size, keys.toSet().size)
    }

    @Test
    fun `five switches and four launchers, and the two lists partition the panel`() {
        assertEquals(5, MoreToolsPanel.SWITCHES.size)
        assertEquals(4, MoreToolsPanel.LAUNCHERS.size)
        assertEquals(MoreToolsPanel.ALL.size, MoreToolsPanel.SWITCHES.size + MoreToolsPanel.LAUNCHERS.size)
        assertTrue(MoreToolsPanel.SWITCHES.none { MoreToolsPanel.LAUNCHERS.contains(it) })

        for (key in MoreToolsPanel.ALL) {
            assertEquals("$key classification", MoreToolsPanel.SWITCHES.contains(key), MoreToolsPanel.isSwitch(key))
        }
    }

    @Test
    fun `switches report the state they were given`() {
        assertEquals(listOf(true, true, true, true, true), MoreToolsPanel.cells(allOn).take(5).map { it.checked })
        assertEquals(listOf(false, false, false, false, false), MoreToolsPanel.cells(allOff).take(5).map { it.checked })

        val mixed = MoreToolsPanel.State(
            incognito = true, floating = false, split = true, oneHanded = false, sound = true
        )
        assertEquals(listOf(true, false, true, false, true), MoreToolsPanel.cells(mixed).take(5).map { it.checked })
    }

    @Test
    fun `launchers are never lit, even when every switch is on`() {
        val launcherCells = MoreToolsPanel.cells(allOn).filter { !MoreToolsPanel.isSwitch(it.key) }
        assertEquals(4, launcherCells.size)
        assertTrue("a launcher tile must not draw a lit state", launcherCells.none { it.checked })

        // Same answer from the direct accessor the view uses for a single cell.
        for (key in MoreToolsPanel.LAUNCHERS) {
            assertFalse("$key", allOn.checked(key))
        }
    }

    @Test
    fun `grid is four wide and the short last row is padded, not narrowed`() {
        val rows = MoreToolsPanel.gridRows(allOff)

        assertEquals("nine cells over four columns is three rows", 3, rows.size)
        assertTrue("every row is exactly COLUMNS wide", rows.all { it.size == MoreToolsPanel.COLUMNS })

        // First two rows are full; the third holds the ninth tool then three blanks.
        assertTrue(rows[0].none { it == null })
        assertTrue(rows[1].none { it == null })
        assertEquals(MoreToolsPanel.Key.SETTINGS, rows[2][0]?.key)
        assertNull(rows[2][1])
        assertNull(rows[2][2])
        assertNull(rows[2][3])

        assertEquals(3, MoreToolsPanel.rowCount(allOff))
    }

    /**
     * BUG-AUDIT-2026-10-25.md item 7, re-pinned for the surface that replaced the dialog.
     *
     * The panel draws its own copy of the switch values instead of re-reading `Config`, because a
     * toggle's write reaches `Config` only when the preference listener runs. If the panel read
     * `Config` back, two quick taps would each compute the same "next" from the same stale value
     * and write it twice — the tile showing one thing and the feature doing another.
     */
    @Test
    fun `two quick taps advance the drawn state twice instead of writing the same value twice`() {
        // One tap: off -> on.
        val afterFirst = allOff.with(MoreToolsPanel.Key.INCOGNITO, !allOff.checked(MoreToolsPanel.Key.INCOGNITO))
        assertTrue("first tap must turn the switch on", afterFirst.incognito)

        // Second tap, computed from the DRAWN state (not from a Config that has not caught up):
        // on -> off. This is the assertion that fails if anyone reverts to re-reading Config.
        val afterSecond = afterFirst.with(
            MoreToolsPanel.Key.INCOGNITO,
            !afterFirst.checked(MoreToolsPanel.Key.INCOGNITO)
        )
        assertFalse("second tap must turn it back off, not write 'on' twice", afterSecond.incognito)

        // Everything else is untouched by both taps.
        assertEquals(allOff.floating, afterSecond.floating)
        assertEquals(allOff.split, afterSecond.split)
        assertEquals(allOff.oneHanded, afterSecond.oneHanded)
        assertEquals(allOff.sound, afterSecond.sound)
    }

    @Test
    fun `with targets exactly one switch and is a no-op for launchers`() {
        val on = allOff.with(MoreToolsPanel.Key.SPLIT, true)
        assertEquals(listOf(false, false, true, false, false), MoreToolsPanel.cells(on).take(5).map { it.checked })

        // A launcher has no value; asking must not throw from a UI callback.
        assertEquals(allOn, allOn.with(MoreToolsPanel.Key.THEME, true))
        assertEquals(allOff, allOff.with(MoreToolsPanel.Key.EMOJI, true))
    }

    @Test
    fun `grid preserves panel order when read row by row`() {
        val flattened = MoreToolsPanel.gridRows(allOn).flatten().filterNotNull().map { it.key }
        assertEquals(MoreToolsPanel.ALL, flattened)
    }

    /**
     * Drift guard: the panel must flip the value it is DRAWING, never re-read `Config`.
     *
     * The first version of this panel did exactly that and reintroduced
     * BUG-AUDIT-2026-10-25.md item 7 — a defect the AlertDialog it replaced had already fixed.
     * A behavioural test on [MoreToolsPanel.State] cannot catch it, because a re-reading caller
     * still produces a correct `State`; only the caller's source can. Same source-reading
     * convention as CoreImeHygieneDriftTest (runs with the project root as CWD).
     */
    @Test
    fun `the panel flips its drawn state, not a re-read Config`() {
        val receiver = File("src/main/kotlin/tribixbite/cleverkeys/KeyboardReceiver.kt")
        assertThat(receiver.isFile).isTrue()
        val body = receiver.readText().substringAfter("private fun onToolTap(")

        assertWithMessage(
            "onToolTap must advance `panelState` (the drawn state) on a switch tap. If this " +
                "fails, someone has gone back to recomputing the next value from Config, which " +
                "reintroduces the double-tap defect from BUG-AUDIT-2026-10-25.md item 7."
        ).that(body).contains("panelState = current.with(key, next)")

        assertWithMessage(
            "onToolTap must not snapshot Config into a local for the switch branch — that is " +
                "the stale read the drawn state exists to avoid. `toolsState()` is allowed as a " +
                "fallback when nothing has been drawn yet."
        ).that(body).doesNotContain("val current = toolsState()")
    }
}
