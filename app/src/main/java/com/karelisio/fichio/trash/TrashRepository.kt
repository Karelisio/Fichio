package com.karelisio.fichio.trash

import android.content.Context
import com.karelisio.fichio.core.fs.FileSystems
import com.karelisio.fichio.core.fs.StoragePaths
import com.karelisio.fichio.data.db.FichioDatabase
import com.karelisio.fichio.data.db.TrashEntity
import com.karelisio.fichio.ops.engine.EngineListener
import kotlinx.coroutines.flow.Flow

/**
 * Trash = hidden folder `.Fichio/trash` on the primary volume + metadata in Room.
 * Thin Android adapter around the pure [TrashStore]; also schedules the daily purge.
 */
class TrashRepository(
    private val context: Context,
    private val db: FichioDatabase,
    private val fs: FileSystems,
) {
    val items: Flow<List<TrashEntity>> = db.trash().observe()
    val count: Flow<Int> = db.trash().observeCount()
    val totalSize: Flow<Long> = db.trash().observeTotalSize()

    val store = TrashStore(
        root = { StoragePaths.trashDir },
        backendFor = fs::backendFor,
        meta = RoomTrashMeta(db.trash()),
    )

    init {
        // Idempotent (KEEP policy); WorkManager may be unavailable in tests.
        runCatching { TrashPurgeWorker.schedule(context) }
    }

    fun isInTrash(path: String) = store.isInTrash(path)

    /** Moves [path] into the trash and records it. Blocking IO inside; call from a coroutine. */
    suspend fun moveToTrash(path: String): TrashEntity = moveToTrash(path, EngineListener.None)

    /** Same, with pause/cancel/progress hooks (used by the operation queue for cross-volume copies). */
    suspend fun moveToTrash(path: String, listener: EngineListener): TrashEntity =
        store.moveToTrash(path, listener).also { fs.notifyChanged(StoragePaths.parent(path)) }

    /** Restores to the original path (keeps both on conflict). Returns the restored path. */
    suspend fun restore(id: String): Result<String> = restore(id, EngineListener.None)

    suspend fun restore(id: String, listener: EngineListener): Result<String> =
        store.restore(id, listener).onSuccess { fs.notifyChanged(StoragePaths.parent(it)) }

    suspend fun deleteForever(id: String) = store.deleteForever(id)

    suspend fun empty() {
        store.empty()
    }

    /** Deletes items older than [days]. Returns how many were purged. */
    suspend fun purgeOlderThan(days: Int): Int = store.purgeOlderThan(days)

    suspend fun get(id: String): TrashEntity? = db.trash().get(id)

    /** Days left before automatic purge. */
    fun daysLeft(item: TrashEntity, retentionDays: Int, now: Long = System.currentTimeMillis()) =
        TrashStore.daysLeft(item, retentionDays, now)
}
