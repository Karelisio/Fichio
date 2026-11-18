package com.karelisio.fichio.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.room.Upsert
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

@Dao
interface TagDao {
    @Query("SELECT * FROM tags ORDER BY position")
    fun observe(): Flow<List<TagEntity>>

    @Query("SELECT * FROM tags ORDER BY position")
    suspend fun all(): List<TagEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfMissing(items: List<TagEntity>)

    @Query("UPDATE tags SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("SELECT tagId, COUNT(*) AS count FROM file_tags GROUP BY tagId")
    fun observeCounts(): Flow<List<TagCount>>

    @Query("SELECT * FROM file_tags")
    fun observeAssignments(): Flow<List<FileTagEntity>>

    @Query("SELECT path FROM file_tags WHERE tagId = :tagId ORDER BY addedAt DESC")
    fun observePaths(tagId: Long): Flow<List<String>>

    @Query("SELECT DISTINCT path FROM file_tags WHERE tagId IN (:ids)")
    suspend fun pathsWith(ids: List<Long>): List<String>

    @Query("SELECT * FROM file_tags WHERE path IN (:paths)")
    suspend fun forPaths(paths: List<String>): List<FileTagEntity>

    @Upsert
    suspend fun assign(items: List<FileTagEntity>)

    @Query("DELETE FROM file_tags WHERE tagId = :tagId AND path IN (:paths)")
    suspend fun unassign(tagId: Long, paths: List<String>)

    /**
     * Re-roots tags after an in-app move/rename. [pattern] is the escaped `old/%` LIKE pattern;
     * OR REPLACE keeps the (path, tagId) key unique when the destination already had the tag.
     */
    @Query(
        "UPDATE OR REPLACE file_tags SET path = :newPath || substr(path, length(:oldPath) + 1) " +
            "WHERE path = :oldPath OR path LIKE :pattern ESCAPE '\\'",
    )
    suspend fun movePrefix(oldPath: String, newPath: String, pattern: String)
}

@Dao
interface ContentDao {
    @Query("SELECT * FROM content_meta WHERE path = :path")
    suspend fun meta(path: String): ContentMetaEntity?

    @Insert
    suspend fun insertMeta(item: ContentMetaEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertText(item: ContentFts)

    @Query("DELETE FROM content_fts WHERE rowid IN (SELECT id FROM content_meta WHERE path = :path)")
    suspend fun deleteText(path: String)

    @Query("DELETE FROM content_meta WHERE path = :path")
    suspend fun deleteMeta(path: String)

    /** Replaces what is known about [path]; [body] null = read but nothing to index. */
    @Transaction
    suspend fun replace(path: String, modified: Long, size: Long, body: String?) {
        deleteText(path)
        deleteMeta(path)
        val id = insertMeta(ContentMetaEntity(path = path, modified = modified, size = size, indexed = body != null))
        if (body != null) insertText(ContentFts(id, path, body))
    }

    /** Drops content rows whose file left the name index (deleted, moved, excluded). */
    @Transaction
    suspend fun purgeOrphans() {
        purgeOrphanText()
        purgeOrphanMeta()
    }

    @Query("DELETE FROM content_fts WHERE rowid IN (SELECT m.id FROM content_meta m LEFT JOIN file_index f ON f.path = m.path WHERE f.path IS NULL)")
    suspend fun purgeOrphanText()

    @Query("DELETE FROM content_meta WHERE path NOT IN (SELECT path FROM file_index)")
    suspend fun purgeOrphanMeta()

    @Query("SELECT COUNT(*) FROM content_meta WHERE indexed = 1")
    fun observeIndexedCount(): Flow<Int>

    /** Files whose content still has to be read (built by ContentRules.pendingQuery). */
    @RawQuery
    suspend fun pending(query: SupportSQLiteQuery): List<IndexedFile>

    /** Content search (built by SearchSqlBuilder.buildContent). */
    @RawQuery(observedEntities = [FileIndexEntity::class, ContentMetaEntity::class, FileTagEntity::class])
    fun observeHits(query: SupportSQLiteQuery): Flow<List<ContentHit>>

    @RawQuery
    suspend fun hits(query: SupportSQLiteQuery): List<ContentHit>
}

@Dao
interface CollectionsDao {
    /** Smart folder counters (built by SmartFolders.countQuery). */
    @RawQuery
    suspend fun counts(query: SupportSQLiteQuery): List<SmartCount>

    /** Smart folder content (built by SmartFolders.listQuery). */
    @RawQuery(observedEntities = [FileIndexEntity::class])
    fun observeFiles(query: SupportSQLiteQuery): Flow<List<IndexedFile>>
}
