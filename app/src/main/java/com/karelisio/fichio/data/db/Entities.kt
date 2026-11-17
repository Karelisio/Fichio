package com.karelisio.fichio.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey

/** Folder view mode, configurable per directory. */
enum class ViewMode { LIST, GRID, LARGE }

/** Sort modes for file listings. */
enum class SortMode { NAME, DATE, SIZE, TYPE }

/** A favorited file or folder. */
@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey val path: String,
    val name: String,
    val isDirectory: Boolean,
    val addedAt: Long,
    val orderIndex: Int
)

/** A pinned folder shown in quick access. */
@Entity(tableName = "pinned")
data class PinnedFolderEntity(
    @PrimaryKey val path: String,
    val name: String,
    val pinnedAt: Long,
    val orderIndex: Int
)

/** An open browser tab (max 5). */
@Entity(tableName = "tabs")
data class OpenTabEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val path: String,
    val name: String,
    val position: Int
)

/** A file or folder moved to the Fichio trash. */
@Entity(tableName = "trash", indices = [Index("deletedAt")])
data class TrashItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val originalPath: String,
    val originalParent: String,
    val name: String,
    val sizeBytes: Long,
    val isDirectory: Boolean,
    val deletedAt: Long
)

/**
 * Local full-text search index (Room FTS4). All columns are TEXT because
 * FTS4 requires text columns; numeric values are string-encoded.
 */
@Fts4
@Entity(tableName = "search_index")
data class SearchIndexEntity(
    @ColumnInfo(name = "path") val path: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "parent") val parent: String,
    @ColumnInfo(name = "extension") val extension: String,
    @ColumnInfo(name = "category") val category: String,
    @ColumnInfo(name = "size_bytes") val sizeBytes: String,
    @ColumnInfo(name = "modified_at") val modifiedAt: String,
    @ColumnInfo(name = "is_directory") val isDirectory: String
)

/** Per-directory view preferences. */
@Entity(tableName = "folder_settings")
data class FolderSettingsEntity(
    @PrimaryKey val path: String,
    val viewMode: ViewMode,
    val sortMode: SortMode,
    val sortAscending: Boolean,
    val foldersFirst: Boolean,
    val showHidden: Boolean
)

/** History of local transfer operations. */
@Entity(tableName = "transfer_history", indices = [Index("timestamp")])
data class TransferLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val direction: String, // "upload" | "download"
    val name: String,
    val sizeBytes: Long,
    val remote: String,
    val success: Boolean
)
