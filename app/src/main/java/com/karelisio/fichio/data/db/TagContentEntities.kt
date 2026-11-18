package com.karelisio.fichio.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * Schema v3: colour tags and full-text content index. Created by [FichioDatabase.MIGRATION_2_3].
 */

/**
 * One of the 7 colour tags. [id] is 1..7 and [color] the palette slot (0..6); only [name] is
 * user-editable. Rows are created on demand by the tags repository.
 */
@Entity(tableName = "tags")
data class TagEntity(
    @PrimaryKey val id: Long,
    val name: String,
    val color: Int,
    val position: Int,
)

/** A tag attached to a file or folder (absolute path, kept in sync by in-app moves/renames). */
@Entity(tableName = "file_tags", primaryKeys = ["path", "tagId"], indices = [Index("tagId")])
data class FileTagEntity(
    val path: String,
    val tagId: Long,
    val addedAt: Long = System.currentTimeMillis(),
)

/**
 * Bookkeeping of the content index: which version (modified/size) of a file was read. Its [id]
 * is the rowid of the matching [ContentFts] row (absent when the file had no extractable text).
 */
@Entity(tableName = "content_meta", indices = [Index(value = ["path"], unique = true)])
data class ContentMetaEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val path: String,
    val modified: Long,
    val size: Long,
    /** True when text was extracted and stored in content_fts. */
    val indexed: Boolean,
)

/** FTS4 index of the beginning (~512 KB) of text-like files and of PDF text. */
@Fts4(tokenizer = FtsOptions.TOKENIZER_UNICODE61, notIndexed = ["path"])
@Entity(tableName = "content_fts")
data class ContentFts(
    @PrimaryKey @ColumnInfo(name = "rowid") val rowId: Long,
    val path: String,
    val body: String,
)

/** Tag usage count (home section, tag filter). */
data class TagCount(val tagId: Long, val count: Int)

/** A search result found through the content index, with its FTS snippet. */
data class ContentHit(
    val path: String,
    val name: String,
    val parent: String,
    val extension: String,
    val type: String,
    val isDirectory: Boolean,
    val size: Long,
    val modified: Long,
    val snippet: String?,
)

/** File count + size of one smart folder. */
data class SmartCount(val kind: String, val count: Int, val size: Long)
