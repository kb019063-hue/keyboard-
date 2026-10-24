package tribixbite.cleverkeys.ui.settings

/**
 * The navigation drawer's items: every settings section, one at a time (phase 4 part 4).
 *
 * The owner's verdict on the old screen — "the settings UI is amateurish … instead of putting
 * everything on vertical" — was about nineteen collapsible sections stacked in one scrolling
 * column, with the search bar scrolling away and every control below the fold of the one before
 * it. The drawer keeps every section exactly as it is (composables, persistence and the generated
 * search index are untouched) and changes only the container: the sections become drawer items
 * and the content pane shows the selected one.
 *
 * The keys are the search index's `sectionKey` vocabulary — the same strings [sectionTitleRes]
 * maps to titles, [expanderFor] maps to expand-state, and the generated index stamps on every
 * entry — so selecting a section, expanding it and landing a search result on it are one
 * vocabulary with no translation layer to drift.
 *
 * Pure Kotlin, like the other policy objects: the composable renders this list, the tests pin it.
 */
object SettingsDrawerItems {

    /**
     * Every section key, in the order the old column stacked them, so the drawer reads like the
     * screen everybody knows — Test Keyboard and Activities first, Help and Info last.
     */
    val ORDER: List<String> = listOf(
        "testKeyboard",
        "activities",
        "swipeTyping",
        "appearance",
        "swipeTrail",
        "toolbar",
        "oneHanded",
        "input",
        "swipeCorrections",
        "gestureTuning",
        "accessibility",
        "clipboard",
        "gif",
        "backupRestore",
        "multiLang",
        "privacy",
        "advanced",
        "info",
        "help",
    )

    /** What an untouched settings screen shows: the first drawer item, as the old column did. */
    val DEFAULT: String = "testKeyboard"
}
