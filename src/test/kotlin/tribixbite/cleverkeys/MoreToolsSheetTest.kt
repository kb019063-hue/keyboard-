package tribixbite.cleverkeys

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the "more tools" sheet's rows (phase 4 part 3).
 *
 * The complaint this guards is "I didn't see many tools not only incognito": the sheet must carry
 * all five feature toggles, in the design doc's order, each showing the state it was given --
 * a sheet that reports the wrong checkmark is worse than no sheet, because it invites the user to
 * flip a feature they believe is already the way they want it.
 */
class MoreToolsSheetTest {

    private val allOn = MoreToolsSheet.State(
        incognito = true, floating = true, split = true, oneHanded = true, sound = true
    )
    private val allOff = MoreToolsSheet.State(
        incognito = false, floating = false, split = false, oneHanded = false, sound = false
    )

    @Test
    fun `all five toggles are rows, in the design order, each exactly once`() {
        assertEquals(
            listOf(
                MoreToolsSheet.Action.INCOGNITO,
                MoreToolsSheet.Action.FLOATING,
                MoreToolsSheet.Action.SPLIT,
                MoreToolsSheet.Action.ONE_HANDED,
                MoreToolsSheet.Action.SOUND,
            ),
            MoreToolsSheet.rows(allOff).map { it.action }
        )
    }

    @Test
    fun `rows report the state they were given`() {
        assertEquals(listOf(true, true, true, true, true), MoreToolsSheet.rows(allOn).map { it.checked })
        assertEquals(listOf(false, false, false, false, false), MoreToolsSheet.rows(allOff).map { it.checked })

        val mixed = MoreToolsSheet.State(
            incognito = true, floating = false, split = true, oneHanded = false, sound = true
        )
        assertEquals(listOf(true, false, true, false, true), MoreToolsSheet.rows(mixed).map { it.checked })
    }
}
