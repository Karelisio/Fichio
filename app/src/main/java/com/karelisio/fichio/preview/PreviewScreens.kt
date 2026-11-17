@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.karelisio.fichio.preview

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.FontDownload
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.karelisio.fichio.container
import com.karelisio.fichio.core.fs.StoragePaths
import com.karelisio.fichio.core.model.FileEntry
import com.karelisio.fichio.core.model.FileType
import com.karelisio.fichio.core.model.FileTypes
import com.karelisio.fichio.core.util.Format
import com.karelisio.fichio.ui.components.BackButton
import com.karelisio.fichio.ui.components.EmptyState
import com.karelisio.fichio.ui.components.FileIcon
import com.karelisio.fichio.ui.components.bouncy
import com.karelisio.fichio.ui.components.rememberHaptics
import com.karelisio.fichio.ui.theme.MonoStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.nio.charset.Charset

/** What a file opens into. */
internal enum class PreviewKind { IMAGE, VIDEO, AUDIO, PDF, TEXT, UNSUPPORTED }

internal fun previewKind(type: FileType, extension: String, mime: String? = null): PreviewKind = when {
    type == FileType.IMAGE || mime?.startsWith("image/") == true -> PreviewKind.IMAGE
    type == FileType.VIDEO || mime?.startsWith("video/") == true -> PreviewKind.VIDEO
    type == FileType.AUDIO || mime?.startsWith("audio/") == true -> PreviewKind.AUDIO
    type == FileType.PDF || mime == "application/pdf" -> PreviewKind.PDF
    type == FileType.TEXT || type == FileType.CODE || extension == "csv" || extension == "tsv" -> PreviewKind.TEXT
    mime != null && (mime.startsWith("text/") || mime == "application/json" || mime == "application/xml") -> PreviewKind.TEXT
    type == FileType.OTHER && extension.isEmpty() -> PreviewKind.TEXT // README, LICENSE, .gitignore…
    else -> PreviewKind.UNSUPPORTED
}

// ---------------------------------------------------------------------------------------------
// Public entry points (contract)
// ---------------------------------------------------------------------------------------------

/** Image (pager + zoom), video/audio (Media3), PDF, text/code. */
@Composable
fun PreviewScreen(path: String, onBack: () -> Unit, onEdit: (String) -> Unit, onShowInFolder: (String) -> Unit) {
    val context = LocalContext.current
    val fs = context.container.fileSystems
    val entry by produceState<Result<FileEntry>?>(null, path) {
        value = runCatching { fs.stat(path) ?: error("Fichier introuvable") }
    }
    LaunchedEffect(path) {
        context.container.recents.record(path)
        withContext(Dispatchers.IO) { runCatching { PreviewCache.trim(context) } }
    }
    when (val e = entry) {
        null -> Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) { LoadingIndicator() }
        else -> e.fold(
            onSuccess = { f -> FilePreview(f, onBack, onEdit, onShowInFolder) },
            onFailure = { err ->
                DocumentScaffold(title = StoragePaths.name(path), subtitle = null, onBack = onBack) {
                    EmptyState(
                        icon = Icons.Rounded.SearchOff,
                        title = "Impossible d'ouvrir ce fichier",
                        message = err.message,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            },
        )
    }
}

/** Preview of a content:// Uri received through ACTION_VIEW. */
@Composable
fun UriPreviewScreen(uri: String, mimeType: String?, onBack: () -> Unit) {
    UriPreview(uri, mimeType, onBack)
}

@Composable
fun EditorScreen(path: String, onBack: () -> Unit) {
    TextEditor(path, onBack)
}

/** Mini player shown above the bottom of the app while audio plays. */
@Composable
fun MiniPlayerBar(onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    MiniPlayer(onOpen, modifier)
}

// ---------------------------------------------------------------------------------------------
// File previews
// ---------------------------------------------------------------------------------------------

@Composable
private fun FilePreview(entry: FileEntry, onBack: () -> Unit, onEdit: (String) -> Unit, onShowInFolder: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    when (previewKind(entry.type, entry.extension)) {
        PreviewKind.IMAGE -> ImageViewer(entry, onBack, onShowInFolder)
        PreviewKind.VIDEO -> VideoPlayerScreen(entry, onBack)
        PreviewKind.AUDIO -> AudioPlayerScreen(entry, onBack, onShowInFolder)
        PreviewKind.PDF -> PdfFilePreview(entry, onBack, onShowInFolder)
        PreviewKind.TEXT -> TextFilePreview(entry, onBack, onEdit, onShowInFolder)
        PreviewKind.UNSUPPORTED -> DocumentScaffold(entry.name, Format.size(entry.size), onBack, actions = {
            FileMenu(entry, onShowInFolder, onBack)
        }) { Unsupported(entry) { ctx -> scope.launch { openWith(ctx, entry.path) } } }
    }
}

@Composable
private fun PdfFilePreview(entry: FileEntry, onBack: () -> Unit, onShowInFolder: (String) -> Unit) {
    val context = LocalContext.current
    val file by produceState<Result<File>?>(null, entry.path) {
        value = runCatching { PreviewCache.localFile(context, entry.path) }
    }
    var pages by remember { mutableStateOf(0) }
    DocumentScaffold(
        title = entry.name,
        subtitle = listOfNotNull(Format.size(entry.size), pages.takeIf { it > 0 }?.let { Format.count(it, "page") }).joinToString(" · "),
        onBack = onBack,
        actions = { FileMenu(entry, onShowInFolder, onBack, extra = listOf(InfoRow("Pages", pages.toString()))) },
    ) {
        when (val f = file) {
            null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator() }
            else -> f.fold(
                onSuccess = { PdfViewer(it, entry, onPageCount = { n -> pages = n }) },
                onFailure = { EmptyState(Icons.Rounded.SearchOff, "Lecture impossible", Modifier.fillMaxSize(), it.message) },
            )
        }
    }
}

@Composable
private fun TextFilePreview(entry: FileEntry, onBack: () -> Unit, onEdit: (String) -> Unit, onShowInFolder: (String) -> Unit) {
    val context = LocalContext.current
    val fs = context.container.fileSystems
    var search by rememberSaveable { mutableStateOf(false) }
    var wrap by rememberSaveable { mutableStateOf(entry.type == FileType.TEXT) }
    var details by remember { mutableStateOf<List<InfoRow>>(emptyList()) }
    val restricted = StoragePaths.isRestricted(entry.path)
    val block = editBlockReason(entry.size, restricted, fs.backendOrNull(entry.path) != null)
    val open: () -> InputStream = remember(entry.path, entry.lastModified) { { fs.backendFor(entry.path).openInput(entry.path) } }
    val lang = remember(entry.extension) { SyntaxHighlighter.languageFor(entry.extension).name }
    DocumentScaffold(
        title = entry.name,
        subtitle = listOfNotNull(Format.size(entry.size), details.firstOrNull { it.label == "Lignes" }?.value?.let { "$it lignes" }, lang).joinToString(" · "),
        onBack = onBack,
        actions = {
            IconButton(onClick = { search = !search }) { Icon(Icons.Rounded.Search, "Rechercher dans le fichier") }
            WrapToggle(wrap) { wrap = !wrap }
            EditButton(enabled = block == null) { onEdit(entry.path) }
            FileMenu(entry, onShowInFolder, onBack, extra = details + listOfNotNull(block?.let { InfoRow("Édition", it) }))
        },
    ) {
        TextViewer(
            name = entry.name,
            extension = entry.extension,
            open = open,
            searchOpen = search,
            onCloseSearch = { search = false },
            wrap = wrap,
            onInfo = { lines, truncated, cs ->
                details = listOfNotNull(
                    InfoRow("Lignes", lines.toString()),
                    InfoRow("Encodage", cs),
                    if (truncated) InfoRow("Affichage", "Tronqué à ${Format.size(TextLoader.VIEW_LIMIT)}") else null,
                )
            },
        )
    }
}

/** Standard top bar + content for document-like previews. */
@Composable
internal fun DocumentScaffold(
    title: String,
    subtitle: String?,
    onBack: () -> Unit,
    actions: @Composable () -> Unit = {},
    snackbar: SnackbarHostState? = null,
    content: @Composable () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title, maxLines = 1, overflow = TextOverflow.MiddleEllipsis, style = MaterialTheme.typography.titleMedium)
                        if (!subtitle.isNullOrEmpty()) {
                            Text(subtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                    }
                },
                navigationIcon = { BackButton(onBack) },
                actions = { actions() },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        snackbarHost = { snackbar?.let { SnackbarHost(it) } },
    ) { p ->
        Box(Modifier.fillMaxSize().padding(p)) { content() }
    }
}

/** Overflow menu shared by the document previews. */
@Composable
private fun FileMenu(entry: FileEntry, onShowInFolder: ((String) -> Unit)?, onDeleted: () -> Unit, extra: List<InfoRow> = emptyList()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    var open by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf(false) }
    var trash by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Rounded.MoreVert, "Plus d'options") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Partager") }, leadingIcon = { Icon(Icons.Rounded.Share, null) },
                onClick = { open = false; scope.launch { shareFile(context, entry.path) } })
            DropdownMenuItem(text = { Text("Ouvrir avec…") }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, null) },
                onClick = { open = false; scope.launch { openWith(context, entry.path) } })
            if (onShowInFolder != null) {
                DropdownMenuItem(text = { Text("Afficher dans le dossier") }, leadingIcon = { Icon(Icons.Rounded.FolderOpen, null) },
                    onClick = { open = false; onShowInFolder(entry.parentPath) })
            }
            DropdownMenuItem(text = { Text("Infos") }, leadingIcon = { Icon(Icons.Rounded.Info, null) },
                onClick = { open = false; info = true })
            HorizontalDivider()
            DropdownMenuItem(text = { Text("Supprimer") }, leadingIcon = { Icon(Icons.Rounded.Delete, null) },
                onClick = { open = false; haptics.longPress(); trash = true })
        }
    }
    if (info) InfoSheet(entry, extra, onDismiss = { info = false })
    if (trash) TrashDialog(entry.name, onDismiss = { trash = false }) {
        trash = false
        if (trashFile(context, entry.path)) { haptics.confirm(); onDeleted() }
    }
}

@Composable
private fun Unsupported(entry: FileEntry, onOpenWith: (Context) -> Unit) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
        FileIcon(entry.type, name = entry.name, size = 112.dp)
        Spacer(Modifier.size(20.dp))
        Text("Pas d'aperçu pour ce type de fichier", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.size(16.dp))
        Button(onClick = { onOpenWith(context) }) {
            Icon(Icons.AutoMirrored.Rounded.OpenInNew, null)
            Spacer(Modifier.width(8.dp))
            Text("Ouvrir avec…")
        }
    }
}

// ---------------------------------------------------------------------------------------------
// content:// previews
// ---------------------------------------------------------------------------------------------

@Composable
private fun UriPreview(uriString: String, mimeType: String?, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val uri = remember(uriString) { Uri.parse(uriString) }
    val name = remember(uri) { displayNameOf(context, uri) }
    val mime = remember(uri, mimeType) {
        mimeType?.takeIf { it != "*/*" && it.isNotBlank() } ?: runCatching { context.contentResolver.getType(uri) }.getOrNull()
            ?: FileTypes.mimeOfName(name)
    }
    val ext = name.substringAfterLast('.', "").lowercase()
    val kind = previewKind(FileTypes.typeOf(ext), ext, mime)
    val size = remember(uri) {
        runCatching { context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } }.getOrNull()?.takeIf { it >= 0 } ?: 0L
    }
    val entry = remember(uri, name) { FileEntry(path = uriString, name = name, isDirectory = false, size = size, lastModified = System.currentTimeMillis()) }

    val saveAction: @Composable () -> Unit = {
        IconButton(onClick = {
            haptics.click()
            scope.launch {
                val saved = runCatching { saveToDownloads(context, uri, name) }
                Toast.makeText(
                    context,
                    saved.fold({ "Enregistré dans Téléchargements (${StoragePaths.name(it)})" }, { "Enregistrement impossible : ${it.message}" }),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }) { Icon(Icons.Rounded.Download, "Enregistrer dans Téléchargements") }
    }

    // Audio and PDF need a seekable local copy.
    val needsCopy = kind == PreviewKind.AUDIO || kind == PreviewKind.PDF
    val cached by produceState<Result<File>?>(null, uri, needsCopy) {
        if (needsCopy) value = runCatching { PreviewCache.fromUri(context, uri, name) }
    }

    when (kind) {
        PreviewKind.IMAGE -> ImageViewer(entry, onBack, onShowInFolder = null, single = true, extraActions = {
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides MaterialTheme.colorScheme.onPrimaryContainer) { saveAction() }
        })
        PreviewKind.VIDEO -> VideoPlayerScreen(entry, onBack, uri = uri, extraActions = saveAction)
        PreviewKind.TEXT -> {
            var search by rememberSaveable { mutableStateOf(false) }
            var wrap by rememberSaveable { mutableStateOf(true) }
            val open: () -> InputStream = remember(uri) { { context.contentResolver.openInputStream(uri) ?: error("Flux indisponible") } }
            DocumentScaffold(name, Format.size(size).takeIf { size > 0 }, onBack, actions = {
                IconButton(onClick = { search = !search }) { Icon(Icons.Rounded.Search, "Rechercher") }
                WrapToggle(wrap) { wrap = !wrap }
                saveAction()
            }) {
                TextViewer(name, ext, open, searchOpen = search, onCloseSearch = { search = false }, wrap = wrap)
            }
        }
        PreviewKind.AUDIO, PreviewKind.PDF -> when (val c = cached) {
            null -> DocumentScaffold(name, null, onBack) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator() } }
            else -> c.fold(
                onSuccess = { file ->
                    if (kind == PreviewKind.AUDIO) AudioPlayerScreen(entry, onBack, onShowInFolder = null, cached = file, extraActions = saveAction)
                    else DocumentScaffold(name, Format.size(size).takeIf { size > 0 }, onBack, actions = { saveAction() }) {
                        PdfViewer(file, entry)
                    }
                },
                onFailure = {
                    DocumentScaffold(name, null, onBack) { EmptyState(Icons.Rounded.SearchOff, "Lecture impossible", Modifier.fillMaxSize(), it.message) }
                },
            )
        }
        PreviewKind.UNSUPPORTED -> DocumentScaffold(name, mime, onBack, actions = { saveAction() }) {
            Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
                FileIcon(FileTypes.typeOf(ext), name = name, size = 112.dp)
                Spacer(Modifier.size(20.dp))
                Text("Pas d'aperçu pour ce type de fichier", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.size(16.dp))
                FilledTonalButton(onClick = {
                    scope.launch {
                        runCatching { saveToDownloads(context, uri, name) }.onSuccess {
                            Toast.makeText(context, "Enregistré dans Téléchargements", Toast.LENGTH_SHORT).show()
                        }
                    }
                }) {
                    Icon(Icons.Rounded.Download, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Enregistrer dans Téléchargements")
                }
            }
        }
    }
}

/** Copies [uri] into Downloads under a free name; returns the new path. */
internal suspend fun saveToDownloads(context: Context, uri: Uri, name: String): String = withContext(Dispatchers.IO) {
    val dir = File(StoragePaths.downloads).apply { mkdirs() }
    val target = uniqueFile(dir, name.replace('/', '_').ifBlank { "fichier" })
    val input = context.contentResolver.openInputStream(uri) ?: error("Flux indisponible")
    input.use { i -> target.outputStream().use { i.copyTo(it, 256 * 1024) } }
    context.container.fileSystems.notifyChanged(dir.path)
    target.path
}

/** "name.ext", then "name (1).ext", "name (2).ext"… */
internal fun uniqueFile(dir: File, name: String): File {
    var f = File(dir, name)
    if (!f.exists()) return f
    val base = name.substringBeforeLast('.', name)
    val ext = name.substringAfterLast('.', "").let { if (it.isEmpty() || it == name) "" else ".$it" }
    var i = 1
    while (f.exists()) { f = File(dir, "$base ($i)$ext"); i++ }
    return f
}

// ---------------------------------------------------------------------------------------------
// Editor
// ---------------------------------------------------------------------------------------------

private class EditorDoc(val text: String, val charset: Charset)

@Composable
private fun TextEditor(path: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val fs = context.container.fileSystems
    val loaded by produceState<Result<EditorDoc>?>(null, path) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val backend = fs.backendFor(path)
                val (bytes, more) = TextLoader.readBounded(backend.openInput(path), TextLoader.EDIT_LIMIT)
                if (more) error("Fichier trop volumineux pour l'éditeur (> ${Format.size(TextLoader.EDIT_LIMIT)})")
                if (TextLoader.looksBinary(bytes)) error("Ce fichier n'est pas du texte")
                val (text, cs) = TextLoader.decode(bytes)
                EditorDoc(text, cs)
            }
        }
    }
    when (val l = loaded) {
        null -> DocumentScaffold(StoragePaths.name(path), null, onBack) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator() } }
        else -> l.fold(
            onSuccess = { EditorBody(path, it, onBack) },
            onFailure = { DocumentScaffold(StoragePaths.name(path), null, onBack) { EmptyState(Icons.Rounded.SearchOff, "Édition impossible", Modifier.fillMaxSize(), it.message) } },
        )
    }
}

@Composable
private fun EditorBody(path: String, doc: EditorDoc, onBack: () -> Unit) {
    val context = LocalContext.current
    val fs = context.container.fileSystems
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val snackbar = remember { SnackbarHostState() }
    val state = remember(doc) { TextFieldState(doc.text) }
    var saved by remember(doc) { mutableStateOf(doc.text) }
    var mono by rememberSaveable { mutableStateOf(true) }
    var wrap by rememberSaveable { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var confirmExit by remember { mutableStateOf(false) }
    val modified by remember { derivedStateOf { state.text.length != saved.length || !state.text.contentEquals(saved) } }

    suspend fun save(): Boolean {
        saving = true
        val text = state.text.toString()
        val result = withContext(Dispatchers.IO) {
            runCatching {
                fs.backendFor(path).openOutput(path).use { it.write(text.toByteArray(doc.charset)) }
            }
        }
        saving = false
        return result.fold(
            onSuccess = {
                saved = text
                fs.notifyChanged(StoragePaths.parent(path))
                haptics.confirm()
                snackbar.showSnackbar("Enregistré")
                true
            },
            onFailure = {
                haptics.reject()
                snackbar.showSnackbar("Échec de l'enregistrement : ${it.message}")
                false
            },
        )
    }

    BackHandler(enabled = modified) { confirmExit = true }
    val back = { if (modified) confirmExit = true else onBack() }

    DocumentScaffold(
        title = StoragePaths.name(path),
        subtitle = if (modified) "Modifié" else "Enregistré · ${doc.charset.displayName()}",
        onBack = back,
        snackbar = snackbar,
        actions = {
            IconButton(enabled = state.undoState.canUndo, onClick = { haptics.tick(); state.undoState.undo() }) {
                Icon(Icons.AutoMirrored.Rounded.Undo, "Annuler")
            }
            IconButton(enabled = state.undoState.canRedo, onClick = { haptics.tick(); state.undoState.redo() }) {
                Icon(Icons.AutoMirrored.Rounded.Redo, "Rétablir")
            }
            IconToggleButton(checked = mono, onCheckedChange = { mono = it }) {
                Icon(Icons.Rounded.FontDownload, "Police à chasse fixe", tint = if (mono) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            WrapToggle(wrap) { wrap = !wrap }
            AnimatedVisibility(modified, enter = scaleIn(bouncy()), exit = scaleOut()) {
                IconButton(enabled = !saving, onClick = { scope.launch { save() } }) {
                    if (saving) LoadingIndicator(Modifier.size(24.dp)) else Icon(Icons.Rounded.Save, "Enregistrer", tint = MaterialTheme.colorScheme.primary)
                }
            }
        },
    ) {
        val cs = MaterialTheme.colorScheme
        val style = (if (mono) MonoStyle.copy(fontSize = MaterialTheme.typography.bodyMedium.fontSize) else MaterialTheme.typography.bodyLarge).copy(color = cs.onSurface)
        BoxWithConstraints(Modifier.fillMaxSize().imePadding()) {
            val minWidth = maxWidth
            val h = rememberScrollState()
            Box(if (wrap) Modifier.fillMaxSize() else Modifier.fillMaxSize().horizontalScroll(h)) {
                BasicTextField(
                    state = state,
                    textStyle = style,
                    cursorBrush = SolidColor(cs.primary),
                    modifier = (if (wrap) Modifier.fillMaxSize() else Modifier.fillMaxHeight().widthIn(min = minWidth))
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .navigationBarsPadding(),
                )
            }
        }
    }

    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text("Modifications non enregistrées") },
            text = { Text("Voulez-vous enregistrer vos modifications avant de quitter ?") },
            confirmButton = {
                TextButton(onClick = {
                    confirmExit = false
                    scope.launch { if (save()) onBack() }
                }) { Text("Enregistrer") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { confirmExit = false; onBack() }) { Text("Quitter sans enregistrer") }
                    TextButton(onClick = { confirmExit = false }) { Text("Annuler") }
                }
            },
        )
    }
}
