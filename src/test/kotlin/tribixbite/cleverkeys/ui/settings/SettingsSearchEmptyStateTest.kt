package tribixbite.cleverkeys.ui.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What the settings search shows when a query matches nothing (2026-11-09, UI/UX round).
 *
 * The results card was gated on `showResults = query.isNotBlank() && filteredSettings.isNotEmpty()`,
 * so a query with no matches collapsed the card and rendered **nothing at all**. The screen then
 * looked identical to a search that had not run — there was no way to tell "that setting does not
 * exist" from "search is broken", and a typo produced silence.
 *
 * The fix adds an explicit empty state. These are source guards: the state is a Composable and the
 * JVM test harness cannot compose Android UI, so the assertion is that the branch and its string
 * exist and are wired to the query.
 */
class SettingsSearchEmptyStateTest {

    private val screenFile =
        File("src/main/kotlin/tribixbite/cleverkeys/ui/settings/SettingsScreen.kt")
    private val stringsFile = File("res/values/strings.xml")

    @Test
    fun `a non-matching query is distinguished from a blank one`() {
        val text = screenFile.readText()
        // `searching` must exist as its own concept: the old code only had `showResults`, which is
        // false for BOTH "blank query" and "no matches", which is exactly how they got conflated.
        assertTrue(
            "expected a `searching` flag separate from `showResults`",
            Regex("""val\s+searching\s*=""").containsMatchIn(text)
        )
        assertTrue(
            "showResults must be derived from `searching`",
            Regex("""val\s+showResults\s*=\s*searching\s*&&""").containsMatchIn(text)
        )
    }

    @Test
    fun `the empty state renders only when a query has no matches`() {
        val text = screenFile.readText()
        // Guard the condition, not just the presence of some Text: an empty state that also fires
        // on a blank query would put "No settings match """ on every visit to the screen.
        assertTrue(
            "expected `if (searching && filteredSettings.isEmpty())`",
            Regex(
                """if\s*\(\s*searching\s*&&\s*filteredSettings\.isEmpty\(\)\s*\)"""
            ).containsMatchIn(text)
        )
        assertTrue(
            "the empty state must name the query the user typed",
            "R.string.settings_search_no_results, settingsSearchQuery" in text
        )
    }

    @Test
    fun `the empty-state string exists and takes the query`() {
        val text = stringsFile.readText()
        assertTrue(
            "res/values/strings.xml must define settings_search_no_results",
            Regex("""<string name="settings_search_no_results">""").containsMatchIn(text)
        )
        assertTrue(
            "the no-results string must interpolate the query",
            Regex("""<string name="settings_search_no_results">[^<]*%1\$s""").containsMatchIn(text)
        )
    }

    @Test
    fun `a blank query does not show the empty state`() {
        // Same condition, read the other way: blanking the query must return to the normal pane
        // with no "no settings match" line left behind.
        val text = screenFile.readText()
        assertFalse(
            "the empty state must not be gated on the query alone",
            Regex("""if\s*\(\s*settingsSearchQuery\.isNotBlank\(\)\s*&&\s*filteredSettings\.isEmpty\(\)\s*\)""")
                .containsMatchIn(text)
        )
        assertTrue(
            "the empty state must be gated on the `searching` flag",
            Regex("""if\s*\(\s*searching\s*&&""").containsMatchIn(text)
        )
    }
}
