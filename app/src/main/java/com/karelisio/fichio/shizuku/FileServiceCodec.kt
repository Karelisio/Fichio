package com.karelisio.fichio.shizuku

/**
 * Text encoding of directory entries exchanged with [FichioUserService] over Binder.
 * Pure Kotlin (no Android types) so it is unit-testable.
 *
 * Listing page:
 * ```
 * total\tnextOffset
 * name\tisDir(0|1)\tsize\tmtime
 * ...
 * ```
 * Tabs, newlines and backslashes inside names are escaped. A page starting with `!` is an
 * error message.
 */
object FileServiceCodec {
    /** Max characters per page: Binder strings are UTF-16, so this stays well under 512 KB. */
    const val MAX_PAGE_CHARS = 200_000
    const val DEFAULT_PAGE_SIZE = 1_000

    data class Entry(val name: String, val isDirectory: Boolean, val size: Long, val lastModified: Long)

    data class Page(val total: Int, val nextOffset: Int, val entries: List<Entry>)

    fun escape(s: String): String {
        if (s.none { it == '\\' || it == '\t' || it == '\n' || it == '\r' }) return s
        val sb = StringBuilder(s.length + 8)
        for (c in s) when (c) {
            '\\' -> sb.append("\\\\")
            '\t' -> sb.append("\\t")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            else -> sb.append(c)
        }
        return sb.toString()
    }

    fun unescape(s: String): String {
        if (!s.contains('\\')) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    't' -> sb.append('\t')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    else -> sb.append(s[i + 1])
                }
                i += 2
            } else {
                sb.append(c); i++
            }
        }
        return sb.toString()
    }

    fun encodeEntry(e: Entry): String =
        "${escape(e.name)}\t${if (e.isDirectory) 1 else 0}\t${e.size}\t${e.lastModified}"

    fun decodeEntry(line: String): Entry? {
        val parts = line.split('\t')
        if (parts.size < 4) return null
        return Entry(
            name = unescape(parts[0]),
            isDirectory = parts[1] == "1",
            size = parts[2].toLongOrNull() ?: 0L,
            lastModified = parts[3].toLongOrNull() ?: 0L,
        )
    }

    /**
     * Encodes entries [offset] until [limit] entries or [MAX_PAGE_CHARS] are reached.
     * [entryAt] lazily produces the entry for an index (null = vanished, skipped).
     */
    fun encodePage(total: Int, offset: Int, limit: Int, entryAt: (Int) -> Entry?): String {
        val body = StringBuilder()
        var i = offset.coerceAtLeast(0)
        var count = 0
        val max = limit.coerceIn(1, 10_000)
        while (i < total && count < max && body.length < MAX_PAGE_CHARS) {
            entryAt(i)?.let { body.append('\n').append(encodeEntry(it)) }
            i++; count++
        }
        return "$total\t$i$body"
    }

    fun decodePage(text: String): Page {
        if (text.startsWith("!")) throw java.io.IOException(text.substring(1))
        val lines = text.split('\n')
        val header = lines.first().split('\t')
        val total = header.getOrNull(0)?.toIntOrNull() ?: 0
        val next = header.getOrNull(1)?.toIntOrNull() ?: total
        val entries = ArrayList<Entry>(lines.size - 1)
        for (k in 1 until lines.size) decodeEntry(lines[k])?.let { entries += it }
        return Page(total, next, entries)
    }

    fun error(message: String?): String = "!" + (message ?: "Erreur").replace('\n', ' ')
}
