package com.karelisio.fichio.trash

import com.karelisio.fichio.ui.components.selectionSemantics
import androidx.compose.foundation.layout.heightIn
import com.karelisio.fichio.core.util.Strings
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import com.karelisio.fichio.R
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.RestoreFromTrash
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.karelisio.fichio.container
import com.karelisio.fichio.core.fs.StoragePaths
import com.karelisio.fichio.core.model.FileEntry
import com.karelisio.fichio.core.util.Format
import com.karelisio.fichio.data.db.TrashEntity
import com.karelisio.fichio.data.prefs.FichioSettings
import com.karelisio.fichio.ui.components.BackButton
import com.karelisio.fichio.ui.components.EmptyState
import com.karelisio.fichio.ui.components.FileThumbnail
import com.karelisio.fichio.ui.components.ListContentPadding
import com.karelisio.fichio.ui.components.bouncy
import com.karelisio.fichio.ui.components.cascadeIn
import com.karelisio.fichio.ui.components.rememberHaptics
import com.karelisio.fichio.ui.theme.LocalReduceMotion
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Per-item stagger of the whirlpool, as a fraction of one item's travel. */
private const val VORTEX_STAGGER = 0.07f
private const val VORTEX_ITEM_MS = 720

/** Anticipating ease-in: items lean back a little, then get sucked in. */
private val SuckIn = CubicBezierEasing(0.55f, -0.35f, 0.75f, 0.45f)

@Composable
fun TrashScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val c = remember(context) { context.container }
    val ops = c.operations
    val haptics = rememberHaptics()
    val reduce = LocalReduceMotion.current
    val scope = rememberCoroutineScope()

    val items by remember { c.trash.items }.collectAsStateWithLifecycle(initialValue = null)
    val totalSize by remember { c.trash.totalSize }.collectAsStateWithLifecycle(initialValue = 0L)
    val settings by remember { c.settings.settings }.collectAsStateWithLifecycle(initialValue = FichioSettings())

    var selected by remember { mutableStateOf(emptySet<String>()) }
    var confirmEmpty by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<List<String>?>(null) }
    /** Frozen list shown while the whirlpool plays (the real one empties underneath). */
    var vortexItems by remember { mutableStateOf<List<TrashEntity>?>(null) }
    val vortexClock = remember { Animatable(0f) }
    val listState = rememberLazyListState()
    val centers = remember { HashMap<String, Offset>() }
    var containerCenter by remember { mutableStateOf(Offset.Zero) }

    val shown = vortexItems ?: items
    val selecting = selected.isNotEmpty()
    // Forget selections of items that disappeared.
    LaunchedEffect(items) {
        val ids = items.orEmpty().mapTo(HashSet()) { it.id }
        if (selected.any { it !in ids }) selected = selected.filterTo(HashSet()) { it in ids }
    }

    BackHandler(selecting) { selected = emptySet() }

    fun restore(ids: List<String>) {
        haptics.confirm()
        ops.restoreFromTrash(ids)
        selected = emptySet()
    }

    fun startVortex() {
        val list = items ?: return
        haptics.longPress()
        if (reduce || list.isEmpty()) {
            ops.emptyTrash(); return
        }
        scope.launch {
            vortexItems = list
            vortexClock.snapTo(0f)
            val job = ops.emptyTrash()
            val visible = listState.layoutInfo.visibleItemsInfo.size.coerceAtLeast(1)
            val end = 1f + VORTEX_STAGGER * visible
            vortexClock.animateTo(end, tween((VORTEX_ITEM_MS * end).toInt(), easing = LinearEasing))
            haptics.confirm()
            ops.await(job)
            vortexItems = null
        }
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            MediumFlexibleTopAppBar(
                title = {
                    AnimatedContent(selecting, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "title") { sel ->
                        Text(if (sel) pluralStringResource(R.plurals.common_selected_count, selected.size, selected.size) else stringResource(R.string.shortcut_trash))
                    }
                },
                subtitle = {
                    val n = items?.size ?: 0
                    Text(if (n == 0) stringResource(R.string.common_empty) else stringResource(R.string.trash_summary, Format.items(n), Format.size(totalSize)))
                },
                navigationIcon = {
                    if (selecting) IconButton(onClick = { selected = emptySet() }) { Icon(Icons.Rounded.Close, stringResource(R.string.browser_cancel_selection)) }
                    else BackButton(onBack)
                },
                actions = {
                    if (selecting) {
                        IconButton(onClick = { haptics.tick(); selected = items.orEmpty().map { it.id }.toSet() }, shapes = IconButtonDefaults.shapes()) {
                            Icon(Icons.Rounded.SelectAll, stringResource(R.string.browser_select_all))
                        }
                    } else if (!items.isNullOrEmpty() && vortexItems == null) {
                        TextButton(onClick = { haptics.click(); confirmEmpty = true }, shapes = ButtonDefaults.shapes()) {
                            Icon(Icons.Rounded.DeleteSweep, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.trash_empty_action))
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        bottomBar = {
            AnimatedVisibility(
                selecting,
                enter = slideInVertically(bouncy()) { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
            ) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 3.dp) {
                    Row(
                        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        FilledTonalButton(
                            onClick = { haptics.click(); confirmDelete = selected.toList() },
                            shapes = ButtonDefaults.shapes(), modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                        ) {
                            Icon(Icons.Rounded.DeleteForever, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.common_delete))
                        }
                        Button(
                            onClick = { restore(selected.toList()) },
                            shapes = ButtonDefaults.shapes(), modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                        ) {
                            Icon(Icons.Rounded.RestoreFromTrash, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.trash_restore))
                        }
                    }
                }
            }
        },
    ) { padding ->
        Box(
            Modifier.fillMaxSize().padding(padding)
                .onGloballyPositioned { containerCenter = it.boundsInRoot().center },
        ) {
            when {
                shown == null -> LoadingIndicator(Modifier.align(Alignment.Center).size(64.dp))
                shown.isEmpty() -> EmptyState(
                    icon = Icons.Rounded.DeleteOutline,
                    title = stringResource(R.string.trash_empty_title),
                    message = if (settings.trashEnabled)
                        pluralStringResource(R.plurals.trash_retention_body, settings.trashRetentionDays, settings.trashRetentionDays)
                    else stringResource(R.string.trash_disabled_body),
                    modifier = Modifier.align(Alignment.Center),
                )
                else -> {
                    if (vortexItems != null) VortexCore(vortexClock, Modifier.align(Alignment.Center))
                    LazyColumn(
                        state = listState,
                        contentPadding = ListContentPadding,
                        userScrollEnabled = vortexItems == null,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        if (!settings.trashEnabled) {
                            item(key = "disabled") { DisabledBanner() }
                        }
                        itemsIndexed(shown, key = { _, it -> it.id }) { index, item ->
                            val order = index - listState.firstVisibleItemIndex
                            TrashRow(
                                item = item,
                                index = index,
                                selected = item.id in selected,
                                selecting = selecting,
                                daysLeft = TrashStore.daysLeft(item, settings.trashRetentionDays),
                                onToggle = {
                                    haptics.tick()
                                    selected = if (item.id in selected) selected - item.id else selected + item.id
                                },
                                onRestore = { restore(listOf(item.id)) },
                                onDelete = { confirmDelete = listOf(item.id) },
                                modifier = Modifier
                                    .animateItem()
                                    .onGloballyPositioned { centers[item.id] = it.boundsInRoot().center }
                                    .graphicsLayer {
                                        if (vortexItems == null) return@graphicsLayer
                                        val raw = (vortexClock.value - order.coerceAtLeast(0) * VORTEX_STAGGER).coerceIn(0f, 1f)
                                        val p = SuckIn.transform(raw).coerceIn(-0.2f, 1f)
                                        val center = centers[item.id] ?: return@graphicsLayer
                                        // Offset of the item relative to the whirlpool centre, rotated and shrunk.
                                        val ox = center.x - containerCenter.x
                                        val oy = center.y - containerCenter.y
                                        val theta = p * 1.7f * PI.toFloat() * (if (index % 2 == 0) 1f else 1.15f)
                                        val r = 1f - p
                                        val nx = (ox * cos(theta) - oy * sin(theta)) * r
                                        val ny = (ox * sin(theta) + oy * cos(theta)) * r
                                        translationX = nx - ox
                                        translationY = ny - oy
                                        rotationZ = p * 540f
                                        val s = (1f - p).coerceIn(0.02f, 1.08f)
                                        scaleX = s; scaleY = s
                                        alpha = (1f - raw * raw * raw).coerceIn(0f, 1f)
                                    },
                            )
                        }
                    }
                }
            }
        }
    }

    if (confirmEmpty) {
        val n = items?.size ?: 0
        AlertDialog(
            onDismissRequest = { confirmEmpty = false },
            icon = { Icon(Icons.Rounded.DeleteSweep, null) },
            title = { Text(stringResource(R.string.trash_empty_confirm_title)) },
            text = { Text(stringResource(R.string.trash_empty_confirm_body, Format.items(n), Format.size(totalSize))) },
            confirmButton = {
                Button(
                    onClick = { confirmEmpty = false; startVortex() },
                    shapes = ButtonDefaults.shapes(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) { Text(stringResource(R.string.trash_empty_action)) }
            },
            dismissButton = { TextButton(onClick = { confirmEmpty = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }

    confirmDelete?.let { ids ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            icon = { Icon(Icons.Rounded.DeleteForever, null) },
            title = { Text(stringResource(R.string.trash_delete_confirm_title)) },
            text = { Text(pluralStringResource(R.plurals.trash_delete_confirm_body, ids.size, ids.size)) },
            confirmButton = {
                Button(
                    onClick = {
                        haptics.confirm()
                        ops.deleteFromTrash(ids)
                        selected = selected - ids.toSet()
                        confirmDelete = null
                    },
                    shapes = ButtonDefaults.shapes(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

/** The spinning burst the items are sucked into. */
@Composable
private fun VortexCore(clock: Animatable<Float, *>, modifier: Modifier = Modifier) {
    Box(
        modifier.size(120.dp).graphicsLayer {
            val t = clock.value
            rotationZ = t * 720f
            val grow = (t / 0.25f).coerceIn(0f, 1f)
            val fade = (1f - (t - 1f).coerceAtLeast(0f) * 3f).coerceIn(0f, 1f)
            val s = grow * fade
            scaleX = s; scaleY = s
            alpha = 0.85f * fade
        }.clip(MaterialShapes.SoftBurst.toShape()).background(MaterialTheme.colorScheme.primaryContainer),
    )
}

@Composable
private fun DisabledBanner() {
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Info, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Spacer(Modifier.width(12.dp))
            Text(
                stringResource(R.string.trash_disabled_new_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

private fun originLabel(originalPath: String): String {
    val parent = StoragePaths.parent(originalPath)
    val primary = runCatching { StoragePaths.primary }.getOrDefault("")
    return when {
        primary.isNotEmpty() && parent == primary -> Strings.get(R.string.storage_internal)
        primary.isNotEmpty() && parent.startsWith("$primary/") -> Strings.get(R.string.storage_internal) + "/" + parent.removePrefix("$primary/")
        else -> parent
    }
}

@Composable
private fun TrashRow(
    item: TrashEntity,
    index: Int,
    selected: Boolean,
    selecting: Boolean,
    daysLeft: Int,
    onToggle: () -> Unit,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val bg by animateColorAsState(if (selected) scheme.secondaryContainer else scheme.surface, label = "rowBg")
    val thumbScale by animateFloatAsState(if (selected) 0.86f else 1f, bouncy(), label = "thumb")
    val entry = remember(item) { FileEntry(item.trashPath, item.name, item.isDirectory, item.size, item.deletedAt) }

    Box(modifier.cascadeIn(index.coerceAtMost(14), item.id)) {
        Row(
            Modifier.fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 2.dp)
                .clip(MaterialTheme.shapes.large)
                .background(bg)
                .combinedClickable(
                    onClick = { if (selecting) onToggle() else menu = true },
                    onLongClick = onToggle,
                )
                .selectionSemantics(selected, onToggle)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(contentAlignment = Alignment.Center) {
                FileThumbnail(entry, Modifier.graphicsLayer { scaleX = thumbScale; scaleY = thumbScale }, size = 48.dp)
                androidx.compose.animation.AnimatedVisibility(selected, enter = scaleIn(bouncy()), exit = scaleOut()) {
                    Box(
                        Modifier.size(26.dp).clip(CircleShape).background(scheme.primary),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Rounded.Check, null, Modifier.size(16.dp), tint = scheme.onPrimary) }
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(item.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                Text(
                    stringResource(R.string.trash_item_deleted, Format.relative(item.deletedAt), if (item.isDirectory) stringResource(R.string.trash_item_folder_prefix) else "", Format.size(item.size)),
                    style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1,
                )
                Text(
                    originLabel(item.originalPath),
                    style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.StartEllipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            val urgent = daysLeft <= 3
            Text(
                if (daysLeft == 0) stringResource(R.string.trash_today) else stringResource(R.string.trash_days_left, daysLeft),
                style = MaterialTheme.typography.labelMedium,
                color = if (urgent) scheme.onErrorContainer else scheme.onSurfaceVariant,
                modifier = Modifier.clip(CircleShape)
                    .background(if (urgent) scheme.errorContainer else scheme.surfaceContainerHighest)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.trash_restore)) },
                leadingIcon = { Icon(Icons.Rounded.RestoreFromTrash, null) },
                onClick = { menu = false; onRestore() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.browser_delete_permanently)) },
                leadingIcon = { Icon(Icons.Rounded.DeleteForever, null) },
                onClick = { menu = false; onDelete() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.common_select)) },
                leadingIcon = { Icon(Icons.Rounded.SelectAll, null) },
                onClick = { menu = false; onToggle() },
            )
        }
    }
}
