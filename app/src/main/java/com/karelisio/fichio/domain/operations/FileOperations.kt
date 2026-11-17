package com.karelisio.fichio.domain.operations

import com.karelisio.fichio.domain.file.extensionOf
import java.io.File

/**
 * How to resolve a name collision when moving/copying a file onto an existing
 * destination.
 */
enum class ConflictAction { REPLACE, SKIP, KEEP_BOTH, ASK }

/**
 * Produces a non-conflicting sibling path by inserting " (n)" before the
 * extension ("keep both" behaviour), e.g. "a.txt" -> "a (1).txt".
 */
fun uniquePath(path: String): String {
    val file = File(path)
    val parent = file.parent ?: return path
    val name = file.name
    val dot = name.lastIndexOf('.')
    val stem = if (dot > 0) name.substring(0, dot) else name
    val ext = if (dot > 0) name.substring(dot) else ""
    var i = 1
    while (true) {
        val candidate = File(parent, "$stem ($i)$ext")
        if (!candidate.exists()) return candidate.absolutePath
        i++
    }
}

/** Case transformation applied to a batch rename. */
enum class CaseTransform { NONE, UPPER, LOWER, TITLE, SENTENCE }

/**
 * Immutable specification for a batch rename. Numbering appends "_01", "_02"…
 * to the stem (before the extension) starting at [startNumber], zero-padded to
 * [padding] digits.
 */
data class BatchRenameSpec(
    val prefix: String = "",
    val suffix: String = "",
    val numbering: Boolean = false,
    val startNumber: Int = 1,
    val padding: Int = 2,
    val find: String = "",
    val replace: String = "",
    val case: CaseTransform = CaseTransform.NONE
)

/** A single previewed or applied rename. */
data class RenamePlanItem(
    val original: String,
    val renamed: String,
    val isDirectory: Boolean
)

/** Pure batch-rename logic with a preview step. */
object BatchRenamer {

    fun preview(names: List<Pair<String, Boolean>>, spec: BatchRenameSpec): List<RenamePlanItem> {
        val used = HashSet<String>()
        return names.mapIndexed { index, (name, isDirectory) ->
            val (stem, ext) = split(name, isDirectory)
            var transformed = stem
            if (spec.find.isNotEmpty()) transformed = transformed.replace(spec.find, spec.replace)
            transformed = applyCase(transformed, spec.case)
            val number = if (spec.numbering) "_" + pad(spec.startNumber + index, spec.padding) else ""
            val base = spec.prefix + transformed + number + spec.suffix + ext
            val unique = uniquify(base, used)
            RenamePlanItem(original = name, renamed = unique, isDirectory = isDirectory)
        }
    }

    /** True when two specs would yield identical output for the same input. */
    fun isIdentity(names: List<Pair<String, Boolean>>, spec: BatchRenameSpec): Boolean =
        preview(names, spec).all { it.original == it.renamed }

    private fun split(name: String, isDirectory: Boolean): Pair<String, String> {
        if (isDirectory) return name to ""
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(0, dot) to name.substring(dot) else name to ""
    }

    private fun applyCase(s: String, case: CaseTransform): String = when (case) {
        CaseTransform.NONE -> s
        CaseTransform.UPPER -> s.uppercase()
        CaseTransform.LOWER -> s.lowercase()
        CaseTransform.TITLE -> s.split(' ').joinToString(" ") { w ->
            w.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
        CaseTransform.SENTENCE -> s.lowercase().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }

    private fun pad(value: Int, width: Int): String =
        value.toString().padStart(width.coerceAtLeast(1), '0')

    private fun uniquify(base: String, used: MutableSet<String>): String {
        if (used.add(base)) return base
        val dot = base.lastIndexOf('.')
        val stem = if (dot > 0) base.substring(0, dot) else base
        val ext = if (dot > 0) base.substring(dot) else ""
        var i = 1
        while (true) {
            val candidate = "$stem ($i)$ext"
            if (used.add(candidate)) return candidate
            i++
        }
    }
}

/**
 * Groups file (path, size) pairs by exact size. Only files with a strictly
 * positive size are considered, and only groups of 2+ are kept. The second
 * phase (byte-hash confirmation) lives in the storage analyzer.
 */
object DuplicateDetector {
    fun groupBySize(files: List<Pair<String, Long>>): Map<Long, List<String>> =
        files.asSequence()
            .filter { it.second > 0L }
            .groupBy({ it.second }, { it.first })
            .filterValues { it.size > 1 }
}

/**
 * One-time access token for the local transfer server. The token is generated
 * on server start, shown as a QR/string, and compared in constant time to
 * avoid timing side-channels.
 */
object TransferToken {
    private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    fun generate(length: Int = 8, rng: kotlin.random.Random = kotlin.random.Random.Default): String =
        buildString(length.coerceAtLeast(4)) {
            repeat(length.coerceAtLeast(4)) { append(ALPHABET[rng.nextInt(ALPHABET.length)]) }
        }

    fun constantTimeEquals(expected: String, actual: String): Boolean {
        if (expected.length != actual.length) return false
        var result = 0
        for (i in expected.indices) result = result or (expected[i].code xor actual[i].code)
        return result == 0
    }
}

/** Extension of a path without the leading dot (re-exported for callers). */
fun extensionOfPath(name: String): String = extensionOf(name)
