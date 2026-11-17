package com.karelisio.fichio.archive

import androidx.compose.runtime.Immutable
import com.karelisio.fichio.core.model.FileEntry
import com.karelisio.fichio.core.model.FileType
import com.karelisio.fichio.core.model.FileTypes
import com.karelisio.fichio.ops.ConflictChoice
import com.karelisio.fichio.ops.TaskScope
import java.io.File
import java.io.IOException

/* Shared model of the archive module: formats, entries, errors, path safety and the folder tree. */

/** Archive formats Fichio can read. */
enum class ArchiveFormat(val label: String) {
    ZIP("ZIP"), SEVEN_Z("7z"), RAR("RAR"),
    TAR("TAR"), TAR_GZ("TAR.GZ"), TAR_BZ2("TAR.BZ2"), TAR_XZ("TAR.XZ"),
    GZ("GZ"), BZ2("BZ2"), XZ("XZ");

    val isTar get() = this == TAR || this == TAR_GZ || this == TAR_BZ2 || this == TAR_XZ
    val isSingleFile get() = this == GZ || this == BZ2 || this == XZ
    /** Whether the format may carry encrypted entries Fichio can decrypt. */
    val supportsPassword get() = this == ZIP || this == SEVEN_Z || this == RAR

    companion object {
        /** Format from the file name only (fast, no I/O). */
        fun fromName(name: String): ArchiveFormat? {
            val n = name.lowercase()
            return when {
                n.endsWith(".tar.gz") || n.endsWith(".tgz") -> TAR_GZ
                n.endsWith(".tar.bz2") || n.endsWith(".tbz2") || n.endsWith(".tbz") -> TAR_BZ2
                n.endsWith(".tar.xz") || n.endsWith(".txz") -> TAR_XZ
                n.endsWith(".tar") -> TAR
                n.endsWith(".zip") || n.endsWith(".jar") || n.endsWith(".apks") || n.endsWith(".xapk") ||
                    n.endsWith(".apkm") || n.endsWith(".cbz") || n.endsWith(".epub") -> ZIP
                n.endsWith(".7z") -> SEVEN_Z
                n.endsWith(".rar") || n.endsWith(".cbr") -> RAR
                n.endsWith(".gz") -> GZ
                n.endsWith(".bz2") -> BZ2
                n.endsWith(".xz") -> XZ
                else -> null
            }
        }

        /** Format from the magic bytes; used when the extension is missing or misleading. */
        fun sniff(file: File): ArchiveFormat? {
            val head = ByteArray(262 + 8)
            val read = runCatching { file.inputStream().use { it.readNBytesCompat(head) } }.getOrDefault(0)
            if (read < 4) return null
            fun at(offset: Int, vararg bytes: Int) =
                read >= offset + bytes.size && bytes.indices.all { head[offset + it] == bytes[it].toByte() }
            return when {
                at(0, 0x50, 0x4B, 0x03, 0x04) || at(0, 0x50, 0x4B, 0x05, 0x06) -> ZIP
                at(0, 0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C) -> SEVEN_Z
                at(0, 0x52, 0x61, 0x72, 0x21) -> RAR
                at(0, 0x1F, 0x8B) -> if (file.name.lowercase().endsWith(".tgz")) TAR_GZ else GZ
                at(0, 0x42, 0x5A, 0x68) -> BZ2
                at(0, 0xFD, 0x37, 0x7A, 0x58, 0x5A, 0x00) -> XZ
                at(257, 0x75, 0x73, 0x74, 0x61, 0x72) -> TAR
                else -> null
            }
        }

        /** Name first, magic bytes as a fallback (or to fix a wrong extension). */
        fun detect(file: File): ArchiveFormat? {
            val byName = fromName(file.name)
            val sniffed = sniff(file)
            return when {
                byName == null -> sniffed
                sniffed == null -> byName
                // A ".tar.gz" really is gzip: keep the more precise name-based answer.
                byName.isTar && (sniffed == GZ || sniffed == BZ2 || sniffed == XZ || sniffed == TAR) -> byName
                byName == sniffed -> byName
                // ".zip" that is actually a 7z / rar…: trust the content.
                else -> sniffed
            }
        }

        private fun java.io.InputStream.readNBytesCompat(buf: ByteArray): Int {
            var total = 0
            while (total < buf.size) {
                val n = read(buf, total, buf.size - total)
                if (n < 0) break
                total += n
            }
            return total
        }
    }
}

/** One entry of an archive, as shown in the archive browser. */
@Immutable
data class ArchiveItem(
    /** Normalised internal path: '/'-separated, no leading or trailing slash. Stable key. */
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    /** Uncompressed size, -1 when unknown. */
    val size: Long,
    /** Compressed size, -1 when unknown (7z solid blocks, tar…). */
    val compressedSize: Long = -1,
    val lastModified: Long = 0,
    val isEncrypted: Boolean = false,
    /** Folder that has no entry of its own in the archive but is implied by its children. */
    val isImplicit: Boolean = false,
    /** True when the stored name tries to escape the destination ("zip slip"): never extracted. */
    val isUnsafe: Boolean = false,
) {
    val parent: String get() = path.substringBeforeLast('/', "")
    val extension: String get() = if (isDirectory) "" else name.substringAfterLast('.', "").lowercase()
    val type: FileType get() = if (isDirectory) FileType.FOLDER else FileTypes.typeOf(extension)

    companion object {
        /** Builds an item from a raw stored name, normalising it and flagging unsafe paths. */
        fun fromRaw(
            rawName: String,
            isDirectory: Boolean,
            size: Long,
            compressedSize: Long = -1,
            lastModified: Long = 0,
            isEncrypted: Boolean = false,
        ): ArchiveItem? {
            val normalized = ArchivePaths.normalize(rawName)
            val path = normalized ?: ArchivePaths.sanitize(rawName)
            if (path.isEmpty()) return null
            return ArchiveItem(
                path = path,
                name = path.substringAfterLast('/'),
                isDirectory = isDirectory || rawName.endsWith("/") || rawName.endsWith("\\"),
                size = if (isDirectory) 0 else size,
                compressedSize = compressedSize,
                lastModified = lastModified.coerceAtLeast(0),
                isEncrypted = isEncrypted,
                isUnsafe = normalized == null || ArchivePaths.isAbsolute(rawName),
            )
        }
    }
}

/* ---------- Errors ---------- */

open class ArchiveException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** The archive (or the requested entry) is encrypted and no password was given. */
class PasswordRequiredException : ArchiveException("Cette archive est protégée par un mot de passe")

/** The given password does not decrypt the archive. */
class WrongPasswordException(cause: Throwable? = null) : ArchiveException("Mot de passe incorrect", cause)

class UnsupportedArchiveException(message: String, cause: Throwable? = null) : ArchiveException(message, cause)

class CorruptedArchiveException(message: String = "Archive endommagée ou illisible", cause: Throwable? = null) :
    ArchiveException(message, cause)

/** An entry would be written outside the destination folder. */
class ZipSlipException(val entry: String) : ArchiveException("Chemin dangereux refusé : $entry")

/** User-facing message for any error thrown by the archive module. */
fun Throwable.archiveMessage(): String = when (this) {
    is ArchiveException -> message ?: "Erreur d'archive"
    is OutOfMemoryError -> "Mémoire insuffisante pour traiter cette archive"
    is SecurityException -> "Accès refusé"
    is java.io.FileNotFoundException -> "Fichier introuvable ou accès refusé"
    else -> message?.takeIf { it.isNotBlank() } ?: "Erreur inattendue"
}

/* ---------- Path safety ---------- */

object ArchivePaths {
    /**
     * Normalises a stored name: backslashes → '/', drive letters and leading slashes removed,
     * "." dropped and ".." resolved lexically. Returns null when ".." climbs above the root.
     */
    fun normalize(raw: String): String? {
        var p = raw.replace('\\', '/')
        if (p.length >= 2 && p[1] == ':' && p[0].isLetter()) p = p.substring(2)
        val out = ArrayDeque<String>()
        for (seg in p.split('/')) {
            when (seg) {
                "", "." -> Unit
                ".." -> if (out.isEmpty()) return null else out.removeLast()
                else -> out.addLast(seg)
            }
        }
        return out.joinToString("/")
    }

    /** Lossy fallback for display: every "..", "." and empty segment dropped. */
    fun sanitize(raw: String): String =
        raw.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." && it != ".." }.joinToString("/")

    fun isAbsolute(raw: String): Boolean =
        raw.startsWith("/") || raw.startsWith("\\") || (raw.length >= 2 && raw[1] == ':' && raw[0].isLetter())

    /** Characters refused by FAT/exFAT cards and some FUSE layers. */
    private val illegal = Regex("[\\u0000-\\u001f\"*:<>?|]")

    fun safeSegment(seg: String): String = seg.replace(illegal, "_").trimEnd('.', ' ').ifEmpty { "_" }

    /**
     * Resolves [relative] inside [destDir], refusing anything that would land outside it
     * (lexical ".." and symlinked parents both checked through the canonical path).
     */
    fun safeTarget(destDir: File, relative: String): File {
        if (isAbsolute(relative)) throw ZipSlipException(relative)
        val norm = normalize(relative) ?: throw ZipSlipException(relative)
        if (norm.isEmpty()) throw ZipSlipException(relative)
        val root = destDir.canonicalFile
        val target = File(root, norm.split('/').joinToString(File.separator) { safeSegment(it) })
        val canon = target.canonicalFile
        if (canon != root && !canon.path.startsWith(root.path + File.separator)) throw ZipSlipException(relative)
        return target
    }

    /** Path of [path] relative to the browsed folder [prefix] ("" = root). */
    fun relativeTo(path: String, prefix: String): String =
        if (prefix.isEmpty()) path else path.removePrefix("$prefix/")

    /** "name (1).ext", "name (2).ext"… next to [target], the first that does not exist. */
    fun uniqueFile(target: File): File {
        if (!target.exists()) return target
        val name = target.name
        val dot = name.lastIndexOf('.').takeIf { it > 0 && !target.isDirectory } ?: -1
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (true) {
            val candidate = File(target.parentFile, "$base ($i)$ext")
            if (!candidate.exists()) return candidate
            i++
        }
    }
}

/* ---------- Tree ---------- */

/**
 * Folder view of a flat entry list: implicit directories are reconstructed, duplicates removed
 * (the last one wins, as with unzip) and children sorted folders-first then by name.
 */
class ArchiveTree(entries: List<ArchiveItem>) {
    val items: List<ArchiveItem>
    private val byPath: Map<String, ArchiveItem>
    private val byParent: Map<String, List<ArchiveItem>>

    init {
        val map = LinkedHashMap<String, ArchiveItem>(entries.size * 2)
        for (e in entries) {
            val existing = map[e.path]
            // An explicit directory entry may come after a file inside it: keep the directory flag.
            map[e.path] = if (existing != null && existing.isDirectory && !e.isDirectory && e.size <= 0) existing else e
        }
        // Implicit parents.
        for (e in map.values.toList()) {
            var parent = e.parent
            while (parent.isNotEmpty() && parent !in map) {
                map[parent] = ArchiveItem(
                    path = parent, name = parent.substringAfterLast('/'), isDirectory = true,
                    size = 0, isImplicit = true,
                )
                parent = parent.substringBeforeLast('/', "")
            }
        }
        items = map.values.toList()
        byPath = map
        byParent = items.groupBy { it.parent }.mapValues { (_, list) ->
            list.sortedWith(compareBy<ArchiveItem>({ !it.isDirectory }, { it.name.lowercase() }))
        }
    }

    fun children(dir: String): List<ArchiveItem> = byParent[dir].orEmpty()

    operator fun get(path: String): ArchiveItem? = byPath[path]

    val files: List<ArchiveItem> by lazy { items.filter { !it.isDirectory } }
    val fileCount: Int get() = files.size
    val folderCount: Int by lazy { items.count { it.isDirectory } }
    val totalSize: Long by lazy { files.sumOf { it.size.coerceAtLeast(0) } }
    /** Sum of the known compressed sizes; -1 when the format does not expose them. */
    val compressedSize: Long by lazy {
        if (files.isEmpty() || files.any { it.compressedSize < 0 }) -1 else files.sumOf { it.compressedSize }
    }
    val hasEncrypted: Boolean by lazy { files.any { it.isEncrypted } }

    /** Every item at or below the given paths (null = the whole archive). */
    fun select(selection: Collection<String>?): List<ArchiveItem> {
        if (selection == null) return items
        val roots = selection.toSet()
        return items.filter { item -> item.path in roots || roots.any { r -> item.path.startsWith("$r/") } }
    }

    fun sizeOf(paths: Collection<String>): Long = select(paths).filter { !it.isDirectory }.sumOf { it.size.coerceAtLeast(0) }

    fun countFiles(dir: String): Int = if (dir.isEmpty()) fileCount else files.count { it.path.startsWith("$dir/") }
}

/* ---------- Callbacks ---------- */

/** Progress + pause/cancel hooks; implemented on top of [TaskScope] in the app, no-op in tests. */
interface ProgressCallbacks {
    /** Suspends while paused, throws CancellationException when cancelled. */
    suspend fun checkpoint() {}
    fun onProgress(doneBytes: Long, totalBytes: Long, currentName: String, doneItems: Int, totalItems: Int) {}
}

interface ExtractCallbacks : ProgressCallbacks {
    /** A file already exists at the destination. */
    suspend fun onConflict(item: ArchiveItem, existing: File): ConflictChoice = ConflictChoice.REPLACE
}

/** Bridges a [TaskScope] (notification progress, pause/cancel, conflict dialog) to the archive engine. */
fun TaskScope.asExtractCallbacks(archivePath: String): ExtractCallbacks {
    val scope = this
    return object : ExtractCallbacks {
        override suspend fun checkpoint() = scope.checkpoint()
        override fun onProgress(doneBytes: Long, totalBytes: Long, currentName: String, doneItems: Int, totalItems: Int) =
            scope.progress(doneBytes, totalBytes, currentName, doneItems, totalItems)

        override suspend fun onConflict(item: ArchiveItem, existing: File): ConflictChoice = scope.askConflict(
            FileEntry("$archivePath/${item.path}", item.name, false, item.size.coerceAtLeast(0), item.lastModified),
            FileEntry(existing.absolutePath, existing.name, existing.isDirectory, existing.length(), existing.lastModified()),
        )
    }
}

fun TaskScope.asProgressCallbacks(): ProgressCallbacks {
    val scope = this
    return object : ProgressCallbacks {
        override suspend fun checkpoint() = scope.checkpoint()
        override fun onProgress(doneBytes: Long, totalBytes: Long, currentName: String, doneItems: Int, totalItems: Int) =
            scope.progress(doneBytes, totalBytes, currentName, doneItems, totalItems)
    }
}

/** What an extraction did. */
data class ExtractResult(val extracted: Int, val skipped: Int, val rejected: Int, val bytes: Long)

/** Where to extract and what. */
data class ExtractRequest(
    val destination: File,
    /** Internal paths (files or folders) to extract; null = everything. */
    val selection: Collection<String>? = null,
    /** Browsed folder: selected items are written relative to it. */
    val stripPrefix: String = "",
)
