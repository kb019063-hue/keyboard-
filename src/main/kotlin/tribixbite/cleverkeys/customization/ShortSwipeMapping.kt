package tribixbite.cleverkeys.customization

import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Represents a single short swipe mapping for a key in a specific direction.
 *
 * @property keyCode The key identifier (lowercase letter, e.g., "a", "b", or special keys like "space")
 * @property direction The swipe direction this mapping applies to
 * @property displayText The text shown on the key's sub-label (max 4 characters for display)
 * @property actionType The type of action to execute
 * @property actionValue The action data (text content, command name, key event code, or
 *     [SimpleDateFormat] pattern for [ActionType.TIMESTAMP])
 * @property useKeyFont Whether to render displayText with the special keyboard icon font
 */
data class ShortSwipeMapping(
    val keyCode: String,
    val direction: SwipeDirection,
    val displayText: String,
    val actionType: ActionType,
    val actionValue: String,
    val useKeyFont: Boolean = false
) {
    init {
        require(keyCode.isNotEmpty()) { "keyCode cannot be empty" }
        require(displayText.length <= MAX_DISPLAY_LENGTH) {
            "displayText must be at most $MAX_DISPLAY_LENGTH characters"
        }
        require(actionValue.length <= MAX_ACTION_LENGTH) {
            "actionValue must be at most $MAX_ACTION_LENGTH characters"
        }
        // For TIMESTAMP, validate the actionValue is a parseable SimpleDateFormat pattern.
        // SimpleDateFormat constructor throws IllegalArgumentException for malformed patterns
        // (e.g. unclosed quotes "abc'") but accepts unknown pattern letters as literals.
        if (actionType == ActionType.TIMESTAMP) {
            require(actionValue.isNotBlank()) { "TIMESTAMP actionValue (pattern) cannot be blank" }
            require(isValidTimestampPattern(actionValue)) {
                "TIMESTAMP actionValue must be a valid SimpleDateFormat pattern: '$actionValue'"
            }
        }
        if (actionType == ActionType.TEMPLATE) {
            require(DynamicTemplate.isValid(actionValue)) { "Invalid template" }
        }
        // Note: We no longer validate command names here because we support two systems:
        // 1. CommandRegistry (camelCase names like "selectAll") - new system with 143+ commands
        // 2. AvailableCommand enum (SCREAMING_SNAKE like "SELECT_ALL") - legacy system
        // The executor handles both naming conventions
    }

    companion object {
        /** Maximum characters for display text on key sub-label */
        const val MAX_DISPLAY_LENGTH = 4

        /** Maximum characters for action value (text input or serialized intent) */
        const val MAX_ACTION_LENGTH = 4096

        /**
         * The `removed` placeholder command (CommandRegistry "REMOVED/PLACEHOLDER KEYS"). A
         * mapping to it blanks its slot: it hides the layout's default subkey there (#171) and
         * does nothing when swiped. The subkey popover treats such a slot as empty.
         */
        const val REMOVED_COMMAND = "removed"

        /** A mapping that blanks [direction] on [keyCode] — see [REMOVED_COMMAND]. */
        fun removal(keyCode: String, direction: SwipeDirection): ShortSwipeMapping = ShortSwipeMapping(
            keyCode = keyCode.lowercase(),
            direction = direction,
            displayText = "",
            actionType = ActionType.COMMAND,
            actionValue = REMOVED_COMMAND
        )

        /**
         * Validate that [pattern] is a parseable [SimpleDateFormat] pattern.
         *
         * SimpleDateFormat's constructor throws [IllegalArgumentException] for unknown
         * pattern letters and unterminated quotes — both cases we want to reject.
         *
         * @return true if pattern is non-blank and can be used to construct a
         *         [SimpleDateFormat] without throwing; false otherwise.
         */
        @JvmStatic
        fun isValidTimestampPattern(pattern: String): Boolean {
            if (pattern.isBlank()) return false
            return try {
                SimpleDateFormat(pattern, Locale.getDefault())
                true
            } catch (_: IllegalArgumentException) {
                false
            } catch (_: NullPointerException) {
                // Defensive: shouldn't happen for non-null non-blank input
                false
            }
        }

        /**
         * [pattern] cut to [MAX_ACTION_LENGTH], refusing a cut that would leave an unterminated
         * quoted literal.
         *
         * `SimpleDateFormat` rejects an odd number of `'`. A pattern longer than the cap whose
         * 4096th character falls inside a quoted run therefore truncates to an INVALID pattern,
         * and because the cap exists to bound storage rather than to express a user intent, the
         * right answer is to drop the unfinished tail back to the last complete quote rather
         * than to throw. Patterns are short in practice (the editor offers `yyyy-MM-dd` and the
         * like), so this only bites a hand-edited or imported backup.
         */
        @JvmStatic
        fun untruncatedTimestampPattern(pattern: String): String {
            if (pattern.length <= MAX_ACTION_LENGTH) return pattern
            val cut = pattern.substring(0, MAX_ACTION_LENGTH)
            // An unterminated quote is the ONLY way a prefix of a valid pattern is invalid, so
            // trim the dangling tail back to its opening quote. Bounded: one pass, no loop.
            // If that leaves nothing usable the pattern began inside a literal, and `init`'s
            // isNotBlank/validity require rejects it with its own message — the same contract
            // this factory already documents, and the load path tolerates it per entry.
            val lastQuote = cut.lastIndexOf('\'')
            return if (lastQuote >= 0 && cut.count { it == '\'' } % 2 == 1) {
                cut.substring(0, lastQuote)
            } else {
                cut
            }
        }

        /**
         * Create a text input mapping.
         */
        fun textInput(
            keyCode: String,
            direction: SwipeDirection,
            displayText: String,
            text: String,
            useKeyFont: Boolean = false
        ): ShortSwipeMapping = ShortSwipeMapping(
            keyCode = keyCode.lowercase(),
            direction = direction,
            displayText = displayText.take(MAX_DISPLAY_LENGTH),
            actionType = ActionType.TEXT,
            actionValue = text.take(MAX_ACTION_LENGTH),
            useKeyFont = useKeyFont
        )

        /**
         * Create a command mapping.
         */
        fun command(
            keyCode: String,
            direction: SwipeDirection,
            displayText: String,
            command: AvailableCommand,
            useKeyFont: Boolean = false
        ): ShortSwipeMapping = ShortSwipeMapping(
            keyCode = keyCode.lowercase(),
            direction = direction,
            displayText = displayText.take(MAX_DISPLAY_LENGTH),
            actionType = ActionType.COMMAND,
            actionValue = command.name,
            useKeyFont = useKeyFont
        )

        /**
         * Create a key event mapping.
         */
        fun keyEvent(
            keyCode: String,
            direction: SwipeDirection,
            displayText: String,
            keyEventCode: Int,
            useKeyFont: Boolean = false
        ): ShortSwipeMapping = ShortSwipeMapping(
            keyCode = keyCode.lowercase(),
            direction = direction,
            displayText = displayText.take(MAX_DISPLAY_LENGTH),
            actionType = ActionType.KEY_EVENT,
            actionValue = keyEventCode.toString(),
            useKeyFont = useKeyFont
        )

        /**
         * Create an intent mapping.
         */
        fun intent(
            keyCode: String,
            direction: SwipeDirection,
            displayText: String,
            intentJson: String,
            useKeyFont: Boolean = false
        ): ShortSwipeMapping = ShortSwipeMapping(
            keyCode = keyCode.lowercase(),
            direction = direction,
            displayText = displayText.take(MAX_DISPLAY_LENGTH),
            actionType = ActionType.INTENT,
            actionValue = intentJson.take(MAX_ACTION_LENGTH),
            useKeyFont = useKeyFont
        )

        /**
         * Create a timestamp mapping.
         *
         * The [pattern] is a [SimpleDateFormat] pattern (e.g. "yyyy-MM-dd HH:mm").
         * At execution time, the current Date is formatted with [Locale.getDefault]
         * and the result is committed as text via InputConnection.
         *
         * @throws IllegalArgumentException if [pattern] is not a valid SimpleDateFormat
         *         pattern (constructor would throw).
         */
        fun timestamp(
            keyCode: String,
            direction: SwipeDirection,
            displayText: String,
            pattern: String,
            useKeyFont: Boolean = false
        ): ShortSwipeMapping = ShortSwipeMapping(
            keyCode = keyCode.lowercase(),
            direction = direction,
            displayText = displayText.take(MAX_DISPLAY_LENGTH),
            actionType = ActionType.TIMESTAMP,
            // Truncate BEFORE constructing, so `init` validates the string that is actually
            // stored. `pattern.take(...)` in the constructor argument is evaluated first, so
            // this is the same value — but the property it has to be *provably* validated now
            // holds by construction rather than by argument order. A cut can turn a valid
            // pattern into an invalid one exactly once: if it lands inside a quoted literal,
            // the remainder has an unterminated quote (`yyyy-'abc` ) — see
            // `GeoShapeFrameTest`'s sibling `ShortSwipeTruncationTest`.
            actionValue = untruncatedTimestampPattern(pattern),
            useKeyFont = useKeyFont
        )
    }

    /** Whether this mapping blanks its slot rather than doing something (see [REMOVED_COMMAND]). */
    val isRemoval: Boolean
        get() = actionType == ActionType.COMMAND && actionValue == REMOVED_COMMAND

    /**
     * Get the command if this is a COMMAND type mapping.
     */
    fun getCommand(): AvailableCommand? {
        return if (actionType == ActionType.COMMAND) {
            AvailableCommand.fromString(actionValue)
        } else null
    }

    /**
     * Get the key event code if this is a KEY_EVENT type mapping.
     */
    fun getKeyEventCode(): Int? {
        return if (actionType == ActionType.KEY_EVENT) {
            actionValue.toIntOrNull()
        } else null
    }

    /**
     * Get the IntentDefinition if this is an INTENT type mapping.
     * Uses null-safe parsing to handle Gson's constructor bypass.
     * @return Parsed IntentDefinition or null if parsing fails or wrong type.
     */
    fun getIntentDefinition(): IntentDefinition? {
        return if (actionType == ActionType.INTENT) {
            IntentDefinition.parseFromGson(actionValue)
        } else null
    }

    /**
     * Get the timestamp pattern if this is a TIMESTAMP type mapping.
     * Validation already happened in [init], so the pattern is guaranteed parseable.
     * @return The [SimpleDateFormat] pattern, or null if not a TIMESTAMP mapping.
     */
    fun getTimestampPattern(): String? {
        return if (actionType == ActionType.TIMESTAMP) actionValue else null
    }

    /**
     * Create a unique key for HashMap storage.
     * Format: "keyCode:direction" e.g., "a:NE"
     */
    fun toStorageKey(): String = "${keyCode.lowercase()}:${direction.name}"
}

/**
 * Storage model for JSON serialization.
 * Groups mappings by key code for efficient storage format.
 */
data class ShortSwipeCustomizations(
    val version: Int = CURRENT_VERSION,
    val mappings: Map<String, Map<String, DirectionMapping>> = emptyMap()
) {
    /**
     * Convert to flat list of ShortSwipeMapping objects.
     *
     * **Per-entry tolerance.** [ShortSwipeMapping]'s `init` REJECTS an entry that violates its
     * invariants (`displayText` > 4 chars, `actionValue` > 4096, a TIMESTAMP pattern that is
     * blank or unparseable, …). This is a LOAD path — `mappings` comes straight from
     * `gson.fromJson` of the user's file or an imported backup — so those invariants are being
     * applied to untrusted input, and they are not all reachable through the app's own editor.
     * A hand-edited or third-party-backup entry can violate one of them.
     *
     * Letting the exception escape would fail the WHOLE file: `loadMappings` catches it and
     * logs "Failed to load custom mappings", leaving the user with NONE of their mappings
     * instead of all-but-one. (The same shape as the import bug G-2 already fixed for an
     * empty payload — "an import that delivers nothing must not destroy everything".) So each
     * entry is converted independently and a bad one is skipped, keeping the rest.
     *
     * A skipped entry is not silently lost forever: it stays in the on-disk JSON (this method
     * does not rewrite the file), so correcting the file or a future release that accepts it
     * restores it, and the count comes back through [convert] for the caller to log.
     */
    fun toMappingList(): List<ShortSwipeMapping> = convert().mappings

    /**
     * [toMappingList] plus the count of entries it had to reject, for the caller to log.
     *
     * A side-effect property was the obvious alternative and is the wrong shape for a `data
     * class`: the count would be mutable state on a value type, shared by every holder of the
     * instance, and correct only if the reader happened to be the thread that called
     * [toMappingList] last. Returning it keeps the value with the call that produced it.
     */
    fun convert(): Converted {
        var skipped = 0
        val out = ArrayList<ShortSwipeMapping>(mappings.size * 4)
        for ((keyCode, directions) in mappings) {
            for ((directionName, mapping) in directions) {
                val direction = SwipeDirection.entries.find { it.name == directionName } ?: continue
                val converted = try {
                    val actionType = ActionType.fromString(mapping.actionType)
                    // A command's old default label (e.g. Private Copy's "🔒⎘") becomes its
                    // current default; user-typed labels are kept as saved.
                    val upgraded = if (actionType == ActionType.COMMAND) {
                        CommandRegistry.upgradeLegacyDefaultLabel(
                            mapping.actionValue, mapping.displayText, mapping.useKeyFont
                        )
                    } else null
                    ShortSwipeMapping(
                        keyCode = keyCode,
                        direction = direction,
                        displayText = upgraded?.displayText ?: mapping.displayText,
                        actionType = actionType,
                        actionValue = mapping.actionValue,
                        useKeyFont = upgraded?.useKeyFont ?: mapping.useKeyFont
                    )
                } catch (_: IllegalArgumentException) {
                    // `init`'s `require` failures are the only IllegalArgumentException this can
                    // throw, and they are exactly the "this stored entry is malformed" case.
                    skipped++
                    continue
                }
                out.add(converted)
            }
        }
        return Converted(out, skipped)
    }

    /** The result of [convert]: the usable mappings plus how many stored entries were dropped. */
    data class Converted(val mappings: List<ShortSwipeMapping>, val skipped: Int)

    companion object {
        const val CURRENT_VERSION = 2

        /**
         * Convert from flat list to storage format.
         */
        fun fromMappingList(mappings: List<ShortSwipeMapping>): ShortSwipeCustomizations {
            val grouped = mappings.groupBy { it.keyCode.lowercase() }
                .mapValues { (_, keyMappings) ->
                    keyMappings.associate { mapping ->
                        mapping.direction.name to DirectionMapping(
                            displayText = mapping.displayText,
                            actionType = mapping.actionType.name,
                            actionValue = mapping.actionValue,
                            useKeyFont = mapping.useKeyFont
                        )
                    }
                }
            return ShortSwipeCustomizations(mappings = grouped)
        }
    }
}

/**
 * JSON-friendly model for a single direction mapping.
 * @property displayText The text/icon to display
 * @property actionType The action type (TEXT, COMMAND, KEY_EVENT, INTENT)
 * @property actionValue The action value (text, command name, keycode, or JSON intent)
 * @property useKeyFont Whether to use the special keyboard icon font for displayText
 */
data class DirectionMapping(
    val displayText: String,
    val actionType: String,
    val actionValue: String,
    val useKeyFont: Boolean = false
)
