package com.karelisio.fichio.settings

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import com.karelisio.fichio.core.util.Strings
import com.karelisio.fichio.R
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Animation
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Gavel
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.ManageSearch
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Update
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.ViewColumn
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.karelisio.fichio.container
import com.karelisio.fichio.core.model.SortBy
import com.karelisio.fichio.core.model.ViewMode
import com.karelisio.fichio.core.util.Format
import com.karelisio.fichio.data.prefs.FichioSettings
import com.karelisio.fichio.data.prefs.SettingsRepository
import com.karelisio.fichio.data.prefs.ThemeMode
import com.karelisio.fichio.onboarding.PermissionsCatalog
import com.karelisio.fichio.onboarding.rememberPermissionSnapshot
import com.karelisio.fichio.ui.components.BackButton
import com.karelisio.fichio.ui.components.ListContentPadding
import com.karelisio.fichio.ui.components.rememberHaptics
import com.karelisio.fichio.update.UpdateChecker
import com.karelisio.fichio.update.UpdateManager
import com.karelisio.fichio.update.UpdateState
import kotlinx.coroutines.launch

private const val POLARO_PACKAGE = "com.karelisio.polaro"

@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenPermissions: () -> Unit) {
    val context = LocalContext.current
    val app = context.container
    val repo = app.settings
    val s by repo.settings.collectAsStateWithLifecycle(FichioSettings())
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val updateState by UpdateManager.state.collectAsStateWithLifecycle()
    val perms by rememberPermissionSnapshot()
    val missing = perms.count { !it.value }
    val polaroInstalled = remember {
        runCatching { context.packageManager.getPackageInfo(POLARO_PACKAGE, 0); true }.getOrDefault(false)
    }
    var portDialog by remember { mutableStateOf(false) }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    fun set(block: suspend SettingsRepository.() -> Unit) { scope.launch { repo.block() } }

    val groups: List<Pair<String, List<SettingRow>>> = listOf(
        stringResource(R.string.settings_appearance) to listOf(
            customRow(Icons.Rounded.DarkMode, stringResource(R.string.settings_theme)) {
                ChoiceButtons(
                    options = listOf(ThemeMode.SYSTEM to stringResource(R.string.settings_theme_system), ThemeMode.LIGHT to stringResource(R.string.settings_theme_light), ThemeMode.DARK to stringResource(R.string.settings_theme_dark)),
                    selected = s.themeMode,
                ) { set { setThemeMode(it) } }
            },
            switchRow(Icons.Rounded.Palette, stringResource(R.string.settings_dynamic_color), stringResource(R.string.settings_dynamic_color_desc), s.dynamicColor) { set { setDynamicColor(it) } },
            switchRow(Icons.Rounded.Animation, stringResource(R.string.settings_reduce_motion), stringResource(R.string.settings_reduce_motion_desc), s.reduceMotion) { set { setReduceMotion(it) } },
            switchRow(Icons.Rounded.Vibration, stringResource(R.string.settings_haptics), stringResource(R.string.settings_haptics_desc), s.haptics) { set { setHaptics(it) } },
        ),
        stringResource(R.string.settings_navigation) to listOf(
            customRow(Icons.Rounded.GridView, stringResource(R.string.settings_default_view)) {
                ChoiceButtons(
                    options = listOf(ViewMode.LIST to stringResource(R.string.view_list), ViewMode.GRID to stringResource(R.string.view_grid), ViewMode.LARGE to stringResource(R.string.view_thumbnails)),
                    selected = s.defaultViewMode,
                ) { set { setDefaultViewMode(it) } }
            },
            customRow(Icons.AutoMirrored.Rounded.Sort, stringResource(R.string.settings_default_sort), if (s.defaultSort.ascending) stringResource(R.string.sort_ascending_order) else stringResource(R.string.sort_descending_order)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChoiceButtons(
                        options = listOf(SortBy.NAME to stringResource(R.string.sort_name), SortBy.DATE to stringResource(R.string.sort_date), SortBy.SIZE to stringResource(R.string.sort_size), SortBy.TYPE to stringResource(R.string.sort_type)),
                        selected = s.defaultSort.by,
                    ) { set { setDefaultSort(s.defaultSort.copy(by = it)) } }
                    ChoiceButtons(
                        options = listOf(true to stringResource(R.string.sort_ascending), false to stringResource(R.string.sort_descending)),
                        selected = s.defaultSort.ascending,
                    ) { set { setDefaultSort(s.defaultSort.copy(ascending = it)) } }
                }
            },
            switchRow(Icons.Rounded.CreateNewFolder, stringResource(R.string.browser_folders_first), null, s.defaultSort.foldersFirst) {
                set { setDefaultSort(s.defaultSort.copy(foldersFirst = it)) }
            },
            switchRow(Icons.Rounded.Visibility, stringResource(R.string.browser_hidden_files), stringResource(R.string.settings_hidden_desc), s.showHidden) { set { setShowHidden(it) } },
            switchRow(Icons.Rounded.ViewColumn, stringResource(R.string.settings_dual_pane), stringResource(R.string.settings_dual_pane_desc), s.dualPaneInLandscape) { set { setDualPaneInLandscape(it) } },
        ),
        stringResource(R.string.settings_files) to listOf(
            switchRow(Icons.Rounded.Warning, stringResource(R.string.settings_confirm_delete), null, s.confirmDelete) { set { setConfirmDelete(it) } },
            customRow(Icons.Rounded.Layers, stringResource(R.string.settings_concurrent_ops), stringResource(R.string.settings_concurrent_ops_desc)) {
                ChoiceButtons(
                    options = (1..SettingsRepository.MAX_CONCURRENT_OPERATIONS).map { it to "$it" },
                    selected = s.concurrentOperations,
                ) { set { setConcurrentOperations(it) } }
            },
            switchRow(Icons.Rounded.Delete, stringResource(R.string.shortcut_trash), stringResource(R.string.settings_trash_desc), s.trashEnabled) { set { setTrashEnabled(it) } },
            intSliderRow(
                Icons.Rounded.DeleteSweep, stringResource(R.string.settings_trash_retention), 1..90, s.trashRetentionDays.coerceIn(1, 90),
                enabled = s.trashEnabled, label = { Strings.plural(R.plurals.settings_days, it) },
            ) { v -> set { setTrashRetentionDays(v) } },
            switchRow(
                Icons.Rounded.Image, stringResource(R.string.settings_polaro),
                if (polaroInstalled) stringResource(R.string.settings_polaro_desc) else stringResource(R.string.settings_polaro_missing),
                s.openImagesInPolaro && polaroInstalled, enabled = polaroInstalled,
            ) { set { setOpenImagesInPolaro(it) } },
        ),
        stringResource(R.string.settings_search) to listOf(
            switchRow(Icons.Rounded.ManageSearch, stringResource(R.string.settings_search_index), stringResource(R.string.settings_search_index_desc), s.searchIndexEnabled) { set { setSearchIndexEnabled(it) } },
            clickRow(
                Icons.Rounded.Refresh, stringResource(R.string.settings_rebuild_index),
                if (s.lastIndexRun > 0) stringResource(R.string.settings_last_index, Format.relative(s.lastIndexRun)) else stringResource(R.string.settings_never_indexed),
                enabled = s.searchIndexEnabled,
            ) {
                runCatching { app.indexer.requestFullScan() }
                scope.launch { snackbar.showSnackbar(Strings.get(R.string.settings_indexing_started)) }
            },
        ),
        stringResource(R.string.settings_analysis) to listOf(
            stepSliderRow(
                Icons.Rounded.Straighten, stringResource(R.string.settings_large_threshold), listOf(1, 5, 10, 25, 50, 100, 250, 500, 1000, 2000),
                s.largeFileThresholdMb, label = { if (it >= 1000) Strings.get(R.string.settings_gb_value, it / 1000) else Strings.get(R.string.settings_mb_value, it) },
            ) { v -> set { setLargeFileThresholdMb(v) } },
            stepSliderRow(
                Icons.Rounded.History, stringResource(R.string.settings_old_after), listOf(7, 30, 90, 180, 365, 730, 1095),
                s.oldFileThresholdDays, label = { if (it >= 365) Strings.plural(R.plurals.settings_years, it / 365) else Strings.get(R.string.settings_days_short, it) },
            ) { v -> set { setOldFileThresholdDays(v) } },
        ),
        stringResource(R.string.settings_transfer) to listOf(
            clickRow(Icons.Rounded.Router, stringResource(R.string.settings_port), stringResource(R.string.settings_port_desc),
                trailing = { Text("${s.transferPort}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary) },
            ) { portDialog = true },
            stepSliderRow(
                Icons.Rounded.Timer, stringResource(R.string.settings_idle_stop), listOf(1, 5, 10, 15, 30, 60, 120),
                s.transferIdleMinutes, label = { if (it >= 60) Strings.get(R.string.settings_hours_value, it / 60) else Strings.get(R.string.settings_minutes_value, it) },
            ) { v -> set { setTransferIdleMinutes(v) } },
        ),
        stringResource(R.string.channel_updates) to listOf(
            switchRow(Icons.Rounded.Update, stringResource(R.string.settings_auto_check), stringResource(R.string.settings_auto_check_desc), s.autoCheckUpdates) { set { setAutoCheckUpdates(it) } },
            clickRow(
                Icons.Rounded.SystemUpdate, stringResource(R.string.settings_check_now), updateSubtitle(updateState, s.lastUpdateCheck),
                trailing = { UpdateTrailing(updateState) },
            ) {
                if (updateState is UpdateState.Available) UpdateManager.showSheet() else UpdateManager.checkNow(context, force = true)
            },
            infoRow(Icons.Rounded.Info, stringResource(R.string.settings_current_version), UpdateManager.currentVersion),
        ),
        stringResource(R.string.perm_title) to listOf(
            clickRow(
                Icons.Rounded.Security, stringResource(R.string.perm_title),
                if (missing == 0) stringResource(R.string.perm_all_good) else pluralStringResource(R.plurals.settings_permissions_to_check, missing, missing),
                trailing = { Icon(Icons.AutoMirrored.Rounded.ArrowForward, null) },
                onClick = onOpenPermissions,
            ),
        ),
        stringResource(R.string.settings_about) to listOf(
            infoRow(Icons.Rounded.AutoAwesome, stringResource(R.string.settings_app_version, UpdateManager.currentVersion), stringResource(R.string.settings_tagline)),
            infoRow(Icons.Rounded.CloudOff, stringResource(R.string.settings_local), stringResource(R.string.settings_local_desc)),
            infoRow(Icons.Rounded.Gavel, stringResource(R.string.settings_license), stringResource(R.string.settings_license_desc)),
            clickRow(Icons.Rounded.Code, stringResource(R.string.settings_source), UpdateChecker.repoUrl.removePrefix("https://")) {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(UpdateChecker.repoUrl))) }
            },
        ),
    )

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            MediumFlexibleTopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                subtitle = { Text(stringResource(R.string.settings_app_version, UpdateManager.currentVersion)) },
                navigationIcon = { BackButton(onBack) },
                scrollBehavior = scroll,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + ListContentPadding.calculateBottomPadding()),
        ) {
            itemsIndexed(groups, key = { _, g -> g.first }) { i, (title, rows) -> SettingsGroup(title, i, rows) }
        }
    }

    if (portDialog) PortDialog(s.transferPort, onDismiss = { portDialog = false }) { p ->
        portDialog = false
        set { setTransferPort(p) }
    }
}

private fun updateSubtitle(state: UpdateState, lastCheck: Long): String = when (state) {
    UpdateState.Checking -> Strings.get(R.string.settings_update_checking)
    is UpdateState.UpToDate -> Strings.get(R.string.settings_update_up_to_date)
    is UpdateState.Available -> Strings.get(R.string.settings_update_available, state.update.version)
    is UpdateState.Downloading -> Strings.get(R.string.settings_update_downloading, state.update.version)
    is UpdateState.Installing -> Strings.get(R.string.settings_update_installing)
    is UpdateState.Error -> state.message
    UpdateState.Idle -> if (lastCheck > 0) Strings.get(R.string.settings_update_last_check, Format.relative(lastCheck)) else Strings.get(R.string.settings_update_never)
}

@Composable
private fun UpdateTrailing(state: UpdateState) {
    AnimatedContent(
        targetState = state::class,
        transitionSpec = { (scaleIn() + fadeIn()) togetherWith fadeOut() },
        label = "updTrail",
    ) { k ->
        when (k) {
            UpdateState.Checking::class, UpdateState.Downloading::class, UpdateState.Installing::class -> LoadingIndicator(Modifier.size(32.dp))
            UpdateState.Available::class -> Icon(Icons.Rounded.SystemUpdate, null, tint = MaterialTheme.colorScheme.primary)
            else -> Spacer(Modifier.size(1.dp))
        }
    }
}

@Composable
private fun <T> ChoiceButtons(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    val haptics = rememberHaptics()
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, (value, label) ->
            SegmentedButton(
                selected = value == selected,
                onClick = { haptics.segment(); onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
                label = { Text(label, maxLines = 1) },
            )
        }
    }
}

@Composable
private fun PortDialog(current: Int, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    var text by remember { mutableStateOf(current.toString()) }
    val value = text.toIntOrNull()
    val valid = value != null && value in 1024..65535
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Wifi, null) },
        title = { Text(stringResource(R.string.settings_port)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { t -> text = t.filter(Char::isDigit).take(5) },
                singleLine = true,
                isError = !valid,
                supportingText = { Text(stringResource(R.string.settings_port_range)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        },
        confirmButton = { TextButton(onClick = { value?.let(onConfirm) }, enabled = valid) { Text(stringResource(R.string.common_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
fun PermissionsScreen(onBack: () -> Unit) {
    PermissionsList(onBack)
}
