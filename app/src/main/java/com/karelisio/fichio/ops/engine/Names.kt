package com.karelisio.fichio.ops.engine

import com.karelisio.fichio.core.util.Strings
import com.karelisio.fichio.R

/** Pure helpers for file names: splitting, "keep both" numbering and validation. */
object Names {
    /** Compound extensions kept together when numbering ("backup (1).tar.gz"). */
    private val compoundExtensions = listOf(".tar.gz", ".tar.bz2", ".tar.xz", ".tar.zst", ".tar.lz", ".tar.lzma")

    private val numberSuffix = Regex(""" \((\d+)\)$""")

    /** Characters refused by FAT/exFAT (SD cards, USB keys) and confusing everywhere else. */
    const val FORBIDDEN_CHARS = "\\:*?\"<>|"

    private const val MAX_NAME_BYTES = 255

    /**
     * Splits [name] into base and extension (the extension keeps its leading dot). Folders and
     * dot-files without a second dot have no extension.
     */
    fun split(name: String, isDirectory: Boolean = false): Pair<String, String> {
        if (isDirectory) return name to ""
        val lower = name.lowercase()
        compoundExtensions.firstOrNull { lower.endsWith(it) && lower.length > it.length }?.let {
            return name.dropLast(it.length) to name.takeLast(it.length)
        }
        val dot = name.lastIndexOf('.')
        return if (dot <= 0 || dot == name.length - 1) name to "" else name.substring(0, dot) to name.substring(dot)
    }

    /** "fichier.txt", n = 2 → "fichier (2).txt". An existing " (k)" suffix is replaced, not stacked. */
    fun numbered(name: String, isDirectory: Boolean, n: Int): String {
        val (base, ext) = split(name, isDirectory)
        val clean = base.replace(numberSuffix, "")
        return "$clean ($n)$ext"
    }

    /** First "name (n).ext" (n ≥ 1) for which [exists] is false. */
    fun unique(name: String, isDirectory: Boolean, exists: (String) -> Boolean): String {
        var n = 1
        while (true) {
            val candidate = numbered(name, isDirectory, n)
            if (!exists(candidate)) return candidate
            n++
        }
    }

    /** Returns a French error message when [name] cannot be used as a file name, null when valid. */
    fun validate(name: String): String? {
        if (name.isBlank()) return Strings.get(R.string.ops_name_empty)
        if (name == "." || name == "..") return Strings.get(R.string.ops_name_reserved)
        if ('/' in name) return Strings.get(R.string.ops_name_slash)
        name.firstOrNull { it in FORBIDDEN_CHARS }?.let { return Strings.get(R.string.ops_name_forbidden_char, it) }
        if (name.any { it.code < 32 || it.code == 127 }) return Strings.get(R.string.ops_name_invisible_char)
        if (name.toByteArray(Charsets.UTF_8).size > MAX_NAME_BYTES) return Strings.get(R.string.ops_name_too_long)
        return null
    }
}

/** Path helpers that do not depend on Android (StoragePaths needs Environment for its roots). */
internal object PathUtil {
    fun join(dir: String, name: String) = if (dir.endsWith("/")) dir + name else "$dir/$name"
    fun parent(path: String): String = path.substringBeforeLast('/', "/").ifEmpty { "/" }
    fun name(path: String): String = path.substringAfterLast('/')
    /** True when [path] is [ancestor] itself or lies below it. */
    fun isSameOrInside(path: String, ancestor: String) = path == ancestor || path.startsWith("$ancestor/")
}
