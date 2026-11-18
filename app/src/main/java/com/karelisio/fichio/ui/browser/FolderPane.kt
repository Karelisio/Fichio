package com.karelisio.fichio.ui.browser

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.karelisio.fichio.container
import com.karelisio.fichio.core.fs.StoragePaths
import com.karelisio.fichio.core.fs.Volume
import com.karelisio.fichio.core.model.FileEntry
import com.karelisio.fichio.core.model.FileType
import com.karelisio.fichio.core.model.ViewMode
import com.karelisio.fichio.core.util.Format
import com.karelisio.fichio.shizuku.ShizukuState
import com.karelisio.fichio.shizuku.ShizukuStatusPanel
import com.karelisio.fichio.ui.components.AnimatedCounter
import com.karelisio.fichio.ui.components.EmptyState
import com.karelisio.fichio.ui.components.FileIcon
import com.karelisio.fichio.ui.components.FlyRegistry
import com.karelisio.fichio.ui.components.cascadeIn
import com.karelisio.fichio.ui.components.flyTarget
import com.karelisio.fichio.ui.components.popOn
import com.karelisio.fichio.ui.components.rememberHaptics
import com.karelisio.fichio.ui.theme.LocalReduceMotion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Drag & drop between the two panes (landscape dual view). */
class PaneDragState {
    var entries by mutableStateOf<List<FileEntry>>(emptyList())
    var position by mutableStateOf(Offset.Zero)
    var hovered by mutableStateOf<String?>(null)
    var sourcePane by mutableStateOf(-1L)
    val active get() = entries.isNotEmpty()
}

val LocalPaneDrag = staticCompositionLocalOf<PaneDragState?> { null }

/**
 * One browser pane: breadcrumbs + the folder content.
 *
 * Signature animation #1 — "drawer": opening a folder morphs its row into the header card of
 * the new listing (shared bounds) while the content slides out below it in a cascade, like a
 * drawer being pulled. Going back closes the drawer; the back gesture *drives* it (predictive
 * back via [SeekableTransitionState]).
 */
@Composable
fun FolderPane(
    pane: PaneState,
    favorites: Set<String>,
    volumes: List<Volume>,
    active: Boolean,
    dualPane: Boolean,
    onOpenFile: (FileEntry) -> Unit,
    onDrop: (List<FileEntry>, String) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    onActivate: () -> Unit = {},
) {
    val reduce = LocalReduceMotion.current
    val seek = remember(pane.id) { SeekableTransitionState(pane.path) }
    val transition = rememberTransition(seek, label = "drawer")
    var backGesture by remember { mutableStateOf(false) }
    val haptics = rememberHaptics()

    LaunchedEffect(pane.path) {
        if (!backGesture && seek.currentState != pane.path) {
            runCatching { if (reduce) seek.snapTo(pane.path) else seek.animateTo(pane.path) }
        }
    }

    BackHandler(enabled = active && pane.inSelection) { pane.clearSelection() }

    PredictiveBackHandler(enabled = active && pane.canGoBack && !pane.inSelection) { events ->
        val prev = pane.previousPath ?: return@PredictiveBackHandler
        backGesture = true
        try {
            events.collect { e -> runCatching { seek.seekTo(e.progress, prev) } }
            pane.back()
            haptics.tick()
            runCatching { if (reduce) seek.snapTo(prev) else seek.animateTo(prev) }
        } catch (e: CancellationException) {
            runCatching { seek.animateTo(seek.currentState) }
        } finally {
            backGesture = false
        }
    }

    Column(modifier) {
        Breadcrumbs(pane.path, volumes, onJump = { onActivate(); pane.jumpTo(it) })
        SharedTransitionLayout(Modifier.weight(1f)) {
            transition.AnimatedContent(
                transitionSpec = { drawerTransition(reduce) },
                contentKey = { it },
                modifier = Modifier.fillMaxSize(),
            ) { path ->
                FolderContent(
                    pane = pane,
                    path = path,
                    favorites = favorites,
                    volumes = volumes,
                    dualPane = dualPane,
                    sharedScope = this@SharedTransitionLayout,
                    animScope = this,
                    contentPadding = contentPadding,
                    onOpenFile = onOpenFile,
                    onDrop = onDrop,
                    onActivate = onActivate,
                )
            }
        }
    }
}

private fun AnimatedContentTransitionScope<String>.drawerTransition(reduce: Boolean): ContentTransform {
    if (reduce) return fadeIn(tween(90)) togetherWith fadeOut(tween(90))
    val forward = targetState.startsWith(initialState.trimEnd('/') + "/")
    val backward = initialState.startsWith(targetState.trimEnd('/') + "/")
    val spec = spring<IntSize>(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow)
    return when {
        forward -> (fadeIn(tween(220, delayMillis = 40)) +
            expandVertically(spec, expandFrom = Alignment.Top) +
            slideInVertically(spring(0.9f, Spring.StiffnessMediumLow)) { -it / 14 }) togetherWith
            (fadeOut(tween(150)) + scaleOut(tween(260), targetScale = 0.94f))
        backward -> (fadeIn(tween(200)) + scaleIn(spring(0.85f, Spring.StiffnessMediumLow), initialScale = 0.94f)) togetherWith
            (fadeOut(tween(200, delayMillis = 40)) + shrinkVertically(spec, shrinkTowards = Alignment.Top))
        else -> fadeIn(tween(220)) togetherWith fadeOut(tween(160))
    }.apply { targetContentZIndex = if (forward) 1f else 0f }.using(SizeTransform(clip = false))
}

/** Breadcrumb trail, horizontally scrollable, auto-scrolled to the current folder. */
@Composable
fun Breadcrumbs(path: String, volumes: List<Volume>, onJump: (String) -> Unit, modifier: Modifier = Modifier) {
    val segments = remember(path, volumes) { StoragePaths.segments(path, volumes) }
    val scroll = rememberScrollState()
    LaunchedEffect(path) { scroll.animateScrollTo(scroll.maxValue) }
    LaunchedEffect(scroll.maxValue) { scroll.scrollTo(scroll.maxValue) }
    Row(
        modifier.fillMaxWidth().horizontalScroll(scroll).padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        segments.forEachIndexed { i, (p, label) ->
            val last = i == segments.lastIndex
            Surface(
                onClick = { onJump(p) },
                shape = RoundedCornerShape(50),
                color = if (last) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0f),
                modifier = Modifier.flyTarget("crumb:$p"),
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (last) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                    maxLines = 1,
                )
            }
            if (!last) Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight, null,
                Modifier.size(18.dp), tint = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun FolderContent(
    pane: PaneState,
    path: String,
    favorites: Set<String>,
    volumes: List<Volume>,
    dualPane: Boolean,
    sharedScope: SharedTransitionScope,
    animScope: AnimatedVisibilityScope,
    contentPadding: PaddingValues,
    onOpenFile: (FileEntry) -> Unit,
    onDrop: (List<FileEntry>, String) -> Unit,
    onActivate: () -> Unit,
) {
    val listing = pane.listing(path)
    val view = pane.view(path)
    val haptics = rememberHaptics()
    val openedAt = remember(path) { SystemClock.uptimeMillis() }
    val isCurrent = path == pane.path

    val actions = remember(pane, path) {
        ItemActions(
            onClick = { e ->
                onActivate()
                if (pane.inSelection) { pane.toggle(e); haptics.tick() }
                else if (e.isDirectory) pane.open(e.path)
                else onOpenFile(e)
            },
            onLongClick = { e -> onActivate(); haptics.longPress(); pane.select(e) },
        )
    }

    // Drawer pairing: folder rows in the parent ↔ header card of the child.
    @Composable
    fun Modifier.drawer(key: String): Modifier = with(sharedScope) {
        this@drawer.sharedBounds(
            rememberSharedContentState("drawer-$key"),
            animScope,
            resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
            boundsTransform = { _, _ -> spring(dampingRatio = 0.84f, stiffness = Spring.StiffnessMediumLow) },
        )
    }

    Box(Modifier.fillMaxSize().flyTarget("pane:${pane.id}:$path")) {
        when (listing) {
            Listing.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                FolderHeader(path, null, volumes, Modifier.align(Alignment.TopCenter).drawer(path), pane.receivedTick)
                LoadingIndicator(Modifier.size(56.dp))
            }
            is Listing.Error -> Column(Modifier.fillMaxSize()) {
                FolderHeader(path, null, volumes, Modifier.drawer(path), pane.receivedTick)
                if (listing.restricted) {
                    val state by LocalContext.current.container.shizuku.state.collectAsStateWithLifecycle()
                    LaunchedEffect(state) { if (state == ShizukuState.READY) pane.load(path) }
                    ShizukuStatusPanel(Modifier.padding(16.dp))
                } else {
                    EmptyState(
                        Icons.Rounded.ErrorOutline, "Impossible d'ouvrir ce dossier", message = listing.message,
                        actionLabel = "Réessayer", onAction = { pane.load(path) },
                    )
                }
            }
            is Listing.Loaded -> {
                val entries = listing.entries
                val selection = if (isCurrent) pane.selection else emptyMap()
                val header: @Composable (Modifier) -> Unit = { m ->
                    Column {
                        FolderHeader(path, listing, volumes, m.drawer(path), pane.receivedTick)
                        // SD card / USB key that java.io cannot write to: offer the SAF grant.
                        RemovableAccessBanner(path)
                    }
                }
                if (entries.isEmpty()) {
                    Column(Modifier.fillMaxSize()) {
                        header(Modifier)
                        EmptyState(Icons.Rounded.FolderOff, "Dossier vide", message = if (listing.hiddenCount > 0) "${listing.hiddenCount} élément(s) caché(s)" else null)
                    }
                } else when (view.mode) {
                    ViewMode.LIST -> {
                        val state = pane.listState(path)
                        LazyColumn(
                            state = state,
                            contentPadding = contentPadding,
                            modifier = Modifier.fillMaxSize().dragGestures(pane, path, entries, dualPane, onDrop, listState = state, gridState = null),
                        ) {
                            item(key = "#header", contentType = "header") { header(Modifier) }
                            itemsIndexed(entries, key = { _, e -> e.path }, contentType = { _, e -> if (e.isDirectory) 0 else 1 }) { i, e ->
                                val cascade = remember { SystemClock.uptimeMillis() - openedAt < 450 }
                                FileListRow(
                                    e, e.path in selection, e.path in favorites, actions,
                                    Modifier.animateItem().cascadeIn(i, path, cascade),
                                    drawerKey = if (e.isDirectory) Modifier.drawer(e.path) else Modifier,
                                )
                            }
                        }
                    }
                    ViewMode.GRID, ViewMode.LARGE -> {
                        val state = pane.gridState(path)
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(if (view.mode == ViewMode.GRID) 104.dp else 160.dp),
                            state = state,
                            contentPadding = PaddingValues(
                                start = 8.dp, end = 8.dp,
                                bottom = contentPadding.calculateBottomPadding(),
                            ),
                            modifier = Modifier.fillMaxSize().dragGestures(pane, path, entries, dualPane, onDrop, listState = null, gridState = state),
                        ) {
                            item(key = "#header", span = { GridItemSpan(maxLineSpan) }, contentType = "header") { header(Modifier) }
                            itemsIndexed(entries, key = { _, e -> e.path }, contentType = { _, e -> if (e.isDirectory) 0 else 1 }) { i, e ->
                                val cascade = remember { SystemClock.uptimeMillis() - openedAt < 450 }
                                val m = Modifier.animateItem().cascadeIn(i, path, cascade)
                                val dk = if (e.isDirectory) Modifier.drawer(e.path) else Modifier
                                if (view.mode == ViewMode.GRID) FileGridCell(e, e.path in selection, e.path in favorites, actions, m, dk)
                                else FileLargeCell(e, e.path in selection, e.path in favorites, actions, m, dk)
                            }
                        }
                    }
                }
                if (entries.size > 60) {
                    FastScroller(
                        count = entries.size,
                        firstVisible = { pane.listState(path).takeIf { view.mode == ViewMode.LIST }?.firstVisibleItemIndex ?: pane.gridState(path).firstVisibleItemIndex },
                        scrollTo = { idx ->
                            if (view.mode == ViewMode.LIST) pane.listState(path).scrollToItem(idx + 1) else pane.gridState(path).scrollToItem(idx + 1)
                        },
                        label = { idx -> entries.getOrNull(idx)?.name?.firstOrNull()?.uppercaseChar()?.toString() ?: "" },
                        modifier = Modifier.align(Alignment.CenterEnd),
                    )
                }
            }
        }
    }
}

/** The "drawer front": big card with the folder's shape, name and live stats. */
@Composable
private fun FolderHeader(path: String, listing: Listing.Loaded?, volumes: List<Volume>, modifier: Modifier, tick: Int) {
    val name = remember(path, volumes) { StoragePaths.displayName(path, volumes) }
    val arrival = FlyRegistry.arrivals[path]
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .flyTarget("header:$path")
            .popOn((arrival ?: 0) + tick, amount = 0.06f),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            FileIcon(FileType.FOLDER, name = StoragePaths.name(path), size = 52.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (listing != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AnimatedCounter(
                            "${listing.folders + listing.files}",
                            style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                        )
                        Text(
                            buildString {
                                append(if (listing.folders + listing.files > 1) " éléments" else " élément")
                                if (listing.files > 0) append(" · ${Format.size(listing.totalFileSize)}")
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    Text("…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/**
 * Long-press then drag: range selection (single pane), or drag & drop of the selection to the
 * other pane / a folder (dual pane). Auto-scrolls near the edges.
 */
private fun Modifier.dragGestures(
    pane: PaneState,
    path: String,
    entries: List<FileEntry>,
    dualPane: Boolean,
    onDrop: (List<FileEntry>, String) -> Unit,
    listState: LazyListState?,
    gridState: LazyGridState?,
): Modifier = composed {
    val drag = LocalPaneDrag.current
    val haptics = rememberHaptics()
    val density = LocalDensity.current
    val edge = with(density) { 64.dp.toPx() }
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var autoScroll by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(autoScroll != 0f) {
        while (isActive && autoScroll != 0f) {
            listState?.scrollBy(autoScroll) ?: gridState?.scrollBy(autoScroll)
            delay(16)
        }
    }

    fun indexAt(offset: Offset): Int? {
        val key = listState?.layoutInfo?.visibleItemsInfo?.firstOrNull { offset.y.toInt() in it.offset..(it.offset + it.size) }?.key
            ?: gridState?.layoutInfo?.visibleItemsInfo?.firstOrNull {
                offset.y.toInt() in it.offset.y..(it.offset.y + it.size.height) && offset.x.toInt() in it.offset.x..(it.offset.x + it.size.width)
            }?.key
        return (key as? String)?.let { k -> entries.indexOfFirst { it.path == k } }?.takeIf { it >= 0 }
    }

    var anchor by remember { mutableStateOf(-1) }
    var lastIndex by remember { mutableStateOf(-1) }
    var base by remember { mutableStateOf(emptySet<String>()) }

    onGloballyPositioned { coords = it }.pointerInput(entries, dualPane, path) {
        detectDragGesturesAfterLongPress(
            onDragStart = { offset ->
                val idx = indexAt(offset) ?: return@detectDragGesturesAfterLongPress
                val e = entries[idx]
                if (dualPane && drag != null) {
                    if (!pane.selection.containsKey(e.path)) pane.select(e)
                    drag.entries = pane.selectedEntries()
                    drag.sourcePane = pane.id
                    drag.position = coords?.localToRoot(offset) ?: offset
                    haptics.longPress()
                } else {
                    base = pane.selection.keys.toSet() - e.path
                    pane.select(e)
                    anchor = idx; lastIndex = idx
                }
            },
            onDrag = { change, _ ->
                change.consume()
                val pos = change.position
                if (drag?.active == true) {
                    val root = coords?.localToRoot(pos) ?: pos
                    drag.position = root
                    val hit = FlyRegistry.hit(root, "folder:") { k -> drag.entries.any { "folder:${it.path}" == k } }
                        ?: FlyRegistry.hit(root, "pane:") { k -> k.startsWith("pane:${pane.id}:") }
                    if (hit != drag.hovered) { drag.hovered = hit; if (hit != null) haptics.tick() }
                } else if (anchor >= 0) {
                    val h = size.height
                    autoScroll = when {
                        pos.y < edge -> -((edge - pos.y) / edge) * 28f
                        pos.y > h - edge -> ((pos.y - (h - edge)) / edge) * 28f
                        else -> 0f
                    }
                    val idx = indexAt(pos)
                    if (idx != null && idx != lastIndex) {
                        lastIndex = idx
                        pane.selectRange(base, anchor, idx)
                        haptics.segment()
                    }
                }
            },
            onDragEnd = {
                autoScroll = 0f
                if (drag?.active == true) {
                    val target = drag.hovered
                    val dropped = drag.entries
                    drag.entries = emptyList(); drag.hovered = null
                    val dest = when {
                        target == null -> null
                        target.startsWith("folder:") -> target.removePrefix("folder:")
                        else -> target.substringAfter("pane:").substringAfter(':')
                    }
                    if (dest != null && dropped.none { it.path == dest } && dropped.any { it.parentPath != dest }) {
                        FlyRegistry.suppressDestination = dest
                        onDrop(dropped, dest)
                        haptics.confirm()
                    }
                }
                anchor = -1
            },
            onDragCancel = {
                autoScroll = 0f
                drag?.let { it.entries = emptyList(); it.hovered = null }
                anchor = -1
            },
        )
    }
}

/** Draggable thumb on the right edge with a letter bubble, for long folders. */
@Composable
private fun FastScroller(
    count: Int,
    firstVisible: () -> Int,
    scrollTo: suspend (Int) -> Unit,
    label: (Int) -> String,
    modifier: Modifier = Modifier,
) {
    var dragging by remember { mutableStateOf(false) }
    var fraction by remember { mutableFloatStateOf(0f) }
    var height by remember { mutableStateOf(1) }
    val scope = rememberCoroutineScope()
    // Shown only while the list scrolls (or while dragging the thumb); hidden, it lets every
    // touch through to the list so normal scrolling is never captured.
    var lastScroll by remember { mutableStateOf(0L) }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        var first = true
        snapshotFlow { firstVisible() }.collect {
            if (!dragging) fraction = it.toFloat() / (count - 1).coerceAtLeast(1)
            if (!first) { lastScroll = System.currentTimeMillis(); visible = true }
            first = false
        }
    }
    LaunchedEffect(lastScroll, dragging) {
        if (!dragging) { delay(1500); visible = false }
    }
    val alpha by androidx.compose.animation.core.animateFloatAsState(if (visible || dragging) 1f else 0f, label = "scroller")
    val thumbH = with(LocalDensity.current) { 48.dp.toPx() }
    Box(
        modifier
            .fillMaxHeight()
            .width(28.dp)
            .padding(vertical = 96.dp)
            .onGloballyPositioned { height = it.size.height }
            .graphicsLayer { this.alpha = alpha }
            .then(if (!(visible || dragging)) Modifier else Modifier.pointerInput(count) {
                detectVerticalDragGestures(
                    onDragStart = { o -> dragging = true; fraction = (o.y / height).coerceIn(0f, 1f) },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false },
                ) { change, _ ->
                    change.consume()
                    fraction = (change.position.y / height).coerceIn(0f, 1f)
                    val idx = (fraction * (count - 1)).toInt()
                    scope.launch { scrollTo(idx) }
                }
            }),
    ) {
        val y = (fraction * (height - thumbH)).coerceAtLeast(0f)
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(end = 4.dp)
                .offset { androidx.compose.ui.unit.IntOffset(0, y.toInt()) }
                .size(width = if (dragging) 8.dp else 5.dp, height = 48.dp)
                .clip(RoundedCornerShape(50))
                .background(if (dragging) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
        )
        if (dragging) {
            Surface(
                shape = RoundedCornerShape(topStart = 50.dp, bottomStart = 50.dp, topEnd = 12.dp, bottomEnd = 50.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset { androidx.compose.ui.unit.IntOffset(-(64.dp.roundToPx()), y.toInt() - 8.dp.roundToPx()) }
                    .size(56.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(label((fraction * (count - 1)).toInt()), style = MaterialTheme.typography.headlineSmall)
                }
            }
        }
    }
}

