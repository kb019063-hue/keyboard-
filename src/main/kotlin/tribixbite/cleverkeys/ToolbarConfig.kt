package tribixbite.cleverkeys

import tribixbite.cleverkeys.customization.CommandRegistry

/**
 * Which commands the keyboard toolbar shows, and in what order.
 *
 * Phase 1 of the HeliBoard gap analysis (`docs/HELIBOARD-GAP-ANALYSIS.md`). HeliBoard exposes a
 * suggestion-strip toolbar of 30 `ToolbarKey` entries. This codebase already had every one of
 * those *actions* — [CommandRegistry.ALL_COMMANDS] holds 240 commands, and
 * `Keyboard2View.onCustomShortSwipe` already dispatches all of them (Editing-kind through
 * `KeyEventHandler.key_up`, Event-kind through `service.triggerKeyboardEvent`, the rest through
 * `CustomShortSwipeExecutor`) — but had no UI surface to reach them without a long-press or a
 * pre-bound swipe. So the toolbar is a *view over existing machinery* and introduces no new
 * command. That is why phase 1 needed no changes to `ActionType`, `AvailableCommand` or the two
 * exhaustive `when` sites in `CustomShortSwipeExecutor` and `XmlAttributeMapper`.
 *
 * A button therefore stores nothing but a [CommandRegistry] command name.
 */
object ToolbarConfig {

    /** Preference key: whether the toolbar row is shown. */
    const val PREF_ENABLED = "toolbar_enabled"

    /** Preference key: the ordered, comma-separated command names. */
    const val PREF_KEYS = "toolbar_keys"

    /** Separator for the persisted ordered list. */
    const val SEPARATOR = ","

    /**
     * The `removed` placeholder a blanked short-swipe slot stores. It is a valid registry entry
     * but meaningless as a button, so it is filtered out rather than rendered dead.
     */
    private const val REMOVED = "removed"

    /**
     * Default buttons: HeliBoard's `ToolbarUtils.defaultToolbarPref`
     * (SETTINGS, VOICE, CLIPBOARD, UNDO, REDO, SELECT_WORD, COPY, PASTE, LEFT, RIGHT) mapped onto
     * this codebase's command names. Keeping the same ten in the same order means a HeliBoard
     * user's muscle memory transfers.
     */
    const val DEFAULT_KEYS_STRING =
        "config,voice_typing,switch_clipboard,undo,redo,select_word,copy,paste,cursor_left,cursor_right"

    /**
     * [DEFAULT_KEYS_STRING] split, with the HeliBoard entry each one stands for:
     *
     * | position | this codebase | HeliBoard `ToolbarKey` | dispatch route |
     * |---|---|---|---|
     * | 1 | `config`           | SETTINGS      | Kind.Event -> `triggerKeyboardEvent` |
     * | 2 | `voice_typing`     | VOICE         | Kind.Event |
     * | 3 | `switch_clipboard` | CLIPBOARD     | Kind.Event, opens the clipboard pane |
     * | 4 | `undo`             | UNDO          | Kind.Editing -> `KeyEventHandler.key_up` |
     * | 5 | `redo`             | REDO          | Kind.Editing |
     * | 6 | `select_word`      | SELECT_WORD   | Kind.Editing |
     * | 7 | `copy`             | COPY          | Kind.Editing |
     * | 8 | `paste`            | PASTE         | Kind.Editing |
     * | 9 | `cursor_left`      | LEFT          | `CustomShortSwipeExecutor` |
     * | 10 | `cursor_right`    | RIGHT         | `CustomShortSwipeExecutor` |
     */
    val DEFAULT_KEYS: List<String> = DEFAULT_KEYS_STRING.split(SEPARATOR)

    /**
     * Parse the persisted value into command names, in order, dropping blanks, duplicates, the
     * `removed` placeholder and anything the registry does not know.
     *
     * Validating against the registry on *read* is what keeps this forward-safe: a command that a
     * future upstream merge renames or deletes makes its button disappear instead of rendering a
     * button that does nothing when tapped. A blank or absent value yields [DEFAULT_KEYS].
     */
    fun parse(raw: String?): List<String> {
        val source = if (raw.isNullOrBlank()) DEFAULT_KEYS_STRING else raw
        val kept = LinkedHashSet<String>()
        for (token in source.split(SEPARATOR)) {
            val name = token.trim()
            if (name.isEmpty() || name == REMOVED) continue
            if (CommandRegistry.getByName(name) == null) continue
            kept.add(name)
        }
        return kept.toList()
    }

    /** Serialize a button order back to the persisted form. */
    fun serialize(keys: List<String>): String = keys.joinToString(SEPARATOR)

    /**
     * Every command name a picker may offer, in registry order (already grouped by category
     * within that order). The settings UI uses this rather than a hardcoded list so the toolbar
     * can never offer something the dispatcher cannot run.
     */
    fun availableKeys(): List<String> = CommandRegistry.ALL_COMMANDS.map { it.name }
}
