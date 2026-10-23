package tribixbite.cleverkeys.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the settings drawer's items (phase 4 part 4).
 *
 * The drawer replaces one scrolling column of nineteen stacked sections, so the list of keys IS
 * the navigation: a key missing from it makes a section unreachable by anything but search, a
 * duplicate renders two items for one section, and a key outside the search index's vocabulary
 * silently falls through [sectionTitleRes]'s fallback and shows the wrong title. The order is
 * the old stacking order, so the drawer reads like the screen everybody knows.
 */
class SettingsDrawerItemsTest {

    @Test
    fun `every section is an item, exactly once, in the old stacking order`() {
        assertEquals(
            listOf(
                "testKeyboard", "activities", "swipeTyping", "appearance", "swipeTrail",
                "toolbar", "oneHanded", "input", "swipeCorrections", "gestureTuning",
                "accessibility", "clipboard", "gif", "backupRestore", "multiLang",
                "privacy", "advanced", "info", "help",
            ),
            SettingsDrawerItems.ORDER
        )
        assertEquals(SettingsDrawerItems.ORDER.size, SettingsDrawerItems.ORDER.distinct().size)
    }

    @Test
    fun `the default is the first item, as the old column started with it`() {
        assertEquals(SettingsDrawerItems.ORDER.first(), SettingsDrawerItems.DEFAULT)
    }

    @Test
    fun `every item resolves to its own section title, never to another item's`() {
        // sectionTitleRes falls back to the Advanced title for a key it does not know; with all
        // nineteen keys present and distinct, a title collision is the only way a typo can hide,
        // and this pins that none exists.
        val titles = SettingsDrawerItems.ORDER.map { sectionTitleRes(it) }
        assertEquals(titles.size, titles.distinct().size)
    }
}
