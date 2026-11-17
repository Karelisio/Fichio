package com.karelisio.fichio.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.room.Upsert
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteDao {
    @Query("SELECT * FROM favorites ORDER BY position")
    fun observe(): Flow<List<FavoriteEntity>>

    @Query("SELECT * FROM favorites ORDER BY position")
    suspend fun all(): List<FavoriteEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE path = :path)")
    fun isFavorite(path: String): Flow<Boolean>

    @Upsert
    suspend fun upsert(item: FavoriteEntity)

    @Upsert
    suspend fun upsertAll(items: List<FavoriteEntity>)

    @Query("DELETE FROM favorites WHERE path = :path")
    suspend fun delete(path: String)

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM favorites")
    suspend fun nextPosition(): Int

    @Query("UPDATE favorites SET path = :newPath || substr(path, length(:oldPath) + 1) WHERE path = :oldPath OR path LIKE :oldPath || '/%'")
    suspend fun movePrefix(oldPath: String, newPath: String)
}

@Dao
interface PinnedDao {
    @Query("SELECT * FROM pinned ORDER BY position")
    fun observe(): Flow<List<PinnedEntity>>

    @Upsert
    suspend fun upsert(item: PinnedEntity)

    @Query("DELETE FROM pinned WHERE path = :path")
    suspend fun delete(path: String)

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM pinned")
    suspend fun nextPosition(): Int
}

@Dao
interface TabDao {
    @Query("SELECT * FROM tabs ORDER BY position")
    suspend fun all(): List<TabEntity>

    @Transaction
    suspend fun replaceAll(tabs: List<TabEntity>) {
        clear()
        insertAll(tabs)
    }

    @Query("DELETE FROM tabs")
    suspend fun clear()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(tabs: List<TabEntity>)
}

@Dao
interface FolderSettingsDao {
    @Query("SELECT * FROM folder_settings WHERE path = :path")
    fun observe(path: String): Flow<FolderSettingsEntity?>

    @Query("SELECT * FROM folder_settings WHERE path = :path")
    suspend fun get(path: String): FolderSettingsEntity?

    @Upsert
    suspend fun upsert(item: FolderSettingsEntity)
}

@Dao
interface TrashDao {
    @Query("SELECT * FROM trash ORDER BY deletedAt DESC")
    fun observe(): Flow<List<TrashEntity>>

    @Query("SELECT * FROM trash ORDER BY deletedAt DESC")
    suspend fun all(): List<TrashEntity>

    @Query("SELECT * FROM trash WHERE id = :id")
    suspend fun get(id: String): TrashEntity?

    @Query("SELECT * FROM trash WHERE deletedAt < :before")
    suspend fun olderThan(before: Long): List<TrashEntity>

    @Query("SELECT COALESCE(SUM(size), 0) FROM trash")
    fun observeTotalSize(): Flow<Long>

    @Query("SELECT COUNT(*) FROM trash")
    fun observeCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: TrashEntity)

    @Query("DELETE FROM trash WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM trash")
    suspend fun clear()
}

/** Lightweight projection used by search results. */
data class IndexedFile(
    val path: String,
    val name: String,
    val parent: String,
    val extension: String,
    val type: String,
    val isDirectory: Boolean,
    val size: Long,
    val modified: Long,
)

@Dao
interface FileIndexDao {
    @Upsert
    suspend fun upsertAll(items: List<FileIndexEntity>)

    @Query("DELETE FROM file_index WHERE path = :path OR path LIKE :path || '/%'")
    suspend fun deleteTree(path: String)

    @Query("DELETE FROM file_index WHERE generation < :generation AND (path = :root OR path LIKE :root || '/%')")
    suspend fun purgeStale(root: String, generation: Long)

    @Query("SELECT COUNT(*) FROM file_index")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM file_index")
    fun observeCount(): Flow<Int>

    /** Arbitrary filtered search built by the search repository (FTS MATCH + filters). */
    @RawQuery(observedEntities = [FileIndexEntity::class])
    fun observeSearch(query: SupportSQLiteQuery): Flow<List<IndexedFile>>

    @RawQuery
    suspend fun search(query: SupportSQLiteQuery): List<IndexedFile>

    /** Category stats for the home screen: count + total size per type. */
    @Query("SELECT type, COUNT(*) AS count, COALESCE(SUM(size), 0) AS size FROM file_index WHERE isDirectory = 0 GROUP BY type")
    fun observeTypeStats(): Flow<List<TypeStat>>

    @Query("SELECT COUNT(*) AS count, COALESCE(SUM(size), 0) AS size FROM file_index WHERE isDirectory = 0 AND parent LIKE :dir || '%'")
    fun observeDirStats(dir: String): Flow<DirStat>

    @Query("SELECT path, name, parent, extension, type, isDirectory, size, modified FROM file_index WHERE isDirectory = 0 ORDER BY modified DESC LIMIT :limit")
    fun observeRecentlyModified(limit: Int): Flow<List<IndexedFile>>

    @Query("SELECT path, name, parent, extension, type, isDirectory, size, modified FROM file_index WHERE isDirectory = 0 AND type IN (:types) ORDER BY modified DESC")
    suspend fun byTypes(types: List<String>): List<IndexedFile>

    @Query("SELECT path, name, parent, extension, type, isDirectory, size, modified FROM file_index WHERE isDirectory = 0 AND type IN (:types) ORDER BY modified DESC")
    fun observeByTypes(types: List<String>): Flow<List<IndexedFile>>
}

data class TypeStat(val type: String, val count: Int, val size: Long)
data class DirStat(val count: Int, val size: Long)

@Dao
interface TransferLogDao {
    @Query("SELECT * FROM transfer_log ORDER BY time DESC LIMIT 200")
    fun observe(): Flow<List<TransferLogEntity>>

    @Insert
    suspend fun insert(item: TransferLogEntity)

    @Query("DELETE FROM transfer_log")
    suspend fun clear()
}

@Dao
interface RecentDao {
    @Query("SELECT * FROM recents ORDER BY openedAt DESC LIMIT :limit")
    fun observe(limit: Int = 30): Flow<List<RecentEntity>>

    @Upsert
    suspend fun upsert(item: RecentEntity)

    @Query("SELECT path FROM recents")
    suspend fun allPaths(): List<String>

    @Query("DELETE FROM recents WHERE path = :path")
    suspend fun delete(path: String)

    @Delete
    suspend fun deleteAll(items: List<RecentEntity>)
}
