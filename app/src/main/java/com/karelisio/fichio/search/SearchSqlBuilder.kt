package com.karelisio.fichio.search

import androidx.compose.runtime.Immutable
import com.karelisio.fichio.core.model.FileType
import java.text.Normalizer

/** Everything the user can ask the search screen for. Blank text + no filter = no query. */
@Immutable
data class SearchQuery(
    val text: String = "",
    /** Restrict to this folder (recursive), null = everywhere. */
    val scopePath: String? = null,
    val types: Set<FileType> = emptySet(),
    val minSize: Long? = null,
    val maxSize: Long? = null,
    val modifiedAfter: Long? = null,
    val modifiedBefore: Long? = null,
    /** Lower-case extensions without dot. */
    val extensions: Set<String> = emptySet(),
    val includeFolders: Boolean = true,
    /** Colour tag ids: keep items carrying at least one of them. */
    val tagIds: Set<Long> = emptySet(),
    /** Also match the indexed content of text files / PDFs ("Rechercher aussi dans le contenu"). */
    val inContent: Boolean = false,
) {
    val hasFilters: Boolean
        get() = types.isNotEmpty() || minSize != null || maxSize != null || modifiedAfter != null ||
            modifiedBefore != null || extensions.isNotEmpty() || !includeFolders || tagIds.isNotEmpty()

    /** True when there is something to search for. */
    val isActive: Boolean get() = text.isNotBlank() || hasFilters
}

/** SQL + bound arguments, ready for a SimpleSQLiteQuery. */
data class BuiltQuery(val sql: String, val args: List<Any>) {
    val argsArray: Array<Any> get() = args.toTypedArray()
}

/**
 * Pure builder for the index search SQL. Every user value is bound as an argument; the FTS
 * expression is itself a bound string made of quoted prefix terms, so no user input ever
 * reaches the SQL text.
 *
 * Strategy:
 *  - text tokens (letters/digits) → `file_index_fts MATCH '"tok1*" "tok2*"'` (implicit AND);
 *  - text containing separators (`IMG_2024.jpg`) → FTS on its tokens + `LIKE '%text%'` to keep
 *    the exact substring semantics;
 *  - 1-2 characters or no indexable token → `LIKE '%text%'` only.
 */
object SearchSqlBuilder {
    const val LIMIT = 500
    private const val MAX_TOKENS = 8
    private const val COLUMNS = "f.path, f.name, f.parent, f.extension, f.type, f.isDirectory, f.size, f.modified"

    /** Splits like the unicode61 tokenizer: runs of letters/digits, lower-cased, deduplicated. */
    fun tokenize(text: String): List<String> {
        val out = LinkedHashSet<String>()
        val sb = StringBuilder()
        fun flush() {
            if (sb.isNotEmpty()) { out += sb.toString().lowercase(); sb.setLength(0) }
        }
        for (c in text) {
            if (c.isLetterOrDigit()) sb.append(c) else flush()
        }
        flush()
        return out.take(MAX_TOKENS)
    }

    /** FTS4 MATCH expression: each token quoted (neutralises AND/OR/NEAR/-) with a prefix star. */
    fun ftsExpression(tokens: List<String>): String =
        tokens.joinToString(" ") { "\"" + it.replace("\"", "\"\"") + "*\"" }

    /** Escapes LIKE wildcards; use with `ESCAPE '\'`. */
    fun escapeLike(s: String): String = buildString(s.length + 4) {
        for (c in s) {
            if (c == '\\' || c == '%' || c == '_') append('\\')
            append(c)
        }
    }

    /** Whether the text has characters that the tokenizer drops (besides plain spaces). */
    fun hasSeparators(text: String): Boolean = text.trim().any { !it.isLetterOrDigit() && !it.isWhitespace() }

    enum class TextMode { NONE, FTS, FTS_AND_LIKE, LIKE }

    fun textMode(text: String): TextMode {
        val t = text.trim()
        if (t.isEmpty()) return TextMode.NONE
        val tokens = tokenize(t)
        return when {
            t.length <= 2 || tokens.isEmpty() -> TextMode.LIKE
            hasSeparators(t) -> TextMode.FTS_AND_LIKE
            else -> TextMode.FTS
        }
    }

    fun build(q: SearchQuery, limit: Int = LIMIT): BuiltQuery {
        val text = q.text.trim()
        val mode = textMode(text)
        val args = ArrayList<Any>()
        val where = ArrayList<String>()
        val sql = StringBuilder("SELECT ").append(COLUMNS).append(" FROM file_index f")

        if (mode == TextMode.FTS || mode == TextMode.FTS_AND_LIKE) {
            sql.append(" JOIN file_index_fts ON file_index_fts.rowid = f.rowid")
            where += "file_index_fts MATCH ?"
            args += ftsExpression(tokenize(text))
        }
        if (mode == TextMode.LIKE || mode == TextMode.FTS_AND_LIKE) {
            where += "f.name LIKE ? ESCAPE '\\'"
            args += "%" + escapeLike(text) + "%"
        }
        filters(q, where, args)
        if (where.isNotEmpty()) sql.append(" WHERE ").append(where.joinToString(" AND "))

        // Relevance: names starting with the query first, then most recent.
        if (text.isNotEmpty()) {
            sql.append(" ORDER BY CASE WHEN f.name LIKE ? ESCAPE '\\' THEN 0 ELSE 1 END, f.modified DESC")
            args += escapeLike(text) + "%"
        } else {
            sql.append(" ORDER BY f.modified DESC")
        }
        sql.append(" LIMIT ").append(limit.coerceIn(1, 5000))
        return BuiltQuery(sql.toString(), args)
    }

    /** Every non-text filter of [q], as `f.` conditions + bound args (shared by name and content SQL). */
    private fun filters(q: SearchQuery, where: MutableList<String>, args: MutableList<Any>) {
        q.scopePath?.trimEnd('/')?.takeIf { it.isNotEmpty() }?.let { scope ->
            where += "f.path LIKE ? ESCAPE '\\'"
            args += escapeLike(scope) + "/%"
        }
        if (q.types.isNotEmpty()) {
            val names = q.types.map { it.name }.sorted()
            where += "f.type IN (" + names.joinToString(",") { "?" } + ")"
            args.addAll(names)
        }
        val includeFolders = q.includeFolders || FileType.FOLDER in q.types
        if (!includeFolders) where += "f.isDirectory = 0"
        q.minSize?.let { where += "f.size >= ?"; args += it }
        q.maxSize?.let { where += "f.size <= ?"; args += it }
        q.modifiedAfter?.let { where += "f.modified >= ?"; args += it }
        q.modifiedBefore?.let { where += "f.modified <= ?"; args += it }
        if (q.extensions.isNotEmpty()) {
            val exts = q.extensions.map { it.trim().trimStart('.').lowercase() }.filter { it.isNotEmpty() }.distinct().sorted()
            if (exts.isNotEmpty()) {
                where += "f.extension IN (" + exts.joinToString(",") { "?" } + ")"
                args.addAll(exts)
            }
        }
        if (q.tagIds.isNotEmpty()) {
            val ids = q.tagIds.sorted()
            where += "f.path IN (SELECT path FROM file_tags WHERE tagId IN (" + ids.joinToString(",") { "?" } + "))"
            args.addAll(ids)
        }
    }

    /** Snippet markers around matched terms (control characters never present in indexed text). */
    const val MARK_START = "\u0002"
    const val MARK_END = "\u0003"
    private const val SNIPPET_TOKENS = 14

    /**
     * Content search: files whose indexed text matches every token of the query (prefix match),
     * with an FTS4 snippet around the hit. Null when the text has no indexable token.
     */
    fun buildContent(q: SearchQuery, limit: Int = LIMIT): BuiltQuery? {
        val tokens = tokenize(q.text.trim())
        if (tokens.isEmpty() || q.text.trim().length < 2) return null
        val args = ArrayList<Any>()
        val where = ArrayList<String>()
        val sql = StringBuilder("SELECT ").append(COLUMNS)
            .append(", snippet(content_fts, ?, ?, '…', 1, ").append(SNIPPET_TOKENS).append(") AS snippet")
            .append(" FROM content_fts JOIN content_meta m ON m.id = content_fts.rowid JOIN file_index f ON f.path = m.path")
        args += MARK_START
        args += MARK_END
        where += "content_fts MATCH ?"
        args += ftsExpression(tokens)
        where += "f.isDirectory = 0"
        filters(q, where, args)
        sql.append(" WHERE ").append(where.joinToString(" AND "))
        sql.append(" ORDER BY f.modified DESC LIMIT ").append(limit.coerceIn(1, 5000))
        return BuiltQuery(sql.toString(), args)
    }

    /** Accent- and case-insensitive form used by the direct (non indexed) search. */
    fun normalize(s: String): String {
        val lower = s.lowercase()
        if (lower.all { it.code < 128 }) return lower
        return Normalizer.normalize(lower, Normalizer.Form.NFD).replace(DIACRITICS, "")
    }

    private val DIACRITICS = Regex("\\p{Mn}+")

    /** In-memory equivalent of the SQL filters, for the live folder walk. */
    fun matches(
        q: SearchQuery,
        name: String,
        isDirectory: Boolean,
        type: FileType,
        extension: String,
        size: Long,
        modified: Long,
        normalizedText: String = normalize(q.text.trim()),
    ): Boolean {
        if (normalizedText.isNotEmpty() && !normalize(name).contains(normalizedText)) return false
        if (q.types.isNotEmpty() && type !in q.types) return false
        val includeFolders = q.includeFolders || FileType.FOLDER in q.types
        if (!includeFolders && isDirectory) return false
        if (q.minSize != null && size < q.minSize) return false
        if (q.maxSize != null && size > q.maxSize) return false
        if (q.modifiedAfter != null && modified < q.modifiedAfter) return false
        if (q.modifiedBefore != null && modified > q.modifiedBefore) return false
        if (q.extensions.isNotEmpty() && extension !in q.extensions) return false
        return true
    }
}
