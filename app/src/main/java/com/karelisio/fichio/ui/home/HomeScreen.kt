package com.karelisio.fichio.ui.home

import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import com.karelisio.fichio.R
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DataUsage
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderSpecial
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.carousel.HorizontalMultiBrowseCarousel
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.karelisio.fichio.core.fs.StoragePaths
import com.karelisio.fichio.core.model.FileCategory
import com.karelisio.fichio.core.model.FileEntry
import com.karelisio.fichio.core.util.Format
import com.karelisio.fichio.data.db.FavoriteEntity
import com.karelisio.fichio.data.db.SmartCount
import com.karelisio.fichio.preview.MiniPlayerBar
import com.karelisio.fichio.search.SmartFolder
import com.karelisio.fichio.tags.Tag
import com.karelisio.fichio.tags.TagLabel
import com.karelisio.fichio.ui.category.icon
import com.karelisio.fichio.ui.category.shape
import com.karelisio.fichio.shizuku.ShizukuState
import com.karelisio.fichio.ui.components.AnimatedCounter
import com.karelisio.fichio.ui.components.FileIcon
import com.karelisio.fichio.ui.components.FileThumbnail
import com.karelisio.fichio.ui.components.SectionHeader
import com.karelisio.fichio.ui.components.cascadeIn
import com.karelisio.fichio.ui.components.icon
import com.karelisio.fichio.ui.components.pressScale
import com.karelisio.fichio.ui.components.rememberHaptics
import com.karelisio.fichio.ui.navigation.Route
import com.karelisio.fichio.ui.navigation.openEntry
import com.karelisio.fichio.ui.navigation.sharedElementKey
import com.karelisio.fichio.ui.theme.polygon
import com.karelisio.fichio.ui.theme.toneOf

@Composable
fun HomeScreen(nav: NavHostController) {
    val vm: HomeViewModel = viewModel()
    val context = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    val recents by vm.recents.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val pinned by vm.pinned.collectAsStateWithLifecycle()
    val trashCount by vm.trashCount.collectAsStateWithLifecycle()
    val shizuku by vm.shizuku.collectAsStateWithLifecycle()
    val smart by vm.smart.collectAsStateWithLifecycle()
    val tags by vm.tags.collectAsStateWithLifecycle()
    var reorder by remember { mutableStateOf(false) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }

    fun browse(path: String) = nav.navigate(Route.Browser(path)) { launchSingleTop = true }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 120.dp),
        ) {
            item(key = "top") {
                HomeTop(onSearch = { nav.navigate(Route.Search()) }, onSettings = { nav.navigate(Route.Settings) })
            }
            item(key = "gauge") {
                GaugeCard(state, Modifier.cascadeIn(0), onClick = { nav.navigate(Route.Analysis) })
            }
            item(key = "quick") {
                QuickAccess(
                    state = state,
                    trashCount = trashCount,
                    shizuku = shizuku,
                    onOpen = ::browse,
                    onTrash = { nav.navigate(Route.Trash) },
                    onApps = { nav.navigate(Route.InstalledApps) },
                    onTransfer = { nav.navigate(Route.Transfer) },
                    modifier = Modifier.cascadeIn(1),
                )
            }
            item(key = "catTitle") { SectionHeader(stringResource(R.string.home_categories)) }
            item(key = "cats") {
                CategoryGrid(state.categories, Modifier.cascadeIn(2)) { cat ->
                    if (cat == FileCategory.DOWNLOADS) browse(StoragePaths.downloads)
                    else nav.navigate(Route.Category(cat.name))
                }
            }
            val smartShown = smart.filter { it.count > 0 }
            if (smartShown.isNotEmpty()) {
                item(key = "smartTitle") { SectionHeader(stringResource(R.string.home_smart_folders)) }
                item(key = "smart") {
                    SmartFolderRow(smartShown, Modifier.cascadeIn(3)) { kind -> nav.navigate(Route.Smart(kind.name)) }
                }
            }
            val usedTags = tags.filter { it.count > 0 }
            if (usedTags.isNotEmpty()) {
                item(key = "tagTitle") { SectionHeader(stringResource(R.string.home_tags)) }
                item(key = "tags") {
                    TagChips(usedTags, Modifier.cascadeIn(4)) { nav.navigate(Route.Tagged(it.id)) }
                }
            }
            if (recents.isNotEmpty()) {
                item(key = "recentTitle") { SectionHeader(stringResource(R.string.home_recents)) }
                item(key = "recents") { RecentCarousel(recents.take(16)) { nav.openEntry(context, it) } }
            }
            item(key = "favTitle") {
                SectionHeader(stringResource(R.string.home_favorites)) {
                    if (favorites.size > 1) TextButton(onClick = { reorder = true }) {
                        Icon(Icons.Rounded.SwapVert, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.home_reorder))
                    }
                }
            }
            if (favorites.isEmpty()) {
                item(key = "favEmpty") {
                    Text(
                        stringResource(R.string.home_favorites_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                }
            } else {
                itemsIndexed(favorites, key = { _, f -> "fav:" + f.path }) { i, f ->
                    FavoriteRow(f, Modifier.animateItem().cascadeIn(i + 3), onClick = { browse(f.path) })
                }
            }
            if (pinned.isNotEmpty()) {
                item(key = "pinTitle") { SectionHeader(stringResource(R.string.home_pinned)) }
                item(key = "pins") {
                    FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        pinned.forEach { p ->
                            Surface(onClick = { browse(p.path) }, shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                                Row(Modifier.padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Rounded.PushPin, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.width(6.dp))
                                    Text(StoragePaths.name(p.path), style = MaterialTheme.typography.labelLarge)
                                    IconButton(onClick = { vm.unpin(p.path) }, modifier = Modifier.minimumInteractiveComponentSize()) { Icon(Icons.Rounded.Close, stringResource(R.string.home_unpin), Modifier.size(16.dp)) }
                                }
                            }
                        }
                    }
                }
            }
            item(key = "footer") {
                Text(
                    if (state.indexedFiles > 0) pluralStringResource(R.plurals.home_indexed_files, state.indexedFiles, state.indexedFiles) else stringResource(R.string.home_indexing),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                )
            }
        }
        Box(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 12.dp)) {
            MiniPlayerBar(onOpen = { nav.navigate(if (it.startsWith("content://")) Route.UriPreview(it) else Route.Preview(it)) })
        }
    }

    if (reorder) ReorderFavoritesSheet(favorites, onDismiss = { reorder = false }, onDone = { vm.reorderFavorites(it); reorder = false }, onRemove = { vm.removeFavorite(it) })
}

@Composable
private fun HomeTop(onSearch: () -> Unit, onSettings: () -> Unit) {
    Column(Modifier.statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displaySmall, modifier = Modifier.weight(1f).padding(start = 4.dp))
            IconButton(onClick = onSettings) { Icon(Icons.Rounded.Settings, stringResource(R.string.settings_title)) }
        }
        Spacer(Modifier.height(8.dp))
        val interaction = remember { MutableInteractionSource() }
        Surface(
            onClick = onSearch,
            interactionSource = interaction,
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).pressScale(interaction, 0.98f).sharedElementKey("searchbar", bounds = true),
        ) {
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(14.dp))
                Text(stringResource(R.string.home_search_hint), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun GaugeCard(state: HomeState, modifier: Modifier, onClick: () -> Unit) {
    val primary = state.primary
    val total = primary?.totalBytes ?: 0
    val used = primary?.usedBytes ?: 0
    val catSum = state.categories.filter { it.category != FileCategory.DOWNLOADS }.sumOf { it.size }
    val segments = remember(state.categories, used) {
        state.categories.filter { it.category != FileCategory.DOWNLOADS }.map { GaugeSegment(it.category.name, it.size, it.category) } +
            GaugeSegment("other", (used - catSum).coerceAtLeast(0), null)
    }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(36.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            LivingGauge(total, used, segments, Modifier.fillMaxWidth(0.82f))
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                state.categories.filter { it.category != FileCategory.DOWNLOADS && it.size > 0 }.sortedByDescending { it.size }.forEach { s ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(12.dp).clip(s.category.polygon().toShape()).background(toneOf(s.category).content))
                        Spacer(Modifier.width(5.dp))
                        Text("${s.category.label} ${Format.size(s.size)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).clip(CircleShape).background(MaterialTheme.colorScheme.outline))
                    Spacer(Modifier.width(5.dp))
                    Text(stringResource(R.string.home_others_size, Format.size((used - catSum).coerceAtLeast(0))), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private data class Quick(val key: String, val label: String, val sub: String, val icon: androidx.compose.ui.graphics.vector.ImageVector, val shape: androidx.graphics.shapes.RoundedPolygon, val onClick: () -> Unit, val fraction: Float? = null)

@Composable
private fun QuickAccess(
    state: HomeState,
    trashCount: Int,
    shizuku: ShizukuState,
    onOpen: (String) -> Unit,
    onTrash: () -> Unit,
    onApps: () -> Unit,
    onTransfer: () -> Unit,
    modifier: Modifier,
) {
    val items = buildList {
        state.primary?.let { add(Quick("internal", stringResource(R.string.storage_internal), stringResource(R.string.home_free, Format.size(it.freeBytes)), Icons.Rounded.PhoneAndroid, MaterialShapes.Cookie9Sided, { onOpen(it.path) }, it.usedFraction)) }
        state.otherVolumes.forEach { v ->
            add(Quick(v.path, v.label, stringResource(R.string.home_free, Format.size(v.freeBytes)), if (v.isUsb) Icons.Rounded.Usb else Icons.Rounded.FolderSpecial, MaterialShapes.Pill, { onOpen(v.path) }, v.usedFraction))
        }
        val lock = if (shizuku == ShizukuState.READY) null else stringResource(R.string.home_via_shevery)
        add(Quick("data", "Android/data", lock ?: stringResource(R.string.home_app_data), if (lock != null) Icons.Rounded.Lock else Icons.Rounded.Folder, MaterialShapes.Clover4Leaf, { onOpen(StoragePaths.androidData) }))
        add(Quick("obb", "Android/obb", lock ?: stringResource(R.string.home_game_data), if (lock != null) Icons.Rounded.Lock else Icons.Rounded.SportsEsports, MaterialShapes.Clover8Leaf, { onOpen(StoragePaths.androidObb) }))
        add(Quick("trash", stringResource(R.string.shortcut_trash), if (trashCount > 0) Format.items(trashCount) else stringResource(R.string.common_empty), Icons.Rounded.Delete, MaterialShapes.Ghostish, onTrash))
        add(Quick("apps", stringResource(R.string.home_apps), stringResource(R.string.home_extract_apks), Icons.Rounded.Apps, MaterialShapes.Sunny, onApps))
        add(Quick("transfer", stringResource(R.string.home_wifi_transfer), stringResource(R.string.home_to_pc), Icons.Rounded.Wifi, MaterialShapes.Puffy, onTransfer))
    }
    LazyRow(modifier.padding(top = 8.dp), contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(items, key = { it.key }) { q -> QuickCard(q) }
    }
}

@Composable
private fun QuickCard(q: Quick) {
    val interaction = remember { MutableInteractionSource() }
    val haptics = rememberHaptics()
    Surface(
        onClick = { haptics.tick(); q.onClick() },
        interactionSource = interaction,
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.width(150.dp).pressScale(interaction),
    ) {
        Column(Modifier.padding(14.dp)) {
            Box(Modifier.size(44.dp).clip(q.shape.toShape()).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
                Icon(q.icon, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            Spacer(Modifier.height(10.dp))
            Text(q.label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(q.sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            if (q.fraction != null) {
                Spacer(Modifier.height(8.dp))
                val f by animateFloatAsState(q.fraction, label = "vol")
                LinearWavyProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun CategoryGrid(stats: List<CategoryStat>, modifier: Modifier, onClick: (FileCategory) -> Unit) {
    Column(modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        stats.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { s -> CategoryTile(s, Modifier.weight(1f)) { onClick(s.category) } }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun CategoryTile(s: CategoryStat, modifier: Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val tone = toneOf(s.category)
    Surface(
        onClick = onClick,
        interactionSource = interaction,
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.pressScale(interaction).sharedElementKey("cat-${s.category.name}", bounds = true),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(s.category.polygon().toShape()).background(tone.container), contentAlignment = Alignment.Center) {
                Icon(s.category.icon(), null, tint = tone.content)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(s.category.label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AnimatedCounter("${s.count}", style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant))
                    Text(" · ${Format.size(s.size)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun SmartFolderRow(items: List<SmartCount>, modifier: Modifier, onOpen: (SmartFolder) -> Unit) {
    LazyRow(modifier, contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(items, key = { it.kind }) { s ->
            val kind = runCatching { SmartFolder.valueOf(s.kind) }.getOrNull() ?: return@items
            SmartFolderCard(kind, s, Modifier.animateItem()) { onOpen(kind) }
        }
    }
}

@Composable
private fun SmartFolderCard(kind: SmartFolder, stat: SmartCount, modifier: Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val haptics = rememberHaptics()
    Surface(
        onClick = { haptics.tick(); onClick() },
        interactionSource = interaction,
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.width(156.dp).pressScale(interaction).sharedElementKey("smart-${kind.name}", bounds = true),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(kind.shape().toShape()).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
                    Icon(kind.icon(), null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                }
                Spacer(Modifier.weight(1f))
                AnimatedCounter("${stat.count}", style = MaterialTheme.typography.titleLarge.copy(color = MaterialTheme.colorScheme.primary))
            }
            Spacer(Modifier.height(10.dp))
            Text(kind.label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${kind.description} · ${Format.size(stat.size)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun TagChips(tags: List<Tag>, modifier: Modifier, onOpen: (Tag) -> Unit) {
    val haptics = rememberHaptics()
    FlowRow(modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tags.forEach { t ->
            val interaction = remember { MutableInteractionSource() }
            Surface(
                onClick = { haptics.tick(); onOpen(t) },
                interactionSource = interaction,
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.pressScale(interaction).sharedElementKey("tag-${t.id}", bounds = true),
            ) {
                TagLabel(t, showCount = true, modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp))
            }
        }
    }
}

@Composable
private fun RecentCarousel(recents: List<FileEntry>, onOpen: (FileEntry) -> Unit) {
    val state = rememberCarouselState { recents.size }
    HorizontalMultiBrowseCarousel(
        state = state,
        preferredItemWidth = 150.dp,
        itemSpacing = 8.dp,
        contentPadding = PaddingValues(horizontal = 16.dp),
        modifier = Modifier.fillMaxWidth().height(170.dp),
    ) { i ->
        val e = recents[i]
        Box(
            Modifier.fillMaxSize().maskClip(MaterialTheme.shapes.extraLarge)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable { onOpen(e) },
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                FileThumbnail(e, size = 64.dp, fill = true, shape = RoundedCornerShape(0.dp), modifier = Modifier.sharedElementKey("thumb-${e.path}"))
            }
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.92f),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
            ) {
                Text(e.name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
            }
        }
    }
}

@Composable
private fun FavoriteRow(f: FavoriteEntity, modifier: Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        interactionSource = interaction,
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp).pressScale(interaction, 0.98f),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            FileIcon(com.karelisio.fichio.core.model.FileType.FOLDER, name = StoragePaths.name(f.path), size = 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(f.label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(f.path.removePrefix(StoragePaths.primary).ifEmpty { "/" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.StartEllipsis)
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.outline)
        }
    }
}

/** Drag the handles to reorder favourites; saved on stringResource(R.string.common_done). */
@Composable
private fun ReorderFavoritesSheet(favorites: List<FavoriteEntity>, onDismiss: () -> Unit, onDone: (List<String>) -> Unit, onRemove: (String) -> Unit) {
    val items = remember(favorites) { favorites.toMutableStateList() }
    var dragIndex by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val haptics = rememberHaptics()
    val rowHeightPx = with(androidx.compose.ui.platform.LocalDensity.current) { 64.dp.toPx() }
    ModalBottomSheet(onDismissRequest = { onDone(items.map { it.path }) }) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.home_reorder_favorites), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = { onDone(items.map { it.path }) }) { Text(stringResource(R.string.common_done)) }
        }
        Column(Modifier.padding(bottom = 32.dp)) {
            items.forEachIndexed { i, f ->
                val dragging = i == dragIndex
                val scale by animateFloatAsState(if (dragging) 1.04f else 1f, label = "drag")
                Row(
                    Modifier
                        .zIndex(if (dragging) 1f else 0f)
                        .graphicsLayer { translationY = if (dragging) dragOffset else 0f; scaleX = scale; scaleY = scale; shadowElevation = if (dragging) 12f else 0f }
                        .fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (dragging) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Rounded.DragHandle, stringResource(R.string.common_move),
                        Modifier.padding(horizontal = 12.dp).pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { dragIndex = items.indexOf(f); dragOffset = 0f; haptics.longPress() },
                                onDragEnd = { dragIndex = -1; dragOffset = 0f },
                                onDragCancel = { dragIndex = -1; dragOffset = 0f },
                            ) { change, amount ->
                                change.consume()
                                dragOffset += amount.y
                                val cur = dragIndex
                                if (dragOffset > rowHeightPx * 0.6f && cur < items.lastIndex) {
                                    items.add(cur + 1, items.removeAt(cur)); dragIndex = cur + 1; dragOffset -= rowHeightPx; haptics.tick()
                                } else if (dragOffset < -rowHeightPx * 0.6f && cur > 0) {
                                    items.add(cur - 1, items.removeAt(cur)); dragIndex = cur - 1; dragOffset += rowHeightPx; haptics.tick()
                                }
                            }
                        },
                    )
                    Text(f.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    IconButton(onClick = { items.remove(f); onRemove(f.path) }) { Icon(Icons.Rounded.Close, stringResource(R.string.common_remove)) }
                }
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}
