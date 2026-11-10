@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package tribixbite.cleverkeys.ui.settings

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tribixbite.cleverkeys.SettingsActivity
import tribixbite.cleverkeys.sliderSteps

// Composable helper components
//
// 2026-11-09 (UI/UX round): a section renders its CONTENT only. The title moved to the top app
// bar and the collapse chevron is gone -- see SettingsPane below and SectionHeaderPolicy for the
// three defects the old titled, collapsible card produced.
/**
 * A settings section's content card.
 *
 * **This deliberately renders no title and no collapse chevron.** The drawer names the section and
 * the top app bar repeats that name in context, so a header here was the third copy -- and a
 * clickable one at that: with exactly one section per pane, collapsing it emptied the screen and
 * left a title with nothing under it. [SectionHeaderPolicy] documents the full reasoning,
 * including the "the pane never said which section you were in" defect that came from the same
 * arrangement.
 *
 * [sectionId] is still honoured: it is the scroll target for search results that land on the
 * section itself rather than on a control inside it.
 */
@Composable
internal fun SettingsActivity.SettingsPane(
    sectionId: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .let { m ->
                if (sectionId != null) m.onGloballyPositioned { coords ->
                    // The read MUST stay inside this layout lambda (issue #79): `ScrollState.value`
                    // is snapshot-backed, so hoisting it into the composition body subscribes the
                    // section to every scroll PIXEL and re-diffs the whole node chain mid-scroll.
                    recordSettingPosition(sectionId, contentYOf(coords))
                } else m
            },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            content()
        }
    }
}

/**
 * Retained name for the nineteen existing section call sites; renders [SettingsPane].
 *
 * The three unused parameters are kept so this change did not have to touch every section in the
 * same commit. They are inert by design, which is what [SectionHeaderPolicy.isCollapsibleInPane]
 * and [SectionHeaderPolicy.showsOwnTitle] pin.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
internal fun SettingsActivity.CollapsibleSettingsSection(
    title: String,
    expanded: Boolean,
    onExpandChange: (Boolean) -> Unit,
    sectionId: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    SettingsPane(sectionId = sectionId, content = content)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SettingsActivity.SettingsSwitch(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    highlightId: String? = null,
    /**
     * False renders the switch VISIBLE but inert and dimmed — for a control whose prerequisite
     * toggle is off (audit 2026-08-26: the next-word switch was HIDDEN when the context LM was
     * off, which left a stale-on pref with no visible owner and made the search entry a dead
     * end). Visible-but-disabled keeps the stored state honest, keeps the control findable by
     * search, and lets the adjacent prerequisite toggle explain itself. Still composed, so its
     * scroll position registers either way.
     */
    enabled: Boolean = true
) {
    // Pulse animation for highlighting. Every control is reachable by the locale-independent
    // setting ids of its title (the generated search entries' settingId, see settingIdsFor);
    // gated entries also highlight via highlightId.
    val regIds = settingIdsFor(title)
    val isHighlighted = highlightedSettingId in regIds ||
        (highlightId != null && highlightedSettingId == highlightId)
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = if (isHighlighted) 1f else 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(400),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )
    val highlightColor = MaterialTheme.colorScheme.primary.copy(alpha = if (isHighlighted) pulseAlpha * 0.3f else 0f)
    val borderColor = MaterialTheme.colorScheme.primary.copy(alpha = if (isHighlighted) 0.5f + pulseAlpha * 0.5f else 0f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { coordinates ->
                // Record position for scroll targeting. Always register under the title's
                // setting ids (the generated search entries' settingId); also under an
                // explicit highlightId when given (gated-setting highlight targets).
                val y = contentYOf(coordinates)
                regIds.forEach { recordSettingPosition(it, y) }
                if (highlightId != null && highlightId !in regIds) recordSettingPosition(highlightId, y)
            }
            .then(
                if (isHighlighted) {
                    Modifier
                        .background(highlightColor, RoundedCornerShape(8.dp))
                        .border(2.dp, borderColor, RoundedCornerShape(8.dp))
                } else Modifier
            )
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(vertical = 8.dp, horizontal = if (isHighlighted) 8.dp else 0.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Material's disabled-content convention (38% alpha) — the row must read as inert,
        // not merely as an off switch.
        val contentAlpha = if (enabled) 1f else 0.38f
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = contentAlpha),
                fontSize = 16.sp
            )
            Text(
                text = description,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
                fontSize = 12.sp
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled
        )
    }
}

@Composable
internal fun SettingsActivity.SettingsSlider(
    title: String,
    description: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    /** Distance between two stops, in [value]'s unit; the Compose step count is derived from it. */
    increment: Float,
    onValueChange: (Float) -> Unit,
    displayValue: String,
    /** False = visible but inert and dimmed; see [SettingsSwitch]'s `enabled` for the rationale. */
    enabled: Boolean = true
) {
    // Register a scroll position under the title's setting ids so search results can scroll here.
    val regIds = settingIdsFor(title)
    val contentAlpha = if (enabled) 1f else 0.38f
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { coordinates ->
                val y = contentYOf(coordinates)
                regIds.forEach { recordSettingPosition(it, y) }
            }
            .then(
                if (highlightedSettingId in regIds)
                    Modifier.background(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                        RoundedCornerShape(8.dp)
                    )
                else Modifier
            )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Same wrap-before-collide rule as the section header: a long translated title
            // must not push the value readout off the row or overlap it.
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = contentAlpha),
                fontSize = 16.sp,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp)
            )
            Text(
                text = displayValue,
                color = MaterialTheme.colorScheme.primary.copy(alpha = contentAlpha),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Text(
            text = description,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
            fontSize = 12.sp,
            modifier = Modifier.padding(vertical = 4.dp)
        )

        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = sliderSteps(valueRange, increment),
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        )
    }
}

@Composable
internal fun SettingsActivity.SettingsDropdown(
    title: String,
    description: String,
    options: List<String>,
    selectedIndex: Int,
    onSelectionChange: (Int) -> Unit,
    /** False = visible but inert and dimmed; see [SettingsSwitch]'s `enabled` for the rationale. */
    enabled: Boolean = true
) {
    var expanded by remember { mutableStateOf(false) }

    // Register a scroll position under the title's setting ids so search results can scroll here.
    val regIds = settingIdsFor(title)
    val contentAlpha = if (enabled) 1f else 0.38f
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { coordinates ->
                val y = contentYOf(coordinates)
                regIds.forEach { recordSettingPosition(it, y) }
            }
            .then(
                if (highlightedSettingId in regIds)
                    Modifier.background(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                        RoundedCornerShape(8.dp)
                    )
                else Modifier
            )
    ) {
        Text(
            text = title,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = contentAlpha),
            fontSize = 16.sp
        )
        Text(
            text = description,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
            fontSize = 12.sp,
            modifier = Modifier.padding(vertical = 4.dp)
        )

        ExposedDropdownMenuBox(
            expanded = expanded,
            // Gate the open gesture, not just the field: the menu box's own toggle is what
            // expands the popup, and a disabled control must not open one.
            onExpandedChange = { if (enabled) expanded = !expanded }
        ) {
            OutlinedTextField(
                value = options[selectedIndex],
                onValueChange = {},
                readOnly = true,
                enabled = enabled,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .menuAnchor()
                    .fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface
                )
            )

            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                options.forEachIndexed { index, option ->
                    DropdownMenuItem(
                        text = { Text(option) },
                        onClick = {
                            onSelectionChange(index)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}
