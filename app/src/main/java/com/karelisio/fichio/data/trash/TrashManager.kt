package com.karelisio.fichio.data.trash

import android.content.Context
import com.karelisio.fichio.FichioApp
import com.karelisio.fichio.data.db.TrashItemEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Moves deleted files into a hidden app-private trash directory and records
 * their original location in Room so they can be restored. Also backs "delete
 * to trash" so the operation can be undone.
 */
class TrashManager(context: Context) {

    private val container = (context.applicationContext as FichioApp).container
    private val dao = container.database.trashDao()
    private val trashDir = File(context.filesDir, TRASH_DIR_NAME)

    /** Moves items into the trash and returns the created metadata rows. */
    suspend fun moveToTrash(items: List<File>): List<TrashItemEntity> = withContext(Dispatchers.IO) {
        if (!trashDir.exists()) trashDir.mkdirs()
        items.mapNotNull { file ->
            val id = dao.insert(
                TrashItemEntity(
                    originalPath = file.absolutePath,
                    originalParent = file.parent ?: "",
                    name = file.name,
                    sizeBytes = sizeOf(file),
                    isDirectory = file.isDirectory,
                    deletedAt = System.currentTimeMillis()
                )
            )
            val target = File(trashDir, id.toString())
            val moved = runCatching { file.renameTo(target) }.getOrDefault(false)
            if (moved) dao.getAll().firstOrNull { it.id == id } else {
                dao.delete(id)
                null
            }
        }
    }

    /** Restores trashed items to their original location. */
    suspend fun restore(items: List<TrashItemEntity>): List<TrashItemEntity> = withContext(Dispatchers.IO) {
        items.mapNotNull { item ->
            val source = File(trashDir, item.id.toString())
            if (!source.exists()) {
                dao.delete(item.id)
                return@mapNotNull null
            }
            val original = File(item.originalPath)
            val parent = original.parentFile ?: return@mapNotNull null
            if (!parent.exists()) parent.mkdirs()
            val restored = runCatching {
                if (original.exists()) {
                    // Keep the restored copy under a non-conflicting name.
                    val alt = File(parent, uniqueName(original.name, parent))
                    source.renameTo(alt)
                } else {
                    source.renameTo(original)
                }
            }.getOrDefault(false)
            if (restored) {
                dao.delete(item.id)
                item
            } else null
        }
    }

    /** Permanently deletes trashed items from disk. */
    suspend fun permanentlyDelete(items: List<TrashItemEntity>): List<Long> = withContext(Dispatchers.IO) {
        items.mapNotNull { item ->
            File(trashDir, item.id.toString()).deleteRecursively()
            dao.delete(item.id)
            item.id
        }
    }

    /** Returns the most recently trashed item, or null if the trash is empty. */
    suspend fun lastTrashed(): TrashItemEntity? = dao.getAll().firstOrNull()

    private fun sizeOf(file: File): Long {
        if (file.isFile) return file.length()
        var total = 0L
        file.listFiles()?.forEach { total += sizeOf(it) }
        return total
    }

    private fun uniqueName(name: String, parent: File): String {
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (File(parent, "$stem ($i)$ext").exists()) i++
        return "$stem ($i)$ext"
    }

    companion object {
        const val TRASH_DIR_NAME = "trash"
    }
}
