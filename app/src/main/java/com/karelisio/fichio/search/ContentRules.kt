package com.karelisio.fichio.search

import com.karelisio.fichio.core.model.FileType

/**
 * Which files the content index reads, how much of them, and how their raw bytes become
 * indexable text. Pure (no Android), so it is unit-tested.
 */
object ContentRules {
    /** Only the beginning of each text file is read and indexed. */
    const val MAX_TEXT_BYTES = 512 * 1024
    /** Indexed text kept per PDF (characters). */
    const val MAX_PDF_CHARS = 512 * 1024
    /** PDFs above this size are skipped (rendering their text would be too slow). */
    const val MAX_PDF_BYTES = 64L * 1024 * 1024
    /** Pages read per PDF. */
    const val MAX_PDF_PAGES = 60

    /** Spreadsheet formats that are plain text. */
    private val TEXT_SPREADSHEETS = setOf("csv", "tsv")
    private val HTML = setOf("html", "htm", "xhtml")

    /** Name-index types whose files are read as text (txt/md/log…, code incl. json/xml/html). */
    val TEXT_TYPES = listOf(FileType.TEXT, FileType.CODE)

    fun isText(type: FileType, extension: String): Boolean =
        type in TEXT_TYPES || extension.lowercase() in TEXT_SPREADSHEETS

    fun isPdf(type: FileType): Boolean = type == FileType.PDF

    fun isIndexable(type: FileType, extension: String, size: Long, pdfSupported: Boolean): Boolean =
        isText(type, extension) || (pdfSupported && isPdf(type) && size <= MAX_PDF_BYTES)

    /**
     * Files of the name index whose content was never read, or changed since (modified/size),
     * most recent first. [dirs] restricts to these parents (on-the-fly re-index after in-app edits).
     */
    fun pendingQuery(limit: Int, pdfSupported: Boolean, dirs: Collection<String>? = null): BuiltQuery {
        val args = ArrayList<Any>()
        val types = TEXT_TYPES.map { it.name } + if (pdfSupported) listOf(FileType.PDF.name) else emptyList()
        val sql = StringBuilder(
            "SELECT f.path, f.name, f.parent, f.extension, f.type, f.isDirectory, f.size, f.modified " +
                "FROM file_index f LEFT JOIN content_meta m ON m.path = f.path WHERE f.isDirectory = 0",
        )
        sql.append(" AND (f.type IN (").append(types.joinToString(",") { "?" }).append(")")
        args.addAll(types)
        sql.append(" OR f.extension IN (").append(TEXT_SPREADSHEETS.joinToString(",") { "?" }).append("))")
        args.addAll(TEXT_SPREADSHEETS.sorted())
        if (pdfSupported) {
            sql.append(" AND (f.type != ? OR f.size <= ?)")
            args += FileType.PDF.name
            args += MAX_PDF_BYTES
        }
        sql.append(" AND (m.id IS NULL OR m.modified != f.modified OR m.size != f.size)")
        if (dirs != null) {
            val list = dirs.distinct().sorted()
            if (list.isEmpty()) sql.append(" AND 0") else {
                sql.append(" AND f.parent IN (").append(list.joinToString(",") { "?" }).append(")")
                args.addAll(list)
            }
        }
        sql.append(" ORDER BY f.modified DESC LIMIT ").append(limit.coerceIn(1, 1000))
        return BuiltQuery(sql.toString(), args)
    }

    /** True when the sample contains NUL bytes: a binary file wearing a text extension. */
    fun looksBinary(bytes: ByteArray, length: Int = bytes.size): Boolean {
        val n = minOf(length, 8192)
        for (i in 0 until n) if (bytes[i] == 0.toByte()) return true
        return false
    }

    /**
     * Raw bytes (the first [MAX_TEXT_BYTES] of a file) → indexable text: UTF-8 (BOM dropped,
     * malformed sequences replaced), markup removed for HTML, whitespace collapsed. Null when
     * nothing worth indexing remains.
     */
    fun textOf(bytes: ByteArray, length: Int, extension: String): String? {
        if (length <= 0 || looksBinary(bytes, length)) return null
        var start = 0
        if (length >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) start = 3
        var end = length
        // Do not cut a multi-byte UTF-8 sequence in half at the read limit.
        if (end - start >= MAX_TEXT_BYTES - 4) {
            while (end > start && (bytes[end - 1].toInt() and 0xC0) == 0x80) end--
            if (end > start && (bytes[end - 1].toInt() and 0x80) != 0) end--
        }
        var text = String(bytes, start, end - start, Charsets.UTF_8)
        if (extension.lowercase() in HTML) text = stripHtml(text)
        return clean(text)
    }

    /** Drops scripts, styles, comments and tags, and decodes the most common entities. */
    fun stripHtml(html: String): String = html
        .replace(BLOCKS, " ")
        .replace(TAG, " ")
        .replace("&nbsp;", " ")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&amp;", "&")

    /** Collapses whitespace runs; null when blank. */
    fun clean(text: String, maxChars: Int = MAX_PDF_CHARS): String? {
        val sb = StringBuilder(minOf(text.length, maxChars))
        var space = false
        for (c in text) {
            if (sb.length >= maxChars) break
            if (c.isWhitespace() || c.isISOControl()) {
                space = true
            } else {
                if (space && sb.isNotEmpty()) sb.append(' ')
                space = false
                sb.append(c)
            }
        }
        return sb.toString().takeIf { it.isNotBlank() }
    }

    private val BLOCKS = Regex("(?is)<(script|style)\\b.*?</\\1\\s*>|<!--.*?-->")
    private val TAG = Regex("(?s)<[^>]{0,2000}>")
}

/** Parses an FTS snippet into (text, highlighted) runs. Pure, unit-tested. */
object Snippets {
    fun segments(raw: String): List<Pair<String, Boolean>> {
        val out = ArrayList<Pair<String, Boolean>>()
        val sb = StringBuilder()
        var marked = false
        fun flush() {
            if (sb.isNotEmpty()) { out += sb.toString() to marked; sb.setLength(0) }
        }
        for (c in raw) {
            when (c.toString()) {
                SearchSqlBuilder.MARK_START -> { flush(); marked = true }
                SearchSqlBuilder.MARK_END -> { flush(); marked = false }
                else -> sb.append(if (c == '\n' || c == '\r' || c == '\t') ' ' else c)
            }
        }
        flush()
        return out
    }
}
