package tribixbite.cleverkeys.customization

import tribixbite.cleverkeys.KeyValue

/**
 * Maps ShortSwipeMapping actions to Unexpected Keyboard XML attribute values.
 *
 * Most command names from `CommandRegistry` are also `KeyValue` names and are used directly as
 * XML attribute values, which gives round-trip compatibility:
 * - User customizes key via UI → stored in JSON
 * - Export to XML → command name used as attribute value
 * - Import XML → `KeyValue.getKeyByName()` parses it back
 *
 * "Most" is the whole point. `KeyValue.getKeyByName` is total — an unknown name comes back as a
 * String key spelling itself, never null — so the export cannot ask it whether a name is real.
 * A String key in a layout's subkey slot TYPES its text, so emitting an unrecognised name writes
 * a layout whose short swipe types that name into the user's document. [toXmlValue] therefore
 * returns null for a mapping the layout format cannot express, and the exporter skips the
 * attribute rather than writing a value that corrupts the document.
 */
object XmlAttributeMapper {

    /** Escape a payload for a single-quoted keydef; backslash first, then the delimiter. */
    private fun escaped(value: String): String =
        value.replace("\\", "\\\\").replace("'", "\\'")

    /**
     * Convert a ShortSwipeMapping to an XML attribute value, or null when the layout format
     * cannot express the mapping (see [commandAttributeValue]).
     *
     * ## What this value is
     *
     * A **keydef** — the right-hand side of a `symbol:keydef` string, which is how this
     * library's own tests parse it (`KeyValueParser.parse("date:" + toXmlValue(…))`). It is
     * written into a layout attribute by [XmlLayoutExporter].
     *
     * ## A contradiction worth recording (reported, not resolved)
     *
     * Those two readings are not the same. A layout attribute value is handed to
     * `KeyValue.getKeyByName` **bare** (`KeyboardData`'s `parse_key_attr`), with no `symbol:`
     * prefix; and `KeyValueParser.parse` returns `makeStringKey(input)` for any string without
     * a colon. So through the layout path the quoted payload of a TEXT mapping parses as the
     * literal text `'Hello'` — quotes included — and the prefixed payloads (`intent:'…'`,
     * `timestamp:'…'`) become plain String keys whose text is `intent:'…'`.
     *
     * That is a pre-existing exporter/mapper mismatch, not something this pass should guess a
     * fix for: choosing between "emit the bare literal" and "emit `1:` / a prefixed keydef"
     * changes the layout format and needs a device check. Escaping backslashes here would have
     * made it worse, because the backslash escape is only correct on the prefixed reading.
     *
     * What IS fixed here is the COMMAND case, where no reading works: the exported name was a
     * String key spelling the command itself, so the short swipe typed it.
     *
     * @param mapping The mapping to convert
     * @return the attribute value, or null when the mapping has no layout representation.
     */
    fun toXmlValue(mapping: ShortSwipeMapping): String? {
        return when (mapping.actionType) {
            ActionType.TEXT -> "'" + mapping.actionValue.replace("'", "\\'") + "'"
            ActionType.COMMAND -> commandAttributeValue(mapping)
            ActionType.KEY_EVENT -> "keyevent:${mapping.actionValue}"
            ActionType.INTENT -> "intent:${quotedPayload(mapping.actionValue)}"
            ActionType.TEMPLATE -> "template:${quotedPayload(mapping.actionValue)}"
            ActionType.TIMESTAMP -> "timestamp:${quotedPayload(mapping.actionValue)}"
        }
    }

    /** Match prefixed action parsing: escape backslashes before quote delimiters. */
    private fun quotedPayload(value: String): String =
        "'${escaped(value)}'"

    /**
     * A COMMAND mapping's XML attribute value: a key name the layout parser understands, or
     * `null` when there is none.
     *
     * ## Why the stored name cannot be used as-is
     *
     * `KeyValue.getKeyByName` is **total** — an unrecognised name comes back as a String key
     * spelling that name, which is not an error and is not null. So "is this a real key?" can
     * never be answered by `getKeyByName(name) != null`: the old code asked exactly that, the
     * branch was therefore always taken, and the legacy fallback below it was dead code.
     * `CommandRouting.isOwnNameFallback` is the test that actually distinguishes the two.
     *
     * Letting a fallback through is not cosmetic. A String key in a layout's subkey slot TYPES
     * its text, so exporting the registry name `paste_pinned_1` or the legacy name `SELECT_ALL`
     * writes a layout whose short swipe types the literal `paste_pinned_1` into the user's
     * document (the same defect as the toolbar's `more_tools`, 2026-11-04). The old fallback's
     * `CURSOR_LEFT -> "cursor_left"` style entries are exactly this bug: they only ever ran for
     * legacy names, and `AvailableCommand.COPY.uppercase()`-stored values (`COPY`) would have
     * exported literally.
     *
     * Three outcomes:
     *  - the stored name resolves to a real key (catalogue names, and the editing keys' legacy
     *    spellings, since `KeyValue` carries `copy`, `textAssist`, `copy_private` …): use it;
     *  - it is a legacy `AvailableCommand` with a real key name behind it: use that;
     *  - there is no key name at all (the three toggle commands, the five `paste_pinned_N`):
     *    `null`, and [toXmlValue] omits the attribute entirely rather than exporting a value
     *    that would type text. Those commands are keyboard-level, not expressible in a layout.
     */
    private fun commandAttributeValue(mapping: ShortSwipeMapping): String? {
        val commandName = mapping.actionValue
        val resolved = KeyValue.getKeyByName(commandName)
        if (!CommandRouting.isOwnNameFallback(commandName, resolved)) return commandName
        // Reached only for a name KeyValue does not know — in practice the legacy
        // SCREAMING_SNAKE AvailableCommand spellings stored by pre-catalogue mappings.
        return mapLegacyCommandToKeyword(mapping.getCommand())
    }

    /**
     * A key name for a legacy [AvailableCommand], or null when the layout format has no
     * equivalent for it. Deleting an entry that does not exist is the honest answer: the
     * alternative (a String key in the layout) types the command's NAME into the editor.
     *
     * `WORD_LEFT`/`WORD_RIGHT` used to map to `cursor_left`/`cursor_right` on the theory that
     * a slider would handle word movement. They are real keys since 2026-10-17, so the export
     * now says what the mapping means instead of moving the cursor one character.
     */
    private fun mapLegacyCommandToKeyword(command: AvailableCommand?): String? {
        return when (command) {
            AvailableCommand.COPY -> "copy"
            AvailableCommand.PASTE -> "paste"
            AvailableCommand.CUT -> "cut"
            AvailableCommand.SELECT_ALL -> "selectAll"
            AvailableCommand.UNDO -> "undo"
            AvailableCommand.REDO -> "redo"
            AvailableCommand.CURSOR_LEFT -> "cursor_left"
            AvailableCommand.CURSOR_RIGHT -> "cursor_right"
            AvailableCommand.CURSOR_UP -> "cursor_up"
            AvailableCommand.CURSOR_DOWN -> "cursor_down"
            AvailableCommand.CURSOR_HOME -> "home"
            AvailableCommand.CURSOR_END -> "end"
            AvailableCommand.CURSOR_DOC_START -> "doc_home"
            AvailableCommand.CURSOR_DOC_END -> "doc_end"
            AvailableCommand.WORD_LEFT -> "word_left"
            AvailableCommand.WORD_RIGHT -> "word_right"
            AvailableCommand.DELETE_WORD -> "delete_word"
            AvailableCommand.SWITCH_IME -> "change_method"
            AvailableCommand.VOICE_INPUT -> "voice_typing"
            AvailableCommand.SWITCH_FORWARD -> "switch_forward"
            AvailableCommand.SWITCH_BACKWARD -> "switch_backward"
            else -> null
        }
    }
}
