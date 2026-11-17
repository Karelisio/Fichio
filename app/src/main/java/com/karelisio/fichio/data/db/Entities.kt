package com.karelisio.fichio.data.db

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Index
import androidx.room.PrimaryKey

/** Favourite folders (home + widget), user-reorderable via [position]. */
@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey val path: String,
    val label: String,
    val position: Int,
    val addedAt: Long = System.currentTimeMillis(),
)

/** Folders pinned at the top of the drawer / quick access. */
@Entity(tableName = "pinned")
data class PinnedEntity(
    @PrimaryKey val path: String,
    val position: Int,
)

/** Open browser tabs (max 5), restored at launch. */
@Entity(tableName = "tabs")
data class TabEntity(
    @PrimaryKey val id: Long,
    val path: String,
    val position: Int,
    val selected: Boolean,
)

/** Per-folder view settings. */
@Entity(tableName = "folder_settings")
data class FolderSettingsEntity(
    @PrimaryKey val path: String,
    val viewMode: String,
    val sortBy: String,
    val ascending: Boolean,
    val foldersFirst: Boolean,
)

/** Metadata of an item moved to the Fichio trash. */
@Entity(tableName = "trash", indices = [Index("deletedAt")])
data class TrashEntity(
    @PrimaryKey val id: String,
    /** Absolute original path, used for restore. */
    val originalPath: String,
    /** Absolute path of the item inside the trash folder. */
    val trashPath: String,
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val deletedAt: Long,
)

/** Search index row (content table for FTS). */
@Entity(
    tableName = "file_index",
    indices = [Index("parent"), Index("type"), Index("size"), Index("modified"), Index("extension")],
)
data class FileIndexEntity(
    @PrimaryKey val path: String,
    val name: String,
    val parent: String,
    val extension: String,
    /** [com.karelisio.fichio.core.model.FileType] name. */
    val type: String,
    val isDirectory: Boolean,
    val size: Long,
    val modified: Long,
    /** Generation of the scan that last saw this row; stale rows are purged after a full scan. */
    val generation: Long,
)

/** FTS4 mirror of [FileIndexEntity.name] for instant prefix search. */
@Fts4(contentEntity = FileIndexEntity::class, tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "file_index_fts")
data class FileIndexFts(
    val name: String,
)

/** Local transfer server log. */
@Entity(tableName = "transfer_log", indices = [Index("time")])
data class TransferLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val time: Long,
    /** "download", "upload", "mkdir", "connect", "denied". */
    val action: String,
    val path: String,
    val size: Long,
    val client: String,
)

/** Recently opened files (home carousel, "never opened" analysis). */
@Entity(tableName = "recents", indices = [Index("openedAt")])
data class RecentEntity(
    @PrimaryKey val path: String,
    val openedAt: Long,
)
