package com.karelisio.fichio.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteDao {
    @Query("SELECT * FROM favorites ORDER BY orderIndex")
    fun observeAll(): Flow<List<FavoriteEntity>>

    @Query("SELECT * FROM favorites ORDER BY orderIndex")
    suspend fun getAll(): List<FavoriteEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(favorite: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE path = :path")
    suspend fun delete(path: String)

    @Query("UPDATE favorites SET orderIndex = :order WHERE path = :path")
    suspend fun updateOrder(path: String, order: Int)

    @Query("SELECT COUNT(*) FROM favorites")
    suspend fun count(): Int
}

@Dao
interface PinnedDao {
    @Query("SELECT * FROM pinned ORDER BY orderIndex")
    fun observeAll(): Flow<List<PinnedFolderEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(pinned: PinnedFolderEntity)

    @Query("DELETE FROM pinned WHERE path = :path")
    suspend fun delete(path: String)

    @Query("UPDATE pinned SET orderIndex = :order WHERE path = :path")
    suspend fun updateOrder(path: String, order: Int)
}

@Dao
interface TabDao {
    @Query("SELECT * FROM tabs ORDER BY position")
    fun observeAll(): Flow<List<OpenTabEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(tab: OpenTabEntity)

    @Query("DELETE FROM tabs WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM tabs")
    suspend fun clear()
}

@Dao
interface TrashDao {
    @Query("SELECT * FROM trash ORDER BY deletedAt DESC")
    fun observeAll(): Flow<List<TrashItemEntity>>

    @Query("SELECT * FROM trash ORDER BY deletedAt DESC")
    suspend fun getAll(): List<TrashItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: TrashItemEntity): Long

    @Query("DELETE FROM trash WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM trash WHERE id IN (:ids)")
    suspend fun deleteAll(ids: List<Long>)

    @Query("SELECT * FROM trash WHERE deletedAt < :cutoff")
    suspend fun olderThan(cutoff: Long): List<TrashItemEntity>
}

@Dao
interface SearchDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entries: List<SearchIndexEntity>)

    @Query("DELETE FROM search_index")
    suspend fun clear()

    @Query("DELETE FROM search_index WHERE path = :path")
    suspend fun delete(path: String)

    @Query("DELETE FROM search_index WHERE path = :dir OR path LIKE :dir || '/%'")
    suspend fun deleteTree(dir: String)

    @Query("SELECT * FROM search_index WHERE search_index MATCH :query LIMIT :limit")
    suspend fun search(query: String, limit: Int): List<SearchIndexEntity>

    @Query("SELECT * FROM search_index LIMIT :limit")
    suspend fun getAll(limit: Int): List<SearchIndexEntity>

    @Query("SELECT COUNT(*) FROM search_index")
    suspend fun count(): Int
}

@Dao
interface FolderSettingsDao {
    @Query("SELECT * FROM folder_settings WHERE path = :path")
    suspend fun get(path: String): FolderSettingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(settings: FolderSettingsEntity)
}

@Dao
interface TransferDao {
    @Query("SELECT * FROM transfer_history ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<TransferLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(log: TransferLogEntity)

    @Query("DELETE FROM transfer_history")
    suspend fun clear()
}
