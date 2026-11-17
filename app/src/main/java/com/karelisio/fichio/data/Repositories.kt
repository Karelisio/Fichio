package com.karelisio.fichio.data

import com.karelisio.fichio.core.fs.FileSystems
import com.karelisio.fichio.core.fs.StoragePaths
import com.karelisio.fichio.core.model.FileEntry
import com.karelisio.fichio.data.db.FavoriteEntity
import com.karelisio.fichio.data.db.FichioDatabase
import com.karelisio.fichio.data.db.PinnedEntity
import com.karelisio.fichio.data.db.RecentEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class FavoritesRepository(private val db: FichioDatabase) {
    val favorites: Flow<List<FavoriteEntity>> = db.favorites().observe()
    val pinned: Flow<List<PinnedEntity>> = db.pinned().observe()

    fun isFavorite(path: String): Flow<Boolean> = db.favorites().isFavorite(path)

    suspend fun add(path: String, label: String = StoragePaths.name(path)) {
        db.favorites().upsert(FavoriteEntity(path, label, db.favorites().nextPosition()))
    }

    suspend fun remove(path: String) = db.favorites().delete(path)

    suspend fun toggle(path: String) {
        if (db.favorites().all().any { it.path == path }) remove(path) else add(path)
    }

    /** Persists a new order after drag-to-reorder. */
    suspend fun reorder(paths: List<String>) {
        val byPath = db.favorites().all().associateBy { it.path }
        db.favorites().upsertAll(paths.mapIndexedNotNull { i, p -> byPath[p]?.copy(position = i) })
    }

    suspend fun rename(path: String, label: String) {
        db.favorites().all().firstOrNull { it.path == path }?.let { db.favorites().upsert(it.copy(label = label)) }
    }

    suspend fun pin(path: String) = db.pinned().upsert(PinnedEntity(path, db.pinned().nextPosition()))
    suspend fun unpin(path: String) = db.pinned().delete(path)

    /** Keeps favourites valid after an in-app move/rename of a folder. */
    suspend fun onMoved(oldPath: String, newPath: String) = db.favorites().movePrefix(oldPath, newPath)
}

class RecentsRepository(
    private val db: FichioDatabase,
    private val fs: FileSystems,
    private val scope: CoroutineScope,
) {
    /** Recently opened files that still exist. */
    val recents: Flow<List<FileEntry>> = db.recents().observe(40).map { rows ->
        rows.mapNotNull { r -> fs.local.stat(r.path)?.takeIf { !it.isDirectory } }
    }.flowOn(Dispatchers.IO)

    fun record(path: String) {
        scope.launch { db.recents().upsert(RecentEntity(path, System.currentTimeMillis())) }
    }

    suspend fun remove(path: String) = db.recents().delete(path)
}
