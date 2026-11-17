package com.karelisio.fichio.preview

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Decoded text ready for the viewer: display rows (long lines are split) + line numbers. */
class TextDocument(
    /** Display rows; a source line longer than [TextLoader.MAX_ROW] spans several rows. */
    val rows: List<String>,
    /** Source line number of each row, or 0 for a continuation row. */
    val lineNumbers: IntArray,
    val lineCount: Int,
    val truncated: Boolean,
    val charset: Charset,
    val longestRow: Int,
)

/** Pure text loading helpers (unit-tested). */
object TextLoader {
    const val VIEW_LIMIT = 5L * 1024 * 1024
    const val EDIT_LIMIT = 2L * 1024 * 1024
    const val MAX_ROW = 2_000

    private val WINDOWS_1252: Charset = runCatching { Charset.forName("windows-1252") }.getOrDefault(Charsets.ISO_8859_1)

    /** Reads at most [limit] bytes; returns the bytes and whether the stream had more. */
    fun readBounded(input: InputStream, limit: Long = VIEW_LIMIT): Pair<ByteArray, Boolean> {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        input.use { s ->
            while (total < limit) {
                val want = minOf(buf.size.toLong(), limit - total).toInt()
                val n = s.read(buf, 0, want)
                if (n < 0) return out.toByteArray() to false
                out.write(buf, 0, n)
                total += n
            }
            return out.toByteArray() to (s.read() >= 0)
        }
    }

    /** Strict UTF-8 (BOM aware), falling back to Windows-1252 for legacy files. */
    fun decode(bytes: ByteArray, truncated: Boolean = false): Pair<String, Charset> {
        var start = 0
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) start = 3
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE) to Charsets.UTF_16LE
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE) to Charsets.UTF_16BE
        }
        // A truncated file may end in the middle of a multi-byte sequence: ignore the tail.
        var end = bytes.size
        if (truncated) {
            var k = end - 1
            var back = 0
            while (k >= start && back < 4 && (bytes[k].toInt() and 0xC0) == 0x80) { k--; back++ }
            if (k >= start && (bytes[k].toInt() and 0x80) != 0) end = k
        }
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes, start, end - start)).toString() to Charsets.UTF_8
        } catch (_: CharacterCodingException) {
            String(bytes, start, bytes.size - start, WINDOWS_1252) to WINDOWS_1252
        }
    }

    /** Heuristic: NUL bytes in the first 8 KiB mean binary content. */
    fun looksBinary(bytes: ByteArray): Boolean {
        val n = minOf(bytes.size, 8192)
        for (i in 0 until n) if (bytes[i] == 0.toByte()) return true
        return false
    }

    fun toDocument(text: String, truncated: Boolean, charset: Charset): TextDocument {
        val rows = ArrayList<String>()
        val numbers = ArrayList<Int>()
        var lineNo = 0
        var longest = 0
        var start = 0
        val n = text.length
        while (start <= n) {
            var end = text.indexOf('\n', start)
            if (end < 0) end = n
            var line = text.substring(start, end)
            if (line.endsWith('\r')) line = line.dropLast(1)
            lineNo++
            if (line.length <= MAX_ROW) {
                rows += line; numbers += lineNo
            } else {
                var k = 0
                while (k < line.length) {
                    rows += line.substring(k, minOf(line.length, k + MAX_ROW))
                    numbers += if (k == 0) lineNo else 0
                    k += MAX_ROW
                }
            }
            longest = maxOf(longest, minOf(line.length, MAX_ROW))
            if (end == n) break
            start = end + 1
        }
        return TextDocument(rows, numbers.toIntArray(), lineNo, truncated, charset, longest)
    }

    /** Every occurrence of [query] (case-insensitive) as (row, start) pairs, capped. */
    fun findAll(rows: List<String>, query: String, cap: Int = 10_000): List<Pair<Int, Int>> {
        if (query.isEmpty()) return emptyList()
        val out = ArrayList<Pair<Int, Int>>()
        for ((r, row) in rows.withIndex()) {
            var from = 0
            while (true) {
                val idx = row.indexOf(query, from, ignoreCase = true)
                if (idx < 0) break
                out += r to idx
                if (out.size >= cap) return out
                from = idx + query.length
            }
        }
        return out
    }
}
