package com.karelisio.fichio.domain.search

import com.karelisio.fichio.data.db.FichioDatabase
import com.karelisio.fichio.data.db.SearchIndexEntity
import com.karelisio.fichio.domain.file.FileCategory
import com.karelisio.fichio.domain.file.extensionOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * Builds and maintains the Room FTS4 search index. A full [rebuild] runs in a
 * WorkManager job; [indexPath] and [removeTree] keep it fresh after in-app
 * operations.
 */
object SearchIndexer {

    private val excludedDirs = setOf("Android", "lost+found", ".fichio", ".thumbnails")

    suspend fun rebuild(db: FichioDatabase, root: File): Int = withContext(Dispatchers.IO) {
        val entries = ArrayList<SearchIndexEntity>()
        walk(root, root.absolutePath, entries)
        db.searchDao().clear()
        var written = 0
        entries.chunked(CHUNK_SIZE).forEach { chunk ->
            db.searchDao().upsert(chunk)
            written += chunk.size
        }
        written
    }

    suspend fun indexPath(db: FichioDatabase, file: File) {
        if (file.name.startsWith(".") || isExcluded(file)) return
        db.searchDao().upsert(listOf(entity(file)))
    }

    suspend fun removeTree(db: FichioDatabase, path: String) {
        db.searchDao().deleteTree(path)
    }

    private suspend fun walk(dir: File, rootPath: String, out: MutableList<SearchIndexEntity>) {
        coroutineContext.ensureActive()
        val children = dir.listFiles() ?: return
        for (f in children) {
            if (f.name.startsWith(".")) continue
            if (f.isDirectory) {
                if (f.name !in excludedDirs) walk(f, rootPath, out)
            } else {
                out.add(entity(f))
            }
        }
    }

    private fun entity(file: File): SearchIndexEntity {
        val ext = extensionOf(file.name)
        val category = if (file.isDirectory) FileCategory.OTHER else FileCategory.fromExtension(ext)
        return SearchIndexEntity(
            path = file.absolutePath,
            name = file.name,
            parent = file.parent ?: "",
            extension = ext,
            category = category.name,
            sizeBytes = file.length().toString(),
            modifiedAt = file.lastModified().toString(),
            isDirectory = if (file.isDirectory) "1" else "0"
        )
    }

    private fun isExcluded(file: File): Boolean =
        file.absolutePath.split('/').any { it in excludedDirs }

    private const val CHUNK_SIZE = 500
}
