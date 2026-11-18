package com.karelisio.fichio.ui.category

import com.karelisio.fichio.ui.components.selectionSemantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import com.karelisio.fichio.core.util.Strings
import com.karelisio.fichio.R
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Label
import androidx.compose.material.icons.automirrored.rounded.LabelOff
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Screenshot
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.sqlite.db.SimpleSQLiteQuery
import com.karelisio.fichio.container
import com.karelisio.fichio.core.fs.StoragePaths
import com.karelisio.fichio.core.model.FileCategory
import com.karelisio.fichio.core.model.FileEntry
import com.karelisio.fichio.core.model.FileType
import com.karelisio.fichio.core.util.Format
import com.karelisio.fichio.core.util.NaturalOrder
import com.karelisio.fichio.data.db.IndexedFile
import com.karelisio.fichio.search.SmartFolder
import com.karelisio.fichio.search.SmartFolders
import com.karelisio.fichio.tags.RenameTagDialog
import com.karelisio.fichio.tags.TagDots
import com.karelisio.fichio.tags.tagColor
import com.karelisio.fichio.ui.browser.SelectionCheck
import com.karelisio.fichio.ui.browser.metaLine
import com.karelisio.fichio.ui.components.AnimatedCounter
import com.karelisio.fichio.ui.components.BackButton
import com.karelisio.fichio.ui.components.EmptyState
import com.karelisio.fichio.ui.components.FileThumbnail
import com.karelisio.fichio.ui.components.cascadeIn
import com.karelisio.fichio.ui.components.icon
import com.karelisio.fichio.ui.components.rememberHaptics
import com.karelisio.fichio.ui.navigation.Opener
import com.karelisio.fichio.ui.navigation.Route
import com.karelisio.fichio.ui.navigation.openEntry
import com.karelisio.fichio.ui.navigation.sharedElementKey
import com.karelisio.fichio.ui.theme.polygon
import com.karelisio.fichio.ui.theme.toneOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private enum class CatSort(@androidx.annotation.StringRes val label: Int) { DATE(R.string.home_recents), NAME(R.string.sort_name), SIZE(R.string.sort_size) }

private fun IndexedFile.toEntry() = FileEntry(path, name, isDirectory, size, modified)

/** Header look of a collection (category, smart folder, tag). */
@Immutable
class CollectionLook(
    val title: String,
    val icon: ImageVector,
    val shape: Shape,
    val container: Color,
    val content: Color,
    /** Shared-element key of the home tile that opened it. */
    val sharedKey: String,
    /** Thumbnail grid instead of rows. */
    val visual: Boolean,
    val emptyMessage: String = Strings.get(R.string.category_index_updating),
)

/** What a collection lists, for its counters ("3 fichiers" / "3 éléments"). */
enum class CollectionNoun { FILES, ITEMS }

/** Extra selection action of a collection (e.g. remove a tag). */
class CollectionAction(val icon: ImageVector, val label: String, val onClick: (List<String>) -> Unit)

/** Files of one category, straight from the local index (instant). */
@Composable
fun CategoryScreen(nav: NavHostController, category: FileCategory) {
    val c = LocalContext.current.container
    val types = remember(category) { FileType.entries.filter { it.category == category }.map { it.name } }
    val rows by remember(types) { c.database.fileIndex().observeByTypes(types).map { l -> l.map { it.toEntry() } } }.collectAsStateWithLifecycle(null)
    val tone = toneOf(category)
    val look = CollectionLook(
        title = category.label, icon = category.icon(), shape = category.polygon().toShape(),
        container = tone.container, content = tone.content, sharedKey = "cat-${category.name}",
        visual = category == FileCategory.IMAGES || category == FileCategory.VIDEOS,
    )
    CollectionScreen(nav, look, rows)
}

/** A smart folder of the home screen (screenshots, WhatsApp, recent downloads…), from the index. */
@Composable
fun SmartFolderScreen(nav: NavHostController, kind: SmartFolder) {
    val c = LocalContext.current.container
    val rows by remember(kind) {
        val roots = c.volumes.volumes.value.map { it.path }.ifEmpty { listOf(StoragePaths.primary) }
        val q = SmartFolders.listQuery(kind, roots, StoragePaths.downloads)
        c.database.collections().observeFiles(SimpleSQLiteQuery(q.sql, q.argsArray)).map { l -> l.map { it.toEntry() } }
    }.collectAsStateWithLifecycle(null)
    val cs = MaterialTheme.colorScheme
    val look = CollectionLook(
        title = kind.label, icon = kind.icon(), shape = kind.shape().toShape(),
        container = cs.secondaryContainer, content = cs.onSecondaryContainer, sharedKey = "smart-${kind.name}",
        visual = kind != SmartFolder.RECENT_DOWNLOADS,
        emptyMessage = stringResource(R.string.category_empty_smart, kind.description),
    )
    CollectionScreen(nav, look, rows)
}

/** Files and folders carrying one colour tag (paths checked on disk: tags may outlive files). */
@Composable
fun TagScreen(nav: NavHostController, tagId: Long) {
    val c = LocalContext.current.container
    val scope = rememberCoroutineScope()
    val tags by c.tags.tags.collectAsStateWithLifecycle(emptyList())
    val tag = tags.firstOrNull { it.id == tagId }
    val rows by remember(tagId) {
        c.tags.pathsOf(tagId).map { paths -> paths.mapNotNull { c.fileSystems.stat(it) } }.flowOn(Dispatchers.IO)
    }.collectAsStateWithLifecycle(null)
    var renaming by remember { mutableStateOf(false) }
    val color = tagColor(tag?.slot ?: 0)
    val look = CollectionLook(
        title = tag?.displayName ?: stringResource(R.string.tag_generic), icon = Icons.AutoMirrored.Rounded.Label, shape = CircleShape,
        container = color.copy(alpha = 0.22f), content = color, sharedKey = "tag-$tagId",
        visual = false, emptyMessage = stringResource(R.string.tag_empty_hint),
    )
    CollectionScreen(
        nav, look, rows, itemNoun = CollectionNoun.ITEMS,
        actions = listOf(CollectionAction(Icons.AutoMirrored.Rounded.LabelOff, stringResource(R.string.tag_remove)) { paths -> scope.launch { c.tags.unassign(tagId, paths) } }),
        topAction = { IconButton(onClick = { renaming = true }, enabled = tag != null) { Icon(Icons.Rounded.Edit, stringResource(R.string.tag_rename)) } },
    )
    if (renaming && tag != null) {
        RenameTagDialog(tag, onDismiss = { renaming = false }, onRename = { name -> scope.launch { c.tags.rename(tag.id, name) }; renaming = false })
    }
}

fun SmartFolder.icon(): ImageVector = when (this) {
    SmartFolder.SCREENSHOTS -> Icons.Rounded.Screenshot
    SmartFolder.WHATSAPP -> Icons.AutoMirrored.Rounded.Chat
    SmartFolder.RECENT_DOWNLOADS -> Icons.Rounded.Download
    SmartFolder.LARGE_VIDEOS -> Icons.Rounded.Movie
    SmartFolder.CAMERA -> Icons.Rounded.CameraAlt
}

fun SmartFolder.shape() = when (this) {
    SmartFolder.SCREENSHOTS -> MaterialShapes.Square
    SmartFolder.WHATSAPP -> MaterialShapes.Bun
    SmartFolder.RECENT_DOWNLOADS -> MaterialShapes.Arrow
    SmartFolder.LARGE_VIDEOS -> MaterialShapes.Cookie6Sided
    SmartFolder.CAMERA -> MaterialShapes.Flower
}

/** Generic list/grid of indexed files with sort chips and a small selection mode. */
@Composable
fun CollectionScreen(
    nav: NavHostController,
    look: CollectionLook,
    rows: List<FileEntry>?,
    itemNoun: CollectionNoun = CollectionNoun.FILES,
    actions: List<CollectionAction> = emptyList(),
    topAction: (@Composable () -> Unit)? = null,
    tagSlotOf: (FileEntry) -> Int? = { null },
) {
    val context = LocalContext.current
    val c = context.container
    val haptics = rememberHaptics()
    var sort by remember { mutableStateOf(CatSort.DATE) }
    val selection = remember { mutableStateListOf<String>() }
    val visual = look.visual

    val entries = remember(rows, sort) {
        val list = rows.orEmpty()
        when (sort) {
            CatSort.DATE -> list.sortedByDescending { it.lastModified }
            CatSort.NAME -> NaturalOrder.sortedBy(list) { it.name }
            CatSort.SIZE -> list.sortedByDescending { it.size }
        }
    }
    BackHandler(selection.isNotEmpty()) { selection.clear() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (selection.isEmpty()) Text(look.title, maxLines = 1, overflow = TextOverflow.Ellipsis) else Text(pluralStringResource(R.plurals.common_selected_count, selection.size, selection.size))
                },
                navigationIcon = {
                    if (selection.isEmpty()) BackButton { nav.popBackStack() }
                    else IconButton(onClick = { selection.clear() }) { Icon(Icons.Rounded.Close, stringResource(R.string.common_cancel)) }
                },
                actions = {
                    if (selection.isNotEmpty()) {
                        actions.forEach { a ->
                            IconButton(onClick = { a.onClick(selection.toList()); selection.clear(); haptics.confirm() }) { Icon(a.icon, a.label) }
                        }
                        val files = selection.filter { p -> entries.firstOrNull { it.path == p }?.isDirectory != true }
                        if (files.isNotEmpty()) IconButton(onClick = { Opener.share(context, files) }) { Icon(Icons.Rounded.Share, stringResource(R.string.common_share)) }
                        if (selection.size == 1) IconButton(onClick = {
                            nav.navigate(Route.Browser(selection.first().substringBeforeLast('/'))); selection.clear()
                        }) { Icon(Icons.Rounded.FolderOpen, stringResource(R.string.common_show_in_folder)) }
                        IconButton(onClick = { c.operations.trash(selection.toList()); selection.clear(); haptics.confirm() }) { Icon(Icons.Rounded.Delete, stringResource(R.string.common_delete)) }
                    } else {
                        topAction?.invoke()
                    }
                },
            )
        },
    ) { p ->
        if (rows == null) {
            Box(Modifier.fillMaxSize().padding(p), contentAlignment = Alignment.Center) { LoadingIndicator() }
            return@Scaffold
        }
        LazyVerticalGrid(
            columns = if (visual) GridCells.Adaptive(112.dp) else GridCells.Fixed(1),
            contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = p.calculateTopPadding(), bottom = 96.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "#header") {
                Column(Modifier.padding(8.dp)) {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(MaterialTheme.colorScheme.surfaceContainer)
                            .sharedElementKey(look.sharedKey, bounds = true).padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(56.dp).clip(look.shape).background(look.container), contentAlignment = Alignment.Center) {
                            Icon(look.icon, null, tint = look.content)
                        }
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Row(verticalAlignment = Alignment.Bottom) {
                                AnimatedCounter("${entries.size}", style = MaterialTheme.typography.headlineMedium)
                                Text(" " + pluralStringResource(if (itemNoun == CollectionNoun.ITEMS) R.plurals.category_items_noun else R.plurals.category_files_noun, entries.size), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 3.dp))
                            }
                            Text(Format.size(entries.sumOf { it.size }), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CatSort.entries.forEach { s -> FilterChip(selected = sort == s, onClick = { sort = s }, label = { Text(stringResource(s.label)) }) }
                    }
                }
            }
            if (entries.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "#empty") {
                    EmptyState(Icons.Rounded.SearchOff, stringResource(if (itemNoun == CollectionNoun.ITEMS) R.string.category_no_items else R.string.category_no_files), message = look.emptyMessage)
                }
            }
            itemsIndexed(entries, key = { _, e -> e.path }) { i, e ->
                val selected = e.path in selection
                val click = Modifier.combinedClickable(
                    onClick = { if (selection.isNotEmpty()) { if (selected) selection.remove(e.path) else selection.add(e.path) } else nav.openEntry(context, e) },
                    onLongClick = { haptics.longPress(); if (!selected) selection.add(e.path) },
                ).selectionSemantics(selected, onToggleSelection = { if (selected) selection.remove(e.path) else selection.add(e.path) })
                if (visual) {
                    Box(Modifier.padding(3.dp).aspectRatio(1f).cascadeIn(i).clip(RoundedCornerShape(if (selected) 28.dp else 18.dp)).then(click)) {
                        FileThumbnail(e, fill = true, size = 48.dp, shape = RoundedCornerShape(0.dp), modifier = Modifier.sharedElementKey("thumb-${e.path}"))
                        Box(Modifier.align(Alignment.TopEnd).padding(6.dp)) { SelectionCheck(selected) }
                    }
                } else {
                    Row(
                        Modifier.fillMaxWidth().cascadeIn(i).clip(RoundedCornerShape(20.dp))
                            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)
                            .then(click).padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(contentAlignment = Alignment.BottomEnd) { FileThumbnail(e, size = 44.dp); SelectionCheck(selected) }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(e.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.MiddleEllipsis, modifier = Modifier.weight(1f, fill = false))
                                tagSlotOf(e)?.let { Spacer(Modifier.width(6.dp)); TagDots(listOf(it)) }
                            }
                            Text(metaLine(e), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
