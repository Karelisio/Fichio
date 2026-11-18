package com.karelisio.fichio.search

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.sqlite.db.SimpleSQLiteQuery
import com.karelisio.fichio.container
import com.karelisio.fichio.core.fs.FileSystems
import com.karelisio.fichio.core.fs.StoragePaths
import com.karelisio.fichio.core.model.FileEntry
import com.karelisio.fichio.core.model.FileType
import com.karelisio.fichio.data.db.ContentHit
import com.karelisio.fichio.data.db.FichioDatabase
import com.karelisio.fichio.data.db.IndexedFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** One emission of a search: results so far, and whether a live folder walk is still running. */
@Immutable
data class SearchOutcome(
    val items: List<IndexedFile>,
    val scanning: Boolean,
    val fromIndex: Boolean,
    /** Path → FTS snippet (with [SearchSqlBuilder.MARK_START]/[SearchSqlBuilder.MARK_END]) for content matches. */
    val snippets: Map<String, String> = emptyMap(),
)

fun ContentHit.toIndexed(): IndexedFile = IndexedFile(path, name, parent, extension, type, isDirectory, size, modified)

/**
 * Name matches first (in their relevance order), then files found only through their content
 * (most recent first); snippets for every content hit. Pure, unit-tested.
 */
fun mergeContentResults(names: List<IndexedFile>, hits: List<ContentHit>, limit: Int = SearchSqlBuilder.LIMIT): Pair<List<IndexedFile>, Map<String, String>> {
    val snippets = HashMap<String, String>()
    hits.forEach { h -> h.snippet?.takeIf { it.isNotBlank() }?.let { snippets[h.path] = it } }
    val seen = names.mapTo(HashSet()) { it.path }
    val merged = ArrayList<IndexedFile>(minOf(limit, names.size + hits.size))
    names.forEach { if (merged.size < limit) merged += it }
    hits.forEach { h -> if (merged.size < limit && seen.add(h.path)) merged += h.toIndexed() }
    return merged to snippets
}

fun IndexedFile.toEntry(): FileEntry = FileEntry(path, name, isDirectory, size, modified)

fun FileEntry.toIndexed(): IndexedFile =
    IndexedFile(path, name, parentPath, extension, type.name, isDirectory, size, lastModified)

val IndexedFile.fileType: FileType
    get() = runCatching { FileType.valueOf(type) }.getOrDefault(FileType.OTHER)

/**
 * Search over the Room FTS index, or, when the index is empty/disabled (or the scope is not
 * indexed, e.g. Android/data), a live recursive walk of the scope that emits as it goes.
 */
class SearchRepository(
    private val db: FichioDatabase,
    private val fs: FileSystems,
    private val indexEnabled: suspend () -> Boolean,
    private val showHidden: suspend () -> Boolean,
) {
    constructor(context: Context) : this(
        context.container.database,
        context.container.fileSystems,
        { context.container.settings.current().searchIndexEnabled },
        { context.container.settings.current().showHidden },
    )

    /** Index-backed results, re-emitted whenever the index changes. */
    fun search(query: SearchQuery): Flow<List<IndexedFile>> {
        if (!query.isActive) return flowOf(emptyList())
        val built = SearchSqlBuilder.build(query)
        return db.fileIndex().observeSearch(SimpleSQLiteQuery(built.sql, built.argsArray))
    }

    /** Content-index matches (empty when the text has no indexable token). */
    fun contentHits(query: SearchQuery): Flow<List<ContentHit>> {
        val built = SearchSqlBuilder.buildContent(query) ?: return flowOf(emptyList())
        return db.content().observeHits(SimpleSQLiteQuery(built.sql, built.argsArray))
    }

    /** Picks the index or the live walk for this query. */
    fun results(query: SearchQuery): Flow<SearchOutcome> = flow {
        if (!query.isActive) {
            emit(SearchOutcome(emptyList(), scanning = false, fromIndex = true)); return@flow
        }
        val scope = query.scopePath
        val useIndex = indexEnabled() && db.fileIndex().count() > 0 &&
            (scope == null || !StoragePaths.isRestricted(scope) && !StoragePaths.isInsideFichioDir(scope))
        if (useIndex && query.inContent && SearchSqlBuilder.buildContent(query) != null) {
            combine(search(query), contentHits(query)) { names, hits -> mergeContentResults(names, hits) }
                .collect { (items, snippets) -> emit(SearchOutcome(items, scanning = false, fromIndex = true, snippets = snippets)) }
        } else if (useIndex) {
            search(query).collect { emit(SearchOutcome(it, scanning = false, fromIndex = true)) }
        } else {
            walk(query).collect { emit(it) }
        }
    }.flowOn(Dispatchers.IO)

    /** Live recursive walk, emitting partial results about every 150 ms. */
    fun walk(query: SearchQuery): Flow<SearchOutcome> {
        if (!query.isActive) return emptyFlow()
        return flow {
            val hidden = showHidden()
            val tagged: Set<String>? = if (query.tagIds.isEmpty()) null
            else runCatching { db.tags().pathsWith(query.tagIds.toList()).toHashSet() }.getOrDefault(hashSetOf())
            val root = query.scopePath ?: StoragePaths.primary
            val needle = SearchSqlBuilder.normalize(query.text.trim())
            val found = ArrayList<IndexedFile>()
            var lastEmit = System.nanoTime()
            val stack = ArrayDeque<String>().apply { addLast(root) }
            emit(SearchOutcome(emptyList(), scanning = true, fromIndex = false))
            while (stack.isNotEmpty() && found.size < SearchSqlBuilder.LIMIT) {
                currentCoroutineContext().ensureActive()
                val dir = stack.removeLast()
                val backend = fs.backendOrNull(dir) ?: continue
                val children = runCatching { backend.list(dir) }.getOrNull() ?: continue
                for (c in children) {
                    if (!hidden && c.isHidden) continue
                    if (c.isDirectory && !StoragePaths.isInsideFichioDir(c.path)) stack.addLast(c.path)
                    if ((tagged == null || c.path in tagged) &&
                        SearchSqlBuilder.matches(query, c.name, c.isDirectory, c.type, c.extension, c.size, c.lastModified, needle)
                    ) {
                        found += c.toIndexed()
                        if (found.size >= SearchSqlBuilder.LIMIT) break
                    }
                }
                val now = System.nanoTime()
                if (now - lastEmit > 150_000_000L) {
                    lastEmit = now
                    emit(SearchOutcome(rank(found, needle), scanning = true, fromIndex = false))
                }
            }
            emit(SearchOutcome(rank(found, needle), scanning = false, fromIndex = false))
        }
    }

    private fun rank(items: List<IndexedFile>, prefix: String): List<IndexedFile> =
        items.sortedWith(
            compareBy<IndexedFile> { if (prefix.isNotEmpty() && SearchSqlBuilder.normalize(it.name).startsWith(prefix)) 0 else 1 }
                .thenByDescending { it.modified },
        )

    /** Number of rows in the index, for the "index status" hint. */
    fun indexCount(): Flow<Int> = db.fileIndex().observeCount()

    /** Distinct extensions present in the index (for the extension filter), most common first. */
    suspend fun topExtensions(limit: Int = 24): List<String> = runCatching {
        db.fileIndex().search(
            SimpleSQLiteQuery(
                "SELECT '' AS path, extension AS name, '' AS parent, extension, '' AS type, 0 AS isDirectory, COUNT(*) AS size, 0 AS modified " +
                    "FROM file_index WHERE isDirectory = 0 AND extension != '' GROUP BY extension ORDER BY COUNT(*) DESC LIMIT ?",
                arrayOf(limit),
            ),
        ).map { it.extension }
    }.getOrDefault(emptyList())
}

/** Small local history of searches (private SharedPreferences, newest first). */
class SearchHistory(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("fichio_search_history", Context.MODE_PRIVATE)

    fun load(): List<String> =
        prefs.getString(KEY, null)?.split(SEP)?.filter { it.isNotBlank() }.orEmpty()

    fun add(term: String): List<String> {
        val t = term.trim()
        if (t.length < 2) return load()
        val list = (listOf(t) + load().filterNot { it.equals(t, ignoreCase = true) }).take(MAX)
        prefs.edit().putString(KEY, list.joinToString(SEP)).apply()
        return list
    }

    fun remove(term: String): List<String> {
        val list = load().filterNot { it == term }
        prefs.edit().putString(KEY, list.joinToString(SEP)).apply()
        return list
    }

    fun clear() = prefs.edit().remove(KEY).apply()

    private companion object {
        const val KEY = "terms"
        const val SEP = "\u001F"
        const val MAX = 12
    }
}
