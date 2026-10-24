package tribixbite.cleverkeys.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import tribixbite.cleverkeys.R
import tribixbite.cleverkeys.SettingsActivity
import tribixbite.cleverkeys.ui.settings.sections.AccessibilitySection
import tribixbite.cleverkeys.ui.settings.sections.ActivitiesSection
import tribixbite.cleverkeys.ui.settings.sections.AdvancedSection
import tribixbite.cleverkeys.ui.settings.sections.AppearanceSection
import tribixbite.cleverkeys.ui.settings.sections.AutoCorrectionSection
import tribixbite.cleverkeys.ui.settings.sections.BackupRestoreSection
import tribixbite.cleverkeys.ui.settings.sections.ClipboardSection
import tribixbite.cleverkeys.ui.settings.sections.GestureTuningSection
import tribixbite.cleverkeys.ui.settings.sections.GifPanelSection
import tribixbite.cleverkeys.ui.settings.sections.HelpSection
import tribixbite.cleverkeys.ui.settings.sections.InputBehaviorSection
import tribixbite.cleverkeys.ui.settings.sections.MultiLanguageSection
import tribixbite.cleverkeys.ui.settings.sections.SwipeTypingSection
import tribixbite.cleverkeys.ui.settings.sections.PrivacySection
import tribixbite.cleverkeys.ui.settings.sections.SwipeTrailSection
import tribixbite.cleverkeys.ui.settings.sections.OneHandedSection
import tribixbite.cleverkeys.ui.settings.sections.ToolbarSection
import tribixbite.cleverkeys.ui.settings.sections.TestKeyboardSection
import tribixbite.cleverkeys.ui.settings.sections.VersionActionsSection

/**
 * The settings container (phase 4 part 4): a navigation drawer of the nineteen sections and a
 * content pane showing ONE of them.
 *
 * The owner's verdict on the old screen — "the settings UI is amateurish … instead of putting
 * everything on vertical" — was about every section stacked in one scrolling column: the search
 * bar scrolled away with the content, every control lived below the fold of the section before
 * it, and the CI hunts needed twelve-scroll budgets to climb between two switches.
 *
 * Only the container changed. The section composables, their collapsible headers, the
 * persistence layer and the generated search index are untouched; [SettingsDrawerItems.ORDER]
 * carries the old stacking order into the drawer, and the search field is pinned between the top
 * app bar and the content, so it can never scroll away again. A search result now also SELECTS
 * its section ([executeSearchAction]), which is what keeps the §7d hunt's search route working
 * with one section on screen.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun SettingsActivity.SettingsScreen() {
        val scrollState = rememberScrollState()
        // Store references for scroll-to-setting functionality.
        // composeScope has MonotonicFrameClock needed for animateScrollTo.
        //
        // Published from SideEffect, not from the composition body (ARC-048): a body write
        // also runs for compositions that are later ABANDONED, and the scope from an
        // abandoned composition is cancelled — so the Activity kept a dead CoroutineScope
        // and every later scrollToSetting() silently no-opped. SideEffect runs only after a
        // composition is successfully applied, and still before this frame's layout pass, so
        // the onGloballyPositioned readers in SettingsControls see the same values as before.
        val scope = rememberCoroutineScope()
        SideEffect {
            mainScrollState = scrollState
            composeScope = scope
        }

        val drawerState = rememberDrawerState(DrawerValue.Closed)

        // A new section starts at its top: the scroll offset belongs to the section that was
        // just left, and keeping it would land the next visitor mid-pane (or below its end).
        LaunchedEffect(selectedSectionKey) { scrollState.scrollTo(0) }

        // Collected Data Viewer Dialog
        if (showCollectedDataViewer) {
            CollectedDataViewerDialog(
                dataList = collectedDataList,
                stats = collectedDataStats,
                onDismiss = { showCollectedDataViewer = false }
            )
        }

        // Performance Stats Viewer Dialog
        if (showPerfStatsViewer) {
            PerfStatsViewerDialog(
                summary = perfStatsSummary,
                onDismiss = { showPerfStatsViewer = false }
            )
        }

        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet {
                    Spacer(Modifier.height(16.dp))
                    // The old column's header, moved into the drawer: the title and the one-line
                    // description every screen used to scroll past.
                    Text(
                        text = stringResource(R.string.settings_title),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                    Text(
                        text = stringResource(R.string.settings_description),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 18.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                    Spacer(Modifier.height(8.dp))
                    SettingsDrawerItems.ORDER.forEach { key ->
                        NavigationDrawerItem(
                            // Section titles carry their leading emoji ("🎨 Appearance"), which is
                            // exactly how the collapsible headers have always read.
                            label = { Text(stringResource(sectionTitleRes(key))) },
                            selected = key == selectedSectionKey,
                            onClick = {
                                selectedSectionKey = key
                                // Selecting a collapsed section would show its header and nothing
                                // else; the drawer promises the section, so open it.
                                expanderFor(key)()
                                scope.launch { drawerState.close() }
                            }
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .statusBarsPadding()
                    .navigationBarsPadding()
            ) {
                // Zero insets: the surrounding Column already applied the system-bar padding, and
                // the default TopAppBar insets would apply it a second time.
                TopAppBar(
                    title = { Text(stringResource(R.string.settings_title)) },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(
                                imageVector = Icons.Default.Menu,
                                contentDescription = stringResource(R.string.settings_drawer_open_desc)
                            )
                        }
                    },
                    windowInsets = WindowInsets(0, 0, 0, 0)
                )

                // Settings Search Bar — pinned between the app bar and the content pane, so the
                // complaint it answers ("the search bar scrolls away") cannot come back.
                val filteredSettings = getFilteredSettings(settingsSearchQuery)
                val showResults = settingsSearchQuery.isNotBlank() && filteredSettings.isNotEmpty()

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    OutlinedTextField(
                        value = settingsSearchQuery,
                        onValueChange = { query ->
                            settingsSearchQuery = query
                        },
                        label = { Text(stringResource(R.string.settings_search_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = stringResource(R.string.common_search)
                            )
                        },
                        trailingIcon = {
                            if (settingsSearchQuery.isNotBlank()) {
                                IconButton(onClick = {
                                    settingsSearchQuery = ""
                                }) {
                                    Icon(
                                        imageVector = Icons.Default.Clear,
                                        contentDescription = stringResource(R.string.common_clear)
                                    )
                                }
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline
                        ),
                        shape = RoundedCornerShape(12.dp)
                    )

                    // Search Results - always below search field, scrollable
                    // Uses nestedScroll barrier to prevent scroll propagation to parent
                    AnimatedVisibility(
                        visible = showResults,
                        enter = expandVertically(),
                        exit = shrinkVertically()
                    ) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 200.dp)
                                .nestedScroll(searchResultsNestedScrollConnection),
                            shape = RoundedCornerShape(8.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Column(
                                modifier = Modifier
                                    .verticalScroll(rememberScrollState())
                                    .padding(4.dp)
                            ) {
                                filteredSettings.forEach { setting ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                executeSearchAction(setting)
                                                settingsSearchQuery = ""
                                            }
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = setting.title,
                                                fontWeight = FontWeight.Medium,
                                                fontSize = 14.sp
                                            )
                                            Text(
                                                text = stringResource(R.string.settings_search_in_section, setting.sectionName),
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // The content pane: ONE section, scrollable on its own. The viewport's top in
                // root space: positions recorded for search scrolling are made relative to it
                // (contentYOf), or every scroll overshoots by this offset.
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp)
                        .onGloballyPositioned { scrollViewportTop = it.positionInRoot().y }
                        .verticalScroll(scrollState),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // The key vocabulary is closed: SettingsDrawerItems.ORDER feeds the drawer,
                    // the generated search index and executeSearchAction, and the else mirrors
                    // sectionTitleRes's own fallback so an unknown key can never show nothing.
                    when (selectedSectionKey) {
                        "testKeyboard" -> TestKeyboardSection()
                        "activities" -> ActivitiesSection()
                        "swipeTyping" -> SwipeTypingSection()
                        "appearance" -> AppearanceSection()
                        "swipeTrail" -> SwipeTrailSection()
                        "toolbar" -> ToolbarSection()
                        "oneHanded" -> OneHandedSection()
                        "input" -> InputBehaviorSection()
                        "swipeCorrections" -> AutoCorrectionSection()
                        "gestureTuning" -> GestureTuningSection()
                        "accessibility" -> AccessibilitySection()
                        "clipboard" -> ClipboardSection()
                        "gif" -> GifPanelSection()
                        "backupRestore" -> BackupRestoreSection()
                        "multiLang" -> MultiLanguageSection()
                        "privacy" -> PrivacySection()
                        "advanced" -> AdvancedSection()
                        "info" -> VersionActionsSection()
                        "help" -> HelpSection()
                        else -> AdvancedSection()
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
