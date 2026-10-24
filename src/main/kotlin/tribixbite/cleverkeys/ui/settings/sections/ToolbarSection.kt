package tribixbite.cleverkeys.ui.settings.sections

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import tribixbite.cleverkeys.R
import tribixbite.cleverkeys.SettingsActivity
import tribixbite.cleverkeys.ToolbarConfig
import tribixbite.cleverkeys.customization.CommandRegistry
import tribixbite.cleverkeys.ui.settings.CollapsibleSettingsSection
import tribixbite.cleverkeys.ui.settings.SettingsSwitch
import tribixbite.cleverkeys.ui.settings.saveSetting

/**
 * Settings for the one-tap command toolbar (HeliBoard gap analysis phase 1).
 *
 * The toolbar stores nothing but [CommandRegistry] command names, so this screen is an editor over
 * that ordered list: enable, reorder, remove, add, reset. Adding reuses the same catalogue the
 * short-swipe and subkey editors use, which is why the toolbar can never offer a command the
 * dispatcher cannot run — `ToolbarConfig.parse` also drops unknown names on read, so a command a
 * future upstream merge removes makes its button disappear instead of rendering dead.
 *
 * Every write goes through [saveSetting], which persists the preference and calls
 * `updateConfigFromSettings()`. `ConfigurationManager`'s SharedPreferences listener then fires
 * `Config.refresh()`, and `PreferenceUIUpdateHandler.handlePreferenceChange` calls
 * `KeyboardToolbar.applyFromConfig` — so a change here takes effect on the open keyboard
 * immediately, without restarting the IME.
 */
@Composable
internal fun SettingsActivity.ToolbarSection() {
    var showPicker by remember { mutableStateOf(false) }

    CollapsibleSettingsSection(
        title = stringResource(R.string.settings_section_toolbar),
        expanded = toolbarSectionExpanded,
        onExpandChange = { toolbarSectionExpanded = it },
        sectionId = "toolbar"
    ) {
        SettingsSwitch(
            title = stringResource(R.string.toolbar_enable_title),
            description = stringResource(R.string.toolbar_enable_desc),
            checked = toolbarEnabled,
            onCheckedChange = {
                toolbarEnabled = it
                saveSetting(ToolbarConfig.PREF_ENABLED, it)
            }
        )

        if (toolbarEnabled) {
            Text(
                text = stringResource(R.string.toolbar_keys_title),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp)
            )

            if (toolbarKeys.isEmpty()) {
                Text(
                    text = stringResource(R.string.toolbar_keys_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            } else {
                // forEachIndexed is inline, so the composable calls inside are fine, and the list
                // is short (bounded by MAX_TOOLBAR_KEYS) so no lazy layout is needed here.
                toolbarKeys.forEachIndexed { index, name ->
                    ToolbarKeyRow(
                        commandName = name,
                        canMoveUp = index > 0,
                        canMoveDown = index < toolbarKeys.lastIndex,
                        onMoveUp = { persistToolbarKeys(swap(toolbarKeys, index, index - 1)) },
                        onMoveDown = { persistToolbarKeys(swap(toolbarKeys, index, index + 1)) },
                        onRemove = { persistToolbarKeys(toolbarKeys.filterIndexed { i, _ -> i != index }) }
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                TextButton(
                    onClick = { showPicker = true },
                    enabled = toolbarKeys.size < ToolbarConfig.MAX_TOOLBAR_KEYS
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(
                        text = stringResource(R.string.toolbar_add_button),
                        modifier = Modifier.padding(start = 6.dp)
                    )
                }
                TextButton(onClick = { persistToolbarKeys(ToolbarConfig.DEFAULT_KEYS) }) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(
                        text = stringResource(R.string.toolbar_reset_defaults),
                        modifier = Modifier.padding(start = 6.dp)
                    )
                }
            }

            Text(
                text = stringResource(
                    R.string.toolbar_keys_count,
                    toolbarKeys.size,
                    ToolbarConfig.MAX_TOOLBAR_KEYS
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }
    }

    if (showPicker) {
        ToolbarCommandPicker(
            existing = toolbarKeys.toSet(),
            onDismiss = { showPicker = false },
            onPick = { name ->
                // Appended at the end rather than inserted, so the user sees where it went and can
                // then move it; the row is already on screen with working up/down controls.
                persistToolbarKeys(toolbarKeys + name)
                showPicker = false
            }
        )
    }
}

/** One row of the current button list: its localized name, reorder controls, and remove. */
@Composable
private fun ToolbarKeyRow(
    commandName: String,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit
) {
    val context = LocalContext.current
    val command = remember(commandName) { CommandRegistry.getByName(commandName) }
    // The registry carries a localized name per command, so the toolbar UI adds no label strings
    // of its own and is translated wherever the command itself is.
    val label = command?.let { context.getString(it.nameRes) } ?: commandName

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp)
        )
        IconButton(onClick = onMoveUp, enabled = canMoveUp) {
            Icon(
                Icons.Filled.KeyboardArrowUp,
                contentDescription = stringResource(R.string.toolbar_move_up)
            )
        }
        IconButton(onClick = onMoveDown, enabled = canMoveDown) {
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = stringResource(R.string.toolbar_move_down)
            )
        }
        IconButton(onClick = onRemove) {
            Icon(
                Icons.Filled.Clear,
                contentDescription = stringResource(R.string.toolbar_remove_button)
            )
        }
    }
}

/**
 * Searchable picker over the whole command catalogue.
 *
 * Deliberately not `CommandPaletteDialog`: that dialog also offers TEXT, INTENT, TIMESTAMP and
 * TEMPLATE actions, none of which the toolbar can store (a button is a command name), so reusing
 * it would mean presenting choices this screen then has to reject.
 *
 * `CommandRegistry.search` is not reusable here either — it is `internal` and takes a `ResultText`
 * from the prediction pipeline, not a plain query string.
 */
@Composable
private fun ToolbarCommandPicker(
    existing: Set<String>,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit
) {
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    val all = remember { CommandRegistry.ALL_COMMANDS }

    val matches = remember(query, all) {
        if (query.isBlank()) {
            all
        } else {
            all.filter { c ->
                c.name.contains(query, ignoreCase = true) ||
                    c.keywords.any { it.contains(query, ignoreCase = true) } ||
                    context.getString(c.nameRes).contains(query, ignoreCase = true)
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.toolbar_picker_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.toolbar_picker_search_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (matches.isEmpty()) {
                    // A plain string rather than a "%d results" count: a count string needs
                    // <plurals> to be correct under Slavic/Baltic rules, and the project converts
                    // them for exactly that reason (see TranslationCoverageDriftTest's
                    // convertedCountPlurals). Not worth that machinery for a search hint.
                    Text(
                        text = stringResource(R.string.toolbar_picker_no_results),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                ) {
                    items(matches, key = { it.name }) { command ->
                        val alreadyAdded = command.name in existing
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !alreadyAdded) { onPick(command.name) }
                                .padding(vertical = 8.dp, horizontal = 4.dp)
                        ) {
                            Text(
                                text = context.getString(command.nameRes),
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (alreadyAdded) {
                                    MaterialTheme.colorScheme.outline
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                }
                            )
                            Text(
                                text = if (alreadyAdded) {
                                    stringResource(R.string.toolbar_picker_already_added)
                                } else {
                                    context.getString(command.descriptionRes)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                                maxLines = 2
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}

/** Swap two positions, clamped, returning a new list. A no-op when the target is out of range. */
private fun swap(keys: List<String>, from: Int, to: Int): List<String> {
    if (to < 0 || to >= keys.size || from == to) return keys
    val out = keys.toMutableList()
    out[from] = keys[to]
    out[to] = keys[from]
    return out
}

/**
 * Write the ordered list to both the Compose state and the preference.
 *
 * An extension on [SettingsActivity] so it closes over `saveSetting`; not `@Composable`, so it can
 * be called from the click lambdas above without a composable context.
 */
private fun SettingsActivity.persistToolbarKeys(keys: List<String>) {
    toolbarKeys = keys
    saveSetting(ToolbarConfig.PREF_KEYS, ToolbarConfig.serialize(keys))
}
