package com.karelisio.fichio.ui.browser

import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import com.karelisio.fichio.core.util.Strings
import com.karelisio.fichio.R
import android.content.res.Configuration
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.automirrored.rounded.Label
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AddHome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.NoteAdd
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarOutline
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Tab
import androidx.compose.material.icons.rounded.Unarchive
import androidx.compose.material.icons.rounded.ViewList
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleFloatingActionButton
import androidx.compose.material3.ToggleFloatingActionButtonDefaults.animateIcon
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.animateFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.karelisio.fichio.archive.CompressSheet
import com.karelisio.fichio.archive.ExtractSheet
import com.karelisio.fichio.container
import com.karelisio.fichio.core.fs.StoragePaths
import com.karelisio.fichio.core.model.FileEntry
import com.karelisio.fichio.core.model.FileType
import com.karelisio.fichio.core.model.SortBy
import com.karelisio.fichio.core.model.ViewMode
import com.karelisio.fichio.core.util.Format
import com.karelisio.fichio.ops.BatchRenameSheet
import com.karelisio.fichio.ops.NewItemDialog
import com.karelisio.fichio.ops.OperationsIndicator
import com.karelisio.fichio.ops.OperationsSheet
import com.karelisio.fichio.ops.PropertiesSheet
import com.karelisio.fichio.ops.RenameDialog
import com.karelisio.fichio.preview.MiniPlayerBar
import com.karelisio.fichio.shortcuts.Shortcuts
import com.karelisio.fichio.tags.TagAssignSheet
import com.karelisio.fichio.ui.components.AnimatedCounter
import com.karelisio.fichio.ui.components.FileThumbnail
import com.karelisio.fichio.ui.components.bouncy
import com.karelisio.fichio.ui.components.flyTarget
import com.karelisio.fichio.ui.components.rememberHaptics
import com.karelisio.fichio.ui.navigation.LocalPickerRequest
import com.karelisio.fichio.ui.navigation.LocalPickerResult
import com.karelisio.fichio.ui.navigation.Opener
import com.karelisio.fichio.ui.navigation.Route
import com.karelisio.fichio.ui.navigation.openEntry
import kotlinx.coroutines.launch

private sealed interface BrowserDialog {
    data class Rename(val entry: FileEntry) : BrowserDialog
    data class BatchRename(val entries: List<FileEntry>) : BrowserDialog
    data class NewItem(val parent: String, val folder: Boolean) : BrowserDialog
    data class Properties(val entries: List<FileEntry>) : BrowserDialog
    data class Compress(val entries: List<FileEntry>, val dest: String) : BrowserDialog
    data class Extract(val path: String) : BrowserDialog
    data class Delete(val entries: List<FileEntry>) : BrowserDialog
    data class Tags(val paths: List<String>) : BrowserDialog
    data object Operations : BrowserDialog
}

@Composable
fun BrowserScreen(nav: NavHostController, initialPath: String?, newTab: Boolean) {
    val vm: BrowserViewModel = viewModel()
    val context = LocalContext.current
    val c = context.container
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val picker = LocalPickerRequest.current
    val pickerResult = LocalPickerResult.current

    LaunchedEffect(initialPath, newTab) { vm.init(initialPath, newTab) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        vm.current?.refresh(); vm.secondary?.refresh(); c.volumes.refresh()
    }

    val settings by vm.settings.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val volumes by vm.volumes.collectAsStateWithLifecycle()
    val tagSlots by vm.tagSlots.collectAsStateWithLifecycle()
    val clip by FileClipboard.content.collectAsStateWithLifecycle()

    val config = LocalConfiguration.current
    val dual = settings.dualPaneInLandscape && picker == null &&
        config.orientation == Configuration.ORIENTATION_LANDSCAPE && config.screenWidthDp >= 600
    LaunchedEffect(dual) { if (dual) vm.ensureSecondary() }

    val pane = vm.activeState(dual) ?: return
    val main = vm.current ?: return
    var dialog by remember { mutableStateOf<BrowserDialog?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val drag = remember { PaneDragState() }

    fun openFile(e: FileEntry) {
        if (picker != null) {
            if (picker.allowMultiple) pane.toggle(e) else pickerResult(listOf(e.path))
            return
        }
        if (e.type == FileType.IMAGE && settings.openImagesInPolaro && Opener.isPolaroInstalled(context)) {
            c.recents.record(e.path)
            if (Opener.openInPolaro(context, e.path)) return
        }
        nav.openEntry(context, e)
    }

    fun paste(dest: String) {
        val content = clip ?: return
        val paths = content.entries.map { it.path }
        if (content.cut) { c.operations.move(paths, dest); FileClipboard.clear() } else c.operations.copy(paths, dest)
        haptics.confirm()
    }

    fun drop(entries: List<FileEntry>, dest: String) {
        val paths = entries.map { it.path }
        // Same volume → move, otherwise copy (like most desktop file managers).
        val sameVolume = volumes.firstOrNull { dest.startsWith(it.path) }?.path == volumes.firstOrNull { paths.first().startsWith(it.path) }?.path
        if (sameVolume) c.operations.move(paths, dest) else c.operations.copy(paths, dest)
        vm.tabs.forEach { it.clearSelection() }; vm.secondary?.clearSelection()
    }

    // Row swipes (list view): left = trash (undo snackbar from the operation events),
    // right = share a file / (un)favourite a folder. Not offered in the picker.
    val swipe = if (picker != null) null else remember(settings.trashEnabled, favorites) {
        SwipeActions(
            onDelete = { e ->
                if (!settings.trashEnabled) dialog = BrowserDialog.Delete(listOf(e))
                else c.operations.trash(listOf(e.path))
            },
            onSecondary = { e ->
                if (e.isDirectory) {
                    val was = e.path in favorites
                    vm.toggleFavorite(e.path)
                    scope.launch { snackbar.showSnackbar(if (was) Strings.get(R.string.browser_favorite_removed, e.name) else Strings.get(R.string.browser_favorite_added, e.name)) }
                } else {
                    Opener.share(context, listOf(e.path))
                }
            },
        )
    }

    CompositionLocalProvider(LocalPaneDrag provides drag) {
        Scaffold(
            topBar = {
                AnimatedContent(
                    targetState = pane.inSelection,
                    transitionSpec = { (fadeIn() + slideInVertically { -it / 3 }) togetherWith (fadeOut() + slideOutVertically { -it / 3 }) },
                    label = "topbar",
                ) { selecting ->
                    if (selecting) SelectionTopBar(
                        count = pane.selection.size,
                        onClose = { pane.clearSelection() },
                        onSelectAll = { pane.selectAll(); haptics.tick() },
                        onInvert = { pane.invertSelection(); haptics.tick() },
                    ) else BrowserTopBar(
                        vm = vm,
                        pane = pane,
                        isFavorite = pane.path in favorites,
                        showHidden = settings.showHidden,
                        dualAvailable = config.orientation == Configuration.ORIENTATION_LANDSCAPE,
                        dualOn = settings.dualPaneInLandscape,
                        onHome = { if (!nav.popBackStack(Route.Home, inclusive = false)) nav.navigate(Route.Home) },
                        onSearch = { nav.navigate(Route.Search(pane.path)) },
                        onNewItem = { folder -> dialog = BrowserDialog.NewItem(pane.path, folder) },
                        onProperties = {
                            c.fileSystems.local.stat(pane.path)?.let { dialog = BrowserDialog.Properties(listOf(it)) }
                        },
                        onPinShortcut = { Shortcuts.pinFolder(context, pane.path, StoragePaths.displayName(pane.path, volumes)) },
                        onToggleDual = { scope.launch { c.settings.setDualPaneInLandscape(!settings.dualPaneInLandscape) } },
                    )
                }
            },
            snackbarHost = { SnackbarHost(snackbar) },
            floatingActionButton = {
                if (picker == null) NewItemFab(
                    visible = !pane.inSelection,
                    onNewFolder = { dialog = BrowserDialog.NewItem(pane.path, true) },
                    onNewFile = { dialog = BrowserDialog.NewItem(pane.path, false) },
                )
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
                val bottomPad = PaddingValues(bottom = 160.dp + padding.calculateBottomPadding())
                if (dual && vm.secondary != null) {
                    Row(Modifier.fillMaxSize()) {
                        PaneFrame(active = vm.activePane == 0, modifier = Modifier.weight(1f)) {
                            FolderPane(main, favorites, volumes, vm.activePane == 0, true, ::openFile, ::drop,
                                contentPadding = bottomPad, onActivate = { vm.activePane = 0 }, tags = tagSlots, swipe = swipe)
                        }
                        VerticalDivider(Modifier.padding(vertical = 24.dp))
                        PaneFrame(active = vm.activePane == 1, modifier = Modifier.weight(1f)) {
                            FolderPane(vm.secondary!!, favorites, volumes, vm.activePane == 1, true, ::openFile, ::drop,
                                contentPadding = bottomPad, onActivate = { vm.activePane = 1 }, tags = tagSlots, swipe = swipe)
                        }
                    }
                } else {
                    FolderPane(main, favorites, volumes, true, false, ::openFile, ::drop, contentPadding = bottomPad, tags = tagSlots, swipe = swipe)
                }

                // Bottom stack: mini player, operations, paste bar, selection toolbar.
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    MiniPlayerBar(onOpen = { nav.navigate(if (it.startsWith("content://")) Route.UriPreview(it) else Route.Preview(it)) })
                    OperationsIndicator(onClick = { dialog = BrowserDialog.Operations })
                    AnimatedVisibility(
                        visible = clip != null && !pane.inSelection && picker == null,
                        enter = scaleIn(bouncy()) + fadeIn(), exit = scaleOut() + fadeOut(),
                    ) {
                        clip?.let { PasteBar(it, onPaste = { paste(pane.path) }, onCancel = { FileClipboard.clear() }) }
                    }
                    AnimatedVisibility(
                        visible = pane.inSelection,
                        enter = slideInVertically(bouncy()) { it } + fadeIn(),
                        exit = slideOutVertically { it } + fadeOut(),
                    ) {
                        if (picker != null) PickerBar(pane.selection.size) { pickerResult(pane.selectedEntries().filter { !it.isDirectory }.map { it.path }) }
                        else SelectionToolbar(
                            entries = pane.selectedEntries(),
                            favorites = favorites,
                            onCopy = { FileClipboard.copy(pane.selectedEntries()); pane.clearSelection(); haptics.confirm() },
                            onCut = { FileClipboard.cut(pane.selectedEntries()); pane.clearSelection(); haptics.confirm() },
                            onDelete = {
                                val sel = pane.selectedEntries()
                                if (settings.confirmDelete) dialog = BrowserDialog.Delete(sel)
                                else { c.operations.trash(sel.map { it.path }); pane.clearSelection() }
                            },
                            onRename = {
                                val sel = pane.selectedEntries()
                                dialog = if (sel.size == 1) BrowserDialog.Rename(sel[0]) else BrowserDialog.BatchRename(sel)
                            },
                            onShare = { Opener.share(context, pane.selectedEntries().filter { !it.isDirectory }.map { it.path }) },
                            onCompress = { dialog = BrowserDialog.Compress(pane.selectedEntries(), pane.path) },
                            onExtract = { e -> dialog = BrowserDialog.Extract(e.path) },
                            onProperties = { dialog = BrowserDialog.Properties(pane.selectedEntries()) },
                            onFavorite = { e -> vm.toggleFavorite(e.path) },
                            onOpenWith = { e -> Opener.openExternally(context, e.path, chooser = true) },
                            onOpenInTab = { e -> if (vm.addTab(e.path)) pane.clearSelection() },
                            onPinShortcut = { e -> Shortcuts.pinFolder(context, e.path, e.name) },
                            onBatchRename = { dialog = BrowserDialog.BatchRename(pane.selectedEntries()) },
                            onTags = { dialog = BrowserDialog.Tags(pane.selectedEntries().map { it.path }) },
                        )
                    }
                }

                // Drag & drop ghost (dual pane).
                if (drag.active) DragGhost(drag)
            }
        }
    }

    // Undo snackbar for operations finished while the browser is visible.
    LaunchedEffect(Unit) {
        c.operations.events.collect { e ->
            if (e is com.karelisio.fichio.ops.OperationEvent.Finished && e.undoLabel != null) {
                scope.launch {
                    val r = snackbar.showSnackbar(e.undoLabel, actionLabel = Strings.get(R.string.common_cancel), withDismissAction = true,
                        duration = androidx.compose.material3.SnackbarDuration.Long)
                    if (r == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                        if (c.undo.undo()) haptics.confirm() else snackbar.showSnackbar(Strings.get(R.string.common_undo_failed))
                    }
                }
            } else if (e is com.karelisio.fichio.ops.OperationEvent.Message) {
                scope.launch { snackbar.showSnackbar(e.text) }
            }
        }
    }

    when (val d = dialog) {
        is BrowserDialog.Rename -> RenameDialog(d.entry, onDismiss = { dialog = null; pane.clearSelection() })
        is BrowserDialog.BatchRename -> BatchRenameSheet(d.entries, onDismiss = { dialog = null; pane.clearSelection() })
        is BrowserDialog.NewItem -> NewItemDialog(d.parent, d.folder, onDismiss = { dialog = null })
        is BrowserDialog.Properties -> PropertiesSheet(d.entries, onDismiss = { dialog = null })
        is BrowserDialog.Compress -> CompressSheet(d.entries, d.dest, onDismiss = { dialog = null; pane.clearSelection() })
        is BrowserDialog.Extract -> ExtractSheet(d.path, onDismiss = { dialog = null; pane.clearSelection() })
        BrowserDialog.Operations -> OperationsSheet(onDismiss = { dialog = null })
        is BrowserDialog.Tags -> TagAssignSheet(d.paths, onDismiss = { dialog = null; pane.clearSelection() })
        is BrowserDialog.Delete -> DeleteDialog(
            d.entries, trashEnabled = settings.trashEnabled,
            onDismiss = { dialog = null },
            onConfirm = { permanent ->
                val paths = d.entries.map { it.path }
                if (permanent) c.operations.deletePermanently(paths) else c.operations.trash(paths)
                pane.clearSelection(); dialog = null; haptics.confirm()
            },
        )
        null -> Unit
    }
}

@Composable
private fun PaneFrame(active: Boolean, modifier: Modifier, content: @Composable () -> Unit) {
    val alpha by animateFloatAsState(if (active) 1f else 0f, label = "paneActive")
    Box(
        modifier.fillMaxHeight().padding(4.dp).clip(RoundedCornerShape(28.dp))
            .border(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f * alpha), RoundedCornerShape(28.dp)),
    ) { content() }
}

@Composable
private fun BrowserTopBar(
    vm: BrowserViewModel,
    pane: PaneState,
    isFavorite: Boolean,
    showHidden: Boolean,
    dualAvailable: Boolean,
    dualOn: Boolean,
    onHome: () -> Unit,
    onSearch: () -> Unit,
    onNewItem: (Boolean) -> Unit,
    onProperties: () -> Unit,
    onPinShortcut: () -> Unit,
    onToggleDual: () -> Unit,
) {
    var viewMenu by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    val view = pane.view(pane.path)
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(start = 4.dp, end = 4.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onHome) { Icon(Icons.Rounded.Home, stringResource(R.string.common_home)) }
        TabStrip(vm, Modifier.weight(1f))
        IconButton(onClick = onSearch) { Icon(Icons.Rounded.Search, stringResource(R.string.shortcut_search)) }
        Box {
            IconButton(onClick = { viewMenu = true }) {
                Icon(when (view.mode) { ViewMode.LIST -> Icons.Rounded.ViewList; ViewMode.GRID -> Icons.Rounded.GridView; ViewMode.LARGE -> Icons.Rounded.PhotoLibrary }, stringResource(R.string.browser_view))
            }
            DropdownMenu(viewMenu, onDismissRequest = { viewMenu = false }, shape = RoundedCornerShape(20.dp)) {
                listOf(ViewMode.LIST to R.string.view_list, ViewMode.GRID to R.string.view_grid, ViewMode.LARGE to R.string.view_large).forEach { (m, label) ->
                    DropdownMenuItem(
                        text = { Text(stringResource(label)) },
                        leadingIcon = { Icon(when (m) { ViewMode.LIST -> Icons.Rounded.ViewList; ViewMode.GRID -> Icons.Rounded.GridView; ViewMode.LARGE -> Icons.Rounded.PhotoLibrary }, null) },
                        trailingIcon = { if (view.mode == m) Icon(Icons.Rounded.Check, null) },
                        onClick = { pane.setView(mode = m); viewMenu = false },
                    )
                }
            }
        }
        Box {
            IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Rounded.Sort, stringResource(R.string.browser_sort)) }
            DropdownMenu(sortMenu, onDismissRequest = { sortMenu = false }, shape = RoundedCornerShape(20.dp)) {
                listOf(SortBy.NAME to R.string.sort_name, SortBy.DATE to R.string.sort_date, SortBy.SIZE to R.string.sort_size, SortBy.TYPE to R.string.sort_type).forEach { (s, label) ->
                    DropdownMenuItem(
                        text = { Text(stringResource(label)) },
                        leadingIcon = { RadioButton(selected = view.sort.by == s, onClick = null) },
                        onClick = { pane.setView(sort = view.sort.copy(by = s)) },
                    )
                }
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sort_ascending_order)) },
                    leadingIcon = { Checkbox(view.sort.ascending, onCheckedChange = null) },
                    onClick = { pane.setView(sort = view.sort.copy(ascending = !view.sort.ascending)) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.browser_folders_first)) },
                    leadingIcon = { Checkbox(view.sort.foldersFirst, onCheckedChange = null) },
                    onClick = { pane.setView(sort = view.sort.copy(foldersFirst = !view.sort.foldersFirst)) },
                )
            }
        }
        Box {
            IconButton(onClick = { more = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.common_more)) }
            DropdownMenu(more, onDismissRequest = { more = false }, shape = RoundedCornerShape(20.dp)) {
                DropdownMenuItem(text = { Text(stringResource(R.string.browser_new_folder)) }, leadingIcon = { Icon(Icons.Rounded.CreateNewFolder, null) }, onClick = { more = false; onNewItem(true) })
                DropdownMenuItem(text = { Text(stringResource(R.string.browser_new_file)) }, leadingIcon = { Icon(Icons.Rounded.NoteAdd, null) }, onClick = { more = false; onNewItem(false) })
                DropdownMenuItem(text = { Text(stringResource(R.string.browser_select_all)) }, leadingIcon = { Icon(Icons.Rounded.SelectAll, null) }, onClick = { more = false; pane.selectAll() })
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(if (isFavorite) stringResource(R.string.browser_remove_favorite) else stringResource(R.string.browser_add_favorite)) },
                    leadingIcon = { Icon(if (isFavorite) Icons.Rounded.Star else Icons.Rounded.StarOutline, null) },
                    onClick = { more = false; vm.toggleFavorite(pane.path) },
                )
                DropdownMenuItem(text = { Text(stringResource(R.string.browser_pin)) }, leadingIcon = { Icon(Icons.Rounded.PushPin, null) }, onClick = { more = false; vm.pin(pane.path) })
                DropdownMenuItem(text = { Text(stringResource(R.string.browser_home_shortcut)) }, leadingIcon = { Icon(Icons.Rounded.AddHome, null) }, onClick = { more = false; onPinShortcut() })
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.browser_new_tab)) }, leadingIcon = { Icon(Icons.Rounded.Tab, null) },
                    enabled = vm.tabs.size < MAX_TABS, onClick = { more = false; vm.addTab(pane.path) },
                )
                if (dualAvailable) DropdownMenuItem(
                    text = { Text(if (dualOn) stringResource(R.string.browser_single_pane) else stringResource(R.string.browser_dual_pane)) }, leadingIcon = { Icon(Icons.Rounded.SwapHoriz, null) },
                    onClick = { more = false; onToggleDual() },
                )
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.browser_hidden_files)) },
                    leadingIcon = { Icon(if (showHidden) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff, null) },
                    trailingIcon = { Checkbox(showHidden, onCheckedChange = null) },
                    onClick = { more = false; vm.setShowHidden(!showHidden) },
                )
                DropdownMenuItem(text = { Text(stringResource(R.string.browser_folder_properties)) }, leadingIcon = { Icon(Icons.Rounded.Info, null) }, onClick = { more = false; onProperties() })
            }
        }
    }
}

/** Up to 5 tabs as expressive chips; tap to switch, × to close, + to add. */
@Composable
private fun TabStrip(vm: BrowserViewModel, modifier: Modifier) {
    val scroll = rememberScrollState()
    val haptics = rememberHaptics()
    Row(modifier.horizontalScroll(scroll), verticalAlignment = Alignment.CenterVertically) {
        vm.tabs.forEachIndexed { i, tab ->
            val selected = i == vm.selectedTab
            val scale by animateFloatAsState(if (selected) 1f else 0.94f, bouncy(), label = "tab")
            Surface(
                onClick = { vm.selectTab(i); haptics.tick() },
                shape = RoundedCornerShape(if (selected) 16.dp else 50.dp),
                color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.padding(end = 6.dp).graphicsLayer { scaleX = scale; scaleY = scale }.animateContentSize(bouncy()),
            ) {
                Row(Modifier.padding(start = 14.dp, end = if (selected && vm.tabs.size > 1) 0.dp else 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        StoragePaths.name(tab.path).ifEmpty { "/" }.let { if (tab.path == StoragePaths.primary) stringResource(R.string.browser_tab_internal) else it },
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 140.dp),
                    )
                    if (selected && vm.tabs.size > 1) {
                        IconButton(onClick = { vm.closeTab(i) }, modifier = Modifier.minimumInteractiveComponentSize()) { Icon(Icons.Rounded.Close, stringResource(R.string.browser_close_tab), Modifier.size(16.dp)) }
                    }
                }
            }
        }
        if (vm.tabs.size < MAX_TABS) {
            IconButton(onClick = { vm.addTab(); haptics.tick() }) { Icon(Icons.Rounded.Add, stringResource(R.string.browser_new_tab)) }
        }
    }
}

@Composable
private fun SelectionTopBar(count: Int, onClose: () -> Unit, onSelectAll: () -> Unit, onInvert: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).statusBarsPadding().padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, stringResource(R.string.browser_cancel_selection)) }
        AnimatedCounter("$count", style = MaterialTheme.typography.titleLarge)
        Text(" " + pluralStringResource(R.plurals.browser_selected_suffix, count), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        TextButton(onClick = onInvert) { Text(stringResource(R.string.browser_invert)) }
        IconButton(onClick = onSelectAll) { Icon(Icons.Rounded.SelectAll, stringResource(R.string.browser_select_all)) }
    }
}

@Composable
private fun SelectionToolbar(
    entries: List<FileEntry>,
    favorites: Set<String>,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit,
    onShare: () -> Unit,
    onCompress: () -> Unit,
    onExtract: (FileEntry) -> Unit,
    onProperties: () -> Unit,
    onFavorite: (FileEntry) -> Unit,
    onOpenWith: (FileEntry) -> Unit,
    onOpenInTab: (FileEntry) -> Unit,
    onPinShortcut: (FileEntry) -> Unit,
    onBatchRename: () -> Unit,
    onTags: () -> Unit,
) {
    var more by remember { mutableStateOf(false) }
    val single = entries.singleOrNull()
    HorizontalFloatingToolbar(
        expanded = true,
        colors = FloatingToolbarDefaults.vibrantFloatingToolbarColors(),
    ) {
        IconButton(onClick = onCopy) { Icon(Icons.Rounded.ContentCopy, stringResource(R.string.common_copy)) }
        IconButton(onClick = onCut) { Icon(Icons.Rounded.ContentCut, stringResource(R.string.common_cut)) }
        IconButton(onClick = onRename) { Icon(Icons.Rounded.DriveFileRenameOutline, stringResource(R.string.common_rename)) }
        IconButton(onClick = onShare, enabled = entries.any { !it.isDirectory }) { Icon(Icons.Rounded.Share, stringResource(R.string.common_share)) }
        IconButton(onClick = onDelete) { Icon(Icons.Rounded.Delete, stringResource(R.string.common_delete)) }
        Box {
            IconButton(onClick = { more = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.common_more)) }
            DropdownMenu(more, onDismissRequest = { more = false }, shape = RoundedCornerShape(20.dp)) {
                DropdownMenuItem(text = { Text(stringResource(R.string.browser_tags)) }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.Label, null) }, onClick = { more = false; onTags() })
                DropdownMenuItem(text = { Text(stringResource(R.string.browser_compress)) }, leadingIcon = { Icon(Icons.Rounded.FolderZip, null) }, onClick = { more = false; onCompress() })
                if (single != null && single.type == FileType.ARCHIVE) DropdownMenuItem(text = { Text(stringResource(R.string.browser_extract)) }, leadingIcon = { Icon(Icons.Rounded.Unarchive, null) }, onClick = { more = false; onExtract(single) })
                if (entries.size > 1) DropdownMenuItem(text = { Text(stringResource(R.string.browser_batch_rename)) }, leadingIcon = { Icon(Icons.Rounded.Edit, null) }, onClick = { more = false; onBatchRename() })
                if (single != null && !single.isDirectory) DropdownMenuItem(text = { Text(stringResource(R.string.browser_open_with)) }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, null) }, onClick = { more = false; onOpenWith(single) })
                if (single != null && single.isDirectory) {
                    DropdownMenuItem(
                        text = { Text(if (single.path in favorites) stringResource(R.string.browser_remove_favorite) else stringResource(R.string.browser_add_favorite)) },
                        leadingIcon = { Icon(if (single.path in favorites) Icons.Rounded.Star else Icons.Rounded.StarOutline, null) },
                        onClick = { more = false; onFavorite(single) },
                    )
                    DropdownMenuItem(text = { Text(stringResource(R.string.browser_open_in_tab)) }, leadingIcon = { Icon(Icons.Rounded.Tab, null) }, onClick = { more = false; onOpenInTab(single) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.browser_home_shortcut)) }, leadingIcon = { Icon(Icons.Rounded.AddHome, null) }, onClick = { more = false; onPinShortcut(single) })
                }
                DropdownMenuItem(text = { Text(stringResource(R.string.common_properties)) }, leadingIcon = { Icon(Icons.Rounded.Info, null) }, onClick = { more = false; onProperties() })
            }
        }
    }
}

@Composable
private fun PasteBar(content: ClipContent, onPaste: () -> Unit, onCancel: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(32.dp),
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        shadowElevation = 6.dp,
        modifier = Modifier.padding(horizontal = 16.dp),
    ) {
        Row(Modifier.padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).flyTarget("clipboard")) {
                content.entries.take(3).forEachIndexed { i, e ->
                    FileThumbnail(e, size = 36.dp, modifier = Modifier.offset { IntOffset(i * 4.dp.roundToPx(), i * 3.dp.roundToPx()) }.graphicsLayer { rotationZ = (i - 1) * 8f })
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.widthIn(max = 180.dp)) {
                Text(Format.items(content.entries.size), style = MaterialTheme.typography.labelLarge)
                Text(if (content.cut) stringResource(R.string.browser_move_here) else stringResource(R.string.browser_copy_here), style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onCancel) { Text(stringResource(R.string.common_cancel), color = MaterialTheme.colorScheme.inversePrimary) }
            androidx.compose.material3.Button(onClick = onPaste, shapes = androidx.compose.material3.ButtonDefaults.shapes()) {
                Icon(if (content.cut) Icons.AutoMirrored.Rounded.DriveFileMove else Icons.Rounded.ContentPaste, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.browser_paste))
            }
        }
    }
}

@Composable
private fun PickerBar(count: Int, onChoose: () -> Unit) {
    androidx.compose.material3.Button(onClick = onChoose, enabled = count > 0, modifier = Modifier.heightIn(min = 56.dp)) {
        Icon(Icons.Rounded.Check, null)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.browser_pick_count, count))
    }
}

@Composable
private fun NewItemFab(visible: Boolean, onNewFolder: () -> Unit, onNewFile: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    FloatingActionButtonMenu(
        expanded = expanded,
        modifier = Modifier.padding(bottom = 72.dp),
        button = {
            ToggleFloatingActionButton(
                checked = expanded,
                onCheckedChange = { expanded = it },
                modifier = Modifier.semantics { contentDescription = Strings.get(R.string.browser_create) }.animateFloatingActionButton(visible, Alignment.BottomEnd),
            ) {
                val icon = if (checkedProgress > 0.5f) Icons.Rounded.Close else Icons.Rounded.Add
                Icon(icon, null, Modifier.animateIcon({ checkedProgress }))
            }
        },
    ) {
        FloatingActionButtonMenuItem(onClick = { expanded = false; onNewFolder() }, icon = { Icon(Icons.Rounded.CreateNewFolder, null) }, text = { Text(stringResource(R.string.browser_new_folder)) })
        FloatingActionButtonMenuItem(onClick = { expanded = false; onNewFile() }, icon = { Icon(Icons.Rounded.NoteAdd, null) }, text = { Text(stringResource(R.string.browser_new_file)) })
    }
}

@Composable
private fun DeleteDialog(entries: List<FileEntry>, trashEnabled: Boolean, onDismiss: () -> Unit, onConfirm: (Boolean) -> Unit) {
    var permanent by remember { mutableStateOf(!trashEnabled) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Delete, null) },
        title = { Text(if (entries.size == 1) stringResource(R.string.browser_delete_one, entries[0].name) else pluralStringResource(R.plurals.browser_delete_many, entries.size, entries.size)) },
        text = {
            Column {
                Text(if (permanent) stringResource(R.string.browser_delete_permanent_body) else stringResource(R.string.browser_delete_trash_body))
                if (trashEnabled) Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(permanent, onCheckedChange = { permanent = it })
                    Text(stringResource(R.string.browser_delete_permanently))
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(permanent) }) { Text(stringResource(R.string.common_delete)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun DragGhost(drag: PaneDragState) {
    val density = LocalDensity.current
    val first = drag.entries.firstOrNull() ?: return
    val scale by animateFloatAsState(if (drag.hovered != null) 0.85f else 1.08f, bouncy(), label = "ghost")
    Box(
        Modifier
            .offset { IntOffset((drag.position.x - with(density) { 32.dp.toPx() }).toInt(), (drag.position.y - with(density) { 32.dp.toPx() }).toInt() - 120) }
            .graphicsLayer { scaleX = scale; scaleY = scale; rotationZ = -6f },
    ) {
        Surface(shape = RoundedCornerShape(20.dp), shadowElevation = 12.dp) {
            Box(Modifier.padding(6.dp)) {
                FileThumbnail(first, size = 56.dp)
                if (drag.entries.size > 1) Surface(
                    shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.TopEnd).offset(8.dp, (-8).dp),
                ) { Text("${drag.entries.size}", Modifier.padding(horizontal = 7.dp, vertical = 2.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimary) }
            }
        }
    }
}
