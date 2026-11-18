package com.karelisio.fichio.trash

import com.karelisio.fichio.core.util.Strings
import com.karelisio.fichio.R
import com.karelisio.fichio.core.fs.FileBackend
import com.karelisio.fichio.data.db.TrashDao
import com.karelisio.fichio.data.db.TrashEntity
import com.karelisio.fichio.ops.engine.EngineListener
import com.karelisio.fichio.ops.engine.Names
import com.karelisio.fichio.ops.engine.OperationEngine
import com.karelisio.fichio.ops.engine.OperationException
import com.karelisio.fichio.ops.engine.PathUtil
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

/** Metadata persistence of the trash (Room in the app, an in-memory fake in tests). */
interface TrashMetaStore {
    suspend fun insert(item: TrashEntity)
    suspend fun get(id: String): TrashEntity?
    suspend fun all(): List<TrashEntity>
    suspend fun olderThan(before: Long): List<TrashEntity>
    suspend fun delete(id: String)
}

class RoomTrashMeta(private val dao: TrashDao) : TrashMetaStore {
    override suspend fun insert(item: TrashEntity) = dao.insert(item)
    override suspend fun get(id: String) = dao.get(id)
    override suspend fun all() = dao.all()
    override suspend fun olderThan(before: Long) = dao.olderThan(before)
    override suspend fun delete(id: String) = dao.delete(id)
}

/**
 * Pure-JVM trash logic. Layout: `<root>/<uuid>/<original name>`, with a `.nomedia` file in the
 * parent of [root] (the hidden `.Fichio` folder) and in [root] so galleries skip it.
 *
 * Moves try a rename through the *source* backend first (for Android/data that is the Shizuku
 * backend, which can reach the trash on the same volume), and fall back to copy + delete.
 */
class TrashStore(
    private val root: () -> String,
    private val backendFor: (String) -> FileBackend,
    private val meta: TrashMetaStore,
    private val engine: OperationEngine = OperationEngine(backendFor),
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000

        /** Whole days left before [item] is purged (0 = today). */
        fun daysLeft(item: TrashEntity, retentionDays: Int, now: Long = System.currentTimeMillis()): Int {
            val expires = item.deletedAt + retentionDays * DAY_MS
            return ((expires - now).coerceAtLeast(0) / DAY_MS).toInt()
        }
    }

    fun isInTrash(path: String) = PathUtil.isSameOrInside(path, root())

    /** Moves [path] to the trash and records it. */
    suspend fun moveToTrash(path: String, listener: EngineListener = EngineListener.None): TrashEntity = withContext(io) {
        val trashRoot = root()
        if (PathUtil.isSameOrInside(path, trashRoot)) throw OperationException(Strings.get(R.string.trash_err_already))
        if (PathUtil.isSameOrInside(trashRoot, path)) throw OperationException(Strings.get(R.string.trash_err_contains))
        val srcB = backendFor(path)
        val entry = srcB.stat(path) ?: throw IOException(Strings.get(R.string.trash_err_not_found, PathUtil.name(path)))
        ensureRoot(trashRoot)

        val id = newId()
        val itemDir = PathUtil.join(trashRoot, id)
        val dest = PathUtil.join(itemDir, entry.name)
        val size = if (entry.isDirectory) measure(srcB, entry) else entry.size

        srcB.mkdirs(itemDir)
        var moved = srcB.rename(path, dest)
        if (!moved) {
            val r = engine.moveExact(listOf(path to dest), listener)
            moved = !srcB.exists(path)
            if (!moved) {
                val trashB = backendFor(dest)
                if (r.failed > 0 || !trashB.exists(dest)) {
                    // Nothing (or only part) reached the trash: undo the attempt, keep the original.
                    trashB.deleteRecursively(itemDir)
                    throw IOException(r.errors.firstOrNull() ?: Strings.get(R.string.trash_err_move, entry.name))
                }
            }
        }
        val item = TrashEntity(
            id = id,
            originalPath = path,
            trashPath = dest,
            name = entry.name,
            isDirectory = entry.isDirectory,
            size = size,
            deletedAt = now(),
        )
        meta.insert(item)
        if (!moved) throw IOException(Strings.get(R.string.trash_err_original_kept, entry.name))
        item
    }

    /** Restores [id] to its original path (parents recreated, both kept on conflict). Returns the final path. */
    suspend fun restore(id: String, listener: EngineListener = EngineListener.None): Result<String> = runCatching {
        withContext(io) {
            val item = meta.get(id) ?: throw IOException(Strings.get(R.string.trash_err_item_missing))
            val trashB = backendFor(item.trashPath)
            if (!trashB.exists(item.trashPath)) {
                meta.delete(id)
                throw IOException(Strings.get(R.string.trash_err_gone, item.name))
            }
            val parent = PathUtil.parent(item.originalPath)
            val targetB = backendFor(item.originalPath)
            targetB.mkdirs(parent)
            var target = item.originalPath
            if (targetB.exists(target)) {
                target = PathUtil.join(parent, Names.unique(item.name, item.isDirectory) { targetB.exists(PathUtil.join(parent, it)) })
            }
            if (!targetB.rename(item.trashPath, target)) {
                val r = engine.moveExact(listOf(item.trashPath to target), listener)
                if (trashB.exists(item.trashPath)) {
                    throw IOException(r.errors.firstOrNull() ?: Strings.get(R.string.trash_err_restore, item.name))
                }
                target = r.transfers.firstOrNull()?.to ?: target
            }
            meta.delete(id)
            deleteItemDir(item)
            target
        }
    }

    suspend fun deleteForever(id: String) = withContext(io) {
        val item = meta.get(id) ?: return@withContext
        deleteItemDir(item)
        meta.delete(id)
    }

    /** Deletes every item, plus orphan folders left in the trash. Returns the number of items. */
    suspend fun empty(): Int = withContext(io) {
        val items = meta.all()
        items.forEach { deleteForever(it.id) }
        val trashRoot = root()
        val b = runCatching { backendFor(trashRoot) }.getOrNull()
        b?.let { runCatching { it.list(trashRoot) }.getOrDefault(emptyList()) }
            ?.filter { it.name != ".nomedia" }
            ?.forEach { b.deleteRecursively(it.path) }
        items.size
    }

    /** Purges items deleted more than [days] days ago and forgets rows whose files vanished. */
    suspend fun purgeOlderThan(days: Int): Int = withContext(io) {
        val old = meta.olderThan(now() - days * DAY_MS)
        old.forEach { deleteForever(it.id) }
        reconcile()
        old.size
    }

    /** Drops rows whose file is gone (deleted by another app, storage wiped…). */
    suspend fun reconcile(): Int = withContext(io) {
        var removed = 0
        for (item in meta.all()) {
            val b = runCatching { backendFor(item.trashPath) }.getOrNull() ?: continue
            if (!b.exists(item.trashPath)) {
                meta.delete(item.id); removed++
            }
        }
        removed
    }

    private fun deleteItemDir(item: TrashEntity) {
        val trashRoot = root()
        val dir = PathUtil.parent(item.trashPath)
        // Safety net: only ever delete inside the trash root.
        val victim = if (dir != trashRoot && PathUtil.isSameOrInside(dir, trashRoot)) dir
        else if (PathUtil.isSameOrInside(item.trashPath, trashRoot) && item.trashPath != trashRoot) item.trashPath
        else return
        runCatching { backendFor(victim).deleteRecursively(victim) }
    }

    private fun ensureRoot(trashRoot: String) {
        val b = backendFor(trashRoot)
        if (!b.mkdirs(trashRoot)) throw IOException(Strings.get(R.string.trash_err_create))
        for (dir in listOf(PathUtil.parent(trashRoot), trashRoot)) {
            val nomedia = PathUtil.join(dir, ".nomedia")
            if (!b.exists(nomedia)) b.createFile(nomedia)
        }
    }

    private fun measure(b: FileBackend, e: com.karelisio.fichio.core.model.FileEntry): Long {
        if (!e.isDirectory) return e.size
        return runCatching { b.list(e.path) }.getOrDefault(emptyList()).sumOf { measure(b, it) }
    }
}
