package com.karelisio.fichio.ui.category

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.karelisio.fichio.container
import com.karelisio.fichio.core.model.FileCategory
import com.karelisio.fichio.core.model.FileEntry
import com.karelisio.fichio.core.model.FileType
import com.karelisio.fichio.core.util.Format
import com.karelisio.fichio.core.util.NaturalOrder
import com.karelisio.fichio.data.db.IndexedFile
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

private enum class CatSort(val label: String) { DATE("Récents"), NAME("Nom"), SIZE("Taille") }

private fun IndexedFile.toEntry() = FileEntry(path, name, isDirectory, size, modified)

/** Files of one category, straight from the local index (instant). */
@Composable
fun CategoryScreen(nav: NavHostController, category: FileCategory) {
    val context = LocalContext.current
    val c = context.container
    val haptics = rememberHaptics()
    val types = remember(category) { FileType.entries.filter { it.category == category }.map { it.name } }
    val rows by remember(types) { c.database.fileIndex().observeByTypes(types) }.collectAsStateWithLifecycle(null)
    var sort by remember { mutableStateOf(CatSort.DATE) }
    val selection = remember { mutableStateListOf<String>() }
    val visual = category == FileCategory.IMAGES || category == FileCategory.VIDEOS
    val tone = toneOf(category)

    val entries = remember(rows, sort) {
        val list = rows.orEmpty().map { it.toEntry() }
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
                    if (selection.isEmpty()) Text(category.label) else Text("${selection.size} sélectionné(s)")
                },
                navigationIcon = {
                    if (selection.isEmpty()) BackButton { nav.popBackStack() }
                    else IconButton(onClick = { selection.clear() }) { Icon(Icons.Rounded.Close, "Annuler") }
                },
                actions = {
                    if (selection.isNotEmpty()) {
                        IconButton(onClick = { Opener.share(context, selection.toList()) }) { Icon(Icons.Rounded.Share, "Partager") }
                        if (selection.size == 1) IconButton(onClick = {
                            nav.navigate(Route.Browser(selection.first().substringBeforeLast('/'))); selection.clear()
                        }) { Icon(Icons.Rounded.FolderOpen, "Afficher dans le dossier") }
                        IconButton(onClick = { c.operations.trash(selection.toList()); selection.clear(); haptics.confirm() }) { Icon(Icons.Rounded.Delete, "Supprimer") }
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
                            .sharedElementKey("cat-${category.name}", bounds = true).padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(56.dp).clip(category.polygon().toShape()).background(tone.container), contentAlignment = Alignment.Center) {
                            Icon(category.icon(), null, tint = tone.content)
                        }
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Row(verticalAlignment = Alignment.Bottom) {
                                AnimatedCounter("${entries.size}", style = MaterialTheme.typography.headlineMedium)
                                Text(if (entries.size > 1) " fichiers" else " fichier", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 3.dp))
                            }
                            Text(Format.size(entries.sumOf { it.size }), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CatSort.entries.forEach { s -> FilterChip(selected = sort == s, onClick = { sort = s }, label = { Text(s.label) }) }
                    }
                }
            }
            if (entries.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    EmptyState(Icons.Rounded.SearchOff, "Aucun fichier", message = "L'index se met à jour en arrière-plan.")
                }
            }
            itemsIndexed(entries, key = { _, e -> e.path }) { i, e ->
                val selected = e.path in selection
                val click = Modifier.combinedClickable(
                    onClick = { if (selection.isNotEmpty()) { if (selected) selection.remove(e.path) else selection.add(e.path) } else nav.openEntry(context, e) },
                    onLongClick = { haptics.longPress(); if (!selected) selection.add(e.path) },
                )
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
                            Text(e.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                            Text(metaLine(e), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
