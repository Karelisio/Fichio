@file:OptIn(ExperimentalMaterial3Api::class)

package com.karelisio.fichio.preview

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.karelisio.fichio.container
import com.karelisio.fichio.core.fs.StoragePaths
import com.karelisio.fichio.core.model.FileEntry
import com.karelisio.fichio.core.model.FileType
import com.karelisio.fichio.core.util.Format
import com.karelisio.fichio.ui.components.FileIcon
import com.karelisio.fichio.ui.navigation.Opener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** Resolves files for viewers that need a real, seekable local file (PdfRenderer, ExoPlayer…). */
object PreviewCache {
    private const val MAX_AGE_MS = 24 * 3600_000L

    fun dir(context: Context): File = File(context.cacheDir, "preview").apply { mkdirs() }

    /** [path] itself for normal storage; a cached copy (read through Shevery) for Android/data|obb. */
    suspend fun localFile(context: Context, path: String): File = withContext(Dispatchers.IO) {
        if (!StoragePaths.isRestricted(path)) return@withContext File(path)
        val backend = context.container.fileSystems.backendFor(path)
        val stat = backend.stat(path)
        val target = File(dir(context), "${path.hashCode().toUInt()}_${stat?.lastModified ?: 0}_${StoragePaths.name(path)}")
        if (!target.exists() || target.length() != (stat?.size ?: -1L)) {
            val tmp = File(target.path + ".part")
            backend.openInput(path).use { input -> tmp.outputStream().use { input.copyTo(it, 256 * 1024) } }
            tmp.renameTo(target)
        }
        target
    }

    /** Copies a content:// Uri into the cache. */
    suspend fun fromUri(context: Context, uri: Uri, name: String): File = withContext(Dispatchers.IO) {
        if (uri.scheme == "file") return@withContext File(requireNotNull(uri.path))
        val safe = name.replace('/', '_').ifBlank { "fichier" }
        val target = File(dir(context), "uri_${uri.toString().hashCode().toUInt()}_$safe")
        if (!target.exists() || target.length() == 0L) {
            val tmp = File(target.path + ".part")
            val input = context.contentResolver.openInputStream(uri) ?: error("Flux indisponible")
            input.use { i -> tmp.outputStream().use { i.copyTo(it, 256 * 1024) } }
            tmp.renameTo(target)
        }
        target
    }

    /** Drops stale copies (called when a viewer opens). */
    fun trim(context: Context) {
        val now = System.currentTimeMillis()
        dir(context).listFiles()?.forEach { if (now - it.lastModified() > MAX_AGE_MS) it.delete() }
    }
}

/** Display name of a content Uri (or its last segment). */
fun displayNameOf(context: Context, uri: Uri): String {
    val fromResolver = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()
    return fromResolver ?: uri.lastPathSegment?.substringAfterLast('/') ?: "fichier"
}

tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Hides the system bars while [immersive] is true; restores them when leaving the screen. */
@Composable
fun ImmersiveMode(immersive: Boolean) {
    val view = LocalView.current
    val activity = LocalContext.current.findActivity() ?: return
    val controller = remember(activity, view) { WindowCompat.getInsetsController(activity.window, view) }
    LaunchedEffect(immersive) {
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (immersive) controller.hide(WindowInsetsCompat.Type.systemBars())
        else controller.show(WindowInsetsCompat.Type.systemBars())
    }
    DisposableEffect(controller) {
        onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

/** Light status-bar icons on dark (image/video) backgrounds, restored afterwards. */
@Composable
fun DarkSystemBars() {
    val view = LocalView.current
    val activity = LocalContext.current.findActivity() ?: return
    DisposableEffect(activity) {
        val c = WindowCompat.getInsetsController(activity.window, view)
        val wasLight = c.isAppearanceLightStatusBars
        val wasLightNav = c.isAppearanceLightNavigationBars
        c.isAppearanceLightStatusBars = false
        c.isAppearanceLightNavigationBars = false
        onDispose {
            c.isAppearanceLightStatusBars = wasLight
            c.isAppearanceLightNavigationBars = wasLightNav
        }
    }
}

@Immutable
data class InfoRow(val label: String, val value: String)

/** Collects details for the "Infos" sheet (EXIF for images, media tags for audio/video). */
suspend fun loadInfo(context: Context, entry: FileEntry, extra: List<InfoRow> = emptyList()): List<InfoRow> = withContext(Dispatchers.IO) {
    val rows = ArrayList<InfoRow>()
    rows += InfoRow("Nom", entry.name)
    rows += InfoRow("Emplacement", entry.parentPath)
    if (!entry.isDirectory) rows += InfoRow("Taille", "${Format.size(entry.size)} (${String.format(Locale.FRANCE, "%,d", entry.size)} octets)")
    rows += InfoRow("Modifié", Format.date(entry.lastModified))
    rows += InfoRow("Type", entry.mimeType)
    runCatching {
        when (entry.type) {
            FileType.IMAGE -> rows += imageInfo(context, entry)
            FileType.VIDEO, FileType.AUDIO -> rows += mediaInfo(context, entry)
            else -> Unit
        }
    }
    rows += extra
    rows
}

private suspend fun imageInfo(context: Context, entry: FileEntry): List<InfoRow> {
    val out = ArrayList<InfoRow>()
    val file = PreviewCache.localFile(context, entry.path)
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, opts)
    if (opts.outWidth > 0) {
        val mp = opts.outWidth.toLong() * opts.outHeight / 1_000_000.0
        out += InfoRow("Dimensions", "${opts.outWidth} × ${opts.outHeight} px" + if (mp >= 0.1) String.format(Locale.FRANCE, " (%.1f Mpx)", mp) else "")
    }
    val ext = entry.extension
    if (ext in setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "dng", "avif")) {
        val exif = ExifInterface(file.path)
        val make = exif.getAttribute(ExifInterface.TAG_MAKE)?.trim()
        val model = exif.getAttribute(ExifInterface.TAG_MODEL)?.trim()
        val camera = listOfNotNull(make, model?.removePrefix(make ?: "")?.trim()).filter { it.isNotEmpty() }.joinToString(" ")
        if (camera.isNotEmpty()) out += InfoRow("Appareil", camera)
        exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)?.let { out += InfoRow("Prise de vue", it.replaceFirst(':', '-').replaceFirst(':', '-')) }
        exif.getAttribute(ExifInterface.TAG_F_NUMBER)?.toDoubleOrNull()?.let { out += InfoRow("Ouverture", String.format(Locale.FRANCE, "f/%.1f", it)) }
        exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)?.toDoubleOrNull()?.let {
            out += InfoRow("Exposition", if (it < 1) "1/${(1 / it).toInt()} s" else String.format(Locale.FRANCE, "%.1f s", it))
        }
        (exif.getAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS))?.let { out += InfoRow("ISO", it) }
        exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH)?.let { raw ->
            val v = raw.split('/').let { p -> if (p.size == 2) p[0].toDoubleOrNull()?.div(p[1].toDoubleOrNull() ?: 1.0) else raw.toDoubleOrNull() }
            if (v != null) out += InfoRow("Focale", String.format(Locale.FRANCE, "%.1f mm", v))
        }
        val latLong = FloatArray(2)
        @Suppress("DEPRECATION")
        if (exif.getLatLong(latLong)) out += InfoRow("Position", String.format(Locale.US, "%.5f, %.5f", latLong[0], latLong[1]))
    }
    return out
}

private suspend fun mediaInfo(context: Context, entry: FileEntry): List<InfoRow> {
    val out = ArrayList<InfoRow>()
    val file = PreviewCache.localFile(context, entry.path)
    val r = MediaMetadataRetriever()
    try {
        r.setDataSource(file.path)
        fun meta(k: Int) = r.extractMetadata(k)?.takeIf { it.isNotBlank() }
        meta(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let { out += InfoRow("Durée", Format.duration(it)) }
        val w = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
        val h = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
        if (w != null && h != null) out += InfoRow("Résolution", "$w × $h")
        meta(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()?.let { out += InfoRow("Débit", "${it / 1000} kb/s") }
        meta(MediaMetadataRetriever.METADATA_KEY_TITLE)?.let { out += InfoRow("Titre", it) }
        meta(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.let { out += InfoRow("Artiste", it) }
        meta(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.let { out += InfoRow("Album", it) }
        meta(MediaMetadataRetriever.METADATA_KEY_YEAR)?.let { out += InfoRow("Année", it) }
        meta(MediaMetadataRetriever.METADATA_KEY_GENRE)?.let { out += InfoRow("Genre", it) }
    } finally {
        runCatching { r.release() }
    }
    return out
}

/** Bottom sheet with the details of a file. */
@Composable
fun InfoSheet(entry: FileEntry, extra: List<InfoRow> = emptyList(), onDismiss: () -> Unit) {
    val context = LocalContext.current
    var rows by remember(entry.path) { mutableStateOf<List<InfoRow>?>(null) }
    LaunchedEffect(entry.path, extra) { rows = runCatching { loadInfo(context, entry, extra) }.getOrDefault(emptyList()) }
    val sheet = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).navigationBarsPadding(),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FileIcon(entry.type, name = entry.name, size = 48.dp)
                Spacer(Modifier.width(16.dp))
                Text("Informations", style = MaterialTheme.typography.titleLarge)
            }
            Spacer(Modifier.height(16.dp))
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    rows.orEmpty().forEach { r ->
                        Column {
                            Text(r.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            Text(r.value, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** "Move to trash?" confirmation, then the operation through the shared engine. */
@Composable
fun TrashDialog(name: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Delete, null) },
        title = { Text("Placer dans la corbeille ?") },
        text = { Text("« $name » pourra être restauré depuis la corbeille.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Supprimer") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

/** Sends [path] to the trash; returns true when the operation was accepted. */
fun trashFile(context: Context, path: String): Boolean = runCatching {
    context.container.operations.trash(listOf(path)); true
}.getOrElse {
    Toast.makeText(context, "Suppression impossible", Toast.LENGTH_SHORT).show(); false
}

/** Shares [path]; files in Android/data are first copied to the cache so other apps can read them. */
suspend fun shareFile(context: Context, path: String) {
    val local = runCatching { PreviewCache.localFile(context, path) }.getOrNull() ?: return
    withContext(Dispatchers.Main) { runCatching { Opener.share(context, listOf(local.path)) } }
}

suspend fun openWith(context: Context, path: String) {
    val local = runCatching { PreviewCache.localFile(context, path) }.getOrNull() ?: return
    withContext(Dispatchers.Main) { Opener.openExternally(context, local.path, chooser = true) }
}

/** Builds a FileEntry from a java.io.File (for cached / Uri copies). */
fun File.toEntry(displayName: String = name): FileEntry =
    FileEntry(path = path, name = displayName, isDirectory = isDirectory, size = length(), lastModified = lastModified())
