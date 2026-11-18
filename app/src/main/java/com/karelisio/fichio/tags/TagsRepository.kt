package com.karelisio.fichio.tags

import com.karelisio.fichio.core.util.Strings
import com.karelisio.fichio.R
import androidx.compose.runtime.Immutable
import com.karelisio.fichio.data.db.FichioDatabase
import com.karelisio.fichio.data.db.FileTagEntity
import com.karelisio.fichio.data.db.TagEntity
import com.karelisio.fichio.search.SearchSqlBuilder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/** A colour tag as shown in the UI. [slot] is the palette index (0..6). */
@Immutable
data class Tag(val id: Long, val name: String, val slot: Int, val count: Int = 0) {
    val displayName: String get() = name.ifBlank { TagPalette.defaultName(slot) }
}

/**
 * The 7 nameable colour tags and their assignment to files/folders (Room tables `tags`,
 * `file_tags`). Paths follow in-app moves/renames through [onMoved].
 */
class TagsRepository(private val db: FichioDatabase) {
    private val dao get() = db.tags()

    /** Creates the 7 default rows when missing (fresh install or first use after upgrade). */
    suspend fun ensureDefaults() {
        dao.insertIfMissing(TagPalette.defaults())
    }

    /** Every tag with its usage count, in palette order. */
    val tags: Flow<List<Tag>> = combine(
        dao.observe().onStart { runCatching { ensureDefaults() } },
        dao.observeCounts(),
    ) { rows, counts ->
        val byId = counts.associate { it.tagId to it.count }
        rows.map { Tag(it.id, it.name, it.color, byId[it.id] ?: 0) }
    }

    /** Path → palette slots of its tags (browser pastilles). */
    val slotsByPath: Flow<Map<String, List<Int>>> = combine(dao.observe(), dao.observeAssignments()) { rows, links ->
        val slot = rows.associate { it.id to it.color }
        val out = HashMap<String, MutableList<Int>>()
        links.forEach { l -> slot[l.tagId]?.let { out.getOrPut(l.path) { ArrayList(2) } += it } }
        out.mapValues { (_, v) -> v.sorted() }
    }

    fun pathsOf(tagId: Long): Flow<List<String>> = dao.observePaths(tagId)

    /** Tag ids currently carried by each of [paths]. */
    suspend fun tagsOf(paths: List<String>): Map<String, Set<Long>> =
        paths.chunked(500).flatMap { dao.forPaths(it) }.groupBy({ it.path }, { it.tagId }).mapValues { it.value.toSet() }

    suspend fun assign(tagId: Long, paths: List<String>) {
        ensureDefaults()
        val now = System.currentTimeMillis()
        dao.assign(paths.distinct().map { FileTagEntity(it, tagId, now) })
    }

    suspend fun unassign(tagId: Long, paths: List<String>) = paths.chunked(500).forEach { dao.unassign(tagId, it) }

    suspend fun rename(tagId: Long, name: String) = dao.rename(tagId, name.trim().take(MAX_NAME))

    /** Keeps tags attached after an in-app move/rename (file, or folder and everything below it). */
    suspend fun onMoved(oldPath: String, newPath: String) {
        if (oldPath == newPath) return
        dao.movePrefix(oldPath, newPath, SearchSqlBuilder.escapeLike(oldPath.trimEnd('/')) + "/%")
    }

    companion object {
        const val MAX_NAME = 24
    }
}

/** Default names/slots. Pure. */
object TagPalette {
    const val COUNT = 7
    private val NAMES = intArrayOf(
        R.string.tag_red, R.string.tag_orange, R.string.tag_yellow, R.string.tag_green,
        R.string.tag_blue, R.string.tag_purple, R.string.tag_grey,
    )
    /** Base hues of slots 0..5 (slot 6 is neutral). */
    val HUES = floatArrayOf(4f, 28f, 48f, 135f, 212f, 278f)

    fun defaultName(slot: Int): String = NAMES.getOrNull(slot)?.let { Strings.get(it) } ?: Strings.get(R.string.tag_default_numbered, slot + 1)

    fun defaults(): List<TagEntity> = (0 until COUNT).map { TagEntity(id = it + 1L, name = "", color = it, position = it) }

    /** Moves [hue] [amount] (0..1) of the way towards [towards] along the shortest arc. */
    fun harmonizedHue(hue: Float, towards: Float, amount: Float = 0.15f): Float {
        var diff = (towards - hue) % 360f
        if (diff > 180f) diff -= 360f
        if (diff < -180f) diff += 360f
        return ((hue + diff * amount) % 360f + 360f) % 360f
    }
}
