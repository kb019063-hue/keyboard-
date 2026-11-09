package tribixbite.cleverkeys

/**
 * The tools panel that opens from the toolbar's grid key — Gboard's shortcut panel, not a dialog.
 *
 * ## Why this file replaced the old model
 *
 * Phase 4 part 3 shipped the five tools as an `AlertDialog` fed by a five-boolean model with a
 * fixed list of five rows. That model could only ever express "these five toggles, in this
 * order", and the only thing it could be rendered as was `AlertDialog.setMultiChoiceItems` —
 * Android's stock list-in-a-box. The owner's verdict on that surface was blunt: "when the tools
 * open, the design is really bad. And it's not even how Gboard looks like."
 *
 * He is right, and the mismatch is structural, not cosmetic. A checkbox list is a *form*. Gboard's
 * panel is a *launcher*: a 4-column grid of round icon tiles with a word under each, drawn on the
 * keyboard's own surface, where most tiles are one-shot actions (emoji, clipboard, theme,
 * settings) and only some are switches that draw a lit state. Three things in the old model made
 * that impossible to express:
 *
 * 1. Every entry was a toggle. Emoji, clipboard, theme and settings are not switches — they open
 *    something and close the panel. They were reachable only as dialog *buttons*, which is exactly
 *    why the sheet did not read as Gboard's panel.
 * 2. A row had no identity beyond "which toggle". The panel needs an icon and a label per cell, so
 *    the model has to name the tool, not just its boolean.
 * 3. There was no notion of the panel being *about* the keyboard — no title, no surface, no close
 *    affordance, no grid geometry. All of that lived, unmodelled, in one 40-line builder.
 *
 * ## What this model is
 *
 * Pure Kotlin, like [CornerLabelPolicy], [SuggestionStripPolicy] and [ToolbarCompactPolicy]: no
 * resource ids, no Android types, no `Config`. It answers three questions and nothing else —
 * which cells exist, what their state is, and how a count of cells becomes a 4-column grid.
 * [MoreToolsPanelView] maps each [Key] to the theme's colours, the registry's strings and the
 * `ic_tool_*` glyphs; this object maps [gridRows] to rows.
 *
 * Order is the contract, as it was in the design doc: the five switches first (switch tiles read
 * as the panel's "state" half), then the four launchers. Two of those are new — emoji and
 * clipboard were previously reachable only from the toolbar's own seven slots, which is no help
 * to a user who has customised the toolbar and dropped them, and that is precisely the complaint
 * this whole feature exists to answer.
 */
object MoreToolsPanel {

    /** One tile in the panel. */
    enum class Key {
        // --- switches: the tile draws a lit state and stays open ---
        INCOGNITO,
        FLOATING,
        SPLIT,
        ONE_HANDED,
        SOUND,

        // --- launchers: one-shot, they hand off to a pane/activity and the panel closes ---
        EMOJI,
        CLIPBOARD,
        THEME,
        SETTINGS,
    }

    /** Keys that carry an on/off state. Everything else is a launcher. */
    val SWITCHES: List<Key> = listOf(Key.INCOGNITO, Key.FLOATING, Key.SPLIT, Key.ONE_HANDED, Key.SOUND)

    /** Keys that open something and close the panel behind them. */
    val LAUNCHERS: List<Key> = listOf(Key.EMOJI, Key.CLIPBOARD, Key.THEME, Key.SETTINGS)

    /**
     * The panel's contents, in display order: [SWITCHES] then [LAUNCHERS].
     *
     * Nine cells on a 4-column grid is 3 rows, the last holding one cell — deliberately the same
     * ragged tail Gboard's panel has, rather than padding the grid to twelve with tools that do
     * not exist. [gridRows] supplies the empty cells the view needs.
     */
    val ALL: List<Key> = SWITCHES + LAUNCHERS

    /** Grid width. Gboard's panel is four tiles wide; so is this one. */
    const val COLUMNS = 4

    /** One cell: which tool, and — for a switch — whether it is on right now. */
    data class Cell(val key: Key, val checked: Boolean)

    /**
     * The five switches' state at the moment the panel opens.
     *
     * Launchers have no state, so they are not part of this; [cells] gives them `checked = false`
     * and the view must not draw a lit tile for them. Keeping the booleans here (rather than
     * reading `Config` in the view) is what lets the panel be pinned by a plain JVM test.
     */
    data class State(
        val incognito: Boolean,
        val floating: Boolean,
        val split: Boolean,
        val oneHanded: Boolean,
        val sound: Boolean,
    ) {
        /** State of one switch. Launchers are always `false`. */
        fun checked(key: Key): Boolean = when (key) {
            Key.INCOGNITO -> incognito
            Key.FLOATING -> floating
            Key.SPLIT -> split
            Key.ONE_HANDED -> oneHanded
            Key.SOUND -> sound
            Key.EMOJI, Key.CLIPBOARD, Key.THEME, Key.SETTINGS -> false
        }
    }

    /** Every cell, in [ALL] order, reporting the state it was given. */
    fun cells(state: State): List<Cell> = ALL.map { Cell(it, state.checked(it)) }

    fun isSwitch(key: Key): Boolean = SWITCHES.contains(key)

    /**
     * Cells laid out [COLUMNS] to a row, with `null` padding the last row to full width.
     *
     * The padding matters: a `LinearLayout` row that is not given real children would size its
     * existing tiles to a different width from the rows above it, and the grid would wobble on
     * its last line. Returning the blanks here rather than letting the view decide keeps that
     * arithmetic in one testable place.
     */
    fun gridRows(state: State): List<List<Cell?>> {
        val all = cells(state)
        val rows = ArrayList<List<Cell?>>((all.size + COLUMNS - 1) / COLUMNS)
        var index = 0
        while (index < all.size) {
            val row = ArrayList<Cell?>(COLUMNS)
            for (column in 0 until COLUMNS) {
                row.add(all.getOrNull(index + column))
            }
            rows.add(row)
            index += COLUMNS
        }
        return rows
    }

    /**
     * How many rows the panel draws for [state]. Used by the layout code and by the tests that
     * pin the panel's height budget.
     */
    fun rowCount(state: State): Int = gridRows(state).size
}
