package tribixbite.cleverkeys

/**
 * The rows of the "more tools" sheet (phase 4 part 3).
 *
 * The owner's complaint: "I didn't see incognito tools on the keyboard unless I looked for it in
 * the settings (and I didn't see many tools not only incognito)." Every toggle already existed as
 * a command — `incognito_toggle`, `floating_toggle`, `split_toggle`, `one_handed_toggle` — or as a
 * preference (`sound_enabled`), but none was reachable from the keyboard without a pre-bound
 * swipe. The sheet puts all five one tap from the toolbar's "more" key, and rows carry their
 * state so the sheet reports the five features as well as flipping them.
 *
 * The order is the contract with the design doc (`docs/PHASE4-POLISH-DESIGN.md` §3): the five
 * feature toggles in the order it lists them. Theme and settings are dialog buttons, not rows —
 * they navigate away rather than toggle — so they are not part of this model.
 *
 * Pure Kotlin, like [CornerLabelPolicy] and [SuggestionStripPolicy]: no resource ids and no
 * Android types. The service maps [Action] to strings and toggle methods; tests pin the model.
 */
object MoreToolsSheet {

    /** The toggles the sheet offers. Each maps to an existing command or preference, never a new one. */
    enum class Action { INCOGNITO, FLOATING, SPLIT, ONE_HANDED, SOUND }

    /** One row: which toggle, and whether it is on right now. */
    data class Row(val action: Action, val checked: Boolean)

    /** The five toggles' state at the moment the sheet opens. */
    data class State(
        val incognito: Boolean,
        val floating: Boolean,
        val split: Boolean,
        val oneHanded: Boolean,
        val sound: Boolean,
    )

    fun rows(state: State): List<Row> = listOf(
        Row(Action.INCOGNITO, state.incognito),
        Row(Action.FLOATING, state.floating),
        Row(Action.SPLIT, state.split),
        Row(Action.ONE_HANDED, state.oneHanded),
        Row(Action.SOUND, state.sound),
    )
}
