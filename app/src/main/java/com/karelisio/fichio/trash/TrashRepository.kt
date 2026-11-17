package com.karelisio.fichio.trash

import android.content.Context
import com.karelisio.fichio.core.fs.FileSystems
import com.karelisio.fichio.data.db.FichioDatabase
import com.karelisio.fichio.data.db.TrashEntity
import kotlinx.coroutines.flow.Flow

/**
 * Trash = hidden folder `.Fichio/trash` on the primary volume + metadata in Room.
 * CONTRACT STUB — implemented by the operations module.
 */
class TrashRepository(
    private val context: Context,
    private val db: FichioDatabase,
    private val fs: FileSystems,
) {
    val items: Flow<List<TrashEntity>> = db.trash().observe()
    val count: Flow<Int> = db.trash().observeCount()
    val totalSize: Flow<Long> = db.trash().observeTotalSize()

    /** Moves [path] into the trash and records it. Blocking IO inside; call from a coroutine. */
    suspend fun moveToTrash(path: String): TrashEntity = TODO()
    /** Restores to the original path (keeps both on conflict). Returns the restored path. */
    suspend fun restore(id: String): Result<String> = TODO()
    suspend fun deleteForever(id: String) {}
    suspend fun empty() {}
    /** Deletes items older than [days]. Returns how many were purged. */
    suspend fun purgeOlderThan(days: Int): Int = 0
}
