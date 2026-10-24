package tribixbite.cleverkeys.ui.settings.sections

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import kotlin.math.roundToInt
import tribixbite.cleverkeys.OneHandedConfig
import tribixbite.cleverkeys.R
import tribixbite.cleverkeys.SettingsActivity
import tribixbite.cleverkeys.ui.settings.CollapsibleSettingsSection
import tribixbite.cleverkeys.ui.settings.SettingsDropdown
import tribixbite.cleverkeys.ui.settings.SettingsSlider
import tribixbite.cleverkeys.ui.settings.SettingsSwitch
import tribixbite.cleverkeys.ui.settings.saveSetting

/**
 * Settings for one-handed mode (HeliBoard gap analysis phase 2).
 *
 * Three controls, one per preference [OneHandedConfig] owns: whether it is on, which edge the
 * keyboard hugs, and how wide it stays. Side and width are hidden while the mode is off, the way
 * the phase-1 toolbar hides its button list — a collapsed section should show one decision, not
 * three, and greying out controls the switch above has already disabled only invites taps that
 * do nothing.
 *
 * Writes go through [saveSetting] exactly as every other section does, which is what makes the
 * keyboard change without an IME restart: `saveSetting` persists and calls
 * `updateConfigFromSettings()`, `ConfigurationManager`'s SharedPreferences listener fires
 * `Config.refresh()`, and `PreferenceUIUpdateHandler.handlePreferenceChange` calls
 * `OneHandedRoot.applyFromConfig`. The same chain is what the `one_handed_toggle` toolbar button
 * drives, so a toggle from the keyboard and a switch here cannot disagree about the stored value.
 *
 * The scale slider writes on every tick rather than on release, matching the hundred-odd existing
 * sliders ([SettingsSlider] exposes no `onValueChangeFinished`). That is cheap here: `applyState`
 * assigns `scaleX`/`pivotX`, which invalidates a render node without requesting a layout, and
 * `saveSetting` ends in `editor.apply()`.
 */
@Composable
internal fun SettingsActivity.OneHandedSection() {
    CollapsibleSettingsSection(
        title = stringResource(R.string.settings_section_one_handed),
        expanded = oneHandedSectionExpanded,
        onExpandChange = { oneHandedSectionExpanded = it },
        sectionId = "oneHanded"
    ) {
        SettingsSwitch(
            title = stringResource(R.string.one_handed_enable_title),
            description = stringResource(R.string.one_handed_enable_desc),
            checked = oneHandedEnabled,
            onCheckedChange = {
                oneHandedEnabled = it
                saveSetting(OneHandedConfig.PREF_ENABLED, it)
            }
        )

        if (oneHandedEnabled) {
            // Read the persisted spelling through the one parser that understands it, so a value
            // this screen did not write — a restored backup, a hand-edited preference — lands on
            // the default instead of throwing.
            val side = OneHandedConfig.parseSide(oneHandedSide)
            // Built as a val rather than an inline listOf(...) so the labels come from resources:
            // HardcodedUiStringTest treats a literal list passed to `options` as untranslated UI
            // prose.
            val sideOptions = listOf(
                stringResource(R.string.one_handed_side_left),
                stringResource(R.string.one_handed_side_right)
            )

            SettingsDropdown(
                title = stringResource(R.string.one_handed_side_title),
                description = stringResource(R.string.one_handed_side_desc),
                options = sideOptions,
                selectedIndex = if (side == OneHandedConfig.Side.LEFT) 0 else 1,
                onSelectionChange = { index ->
                    val next =
                        if (index == 0) OneHandedConfig.Side.LEFT else OneHandedConfig.Side.RIGHT
                    oneHandedSide = OneHandedConfig.serializeSide(next)
                    saveSetting(OneHandedConfig.PREF_SIDE, oneHandedSide)
                }
            )

            SettingsSlider(
                title = stringResource(R.string.one_handed_scale_title),
                description = stringResource(R.string.one_handed_scale_desc),
                value = oneHandedScale.toFloat(),
                valueRange =
                    OneHandedConfig.MIN_SCALE.toFloat()..OneHandedConfig.MAX_SCALE.toFloat(),
                increment = 5f,
                onValueChange = {
                    // Clamped as well as ranged, because the slider's step maths can hand back a
                    // value a hair outside the range and the stored preference is what Config
                    // reads on the next launch.
                    oneHandedScale = OneHandedConfig.clampScale(it.roundToInt())
                    saveSetting(OneHandedConfig.PREF_SCALE, oneHandedScale)
                },
                displayValue = stringResource(R.string.one_handed_scale_value, oneHandedScale)
            )
        }
    }
}
