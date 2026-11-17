package com.karelisio.fichio.transfer

import java.io.File

/**
 * Maps client paths (relative to the shared root, e.g. "/DCIM/Camera") to files and refuses
 * everything outside the root or inside a denied folder (Android/data, Android/obb, .Fichio).
 *
 * Defence in depth: `..` / NUL / backslash segments are rejected outright, then the path is
 * canonicalised (which resolves symlinks) and checked again, case-insensitively for the denied
 * folders because shared storage is case-insensitive on Android.
 */
class PathGuard(root: String, denied: Collection<String>) {
    val root: File = File(root).canonicalFile
    private val rootPath = this.root.path
    private val deniedPaths: List<String> = denied.map { canonical(File(it)).lowercase() }

    /** Resolves [clientPath] to a canonical file inside the root, or null when forbidden. */
    fun resolve(clientPath: String?): File? {
        val raw = (clientPath ?: "").trim()
        if (raw.length > 4096 || raw.any { it == '\u0000' || it == '\\' }) return null
        val segments = raw.split('/').filter { it.isNotEmpty() && it != "." }
        if (segments.any { it == ".." }) return null
        val joined = segments.fold(root) { f, s -> File(f, s) }
        val canon = runCatching { joined.canonicalFile }.getOrNull() ?: return null
        return canon.takeIf { isAllowed(it) }
    }

    /** Whether a canonical [file] is inside the root and outside every denied folder. */
    fun isAllowed(file: File): Boolean {
        val p = canonical(file)
        if (p != rootPath && !p.startsWith("$rootPath/")) return false
        val lower = p.lowercase()
        return deniedPaths.none { lower == it || lower.startsWith("$it/") }
    }

    /** Client-facing path ("/" for the root) of an allowed file. */
    fun relative(file: File): String {
        val p = file.path
        return if (p == rootPath) "/" else "/" + p.removePrefix("$rootPath/")
    }

    private fun canonical(f: File): String = runCatching { f.canonicalPath }.getOrDefault(f.absolutePath)

    companion object {
        private val reserved = setOf("", ".", "..")

        /**
         * Sanitises a file name sent by a browser: drops any directory part, control
         * characters and trailing dots/spaces. Returns null when nothing usable remains.
         */
        fun safeName(name: String?): String? {
            if (name == null) return null
            val base = name.substringAfterLast('/').substringAfterLast('\\')
            val cleaned = base.filter { it >= ' ' && it != '\u007f' }.trim().trimEnd('.', ' ')
            if (cleaned in reserved || cleaned.toByteArray().size > 255) return null
            return cleaned
        }

        /** "name.ext", then "name (1).ext", "name (2).ext"… until [exists] is false. */
        fun uniqueName(name: String, exists: (String) -> Boolean): String {
            if (!exists(name)) return name
            val dot = name.lastIndexOf('.')
            val (stem, ext) = if (dot > 0) name.substring(0, dot) to name.substring(dot) else name to ""
            var i = 1
            while (true) {
                val candidate = "$stem ($i)$ext"
                if (!exists(candidate)) return candidate
                i++
            }
        }
    }
}
