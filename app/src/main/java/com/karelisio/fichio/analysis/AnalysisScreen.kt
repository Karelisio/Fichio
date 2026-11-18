package com.karelisio.fichio.analysis

import com.karelisio.fichio.ui.components.selectionSemantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import com.karelisio.fichio.core.util.Strings
import com.karelisio.fichio.R
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AutoDelete
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DataUsage
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SdStorage
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.karelisio.fichio.container
import com.karelisio.fichio.core.model.FileEntry
import com.karelisio.fichio.core.model.FileType
import com.karelisio.fichio.core.util.Format
import com.karelisio.fichio.data.prefs.FichioSettings
import com.karelisio.fichio.ui.components.AnimatedCounter
import com.karelisio.fichio.ui.components.BackButton
import com.karelisio.fichio.ui.components.EmptyState
import com.karelisio.fichio.ui.components.FileIcon
import com.karelisio.fichio.ui.components.FileThumbnail
import com.karelisio.fichio.ui.components.bouncy
import com.karelisio.fichio.ui.components.cascadeIn
import com.karelisio.fichio.ui.components.pressScale
import com.karelisio.fichio.ui.components.rememberHaptics
import com.karelisio.fichio.ui.theme.GaugeNumberStyle
import com.karelisio.fichio.ui.theme.LocalReduceMotion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.sqrt

private enum class AnalysisTab(@androidx.annotation.StringRes val labelRes: Int, val icon: ImageVector) {
    MAP(R.string.analysis_tab_map, Icons.Rounded.DataUsage),
    LARGE(R.string.analysis_tab_large, Icons.Rounded.SdStorage),
    DUPLICATES(R.string.analysis_tab_duplicates, Icons.Rounded.ContentCopy),
    OLD(R.string.analysis_tab_old, Icons.Rounded.History),
    EMPTY(R.string.analysis_tab_empty, Icons.Rounded.FolderOff);

    val label: String get() = Strings.get(labelRes)
}

private val LARGE_STEPS = listOf(1, 5, 10, 25, 50, 100, 250, 500, 1000, 2000)
private val OLD_STEPS = listOf(7 to R.string.analysis_old_1w, 30 to R.string.analysis_old_1m, 90 to R.string.analysis_old_3m, 180 to R.string.analysis_old_6m, 365 to R.string.analysis_old_1y, 730 to R.string.analysis_old_2y)
/** Minimum sizes (KiB) of the old-files and duplicates tabs; all at or above the pool floor (64 Ko). */
private val OLD_SIZE_STEPS = listOf(64, 512, 1024, 10 * 1024, 100 * 1024)
private val DUP_STEPS = listOf(64, 100, 256, 512, 1024, 10 * 1024, 100 * 1024)
private const val DAY_MS = 24L * 3600 * 1000
private const val MB = 1024L * 1024

/** "64 Ko", "1 Mo", "100 Mo", "2 Go" for a threshold in KiB. */
private fun kbLabel(kb: Int): String = when {
    kb >= 1024 * 1024 && kb % (1024 * 1024) == 0 -> Strings.get(R.string.settings_gb_value, kb / (1024 * 1024))
    kb >= 1024 && kb % 1024 == 0 -> Strings.get(R.string.settings_mb_value, kb / 1024)
    else -> Strings.get(R.string.analysis_kb_value, kb)
}

/** Sunburst map + large files, duplicates, old files, empty folders. */
@Composable
fun AnalysisScreen(onBack: () -> Unit, onOpenFolder: (String) -> Unit) {
    val context = LocalContext.current
    val container = context.container
    val result by AnalysisController.result.collectAsStateWithLifecycle()
    val status by AnalysisController.status.collectAsStateWithLifecycle()
    val settings by container.settings.settings.collectAsStateWithLifecycle(FichioSettings())
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()

    // First visit: scan; afterwards the cached result is reused.
    LaunchedEffect(Unit) { AnalysisController.start(container) }

    var tab by rememberSaveable { mutableStateOf(AnalysisTab.MAP) }
    // Zoom path of the sunburst, kept across tab switches and rescans.
    var mapPaths by remember { mutableStateOf(emptyList<String>()) }
    val selections = remember { mutableStateMapOf<AnalysisTab, Set<String>>() }
    var pendingTrash by remember { mutableStateOf<Pair<AnalysisTab, List<String>>?>(null) }

    var recents by remember { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(result) {
        recents = withContext(Dispatchers.IO) { runCatching { container.database.recents().allPaths().toHashSet() }.getOrDefault(emptySet()) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.analysis_title)) },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    IconButton(
                        onClick = { haptics.click(); AnalysisController.start(container, force = true) },
                        enabled = status !is ScanStatus.Running,
                    ) { Icon(Icons.Rounded.Refresh, stringResource(R.string.analysis_rescan)) }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val r = result
        Box(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            when {
                r == null && status is ScanStatus.Failed -> EmptyState(
                    icon = Icons.Rounded.CleaningServices,
                    title = stringResource(R.string.analysis_err_failed),
                    message = (status as ScanStatus.Failed).message,
                    actionLabel = stringResource(R.string.common_retry),
                    onAction = { AnalysisController.start(container, force = true) },
                    modifier = Modifier.align(Alignment.Center),
                )
                r == null -> ScanningPane(status as? ScanStatus.Running ?: ScanStatus.Running(), Modifier.align(Alignment.Center))
                else -> Column(Modifier.fillMaxSize()) {
                    AnimatedVisibility(status is ScanStatus.Running, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                        RescanBanner(status as? ScanStatus.Running ?: ScanStatus.Running())
                    }
                    val visible = remember(settings.showHidden) { { p: String -> settings.showHidden || !p.contains("/.") } }
                    // Thresholds are applied to the scan's compact pool: changing one recomputes the list
                    // at once, without rescanning (duplicates of smaller files are hashed on demand).
                    val floor = r.pool.floor
                    val thresholdMb = settings.largeFileThresholdMb.coerceAtLeast(1)
                    val largeMin = maxOf(thresholdMb * MB, floor)
                    val large = remember(r, largeMin, visible) { r.large(largeMin, visible) }
                    val oldDays = settings.oldFileThresholdDays.coerceAtLeast(1)
                    val oldMin = maxOf(settings.oldFileMinKb * 1024L, floor)
                    val old = remember(r, oldDays, oldMin, recents, visible) {
                        r.old(oldMin, oldDays * DAY_MS, accept = { it !in recents && visible(it) })
                    }
                    val dupMin = maxOf(settings.duplicateMinKb * 1024L, floor)
                    LaunchedEffect(r, dupMin) { AnalysisController.ensureDuplicates(container, dupMin) }
                    val dupProgress by AnalysisController.duplicateProgress.collectAsStateWithLifecycle()
                    val dups = remember(r, dupMin, visible) {
                        r.duplicates(dupMin).mapNotNull { g ->
                            val f = g.files.filter { visible(it.path) }
                            if (f.size >= 2) g.copy(files = f) else null
                        }
                    }
                    val empties = remember(r, visible) { r.emptyDirs.filter(visible) }
                    LaunchedEffect(dups) {
                        if (selections[AnalysisTab.DUPLICATES] == null && dups.isNotEmpty()) {
                            selections[AnalysisTab.DUPLICATES] = keepNewestSelection(dups)
                        }
                    }

                    PrimaryScrollableTabRow(selectedTabIndex = tab.ordinal, edgePadding = 12.dp) {
                        AnalysisTab.entries.forEach { t ->
                            val count = when (t) {
                                AnalysisTab.MAP -> null
                                AnalysisTab.LARGE -> large.size
                                AnalysisTab.DUPLICATES -> dups.size
                                AnalysisTab.OLD -> old.size
                                AnalysisTab.EMPTY -> empties.size
                            }
                            Tab(
                                selected = tab == t,
                                onClick = { if (tab != t) haptics.segment(); tab = t },
                                text = { Text(if (count == null) t.label else "${t.label} · $count") },
                                icon = { Icon(t.icon, null) },
                            )
                        }
                    }

                    val reduce = LocalReduceMotion.current
                    AnimatedContent(
                        targetState = tab,
                        transitionSpec = {
                            if (reduce) fadeIn() togetherWith fadeOut()
                            else {
                                val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
                                (slideInHorizontally(bouncy()) { it / 5 * dir } + fadeIn()) togetherWith
                                    (slideOutHorizontally { -it / 6 * dir } + fadeOut())
                            }
                        },
                        modifier = Modifier.weight(1f),
                        label = "tab",
                    ) { t ->
                        val sel = selections[t].orEmpty()
                        val toggle: (String) -> Unit = { p ->
                            haptics.tick()
                            selections[t] = if (p in sel) sel - p else sel + p
                        }
                        // Only what the tab currently shows (a threshold change may have hidden selected items).
                        fun askTrash(shown: () -> List<String>): () -> Unit = {
                            val paths = shown().filter { it in sel }
                            if (paths.isNotEmpty()) pendingTrash = t to paths
                        }
                        when (t) {
                            AnalysisTab.MAP -> MapTab(r, onOpenFolder, mapPaths) { mapPaths = it }
                            AnalysisTab.LARGE -> FilesTab(
                                files = large, selected = sel, onToggle = toggle,
                                onSelectAll = { selections[t] = large.mapTo(HashSet()) { it.path } },
                                onClear = { selections[t] = emptySet() },
                                onTrash = askTrash { large.map { it.path } }, onOpenFolder = onOpenFolder,
                                emptyTitle = stringResource(R.string.analysis_no_large),
                                emptyMessage = stringResource(R.string.analysis_no_large_body, Format.size(largeMin)),
                                emptyIcon = Icons.Rounded.SdStorage,
                            ) {
                                LargeThresholdControl(
                                    currentMb = thresholdMb,
                                    histogram = r.histogram,
                                    stats = remember(r, floor) { { bytes: Long -> r.pool.stats(maxOf(bytes, floor)) } },
                                ) { mb -> scope.launch { container.settings.setLargeFileThresholdMb(mb) } }
                            }
                            AnalysisTab.OLD -> FilesTab(
                                files = old, selected = sel, onToggle = toggle,
                                onSelectAll = { selections[t] = old.mapTo(HashSet()) { it.path } },
                                onClear = { selections[t] = emptySet() },
                                onTrash = askTrash { old.map { it.path } }, onOpenFolder = onOpenFolder,
                                emptyTitle = stringResource(R.string.analysis_no_old),
                                emptyMessage = stringResource(R.string.analysis_no_old_body),
                                emptyIcon = Icons.Rounded.History,
                                showDate = true,
                            ) {
                                OldThresholdControl(
                                    currentDays = oldDays,
                                    currentMinKb = settings.oldFileMinKb,
                                    floorBytes = floor,
                                    onChangeSize = { kb -> scope.launch { container.settings.setOldFileMinKb(kb) } },
                                ) { d -> scope.launch { container.settings.setOldFileThresholdDays(d) } }
                            }
                            AnalysisTab.DUPLICATES -> DuplicatesTab(
                                groups = dups, selected = sel, onToggle = toggle,
                                onKeepNewest = { haptics.confirm(); selections[t] = keepNewestSelection(dups) },
                                onClear = { selections[t] = emptySet() },
                                onTrash = askTrash { dups.flatMap { g -> g.files.map { it.path } } }, onOpenFolder = onOpenFolder,
                                minBytes = dupMin,
                                searching = dupProgress,
                            ) {
                                DuplicateThresholdControl(
                                    currentKb = settings.duplicateMinKb,
                                    floorBytes = floor,
                                    progress = dupProgress,
                                ) { kb -> scope.launch { container.settings.setDuplicateMinKb(kb) } }
                            }
                            AnalysisTab.EMPTY -> EmptyDirsTab(
                                dirs = empties, selected = sel, onToggle = toggle,
                                onSelectAll = { selections[t] = empties.toHashSet() },
                                onClear = { selections[t] = emptySet() },
                                onTrash = askTrash { empties }, onOpenFolder = onOpenFolder,
                            )
                        }
                    }
                }
            }
        }
    }

    pendingTrash?.let { (t, paths) ->
        AlertDialog(
            onDismissRequest = { pendingTrash = null },
            icon = { Icon(Icons.Rounded.Delete, null) },
            title = { Text(stringResource(R.string.analysis_trash_confirm, Format.items(paths.size))) },
            text = { Text(stringResource(R.string.analysis_trash_confirm_body)) },
            confirmButton = {
                Button(onClick = {
                    pendingTrash = null
                    val ok = runCatching { container.operations.trash(paths) }.isSuccess
                    if (ok) {
                        haptics.confirm()
                        AnalysisController.applyRemoval(paths)
                        selections[t] = emptySet()
                        // Other tabs may reference the same files.
                        selections.keys.toList().forEach { k -> selections[k] = selections[k].orEmpty() - paths.toSet() }
                    } else {
                        haptics.reject()
                        scope.launch { snackbar.showSnackbar(Strings.get(R.string.analysis_trash_failed)) }
                    }
                }, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.shortcut_trash)) }
            },
            dismissButton = { TextButton(onClick = { pendingTrash = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

private fun keepNewestSelection(groups: List<DuplicateGroup>): Set<String> =
    groups.flatMapTo(HashSet()) { g -> val keep = g.newest; g.files.filter { it !== keep }.map { it.path } }

private fun formatCount(n: Int): String = String.format(Strings.locale, "%,d", n)

@Composable
private fun ScanningPane(s: ScanStatus.Running, modifier: Modifier = Modifier) {
    Column(modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        ContainedLoadingIndicator(Modifier.size(112.dp))
        Spacer(Modifier.height(28.dp))
        AnimatedCounter(formatCount(s.files), style = GaugeNumberStyle.copy(color = MaterialTheme.colorScheme.onSurface))
        Text(pluralStringResource(R.plurals.analysis_files_scanned_suffix, s.files), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        Text(Format.size(s.bytes), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
        AnimatedVisibility(s.phase == StorageAnalyzer.Phase.DUPLICATES) {
            Column(Modifier.padding(top = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.analysis_finding_duplicates, s.hashed, s.hashTotal), style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(10.dp))
                val frac by animateFloatAsState(if (s.hashTotal > 0) s.hashed.toFloat() / s.hashTotal else 0f, label = "hash")
                LinearWavyProgressIndicator(progress = { frac }, modifier = Modifier.width(220.dp))
            }
        }
        Spacer(Modifier.height(28.dp))
        OutlinedButton(onClick = { AnalysisController.cancel() }, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.common_cancel)) }
    }
}

@Composable
private fun RescanBanner(s: ScanStatus.Running) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (s.phase == StorageAnalyzer.Phase.DUPLICATES) stringResource(R.string.analysis_duplicates_progress, s.hashed, s.hashTotal)
                    else stringResource(R.string.analysis_new_scan, formatCount(s.files)),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { AnalysisController.cancel() }) { Text(stringResource(R.string.common_cancel)) }
            }
            LinearWavyProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

// ---------------------------------------------------------------- Map

@Composable
private fun MapTab(
    result: AnalysisResult,
    onOpenFolder: (String) -> Unit,
    restorePaths: List<String>,
    onPathsChanged: (List<String>) -> Unit,
) {
    val model = remember(result) { SunburstModel(result.root) }
    val state = rememberSunburstState(model, restorePaths)
    LaunchedEffect(state.stack) { onPathsChanged(state.stack.map { it.path }) }
    val haptics = rememberHaptics()
    val scheme = MaterialTheme.colorScheme
    val frame = state.label
    val children = remember(frame) { model.displayChildren(frame) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item(key = "summary") {
            Text(
                stringResource(R.string.analysis_summary, Format.size(result.totalBytes), pluralStringResource(R.plurals.common_files_count, result.totalFiles, result.totalFiles), Format.relative(result.scannedAt)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }
        item(key = "chart") {
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                SunburstChart(state, onOpenFolder)
            }
        }
        item(key = "crumbs") {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                state.stack.forEachIndexed { i, node ->
                    if (i > 0) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.outline)
                    val last = i == state.stack.lastIndex
                    FilterChip(
                        selected = last,
                        onClick = { if (!last) { haptics.tick(); state.jumpTo(i) } },
                        label = { Text(node.name, maxLines = 1) },
                    )
                }
                Spacer(Modifier.width(8.dp))
                FilledTonalButton(
                    onClick = { haptics.click(); onOpenFolder(state.frame.dirPath) },
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Icon(Icons.Rounded.FolderOpen, null, Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                    Text(stringResource(R.string.common_open))
                }
            }
        }
        itemsIndexed(children, key = { _, c -> c.path }) { i, child ->
            LegendRow(
                child = child,
                fraction = if (frame.size > 0) child.size.toFloat() / frame.size else 0f,
                color = state.legendColor(scheme, child, i),
                onClick = { if (state.zoomInto(child)) haptics.click() else haptics.reject() },
                onLongClick = { haptics.longPress(); onOpenFolder(child.dirPath) },
                modifier = Modifier.animateItem().cascadeIn(i, key = frame.path),
            )
        }
    }
}

@Composable
private fun LegendRow(
    child: DirNode,
    fraction: Float,
    color: Color,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val bar by animateFloatAsState(fraction, bouncy(), label = "bar")
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .clip(MaterialTheme.shapes.large)
            .combinedClickable(interactionSource = interaction, indication = androidx.compose.material3.ripple(), onClick = onClick, onLongClick = onLongClick)
            .pressScale(interaction, 0.97f)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(18.dp).clip(MaterialShapes.Cookie6Sided.toShape()).background(color))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    child.name, style = MaterialTheme.typography.titleSmall, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                Text(Format.size(child.size), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
                Box(Modifier.fillMaxWidth(bar.coerceIn(0.005f, 1f)).fillMaxHeight().clip(CircleShape).background(color))
            }
            Spacer(Modifier.height(4.dp))
            Text(
                when (child.kind) {
                    NodeKind.FILES -> stringResource(R.string.analysis_files_here, pluralStringResource(R.plurals.common_files_count, child.fileCount, child.fileCount))
                    NodeKind.OTHERS -> pluralStringResource(R.plurals.analysis_small_items, child.children.size, child.children.size) + " · " + pluralStringResource(R.plurals.common_files_count, child.fileCount, child.fileCount)
                    NodeKind.DIR -> stringResource(R.string.analysis_percent, Format.decimal(fraction * 100.0)) + " · " + pluralStringResource(R.plurals.common_files_count, child.fileCount, child.fileCount)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---------------------------------------------------------------- Cleanup lists

/** Common frame of the cleanup tabs: big "recoverable space" header, list, floating trash bar. */
@Composable
private fun CleanupScaffold(
    totalBytes: Long,
    totalCount: Int,
    selectedBytes: Long,
    selectedCount: Int,
    onTrash: () -> Unit,
    selectionActions: @Composable () -> Unit,
    controls: (@Composable () -> Unit)?,
    isEmpty: Boolean,
    empty: @Composable () -> Unit,
    content: LazyListScope.() -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 140.dp)) {
            item(key = "recover") { RecoverCard(totalBytes, totalCount, selectedBytes, selectedCount, selectionActions) }
            if (controls != null) item(key = "controls") { controls() }
            if (isEmpty) item(key = "empty") { empty() } else content()
        }
        AnimatedVisibility(
            visible = selectedCount > 0,
            enter = slideInVertically(bouncy()) { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.primaryContainer,
                shadowElevation = 6.dp,
                modifier = Modifier.navigationBarsPadding().padding(16.dp),
            ) {
                Row(Modifier.padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f, fill = false)) {
                        Text(Format.items(selectedCount), style = MaterialTheme.typography.labelLarge)
                        Text(Format.size(selectedBytes), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(16.dp))
                    Button(onClick = onTrash, shapes = ButtonDefaults.shapes()) {
                        Icon(Icons.Rounded.AutoDelete, null, Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                        Text(stringResource(R.string.shortcut_trash))
                    }
                }
            }
        }
    }
}

@Composable
private fun RecoverCard(totalBytes: Long, totalCount: Int, selectedBytes: Long, selectedCount: Int, actions: @Composable () -> Unit) {
    val showSel = selectedCount > 0
    val bytes = if (showSel) selectedBytes else totalBytes
    val (num, unit) = Format.sizeParts(bytes)
    val bg by animateColorAsState(
        if (showSel) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.primaryContainer, label = "recover",
    )
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = bg,
        modifier = Modifier.fillMaxWidth().padding(16.dp),
    ) {
        Column(Modifier.padding(24.dp)) {
            AnimatedContent(showSel, label = "recoverTitle") { sel ->
                Text(
                    if (sel) stringResource(R.string.analysis_selection_to_free) else stringResource(R.string.analysis_reclaimable),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Row(verticalAlignment = Alignment.Bottom) {
                AnimatedCounter(num, style = GaugeNumberStyle)
                Text(" $unit", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 8.dp))
            }
            Text(
                if (showSel) stringResource(R.string.analysis_selected_of, Format.items(selectedCount), totalCount) else Format.items(totalCount),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (totalCount > 0) {
                Spacer(Modifier.height(8.dp))
                actions()
            }
        }
    }
}

@Composable
private fun SelectAllActions(allSelected: Boolean, onSelectAll: () -> Unit, onClear: () -> Unit) {
    AnimatedContent(allSelected, label = "selAll") { all ->
        if (all) TextButton(onClick = onClear) { Text(stringResource(R.string.analysis_deselect_all)) }
        else TextButton(onClick = onSelectAll) { Text(stringResource(R.string.browser_select_all)) }
    }
}

@Composable
private fun FilesTab(
    files: List<FileRef>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onSelectAll: () -> Unit,
    onClear: () -> Unit,
    onTrash: () -> Unit,
    onOpenFolder: (String) -> Unit,
    emptyTitle: String,
    emptyMessage: String,
    emptyIcon: ImageVector,
    showDate: Boolean = false,
    controls: @Composable () -> Unit,
) {
    val selFiles = remember(files, selected) { files.filter { it.path in selected } }
    CleanupScaffold(
        totalBytes = remember(files) { files.sumOf { it.size } },
        totalCount = files.size,
        selectedBytes = selFiles.sumOf { it.size },
        selectedCount = selFiles.size,
        onTrash = onTrash,
        selectionActions = { SelectAllActions(selFiles.size == files.size, onSelectAll, onClear) },
        controls = controls,
        isEmpty = files.isEmpty(),
        empty = { EmptyState(emptyIcon, emptyTitle, message = emptyMessage) },
    ) {
        itemsIndexed(files, key = { _, f -> f.path }) { i, f ->
            FileRow(
                ref = f,
                selected = f.path in selected,
                onToggle = { onToggle(f.path) },
                onLongClick = { onOpenFolder(f.path.substringBeforeLast('/')) },
                subtitle = if (showDate) stringResource(R.string.analysis_size_modified, Format.size(f.size), Format.relative(f.lastModified))
                else "${Format.size(f.size)} · ${f.path.substringBeforeLast('/').substringAfterLast('/')}",
                modifier = Modifier.animateItem().cascadeIn(i, key = f.path),
            )
        }
    }
}

@Composable
private fun DuplicatesTab(
    groups: List<DuplicateGroup>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onKeepNewest: () -> Unit,
    onClear: () -> Unit,
    onTrash: () -> Unit,
    onOpenFolder: (String) -> Unit,
    minBytes: Long,
    searching: Pair<Int, Int>?,
    controls: @Composable () -> Unit,
) {
    val allFiles = remember(groups) { groups.flatMap { it.files } }
    val selFiles = remember(allFiles, selected) { allFiles.filter { it.path in selected } }
    CleanupScaffold(
        totalBytes = remember(groups) { groups.sumOf { it.wasted } },
        totalCount = allFiles.size - groups.size,
        selectedBytes = selFiles.sumOf { it.size },
        selectedCount = selFiles.size,
        onTrash = onTrash,
        selectionActions = {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onKeepNewest, shapes = ButtonDefaults.shapes()) {
                    Icon(Icons.Rounded.Star, null, Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                    Text(stringResource(R.string.analysis_keep_newest))
                }
                TextButton(onClick = onClear) { Text(stringResource(R.string.analysis_none)) }
            }
        },
        controls = controls,
        isEmpty = groups.isEmpty(),
        empty = {
            EmptyState(
                Icons.Rounded.ContentCopy,
                if (searching != null) stringResource(R.string.settings_update_checking) else stringResource(R.string.analysis_no_duplicates),
                message = if (searching != null) stringResource(R.string.analysis_comparing, Format.size(minBytes))
                else stringResource(R.string.analysis_no_duplicates_body, Format.size(minBytes)),
            )
        },
    ) {
        groups.forEachIndexed { gi, g ->
            item(key = "g:${g.hash}:${g.size}") {
                Row(
                    Modifier.fillMaxWidth().padding(start = 24.dp, end = 20.dp, top = 18.dp, bottom = 6.dp).animateItem(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(28.dp).clip(MaterialShapes.Cookie9Sided.toShape()).background(MaterialTheme.colorScheme.secondaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("${g.files.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        stringResource(R.string.analysis_copies_of, g.files.size, Format.size(g.size)),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    Text(stringResource(R.string.analysis_reclaimable_amount, Format.size(g.wasted)), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            val newest = g.newest
            itemsIndexed(g.files, key = { _, f -> "d:${f.path}" }) { i, f ->
                FileRow(
                    ref = f,
                    selected = f.path in selected,
                    onToggle = { onToggle(f.path) },
                    onLongClick = { onOpenFolder(f.path.substringBeforeLast('/')) },
                    subtitle = (if (f === newest) stringResource(R.string.ops_newer) + " · " else "") + "${Format.relative(f.lastModified)} · ${f.path.substringBeforeLast('/').substringAfterLast('/')}",
                    highlight = f === newest,
                    modifier = Modifier.animateItem().cascadeIn(gi + i, key = f.path),
                )
            }
        }
    }
}

@Composable
private fun EmptyDirsTab(
    dirs: List<String>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onSelectAll: () -> Unit,
    onClear: () -> Unit,
    onTrash: () -> Unit,
    onOpenFolder: (String) -> Unit,
) {
    val selCount = remember(dirs, selected) { dirs.count { it in selected } }
    CleanupScaffold(
        totalBytes = 0,
        totalCount = dirs.size,
        selectedBytes = 0,
        selectedCount = selCount,
        onTrash = onTrash,
        selectionActions = { SelectAllActions(selCount == dirs.size, onSelectAll, onClear) },
        controls = null,
        isEmpty = dirs.isEmpty(),
        empty = { EmptyState(Icons.Rounded.FolderOff, stringResource(R.string.analysis_no_empty), message = stringResource(R.string.analysis_no_empty_body)) },
    ) {
        itemsIndexed(dirs, key = { _, d -> d }) { i, d ->
            SelectableRow(
                selected = d in selected,
                onToggle = { onToggle(d) },
                onLongClick = { onOpenFolder(d) },
                leading = { FileIcon(FileType.FOLDER, name = d.substringAfterLast('/'), size = 48.dp) },
                title = d.substringAfterLast('/'),
                subtitle = d.substringBeforeLast('/').substringAfterLast('/').ifEmpty { "/" },
                modifier = Modifier.animateItem().cascadeIn(i, key = d),
            )
        }
    }
}

@Composable
private fun FileRow(
    ref: FileRef,
    selected: Boolean,
    onToggle: () -> Unit,
    onLongClick: () -> Unit,
    subtitle: String,
    modifier: Modifier = Modifier,
    highlight: Boolean = false,
) {
    val entry = remember(ref) { FileEntry(ref.path, ref.name, false, ref.size, ref.lastModified) }
    SelectableRow(
        selected = selected,
        onToggle = onToggle,
        onLongClick = onLongClick,
        leading = { FileThumbnail(entry, size = 48.dp) },
        title = ref.name,
        subtitle = subtitle,
        highlight = highlight,
        modifier = modifier,
    )
}

@Composable
private fun SelectableRow(
    selected: Boolean,
    onToggle: () -> Unit,
    onLongClick: () -> Unit,
    leading: @Composable () -> Unit,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    highlight: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val bg by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface, label = "rowBg",
    )
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .clip(MaterialTheme.shapes.large)
            .background(bg)
            .combinedClickable(interactionSource = interaction, indication = androidx.compose.material3.ripple(), onClick = onToggle, onLongClick = onLongClick)
            .selectionSemantics(selected, onToggle)
            .pressScale(interaction, 0.97f)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Checkbox(checked = selected, onCheckedChange = { onToggle() })
    }
}

@Composable
private fun LargeThresholdControl(
    currentMb: Int,
    histogram: SizeHistogram,
    stats: (Long) -> Pair<Int, Long>,
    onChange: (Int) -> Unit,
) {
    var index by remember(currentMb) {
        mutableStateOf(LARGE_STEPS.indexOfFirst { it >= currentMb }.let { if (it < 0) LARGE_STEPS.lastIndex else it }.toFloat())
    }
    val haptics = rememberHaptics()
    val mb = LARGE_STEPS[index.toInt().coerceIn(0, LARGE_STEPS.lastIndex)]
    // Live while dragging: counted on the pool, the list itself is rebuilt when the finger lifts.
    val (count, bytes) = remember(mb, stats) { stats(mb * MB) }
    Column(Modifier.padding(horizontal = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.analysis_min_size_value, if (mb >= 1000) stringResource(R.string.settings_gb_value, mb / 1000) else stringResource(R.string.settings_mb_value, mb)),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${Format.items(count)} · ${Format.size(bytes)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        SizeHistogramBars(histogram, fromBytes = MB, thresholdBytes = mb * MB)
        Slider(
            value = index,
            onValueChange = {
                if (it.toInt() != index.toInt()) haptics.segment()
                index = it
            },
            onValueChangeFinished = { onChange(mb) },
            valueRange = 0f..LARGE_STEPS.lastIndex.toFloat(),
            steps = LARGE_STEPS.size - 2,
        )
    }
}

/** Bytes per size bucket (log2) from [fromBytes]; the buckets holding files above the threshold are lit. */
@Composable
private fun SizeHistogramBars(histogram: SizeHistogram, fromBytes: Long, thresholdBytes: Long) {
    val buckets = remember(histogram, fromBytes) { histogram.buckets().filter { it.first >= fromBytes } }
    if (buckets.isEmpty()) return
    val maxBytes = buckets.maxOf { it.third }.coerceAtLeast(1)
    val reduce = LocalReduceMotion.current
    Row(
        Modifier.fillMaxWidth().height(36.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        buckets.forEach { (lower, _, bytes) ->
            // Bucket [lower, 2·lower): lit when part of it is at or above the threshold.
            val lit = lower * 2 > thresholdBytes
            val color by animateColorAsState(
                if (lit) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                animationSpec = if (reduce) snap() else spring(),
                label = "bar",
            )
            val fraction = sqrt(bytes.toDouble() / maxBytes).toFloat().coerceIn(0.06f, 1f)
            Box(
                Modifier.weight(1f).fillMaxHeight(fraction)
                    .clip(MaterialTheme.shapes.extraSmall)
                    .background(color),
            )
        }
    }
}

@Composable
private fun OldThresholdControl(
    currentDays: Int,
    currentMinKb: Int,
    floorBytes: Long,
    onChangeSize: (Int) -> Unit,
    onChange: (Int) -> Unit,
) {
    val haptics = rememberHaptics()
    Column(Modifier.padding(horizontal = 24.dp)) {
        Text(stringResource(R.string.analysis_old_since), style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OLD_STEPS.forEach { (days, label) ->
                FilterChip(
                    selected = currentDays == days,
                    onClick = { haptics.tick(); onChange(days) },
                    label = { Text(stringResource(label)) },
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(stringResource(R.string.analysis_min_size), style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OLD_SIZE_STEPS.forEach { kb ->
                FilterChip(
                    selected = currentMinKb == kb,
                    enabled = kb * 1024L >= floorBytes || kb == OLD_SIZE_STEPS.last(),
                    onClick = { haptics.tick(); onChangeSize(kb) },
                    label = { Text(kbLabel(kb)) },
                )
            }
        }
        FloorNote(floorBytes)
    }
}

@Composable
private fun DuplicateThresholdControl(
    currentKb: Int,
    floorBytes: Long,
    progress: Pair<Int, Int>?,
    onChange: (Int) -> Unit,
) {
    val steps = remember(floorBytes) { DUP_STEPS.filter { it * 1024L >= floorBytes }.ifEmpty { listOf(DUP_STEPS.last()) } }
    var index by remember(currentKb, steps) {
        mutableStateOf(steps.indexOfFirst { it >= currentKb }.let { if (it < 0) steps.lastIndex else it }.toFloat())
    }
    val haptics = rememberHaptics()
    val kb = steps[index.toInt().coerceIn(0, steps.lastIndex)]
    Column(Modifier.padding(horizontal = 24.dp)) {
        Text(stringResource(R.string.analysis_min_size_value, kbLabel(kb)), style = MaterialTheme.typography.labelLarge)
        if (steps.size > 1) {
            Slider(
                value = index,
                onValueChange = {
                    if (it.toInt() != index.toInt()) haptics.segment()
                    index = it
                },
                onValueChangeFinished = { onChange(kb) },
                valueRange = 0f..steps.lastIndex.toFloat(),
                steps = (steps.size - 2).coerceAtLeast(0),
            )
        }
        AnimatedVisibility(progress != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            val (done, total) = progress ?: (0 to 0)
            Column {
                Text(
                    if (total > 0) stringResource(R.string.analysis_comparing_small, done, total) else stringResource(R.string.analysis_same_size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                if (total > 0) LinearWavyProgressIndicator(progress = { done.toFloat() / total }, modifier = Modifier.fillMaxWidth())
                else LinearWavyProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        FloorNote(floorBytes)
    }
}

/** Shown only when the storage held so many files that the pool had to drop its smallest ones. */
@Composable
private fun FloorNote(floorBytes: Long) {
    if (floorBytes <= StorageAnalyzer.POOL_MIN_BYTES) return
    Text(
        stringResource(R.string.analysis_many_files, Format.size(floorBytes)),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp),
    )
}
