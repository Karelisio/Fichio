package com.karelisio.fichio.ui.browser

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.List
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material.icons.outlined.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.karelisio.fichio.data.db.SortMode
import com.karelisio.fichio.data.db.ViewMode
import com.karelisio.fichio.domain.file.FileItem
import com.karelisio.fichio.domain.file.formatBytes
import com.karelisio.fichio.domain.file.formatDate
import com.karelisio.fichio.ui.components.FileThumbnail
import com.karelisio.fichio.ui.components.FileTypeIcon

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun BrowserScreen(
    path: String,
    onOpenFolder: (String) -> Unit,
    onBack: () -> Unit,
    onOpenFile: (FileItem) -> Unit
) {
    val viewModel: BrowserViewModel = viewModel(factory = BrowserViewModel.factory(path))
    val items by viewModel.items.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val viewMode by viewModel.viewMode.collectAsStateWithLifecycle()
    val sortMode by viewModel.sortMode.collectAsStateWithLifecycle()
    val sortAscending by viewModel.sortAscending.collectAsStateWithLifecycle()
    val foldersFirst by viewModel.foldersFirst.collectAsStateWithLifecycle()
    val showHidden by viewModel.showHidden.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()

    var sortMenuOpen by remember { mutableStateOf(false) }
    val clipboard by viewModel.clipboard.collectAsStateWithLifecycle()
    val undoMessage by viewModel.undoMessage.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var renameTarget by remember { mutableStateOf<FileItem?>(null) }

    LaunchedEffect(undoMessage) {
        val message = undoMessage ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = message,
            actionLabel = "Annuler",
            duration = SnackbarDuration.Short
        )
        if (result == SnackbarResult.ActionPerformed) viewModel.undo() else viewModel.dismissUndo()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (selection.isNotEmpty()) {
                SelectionActionBar(
                    count = selection.size,
                    onCopy = viewModel::copySelection,
                    onCut = viewModel::cutSelection,
                    onRename = { renameTarget = items.firstOrNull { it.path in selection } },
                    onDelete = viewModel::deleteSelection,
                    onSelectAll = viewModel::selectAll,
                    onInvert = viewModel::invertSelection
                )
            } else if (clipboard) {
                PasteBar(
                    onPaste = viewModel::paste,
                    onClear = viewModel::clearClipboard
                )
            }
        },
        topBar = {
            Column {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Retour")
                        }
                    },
                    title = { Breadcrumb(path = path, onOpenFolder = onOpenFolder) },
                    actions = {
                        if (selection.isNotEmpty()) {
                            IconButton(onClick = viewModel::selectAll) {
                                Icon(Icons.Outlined.Check, contentDescription = "Tout sélectionner")
                            }
                            IconButton(onClick = viewModel::clearSelection) {
                                Text("${selection.size}")
                            }
                        } else {
                            IconButton(onClick = { sortMenuOpen = true }) {
                                Icon(Icons.Outlined.Sort, contentDescription = "Trier")
                            }
                            DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                                SortMode.entries.forEach { mode ->
                                    DropdownMenuItem(
                                        text = { Text(sortLabel(mode)) },
                                        onClick = {
                                            viewModel.setSortMode(mode)
                                            sortMenuOpen = false
                                        }
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text(if (sortAscending) "Ordre croissant" else "Ordre décroissant") },
                                    onClick = { viewModel.toggleSortDirection() }
                                )
                                DropdownMenuItem(
                                    text = { Text("Dossiers en premier · ${if (foldersFirst) "oui" else "non"}") },
                                    onClick = { viewModel.toggleFoldersFirst() }
                                )
                                DropdownMenuItem(
                                    text = { Text("Fichiers cachés · ${if (showHidden) "oui" else "non"}") },
                                    onClick = { viewModel.toggleHidden() }
                                )
                            }
                        }
                    }
                )
                ViewToggleRow(viewMode = viewMode, onSelect = viewModel::setViewMode)
            }
        }
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                isLoading -> Text(
                    "Chargement…",
                    modifier = Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                items.isEmpty() -> Text(
                    "Dossier vide",
                    modifier = Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                viewMode == ViewMode.LIST -> FileList(
                    items = items,
                    selection = selection,
                    onToggle = { viewModel.toggleSelection(it.path) },
                    onOpenFolder = onOpenFolder,
                    onOpenFile = onOpenFile
                )
                else -> FileGrid(
                    items = items,
                    selection = selection,
                    large = viewMode == ViewMode.LARGE,
                    onToggle = { viewModel.toggleSelection(it.path) },
                    onOpenFolder = onOpenFolder,
                    onOpenFile = onOpenFile
                )
            }
        }
    }

    renameTarget?.let { target ->
        RenameDialog(
            item = target,
            onDismiss = { renameTarget = null },
            onConfirm = { newName ->
                viewModel.renameItem(target.path, newName)
                renameTarget = null
            }
        )
    }
}

@Composable
private fun RenameDialog(item: FileItem, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var value by remember(item.path) { mutableStateOf(item.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Renommer") },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value) }, enabled = value.isNotBlank()) { Text("Renommer") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annuler") }
        }
    )
}

@Composable
private fun SelectionActionBar(
    count: Int,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onSelectAll: () -> Unit,
    onInvert: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        Text("$count", style = MaterialTheme.typography.titleMedium)
        ActionIcon(Icons.Outlined.ContentCopy, "Copier", onCopy)
        ActionIcon(Icons.Outlined.ContentCut, "Couper", onCut)
        ActionIcon(Icons.Outlined.DriveFileRenameOutline, "Renommer", onRename)
        ActionIcon(Icons.Outlined.Delete, "Supprimer", onDelete)
        ActionIcon(Icons.Outlined.SelectAll, "Tout sélectionner", onSelectAll)
        ActionIcon(Icons.Outlined.Check, "Inverser", onInvert)
    }
}

@Composable
private fun PasteBar(onPaste: () -> Unit, onClear: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Outlined.ContentPaste,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(12.dp))
        Text("Éléments prêts à coller", modifier = Modifier.weight(1f))
        TextButton(onClick = onClear) { Text("Effacer") }
        TextButton(onClick = onPaste) { Text("Coller") }
    }
}

@Composable
private fun ActionIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(icon, contentDescription = label)
    }
}

@Composable
private fun Breadcrumb(path: String, onOpenFolder: (String) -> Unit) {
    val segments = path.split('/').filter { it.isNotBlank() }
    Row(
        modifier = Modifier
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "Fichio",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickableX { onOpenFolder("/storage/emulated/0") }
        )
        var cumulative = ""
        segments.forEach { segment ->
            cumulative += "/$segment"
            Text(" / ", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                segment,
                style = MaterialTheme.typography.titleMedium,
                color = if (cumulative == path) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
                maxLines = 1,
                modifier = Modifier.clickableX { onOpenFolder(cumulative) }
            )
        }
    }
}

@Composable
private fun Modifier.clickableX(onClick: () -> Unit): Modifier =
    this.clip(MaterialTheme.shapes.small).clickable(onClick = onClick)

@Composable
private fun ViewToggleRow(viewMode: ViewMode, onSelect: (ViewMode) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        ViewToggleButton(Icons.Outlined.List, selected = viewMode == ViewMode.LIST) { onSelect(ViewMode.LIST) }
        ViewToggleButton(Icons.Outlined.GridView, selected = viewMode == ViewMode.GRID) { onSelect(ViewMode.GRID) }
        ViewToggleButton(Icons.Outlined.PhotoLibrary, selected = viewMode == ViewMode.LARGE) { onSelect(ViewMode.LARGE) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ViewToggleButton(icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .padding(4.dp)
            .clip(CircleShape)
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
            .combinedClickable(onClick = onClick)
            .padding(6.dp),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileList(
    items: List<FileItem>,
    selection: Set<String>,
    onToggle: (FileItem) -> Unit,
    onOpenFolder: (String) -> Unit,
    onOpenFile: (FileItem) -> Unit
) {
    LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
        items(items, key = { it.path }) { item ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (item.path in selection) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) else MaterialTheme.colorScheme.surface)
                    .combinedClickable(
                        onClick = {
                            if (selection.isNotEmpty()) onToggle(item)
                            else if (item.isDirectory) onOpenFolder(item.path) else onOpenFile(item)
                        },
                        onLongClick = { onToggle(item) }
                    )
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FileTypeIcon(item.isDirectory, item.extension, size = 22.dp)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (item.isDirectory) "Dossier" else "${formatBytes(item.sizeBytes)} · ${formatDate(item.modifiedAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (selection.isNotEmpty()) {
                    SelectionDot(selected = item.path in selection)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileGrid(
    items: List<FileItem>,
    selection: Set<String>,
    large: Boolean,
    onToggle: (FileItem) -> Unit,
    onOpenFolder: (String) -> Unit,
    onOpenFile: (FileItem) -> Unit
) {
    val columns = if (large) 2 else 3
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(items, key = { it.path }) { item ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        if (item.path in selection) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) else MaterialTheme.colorScheme.surface
                    )
                    .combinedClickable(
                        onClick = {
                            if (selection.isNotEmpty()) onToggle(item)
                            else if (item.isDirectory) onOpenFolder(item.path) else onOpenFile(item)
                        },
                        onLongClick = { onToggle(item) }
                    )
            ) {
                Box {
                    FileThumbnail(
                        file = item.file,
                        isDirectory = item.isDirectory,
                        extension = item.extension,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f),
                        cornerRadius = 16.dp
                    )
                    if (selection.isNotEmpty()) {
                        SelectionDot(
                            selected = item.path in selection,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(6.dp)
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    item.name,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = if (large) 2 else 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
            }
        }
    }
}

@Composable
private fun SelectionDot(selected: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
            ),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Icon(Icons.Outlined.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp))
        }
    }
}

private fun sortLabel(mode: SortMode): String = when (mode) {
    SortMode.NAME -> "Nom"
    SortMode.DATE -> "Date"
    SortMode.SIZE -> "Taille"
    SortMode.TYPE -> "Type"
}
