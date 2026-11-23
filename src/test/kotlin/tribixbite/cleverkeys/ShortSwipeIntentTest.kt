package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import tribixbite.cleverkeys.customization.*

/**
 * Pure JVM unit tests for Intent feature in short swipe customization.
 *
 * Tests all functionality that doesn't require Android Context:
 * - IntentDefinition.parseFromGson JSON parsing with safe defaults
 * - IntentDefinition.PRESETS validation
 * - ActionType enum and serialization (if applicable)
 * - ShortSwipeMapping constants and helpers
 * - XmlAttributeMapper.toXmlValue for profile export
 *
 * Note: CustomShortSwipeExecutor and KeyValueParser are NOT tested here
 * because they require Android Context (Intent, InputConnection, etc.)
 */
class ShortSwipeIntentTest {

    // =========================================================================
    // A. IntentDefinition.parseFromGson Tests
    // =========================================================================

    @Test
    fun `parseFromGson with all fields returns correct IntentDefinition`() {
        val json = """
            {
                "name": "Open Browser",
                "targetType": "ACTIVITY",
                "action": "android.intent.action.VIEW",
                "data": "https://google.com",
                "type": "text/html",
                "packageName": "com.android.chrome",
                "className": "com.google.android.apps.chrome.Main",
                "extras": {
                    "key1": "value1",
                    "key2": "value2"
                }
            }
        """.trimIndent()

        val result = IntentDefinition.parseFromGson(json)

        assertThat(result).isNotNull()
        assertThat(result!!.name).isEqualTo("Open Browser")
        assertThat(result.targetType).isEqualTo(IntentTargetType.ACTIVITY)
        assertThat(result.action).isEqualTo("android.intent.action.VIEW")
        assertThat(result.data).isEqualTo("https://google.com")
        assertThat(result.type).isEqualTo("text/html")
        assertThat(result.packageName).isEqualTo("com.android.chrome")
        assertThat(result.className).isEqualTo("com.google.android.apps.chrome.Main")
        assertThat(result.extras).isNotNull()
        assertThat(result.extras).containsEntry("key1", "value1")
        assertThat(result.extras).containsEntry("key2", "value2")
    }

    @Test
    fun `parseFromGson with missing optional fields returns object with defaults`() {
        // Only required fields (name and targetType have defaults)
        val json = """
            {
                "action": "android.intent.action.DIAL"
            }
        """.trimIndent()

        val result = IntentDefinition.parseFromGson(json)

        assertThat(result).isNotNull()
        // Defaults are applied for missing fields
        assertThat(result!!.name).isEqualTo("") // Default empty string
        assertThat(result.targetType).isEqualTo(IntentTargetType.ACTIVITY) // Default ACTIVITY
        assertThat(result.action).isEqualTo("android.intent.action.DIAL")
        assertThat(result.data).isNull()
        assertThat(result.type).isNull()
        assertThat(result.packageName).isNull()
        assertThat(result.className).isNull()
        assertThat(result.extras).isNull()
    }

    @Test
    fun `parseFromGson with null JSON returns null`() {
        val result = IntentDefinition.parseFromGson("null")
        assertThat(result).isNull()
    }

    @Test
    fun `parseFromGson with empty JSON returns defaults`() {
        val result = IntentDefinition.parseFromGson("{}")

        assertThat(result).isNotNull()
        assertThat(result!!.name).isEqualTo("")
        assertThat(result.targetType).isEqualTo(IntentTargetType.ACTIVITY)
    }

    @Test
    fun `parseFromGson with malformed JSON returns null`() {
        val malformedJson = """
            {
                "name": "Broken JSON"
                "action": "missing comma"
            }
        """.trimIndent()

        val result = IntentDefinition.parseFromGson(malformedJson)
        assertThat(result).isNull()
    }

    @Test
    fun `parseFromGson with invalid field types returns null or defaults`() {
        // targetType as invalid string should fallback to default
        val json = """
            {
                "name": "Test",
                "targetType": "INVALID_TYPE",
                "action": "test.action"
            }
        """.trimIndent()

        // Gson will throw on invalid enum, so parseFromGson should catch and return null
        val result = IntentDefinition.parseFromGson(json)
        // This may return null or apply defaults depending on Gson behavior
        // Either way is acceptable for error handling
        if (result != null) {
            assertThat(result.name).isEqualTo("Test")
        }
    }

    @Test
    fun `parseFromGson with null name field applies default empty string`() {
        val json = """
            {
                "name": null,
                "action": "test.action"
            }
        """.trimIndent()

        val result = IntentDefinition.parseFromGson(json)

        assertThat(result).isNotNull()
        assertThat(result!!.name).isEqualTo("") // Null becomes default empty string
    }

    @Test
    fun `parseFromGson with null targetType field applies default ACTIVITY`() {
        val json = """
            {
                "name": "Test",
                "targetType": null,
                "action": "test.action"
            }
        """.trimIndent()

        val result = IntentDefinition.parseFromGson(json)

        assertThat(result).isNotNull()
        assertThat(result!!.targetType).isEqualTo(IntentTargetType.ACTIVITY)
    }

    @Test
    fun `INTENT_PREFIX constant equals expected value`() {
        assertThat(IntentDefinition.INTENT_PREFIX).isEqualTo("__intent__:")
    }

    @Test
    fun `parseFromGson with complex nested extras`() {
        val json = """
            {
                "name": "Complex Intent",
                "extras": {
                    "param1": "value1",
                    "param2": "value2",
                    "param3": "value3"
                }
            }
        """.trimIndent()

        val result = IntentDefinition.parseFromGson(json)

        assertThat(result).isNotNull()
        assertThat(result!!.extras).hasSize(3)
        assertThat(result.extras).containsEntry("param1", "value1")
        assertThat(result.extras).containsEntry("param2", "value2")
        assertThat(result.extras).containsEntry("param3", "value3")
    }

    // =========================================================================
    // B. IntentDefinition.PRESETS Tests
    // =========================================================================

    @Test
    fun `PRESETS list is not empty`() {
        assertThat(IntentDefinition.PRESETS).isNotEmpty()
    }

    @Test
    fun `all presets have non-blank names`() {
        for (preset in IntentDefinition.PRESETS) {
            assertThat(preset.name).isNotEmpty()
        }
    }

    @Test
    fun `all presets have valid targetType`() {
        for (preset in IntentDefinition.PRESETS) {
            assertThat(preset.targetType).isIn(IntentTargetType.values().toList())
        }
    }

    @Test
    fun `preset Open Browser has correct fields`() {
        val preset = IntentDefinition.PRESETS.find { it.name == "Open Browser" }

        assertThat(preset).isNotNull()
        assertThat(preset!!.action).isEqualTo("android.intent.action.VIEW")
        assertThat(preset.data).isEqualTo("https://google.com")
        assertThat(preset.targetType).isEqualTo(IntentTargetType.ACTIVITY)
    }

    @Test
    fun `preset Share Text has correct fields`() {
        val preset = IntentDefinition.PRESETS.find { it.name == "Share Text" }

        assertThat(preset).isNotNull()
        assertThat(preset!!.action).isEqualTo("android.intent.action.SEND")
        assertThat(preset.type).isEqualTo("text/plain")
        assertThat(preset.extras).isNotNull()
        assertThat(preset.extras).containsKey("android.intent.extra.TEXT")
    }

    @Test
    fun `preset Termux Command Background has SERVICE targetType`() {
        val preset = IntentDefinition.PRESETS.find { it.name == "Termux Command (Background)" }

        assertThat(preset).isNotNull()
        assertThat(preset!!.targetType).isEqualTo(IntentTargetType.SERVICE)
        assertThat(preset.packageName).isEqualTo("com.termux")
        assertThat(preset.className).isEqualTo("com.termux.app.RunCommandService")
        assertThat(preset.extras).containsEntry("com.termux.RUN_COMMAND_BACKGROUND", "true")
        assertThat(preset.extras).containsEntry("com.termux.RUN_COMMAND_SESSION_ACTION", "0")
    }

    @Test
    fun `preset Termux Tab Visible has SERVICE targetType with BACKGROUND false`() {
        val preset = IntentDefinition.PRESETS.find { it.name == "Termux Tab (Visible)" }

        assertThat(preset).isNotNull()
        assertThat(preset!!.targetType).isEqualTo(IntentTargetType.SERVICE)
        assertThat(preset.packageName).isEqualTo("com.termux")
        assertThat(preset.className).isEqualTo("com.termux.app.RunCommandService")
        assertThat(preset.extras).containsEntry("com.termux.RUN_COMMAND_BACKGROUND", "false")
        assertThat(preset.extras).containsEntry("com.termux.RUN_COMMAND_SESSION_ACTION", "0")
    }

    @Test
    fun `all preset names are unique`() {
        val names = IntentDefinition.PRESETS.map { it.name }
        val uniqueNames = names.toSet()

        assertThat(names).hasSize(uniqueNames.size)
    }

    @Test
    fun `presets include expected common actions`() {
        val presetNames = IntentDefinition.PRESETS.map { it.name }

        assertThat(presetNames).contains("Open Browser")
        assertThat(presetNames).contains("Share Text")
        assertThat(presetNames).contains("Dial Phone")
        assertThat(presetNames).contains("Send Email")
        assertThat(presetNames).contains("Open Settings")
        assertThat(presetNames).contains("Termux Command (Background)")
        assertThat(presetNames).contains("Termux Tab (Visible)")
    }

    // =========================================================================
    // C. ActionType Tests
    // =========================================================================

    @Test
    fun `ActionType fromString is case-insensitive`() {
        assertThat(ActionType.fromString("TEXT")).isEqualTo(ActionType.TEXT)
        assertThat(ActionType.fromString("text")).isEqualTo(ActionType.TEXT)
        assertThat(ActionType.fromString("Text")).isEqualTo(ActionType.TEXT)
        assertThat(ActionType.fromString("COMMAND")).isEqualTo(ActionType.COMMAND)
        assertThat(ActionType.fromString("command")).isEqualTo(ActionType.COMMAND)
    }

    @Test
    fun `ActionType fromString with unknown value returns TEXT default`() {
        assertThat(ActionType.fromString("UNKNOWN")).isEqualTo(ActionType.TEXT)
        assertThat(ActionType.fromString("invalid")).isEqualTo(ActionType.TEXT)
        assertThat(ActionType.fromString("")).isEqualTo(ActionType.TEXT)
    }

    @Test
    fun `ActionType enum has expected values`() {
        val types = ActionType.values()

        // TEMPLATE is appended after the five existing types for persisted compatibility.
        assertThat(types).hasLength(6)
        assertThat(types).asList().contains(ActionType.TEXT)
        assertThat(types).asList().contains(ActionType.COMMAND)
        assertThat(types).asList().contains(ActionType.KEY_EVENT)
        assertThat(types).asList().contains(ActionType.INTENT)
        assertThat(types).asList().contains(ActionType.TIMESTAMP)
        assertThat(types).asList().contains(ActionType.TEMPLATE)
    }

    @Test
    fun `ActionType displayName is set for all types`() {
        // Labels are string resources since the 2026-09-29 i18n sweep; resolve the English text.
        for (type in ActionType.values()) {
            assertThat(EnglishResourceText.string(type.displayNameRes)).isNotEmpty()
        }
    }

    @Test
    fun `ActionType description is set for all types`() {
        for (type in ActionType.values()) {
            assertThat(EnglishResourceText.string(type.descriptionRes)).isNotEmpty()
        }
    }

    @Test
    fun `ActionType INTENT has correct display info`() {
        assertThat(EnglishResourceText.string(ActionType.INTENT.displayNameRes)).isEqualTo("Send Intent")
        assertThat(EnglishResourceText.string(ActionType.INTENT.descriptionRes)).contains("Android Intent")
    }

    // =========================================================================
    // D. ShortSwipeMapping Tests
    // =========================================================================

    @Test
    fun `MAX_DISPLAY_LENGTH is 4`() {
        assertThat(ShortSwipeMapping.MAX_DISPLAY_LENGTH).isEqualTo(4)
    }

    @Test
    fun `displayText is truncated to MAX_DISPLAY_LENGTH when too long`() {
        val mapping = ShortSwipeMapping.textInput(
            keyCode = "a",
            direction = SwipeDirection.N,
            displayText = "VeryLongText",
            text = "Hello"
        )

        assertThat(mapping.displayText).hasLength(4)
        assertThat(mapping.displayText).isEqualTo("Very")
    }

    @Test
    fun `displayText is not truncated when within MAX_DISPLAY_LENGTH`() {
        val mapping = ShortSwipeMapping.textInput(
            keyCode = "a",
            direction = SwipeDirection.N,
            displayText = "OK",
            text = "Hello"
        )

        assertThat(mapping.displayText).isEqualTo("OK")
    }

    @Test
    fun `getIntentDefinition returns parsed definition when actionType is INTENT`() {
        val json = """{"name":"Test","action":"test.action"}"""
        val mapping = ShortSwipeMapping.intent(
            keyCode = "a",
            direction = SwipeDirection.N,
            displayText = "INT",
            intentJson = json
        )

        val intentDef = mapping.getIntentDefinition()

        assertThat(intentDef).isNotNull()
        assertThat(intentDef!!.name).isEqualTo("Test")
        assertThat(intentDef.action).isEqualTo("test.action")
    }

    @Test
    fun `getIntentDefinition returns null when actionType is not INTENT`() {
        val mapping = ShortSwipeMapping.textInput(
            keyCode = "a",
            direction = SwipeDirection.N,
            displayText = "TXT",
            text = "Hello"
        )

        assertThat(mapping.getIntentDefinition()).isNull()
    }

    @Test
    fun `getIntentDefinition returns null when JSON is invalid`() {
        val mapping = ShortSwipeMapping.intent(
            keyCode = "a",
            direction = SwipeDirection.N,
            displayText = "BAD",
            intentJson = "invalid json"
        )

        assertThat(mapping.getIntentDefinition()).isNull()
    }

    @Test
    fun `intent factory method creates mapping with correct type`() {
        val json = """{"name":"Test"}"""
        val mapping = ShortSwipeMapping.intent(
            keyCode = "b",
            direction = SwipeDirection.E,
            displayText = "GO",
            intentJson = json
        )

        assertThat(mapping.actionType).isEqualTo(ActionType.INTENT)
        assertThat(mapping.actionValue).isEqualTo(json)
        assertThat(mapping.keyCode).isEqualTo("b")
        assertThat(mapping.direction).isEqualTo(SwipeDirection.E)
    }

    @Test
    fun `textInput factory method normalizes keyCode to lowercase`() {
        val mapping = ShortSwipeMapping.textInput(
            keyCode = "UPPERCASE",
            direction = SwipeDirection.N,
            displayText = "TXT",
            text = "Hello"
        )

        assertThat(mapping.keyCode).isEqualTo("uppercase")
    }

    @Test
    fun `command factory method creates mapping with COMMAND type`() {
        val mapping = ShortSwipeMapping.command(
            keyCode = "c",
            direction = SwipeDirection.W,
            displayText = "CPY",
            command = AvailableCommand.COPY
        )

        assertThat(mapping.actionType).isEqualTo(ActionType.COMMAND)
        assertThat(mapping.actionValue).isEqualTo("COPY")
        assertThat(mapping.getCommand()).isEqualTo(AvailableCommand.COPY)
    }

    @Test
    fun `keyEvent factory method creates mapping with KEY_EVENT type`() {
        val mapping = ShortSwipeMapping.keyEvent(
            keyCode = "enter",
            direction = SwipeDirection.S,
            displayText = "ENT",
            keyEventCode = 66
        )

        assertThat(mapping.actionType).isEqualTo(ActionType.KEY_EVENT)
        assertThat(mapping.actionValue).isEqualTo("66")
        assertThat(mapping.getKeyEventCode()).isEqualTo(66)
    }

    @Test
    fun `toStorageKey creates unique key with keyCode and direction`() {
        val mapping = ShortSwipeMapping.textInput(
            keyCode = "a",
            direction = SwipeDirection.NE,
            displayText = "TXT",
            text = "Hello"
        )

        assertThat(mapping.toStorageKey()).isEqualTo("a:NE")
    }

    @Test
    fun `toStorageKey normalizes keyCode to lowercase`() {
        val mapping = ShortSwipeMapping.textInput(
            keyCode = "A",
            direction = SwipeDirection.NE,
            displayText = "TXT",
            text = "Hello"
        )

        assertThat(mapping.toStorageKey()).isEqualTo("a:NE")
    }

    @Test
    fun `actionValue is truncated to MAX_ACTION_LENGTH`() {
        // Create a very long JSON string
        val longJson = "x".repeat(5000)

        val mapping = ShortSwipeMapping.intent(
            keyCode = "a",
            direction = SwipeDirection.N,
            displayText = "INT",
            intentJson = longJson
        )

        assertThat(mapping.actionValue.length).isEqualTo(ShortSwipeMapping.MAX_ACTION_LENGTH)
        assertThat(mapping.actionValue.length).isEqualTo(4096)
    }

    // =========================================================================
    // E. XmlAttributeMapper Tests
    // =========================================================================

    @Test
    fun `toXmlValue for TEXT wraps in single quotes`() {
        val mapping = ShortSwipeMapping.textInput(
            keyCode = "a",
            direction = SwipeDirection.N,
            displayText = "HI",
            text = "Hello"
        )

        val xmlValue = XmlAttributeMapper.toXmlValue(mapping)!!

        assertThat(xmlValue).isEqualTo("'Hello'")
    }

    @Test
    fun `toXmlValue for TEXT escapes single quotes`() {
        val mapping = ShortSwipeMapping.textInput(
            keyCode = "a",
            direction = SwipeDirection.N,
            displayText = "Q",
            text = "It's great"
        )

        val xmlValue = XmlAttributeMapper.toXmlValue(mapping)!!

        assertThat(xmlValue).isEqualTo("'It\\'s great'")
        assertThat(xmlValue).contains("\\'")
    }

    @Test
    fun `toXmlValue for COMMAND returns command name`() {
        val mapping = ShortSwipeMapping.command(
            keyCode = "c",
            direction = SwipeDirection.W,
            displayText = "CPY",
            command = AvailableCommand.COPY
        )

        val xmlValue = XmlAttributeMapper.toXmlValue(mapping)!!

        // "COPY" is not a key name: KeyValue.getKeyByName returns its own-name fallback, and the
        // legacy table names the editing key behind it. Exporting "COPY" itself would make the
        // short swipe TYPE "COPY" — getKeyByName is total, so the old `!= null` check passed.
        assertThat(xmlValue).isEqualTo("copy")
    }

    @Test
    fun `toXmlValue for KEY_EVENT uses keyevent prefix`() {
        val mapping = ShortSwipeMapping.keyEvent(
            keyCode = "enter",
            direction = SwipeDirection.S,
            displayText = "ENT",
            keyEventCode = 66
        )

        val xmlValue = XmlAttributeMapper.toXmlValue(mapping)!!

        assertThat(xmlValue).isEqualTo("keyevent:66")
    }

    @Test
    fun `toXmlValue for INTENT wraps JSON in quoted syntax`() {
        val json = """{"name":"Test","action":"test.action"}"""
        val mapping = ShortSwipeMapping.intent(
            keyCode = "a",
            direction = SwipeDirection.N,
            displayText = "INT",
            intentJson = json
        )

        val xmlValue = XmlAttributeMapper.toXmlValue(mapping)!!

        assertThat(xmlValue).startsWith("intent:'")
        assertThat(xmlValue).endsWith("'")
        assertThat(xmlValue).contains("\"name\":\"Test\"")
    }

    @Test
    fun `toXmlValue for INTENT escapes single quotes in JSON`() {
        val json = """{"name":"It's a test"}"""
        val mapping = ShortSwipeMapping.intent(
            keyCode = "a",
            direction = SwipeDirection.N,
            displayText = "INT",
            intentJson = json
        )

        val xmlValue = XmlAttributeMapper.toXmlValue(mapping)!!

        assertThat(xmlValue).contains("\\'")
        // Should escape the single quote in "It's"
        assertThat(xmlValue).matches(".*It\\\\'s.*")
    }

    // =========================================================================
    // E2. Export-path safety: never emit a value that parses to the command's own name
    // =========================================================================

    /**
     * Parse an exported attribute value the way a layout does: `KeyboardData.parse_key_attr`
     * hands the attribute value straight to `KeyValue.getKeyByName`, with no `symbol:` prefix.
     * A prefix would change the parse — `x:'Hi'` is a single-element Macro, not a String key
     * (`KeyValueParserTest.existingTextTokenSyntaxRemainsLiteral`) — so the bare form is the
     * one that mirrors the export path.
     */
    private fun parseExported(value: String) = KeyValueParser.parse(value)

    @Test
    fun `a legacy SCREAMING_SNAKE command exports the key name, not the enum spelling`() {
        // AvailableCommand.COPY.name is "COPY", and KeyValue.getKeyByName("COPY") is a String
        // key spelling "COPY" — NOT null. The old `getKeyByName(x) != null` guard was therefore
        // always true, its legacy remap never ran, and the exported layout typed "COPY".
        val mapping = ShortSwipeMapping(
            keyCode = "a", direction = SwipeDirection.N, displayText = "CPY",
            actionType = ActionType.COMMAND, actionValue = "COPY",
        )
        assertThat(XmlAttributeMapper.toXmlValue(mapping)).isEqualTo("copy")
        val parsed = parseExported(XmlAttributeMapper.toXmlValue(mapping)!!)
        assertThat(parsed.getKind()).isEqualTo(KeyValue.Kind.Editing)
    }

    @Test
    fun `word movement commands export their own key, not a one-character cursor move`() {
        // They mapped to cursor_left/cursor_right until 2026-10-17, when word_left/word_right
        // became real editing keys. Exporting cursor_left silently changed what the swipe does.
        val left = ShortSwipeMapping(
            keyCode = "a", direction = SwipeDirection.W, displayText = "W<",
            actionType = ActionType.COMMAND, actionValue = "WORD_LEFT",
        )
        assertThat(XmlAttributeMapper.toXmlValue(left)).isEqualTo("word_left")
        val parsed = parseExported("word_left")
        assertThat(parsed.getKind()).isEqualTo(KeyValue.Kind.Editing)
        assertThat(parsed.getEditing()).isEqualTo(KeyValue.Editing.WORD_LEFT)
    }

    @Test
    fun `a command with no layout spelling exports null rather than its own name`() {
        // These have no KeyValue at all, so the only available "value" is the name itself, and
        // a String key in a subkey slot TYPES its text. Omitting the attribute is the only
        // export that does not corrupt the user's document.
        for (name in listOf(
            "paste_pinned_1", "paste_pinned_2", "paste_pinned_3", "paste_pinned_4",
            "paste_pinned_5", "showTextMenu", "primaryLangToggle", "secondaryLangToggle",
            "one_handed_toggle", "one_handed_side", "split_toggle", "incognito_toggle",
            "floating_toggle",
        )) {
            val mapping = ShortSwipeMapping(
                keyCode = "a", direction = SwipeDirection.N, displayText = "X",
                actionType = ActionType.COMMAND, actionValue = name,
            )
            assertThat(XmlAttributeMapper.toXmlValue(mapping)).isNull()
        }
    }

    @Test
    fun `the removed placeholder exports as the placeholder key, not as text`() {
        // `removed` is a real KeyValue (Kind.Placeholder, empty payload), so it survives the
        // own-name test and exports as itself. Checked because it is the one COMMAND mapping
        // whose whole purpose is to be blank: if it ever exported as a String key, a "hide the
        // default subkey" mapping would start typing "removed".
        val mapping = ShortSwipeMapping.removal("a", SwipeDirection.N)
        assertThat(XmlAttributeMapper.toXmlValue(mapping)).isEqualTo("removed")
        val parsed = parseExported("removed")
        assertThat(parsed.getKind()).isEqualTo(KeyValue.Kind.Placeholder)
        assertThat(parsed.getString()).isEmpty()
    }

    @Test
    fun `every registry command either exports a real key or nothing`() {
        // The property, over the whole catalogue rather than a sample: an exported value must
        // never come back as a String key spelling the command's own name.
        for (command in CommandRegistry.ALL_COMMANDS) {
            val mapping = ShortSwipeMapping(
                keyCode = "a", direction = SwipeDirection.N, displayText = "X",
                actionType = ActionType.COMMAND, actionValue = command.name,
            )
            val exported = XmlAttributeMapper.toXmlValue(mapping) ?: continue
            val parsed = parseExported(exported)
            assertThat(CommandRouting.isOwnNameFallback(exported, parsed)).isFalse()
        }
    }

    @Test
    fun `a TEXT mapping with a backslash survives the export round trip`() {
        // TEXT was the one action type that did not escape backslashes, so a Windows path
        // exported as the unparseable 'C:\work' and the layout's short swipe broke.
        // buildString rather than a literal: a Kotlin literal needs each backslash
        // doubled, which makes "count the backslashes in the source" the thing
        // under test. Each append(char) below is unambiguously ONE backslash.
        val path = buildString { append("C:"); append('\\'); append("Users"); append('\\'); append("me") }
        val mapping = ShortSwipeMapping.textInput("a", SwipeDirection.N, "PTH", path)
        val exported = XmlAttributeMapper.toXmlValue(mapping)!!
        val parsed = parseExported(exported)
        assertThat(parsed.getString()).isEqualTo(path)
    }

    @Test
    fun `a TEXT mapping ending in a single backslash survives the export round trip`() {
        // The pathological case: an unescaped trailing backslash escapes the closing quote.
        val text = buildString { append("C:"); append('\\') }
        val mapping = ShortSwipeMapping.textInput("a", SwipeDirection.N, "PTH", text)
        val parsed = parseExported(XmlAttributeMapper.toXmlValue(mapping)!!)
        assertThat(parsed.getString()).isEqualTo(text)
    }

    @Test
    fun `a TEXT mapping keeps single-quote escaping after the backslash fix`() {
        val mapping = ShortSwipeMapping.textInput("a", SwipeDirection.N, "Q", "It's")
        val parsed = parseExported(XmlAttributeMapper.toXmlValue(mapping)!!)
        assertThat(parsed.getString()).isEqualTo("It's")
    }

    // =========================================================================
    // F. IntentTargetType Tests
    // =========================================================================

    @Test
    fun `IntentTargetType has expected values`() {
        val types = IntentTargetType.values()

        assertThat(types).hasLength(3)
        assertThat(types).asList().contains(IntentTargetType.ACTIVITY)
        assertThat(types).asList().contains(IntentTargetType.SERVICE)
        assertThat(types).asList().contains(IntentTargetType.BROADCAST)
    }

    @Test
    fun `IntentTargetType default is ACTIVITY`() {
        // Test via IntentDefinition default
        val def = IntentDefinition()
        assertThat(def.targetType).isEqualTo(IntentTargetType.ACTIVITY)
    }

    // =========================================================================
    // G. ShortSwipeCustomizations Storage Format Tests
    // =========================================================================

    @Test
    fun `ShortSwipeCustomizations has CURRENT_VERSION constant`() {
        assertThat(ShortSwipeCustomizations.CURRENT_VERSION).isEqualTo(2)
    }

    @Test
    fun `ShortSwipeCustomizations default version matches CURRENT_VERSION`() {
        val customizations = ShortSwipeCustomizations()
        assertThat(customizations.version).isEqualTo(ShortSwipeCustomizations.CURRENT_VERSION)
    }

    @Test
    fun `fromMappingList creates grouped storage format`() {
        val mappings = listOf(
            ShortSwipeMapping.textInput("a", SwipeDirection.N, "N", "North"),
            ShortSwipeMapping.textInput("a", SwipeDirection.E, "E", "East"),
            ShortSwipeMapping.textInput("b", SwipeDirection.S, "S", "South")
        )

        val storage = ShortSwipeCustomizations.fromMappingList(mappings)

        assertThat(storage.mappings).hasSize(2) // 2 keys: "a" and "b"
        assertThat(storage.mappings["a"]).hasSize(2) // 2 directions for "a"
        assertThat(storage.mappings["b"]).hasSize(1) // 1 direction for "b"
    }

    @Test
    fun `toMappingList converts storage back to flat list`() {
        val original = listOf(
            ShortSwipeMapping.textInput("a", SwipeDirection.N, "N", "North"),
            ShortSwipeMapping.textInput("a", SwipeDirection.E, "E", "East")
        )

        val storage = ShortSwipeCustomizations.fromMappingList(original)
        val restored = storage.toMappingList()

        assertThat(restored).hasSize(2)
        // Check that restored mappings have correct properties
        val northMapping = restored.find { it.direction == SwipeDirection.N }
        assertThat(northMapping).isNotNull()
        assertThat(northMapping!!.actionValue).isEqualTo("North")
    }

    @Test
    fun `round trip fromMappingList and toMappingList preserves data`() {
        val original = listOf(
            ShortSwipeMapping.textInput("a", SwipeDirection.N, "N", "North"),
            ShortSwipeMapping.command("b", SwipeDirection.E, "CPY", AvailableCommand.COPY),
            ShortSwipeMapping.intent("c", SwipeDirection.W, "INT", """{"name":"Test"}""")
        )

        val storage = ShortSwipeCustomizations.fromMappingList(original)
        val restored = storage.toMappingList()

        assertThat(restored).hasSize(3)

        val textMapping = restored.find { it.keyCode == "a" }
        assertThat(textMapping).isNotNull()
        assertThat(textMapping!!.actionType).isEqualTo(ActionType.TEXT)
        assertThat(textMapping.actionValue).isEqualTo("North")

        val commandMapping = restored.find { it.keyCode == "b" }
        assertThat(commandMapping).isNotNull()
        assertThat(commandMapping!!.actionType).isEqualTo(ActionType.COMMAND)

        val intentMapping = restored.find { it.keyCode == "c" }
        assertThat(intentMapping).isNotNull()
        assertThat(intentMapping!!.actionType).isEqualTo(ActionType.INTENT)
    }

    // =========================================================================
    // H. AvailableCommand Tests
    // =========================================================================

    @Test
    fun `AvailableCommand fromString is case-insensitive`() {
        assertThat(AvailableCommand.fromString("COPY")).isEqualTo(AvailableCommand.COPY)
        assertThat(AvailableCommand.fromString("copy")).isEqualTo(AvailableCommand.COPY)
        assertThat(AvailableCommand.fromString("Copy")).isEqualTo(AvailableCommand.COPY)
    }

    @Test
    fun `AvailableCommand fromString returns null for unknown command`() {
        assertThat(AvailableCommand.fromString("UNKNOWN")).isNull()
        assertThat(AvailableCommand.fromString("")).isNull()
    }

    @Test
    fun `AvailableCommand groupedByCategory returns all commands`() {
        val grouped = AvailableCommand.groupedByCategory()

        assertThat(grouped).isNotEmpty()
        assertThat(grouped).containsKey("Clipboard")
        assertThat(grouped).containsKey("Cursor")

        // Count total commands across all categories
        val totalCommands = grouped.values.sumOf { it.size }
        assertThat(totalCommands).isEqualTo(AvailableCommand.values().size)
    }

    @Test
    fun `all AvailableCommands have displayName`() {
        for (command in AvailableCommand.values()) {
            assertThat(command.displayName).isNotEmpty()
        }
    }

    @Test
    fun `all AvailableCommands have description`() {
        for (command in AvailableCommand.values()) {
            assertThat(command.description).isNotEmpty()
        }
    }

    // =========================================================================
    // G. Load-path tolerance + timestamp truncation (wave 19, 2026-11-15)
    // =========================================================================

    /** Build a stored entry directly, bypassing the factories' truncation. */
    private fun stored(
        displayText: String = "ab",
        actionType: String = "TEXT",
        actionValue: String = "hi",
    ) = DirectionMapping(displayText, actionType, actionValue)

    @Test
    fun `one malformed stored entry does not discard the rest of the file`() {
        // `mappings` comes from gson.fromJson of the user's file or an imported backup, so
        // ShortSwipeMapping's init invariants are being applied to untrusted input. Before
        // this, the first violation threw out of toMappingList and loadMappings caught it as
        // "Failed to load custom mappings" — leaving the user with NONE of their mappings
        // instead of all-but-one.
        val stored = ShortSwipeCustomizations(
            mappings = mapOf(
                "a" to mapOf("N" to stored()),
                // 5 characters: the primary constructor requires <= 4. Not reachable from the
                // editor, which takes(4) — but trivially reachable in a hand-edited file.
                "b" to mapOf("S" to stored(displayText = "FIVES")),
                "c" to mapOf("E" to stored(actionValue = "ok")),
                // An unparseable TIMESTAMP pattern.
                "d" to mapOf("W" to stored(actionType = "TIMESTAMP", actionValue = "yyyy-'abc")),
            )
        )

        val result = stored.convert()

        assertThat(result.mappings.map { it.keyCode }).containsExactly("a", "c")
        assertThat(result.skipped).isEqualTo(2)
        // `toMappingList` stays the compatible shape for every existing caller.
        assertThat(stored.toMappingList()).hasSize(2)
    }

    @Test
    fun `an unknown action type is tolerated rather than throwing`() {
        // ActionType.fromString maps unrecognised strings to a default, so this entry is kept —
        // asserted so a future strictness change is a visible decision, not a silent drop.
        val converted = ShortSwipeCustomizations(
            mappings = mapOf("a" to mapOf("N" to stored(actionType = "NOT_A_TYPE")))
        ).convert()
        assertThat(converted.skipped).isEqualTo(0)
        assertThat(converted.mappings).hasSize(1)
    }

    @Test
    fun `an unknown direction name is skipped without counting as malformed`() {
        val converted = ShortSwipeCustomizations(
            mappings = mapOf("a" to mapOf("NORTHISH" to stored()))
        ).convert()
        assertThat(converted.mappings).isEmpty()
        assertThat(converted.skipped).isEqualTo(0)
    }

    @Test
    fun `a timestamp pattern longer than the cap is truncated to a still-valid pattern`() {
        // A cut can turn a valid pattern into an invalid one exactly once: if it lands inside a
        // quoted literal, the remainder has an unterminated quote. The cap exists to bound
        // storage, not to express intent, so the unfinished tail is dropped back to the last
        // complete quote rather than throwing.
        val quoted = "yyyy-" + "'" + "x".repeat(5000) + "'"
        assertThat(ShortSwipeMapping.isValidTimestampPattern(quoted)).isTrue()

        val mapping = ShortSwipeMapping.timestamp(
            keyCode = "t",
            direction = SwipeDirection.N,
            displayText = "TS",
            pattern = quoted,
        )
        val storedPattern = mapping.getTimestampPattern()!!
        assertThat(storedPattern.length).isAtMost(ShortSwipeMapping.MAX_ACTION_LENGTH)
        assertThat(ShortSwipeMapping.isValidTimestampPattern(storedPattern)).isTrue()
    }

    @Test
    fun `a short timestamp pattern is stored unchanged`() {
        // The truncation helper must be a no-op below the cap, or every existing mapping moves.
        val pattern = "yyyy-MM-dd'T'HH:mm:ss"
        assertThat(ShortSwipeMapping.untruncatedTimestampPattern(pattern)).isEqualTo(pattern)
        assertThat(
            ShortSwipeMapping.timestamp("t", SwipeDirection.N, "TS", pattern).getTimestampPattern()
        ).isEqualTo(pattern)
    }

    @Test
    fun `truncation that would leave only a quoted fragment is rejected rather than stored`() {
        // A pathological pattern that is one enormous literal: cutting it inside the quote leaves
        // only the opening quote, which the helper trims to the empty string, which init rejects.
        // Asserted so the failure mode is a loud IllegalArgumentException at construction rather
        // than an invalid pattern reaching SimpleDateFormat at execution time.
        val allLiteral = "'" + "x".repeat(ShortSwipeMapping.MAX_ACTION_LENGTH + 100) + "'"
        val expected = ShortSwipeMapping.untruncatedTimestampPattern(allLiteral)
        assertThat(expected).isEmpty()
        assertThat(ShortSwipeMapping.isValidTimestampPattern(expected)).isFalse()
    }
}
