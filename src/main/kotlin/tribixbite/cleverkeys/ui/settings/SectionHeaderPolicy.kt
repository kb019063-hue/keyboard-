package tribixbite.cleverkeys.ui.settings

/**
 * How a settings section presents itself (2026-11-09, UI/UX round).
 *
 * ## The problem this solves
 *
 * The drawer shows one section at a time, and every section composable wrapped its controls in
 * [CollapsibleSettingsSection] carrying **its own title** — the same string the drawer item was
 * already showing. So the screen read:
 *
 * ```
 *   [☰]  ⚙️ Mr. Brown's Keyboard Settings          <- top app bar: which SCREEN
 *        Search settings…
 *   ┌──────────────────────────────┐
 *   │ ♿ Accessibility          ⌄  │                <- repeat of the drawer item
 *   │   Vibrate on key press  [▣] │
 *   └──────────────────────────────┘
 * ```
 *
 * Three separate defects came out of that one arrangement:
 *
 *  1. **The title was printed twice** — in the drawer (correctly) and again in the pane.
 *  2. **The pane never said which section you were in.** With the drawer closed the app bar reads
 *     "Settings" for all nineteen sections, so the repeated header was the *only* position
 *     indicator — and the redundant copy was doing the job the app bar should have done.
 *  3. **Collapsing the header emptied the screen.** There is one section per pane, so collapsing
 *     it left a title and nothing else; the only way back was to tap the title again. A control
 *     whose only reachable state is "hide everything" is a dead end, not a choice.
 *
 * So the header is not a control any more: the pane becomes a plain card, and the section's title
 * moves to the top app bar where it answers "where am I?" for the whole screen. [headerText]
 * exists so that text is derived by a pure, tested function rather than assembled inline in a
 * composable, and [showsOwnTitle] pins the rule that no section prints its own title.
 */
object SectionHeaderPolicy {

    /**
     * The title shown in the top app bar for [sectionTitle] — the section's own title, unchanged.
     *
     * Kept as a function (rather than passing the string straight through) because the app bar
     * has two jobs and they must not be confused: it names the *app* when nothing is selected and
     * the *section* once one is. This is the second case, and it is total — a section always has a
     * title, so the app bar never falls back to the generic name while a section is on screen.
     */
    fun headerText(sectionTitle: String): String = sectionTitle

    /**
     * Whether a section renders its own title inside the content pane.
     *
     * Always false: the drawer names the section and the app bar repeats it in context, so a third
     * copy only pushes the first control further down the screen. Sealed as a function so the
     * intent is testable and a future section cannot quietly reintroduce the duplicate.
     */
    fun showsOwnTitle(): Boolean = false

    /**
     * Whether a section's controls are collapsible inside the pane.
     *
     * Always false, for the same reason as [showsOwnTitle]: with exactly one section per pane,
     * collapsed means "the screen is empty". Nested groups *inside* a section body (the advanced
     * word-prediction block, for example) are a different thing and are still allowed — they hide
     * a detail, never the whole pane.
     */
    fun isCollapsibleInPane(): Boolean = false
}
