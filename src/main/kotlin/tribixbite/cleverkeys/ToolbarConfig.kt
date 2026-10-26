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
     * Upper bound on buttons. The row scrolls rather than shrinking below its 40dp minimum, so
     * this is not a layout constraint but a usability one: past a dozen or so the button you want
     * is more likely to be off-screen than the swipe you replaced. The Add control disables at it.
     */
    const val MAX_TOOLBAR_KEYS = 12

    /**
     * The `removed` placeholder a blanked short-swipe slot stores. It is a valid registry entry
     * but meaningless as a button, so it is filtered out rather than rendered dead.
     */
    private const val REMOVED = "removed"

    /**
     * Default buttons: Gboard's toolbar, left to right, mapped onto this codebase's commands.
     *
     * | Gboard slot | here            |
     * |---|---|
     * | four-dot grid | `more_tools`  |
     * | undo          | `undo`        |
     * | select (I-beam) | `select_word` |
     * | clipboard     | `switch_clipboard` |
     * | gear          | `config`      |
     * | GIF           | `switch_gif`  |
     * | mic (far right) | `voice_typing` |
     *
     * Seven buttons, not ten. The previous default was HeliBoard's ten
     * (settings, voice, clipboard, undo, redo, select, copy, paste, left, right): redo/copy/paste
     * and the two cursor keys are all reachable by long-press and swipe on the keys that own
     * them, and ten 40 dp buttons on a phone-width toolbar is most of the row spent on duplicates.
     * Gboard puts one button per action the keyboard cannot otherwise reach and leaves the rest
     * to the keys, which is why this list is shorter.
     *
     * The mic sits last because that is where Gboard puts it and where a thumb reaches for it.
     * `config` stays alongside `more_tools` (whose sheet also hosts settings) because Gboard has
     * both a grid and a gear, and one-tap settings is worth a slot.
     */
    const val DEFAULT_KEYS_STRING =
        "more_tools,undo,select_word,switch_clipboard,config,switch_gif,voice_typing"

    /**
     * [DEFAULT_KEYS_STRING] split, so each button's route through the dispatcher is explicit:
     *
     * | position | command | route |
     * |---|---|---|
     * | 1 | `more_tools`       | custom command -> `showMoreToolsSheet` |
     * | 2 | `undo`             | Kind.Editing -> `KeyEventHandler.key_up` |
     * | 3 | `select_word`      | Kind.Editing |
     * | 4 | `switch_clipboard` | Kind.Event, opens the clipboard pane |
     * | 5 | `config`           | Kind.Event, opens settings |
     * | 6 | `switch_gif`       | Kind.Event, opens the GIF pane |
     * | 7 | `voice_typing`     | Kind.Event -> in-service dictation |
     */
    val DEFAULT_KEYS: List<String> = DEFAULT_KEYS_STRING.split(SEPARATOR)

    /**
     * Parse the persisted value into command names, in order, dropping blanks, duplicates, the
     * `removed` placeholder and anything the registry does not know.
     *
     * Validating against the registry on *read* is what keeps this forward-safe: a command that a
     * future upstream merge renames or deletes makes its button disappear instead of rendering a
     * button that does nothing when tapped. A blank, absent, or wholly unrecognisable value
     * yields [DEFAULT_KEYS] — otherwise a corrupt preference would hide the entire toolbar.
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
        return if (kept.isEmpty()) DEFAULT_KEYS else kept.toList()
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
