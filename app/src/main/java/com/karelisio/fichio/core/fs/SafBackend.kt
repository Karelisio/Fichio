package com.karelisio.fichio.core.fs

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import com.karelisio.fichio.core.model.FileEntry
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Backend for a removable volume (SD card, USB key) that java.io cannot write to, through a
 * Storage Access Framework tree the user granted on the volume root (persisted permission).
 *
 * Reads stay on java.io whenever it works (much faster than the provider and always allowed
 * with « Accès à tous les fichiers »); writes, renames, moves and deletions go through
 * DocumentsContract. Paths are mapped to document ids by [SafPaths].
 */
class SafBackend(
    private val context: Context,
    val volumeRoot: String,
    val volumeId: String,
    val treeUri: Uri,
) : FileBackend {
    private val treeDocId: String = DocumentsContract.getTreeDocumentId(treeUri)
    private val local = LocalBackend()
    private val resolver get() = context.contentResolver

    override val id: String = "saf:$volumeId"

    fun covers(path: String) = SafPaths.covers(volumeRoot, volumeId, treeDocId, path)

    private fun docId(path: String): String =
        SafPaths.documentIdFor(volumeRoot, volumeId, treeDocId, path)
            ?: throw IOException("« ${StoragePaths.name(path)} » est hors du dossier autorisé")

    private fun uri(path: String): Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId(path))

    private inline fun <T> io(block: () -> T): T = try {
        block()
    } catch (e: IOException) {
        throw e
    } catch (e: Exception) {
        // SecurityException (permission revoked), IllegalArgumentException / IllegalStateException from the provider.
        throw IOException(e.message ?: "Accès au volume refusé", e)
    }

    // ------------------------------------------------------------------ reads

    override fun list(path: String): List<FileEntry> =
        runCatching { local.list(path) }.getOrNull() ?: io { safList(path) }

    private fun safList(path: String): List<FileEntry> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId(path))
        val out = ArrayList<FileEntry>()
        resolver.query(children, PROJECTION, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0) ?: continue
                out += entry(StoragePaths.join(path, name), name, c.getString(1), c.getLong(2), c.getLong(3))
            }
        } ?: throw IOException("Impossible de lire $path")
        return out
    }

    private fun safStat(path: String): FileEntry? = runCatching {
        resolver.query(uri(path), PROJECTION, null, null, null)?.use { c ->
            if (!c.moveToFirst()) null
            else entry(path, c.getString(0) ?: StoragePaths.name(path), c.getString(1), c.getLong(2), c.getLong(3))
        }
    }.getOrNull()

    private fun entry(path: String, name: String, mime: String?, size: Long, modified: Long): FileEntry {
        val dir = mime == Document.MIME_TYPE_DIR
        return FileEntry(path, name, dir, if (dir) 0 else size, modified)
    }

    override fun stat(path: String): FileEntry? = local.stat(path) ?: safStat(path)

    override fun exists(path: String): Boolean = local.exists(path) || safStat(path) != null

    override fun openInput(path: String): InputStream = try {
        local.openInput(path)
    } catch (_: IOException) {
        io { resolver.openInputStream(uri(path)) ?: throw IOException("Impossible d'ouvrir ${StoragePaths.name(path)}") }
    }

    // ------------------------------------------------------------------ writes

    override fun openOutput(path: String, append: Boolean): OutputStream = io {
        if (!exists(path)) {
            if (!mkdirs(StoragePaths.parent(path))) throw IOException("Impossible de créer le dossier de ${StoragePaths.name(path)}")
            if (!createFile(path)) throw IOException("Impossible de créer ${StoragePaths.name(path)}")
        }
        resolver.openOutputStream(uri(path), if (append) "wa" else "wt")
            ?: throw IOException("Impossible d'écrire ${StoragePaths.name(path)}")
    }

    override fun mkdirs(path: String): Boolean = runCatching {
        if (stat(path)?.isDirectory == true) return@runCatching true
        val missing = SafPaths.missingDirs(volumeRoot, volumeId, treeDocId, path) { exists(it) } ?: return@runCatching false
        for (dir in missing) {
            if (stat(dir)?.isDirectory == true) continue
            val created = DocumentsContract.createDocument(resolver, uri(StoragePaths.parent(dir)), Document.MIME_TYPE_DIR, StoragePaths.name(dir))
                ?: return@runCatching false
            if (displayName(created) != StoragePaths.name(dir)) return@runCatching false
        }
        true
    }.getOrDefault(false)

    override fun createFile(path: String): Boolean = runCatching {
        if (exists(path)) return@runCatching false
        val parent = StoragePaths.parent(path)
        if (!mkdirs(parent)) return@runCatching false
        // octet-stream: the provider keeps the name as is (no extension added from the MIME type).
        val created = DocumentsContract.createDocument(resolver, uri(parent), "application/octet-stream", StoragePaths.name(path))
            ?: return@runCatching false
        if (displayName(created) == StoragePaths.name(path)) true
        else {
            // The provider picked another name (e.g. "x (1)"): undo, the caller decides what to do.
            DocumentsContract.deleteDocument(resolver, created)
            false
        }
    }.getOrDefault(false)

    override fun deleteSingle(path: String): Boolean = runCatching {
        val e = stat(path) ?: return@runCatching false
        // FileBackend contract: a non-empty directory is not deleted here (SAF would delete it all).
        if (e.isDirectory && list(path).isNotEmpty()) return@runCatching false
        DocumentsContract.deleteDocument(resolver, uri(path))
    }.getOrDefault(false)

    override fun deleteRecursively(path: String): Boolean = runCatching {
        DocumentsContract.deleteDocument(resolver, uri(path))
    }.getOrDefault(false)

    override fun rename(from: String, to: String): Boolean = runCatching {
        if (from == to) return@runCatching true
        if (!covers(from) || !covers(to)) return@runCatching false
        if (exists(to) && !from.equals(to, ignoreCase = true)) return@runCatching false
        val fromParent = StoragePaths.parent(from)
        val toParent = StoragePaths.parent(to)
        var current = uri(from)
        if (fromParent != toParent) {
            if (!mkdirs(toParent)) return@runCatching false
            current = DocumentsContract.moveDocument(resolver, current, uri(fromParent), uri(toParent)) ?: return@runCatching false
        }
        val newName = StoragePaths.name(to)
        if (StoragePaths.name(from) != newName) {
            val renamed = DocumentsContract.renameDocument(resolver, current, newName) ?: return@runCatching false
            if (displayName(renamed) != newName) {
                // e.g. a case-only change on FAT became "Name (1)": put it back and report failure.
                DocumentsContract.renameDocument(resolver, renamed, StoragePaths.name(from))
                return@runCatching false
            }
        }
        true
    }.getOrDefault(false)

    override fun freeSpace(path: String): Long = runCatching { StatFs(volumeRoot).availableBytes }.getOrDefault(Long.MAX_VALUE)

    private fun displayName(doc: Uri): String? = runCatching {
        resolver.query(doc, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    private companion object {
        val PROJECTION = arrayOf(
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
        )
    }
}
