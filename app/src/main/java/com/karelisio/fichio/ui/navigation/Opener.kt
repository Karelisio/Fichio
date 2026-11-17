package com.karelisio.fichio.ui.navigation

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import com.karelisio.fichio.core.model.FileEntry
import com.karelisio.fichio.core.model.FileType
import com.karelisio.fichio.core.model.FileTypes
import java.io.File

/** Decides where a file opens: inside Fichio (preview, archive, APK) or in another app. */
object Opener {
    const val POLARO_PACKAGE = "com.karelisio.polaro"

    fun internalRoute(entry: FileEntry): Route? = when (entry.type) {
        FileType.FOLDER -> Route.Browser(entry.path)
        FileType.IMAGE, FileType.VIDEO, FileType.AUDIO, FileType.PDF, FileType.TEXT, FileType.CODE -> Route.Preview(entry.path)
        FileType.ARCHIVE -> if (entry.extension in setOf("zip", "7z", "rar", "tar", "gz", "tgz", "bz2", "tbz2", "xz", "txz", "jar")) Route.Archive(entry.path) else null
        FileType.APK -> Route.ApkInfo(entry.path)
        FileType.SPREADSHEET -> if (entry.extension == "csv" || entry.extension == "tsv") Route.Preview(entry.path) else null
        else -> null
    }

    fun uriFor(context: Context, path: String): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.provider", File(path))

    /** "Ouvrir avec": the default app, or a chooser when [chooser] is true. */
    fun openExternally(context: Context, path: String, chooser: Boolean = false, mime: String? = null): Boolean {
        val uri = runCatching { uriFor(context, path) }.getOrElse {
            Toast.makeText(context, "Impossible de partager ce fichier", Toast.LENGTH_SHORT).show(); return false
        }
        val type = mime ?: FileTypes.mimeOfName(path)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, type)
            clipData = ClipData.newRawUri(null, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(if (chooser) Intent.createChooser(intent, "Ouvrir avec").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) else intent)
            true
        } catch (_: ActivityNotFoundException) {
            if (!chooser) openExternally(context, path, chooser = true, mime = "*/*")
            else { Toast.makeText(context, "Aucune appli ne peut ouvrir ce fichier", Toast.LENGTH_SHORT).show(); false }
        }
    }

    fun isPolaroInstalled(context: Context): Boolean =
        runCatching { context.packageManager.getPackageInfo(POLARO_PACKAGE, 0); true }.getOrDefault(false)

    /** Opens an image in Polaro (com.karelisio.polaro). */
    fun openInPolaro(context: Context, path: String): Boolean {
        val uri = runCatching { uriFor(context, path) }.getOrNull() ?: return false
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, FileTypes.mimeOfName(path))
            setPackage(POLARO_PACKAGE)
            clipData = ClipData.newRawUri(null, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { context.startActivity(intent); true }.getOrDefault(false)
    }

    fun share(context: Context, paths: List<String>) {
        val uris = paths.mapNotNull { runCatching { uriFor(context, it) }.getOrNull() }
        if (uris.isEmpty()) return
        val mime = paths.map { FileTypes.mimeOfName(it) }.distinct().singleOrNull() ?: "*/*"
        val intent = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        intent.type = mime
        intent.clipData = ClipData.newRawUri(null, uris[0]).also { c -> uris.drop(1).forEach { c.addItem(ClipData.Item(it)) } }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, "Partager").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
