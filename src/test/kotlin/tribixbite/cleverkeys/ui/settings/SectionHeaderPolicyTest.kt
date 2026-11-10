package tribixbite.cleverkeys.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The settings section header (2026-11-09, UI/UX round).
 *
 * The drawer shows one section at a time, but every section still wrapped its controls in a
 * `CollapsibleSettingsSection` carrying its own title — the same string the drawer item showed.
 * That one arrangement caused three visible defects:
 *
 *  1. the section title was printed twice (drawer + pane);
 *  2. with the drawer closed the app bar said only "Settings" for all nineteen sections, so the
 *     redundant pane header was the only position indicator on screen;
 *  3. collapsing it emptied the pane — the screen became a title with nothing under it, and the
 *     only way back was to tap the title again.
 *
 * The fix moves the title to the app bar and makes the pane a plain card. These tests pin the
 * policy half and guard the two composables it describes, because Compose cannot be driven in a
 * JVM test here — same reason the repo's other surface-drift tests read source text.
 */
class SectionHeaderPolicyTest {

    private val controlsFile =
        File("src/main/kotlin/tribixbite/cleverkeys/ui/settings/SettingsControls.kt")
    private val screenFile =
        File("src/main/kotlin/tribixbite/cleverkeys/ui/settings/SettingsScreen.kt")

    @Test
    fun `the app bar names the section, verbatim`() {
        // Total function: a section always has a title, so the bar never falls back to the
        // generic app name while a section is on screen.
        assertEquals("♿ Accessibility", SectionHeaderPolicy.headerText("♿ Accessibility"))
        assertEquals("Activities", SectionHeaderPolicy.headerText("Activities"))
        assertEquals("", SectionHeaderPolicy.headerText(""))
    }

    @Test
    fun `a section does not print its own title`() {
        assertFalse(SectionHeaderPolicy.showsOwnTitle())
    }

    @Test
    fun `a section is not collapsible inside its pane`() {
        // With exactly one section per pane, "collapsed" means "the screen is empty".
        assertFalse(SectionHeaderPolicy.isCollapsibleInPane())
    }

    // ── source guards: the composables must match the policy ──────────────────

    @Test
    fun `the pane composable renders no title and no chevron`() {
        val text = controlsFile.readText()
        val pane = Regex("""internal fun SettingsActivity\.SettingsPane\([\s\S]*?\n\}\n""")
            .find(text)?.value
        checkNotNull(pane) { "SettingsPane not found — re-point this test, don't delete it." }

        assertFalse(
            "SettingsPane must not carry a title parameter",
            "title: String" in pane
        )
        assertFalse(
            "SettingsPane must not draw a collapse chevron",
            "KeyboardArrowUp" in pane || "KeyboardArrowDown" in pane
        )
    }

    @Test
    fun `the retained section wrapper forwards without drawing a header`() {
        val text = controlsFile.readText()
        val wrapper = Regex(
            """internal fun SettingsActivity\.CollapsibleSettingsSection\([\s\S]*?\n\}\n"""
        ).find(text)?.value
        checkNotNull(wrapper) {
            "CollapsibleSettingsSection was removed — the nineteen section call sites would not " +
                "compile. Re-point this test, don't delete it."
        }

        assertTrue(
            "the wrapper must render SettingsPane",
            "SettingsPane(" in wrapper
        )
        assertFalse(
            "the wrapper must not draw a header row of its own",
            "KeyboardArrowUp" in wrapper || "KeyboardArrowDown" in wrapper
        )
        assertFalse(
            "the wrapper must not render the title text it is given",
            "text = title" in wrapper
        )
    }

    @Test
    fun `the top app bar shows the selected section, not just the app name`() {
        val text = screenFile.readText()
        val bar = Regex("""TopAppBar\([\s\S]*?\n\s{16}\}""").find(text)?.value
        checkNotNull(bar) { "TopAppBar not found in SettingsScreen — re-point this test." }

        assertTrue(
            "the app bar must name the current section",
            "SectionHeaderPolicy.headerText(" in bar
        )
        assertTrue(
            "the section title must come from the drawer key vocabulary",
            "sectionTitleRes(selectedSectionKey)" in bar
        )
    }

    @Test
    fun `the drawer no longer repeats the settings blurb over the item list`() {
        // The drawer is a navigation surface; the "Configure your keyboard settings…" paragraph
        // pushed the first item down for no navigational benefit.
        val text = screenFile.readText()
        val drawerStart = text.indexOf("ModalDrawerSheet {")
        val drawerEnd = text.indexOf("Spacer(Modifier.height(16.dp))\n                }", drawerStart)
        check(drawerStart > 0 && drawerEnd > drawerStart) { "drawer block not found" }
        val drawer = text.substring(drawerStart, drawerEnd)

        assertFalse(
            "the settings description blurb belongs on the About/Info surface, not the drawer",
            "R.string.settings_description" in drawer
        )
    }
}
