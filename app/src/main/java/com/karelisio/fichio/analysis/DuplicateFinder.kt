package com.karelisio.fichio.analysis

import androidx.compose.runtime.Immutable
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import kotlin.coroutines.cancellation.CancellationException

/** Lightweight file reference kept by the analyzer (no Android types: unit-testable). */
@Immutable
data class FileRef(val path: String, val size: Long, val lastModified: Long) {
    val name: String get() = path.substringAfterLast('/')
}

/** Files with identical content (confirmed by a full SHA-256). */
@Immutable
data class DuplicateGroup(val hash: String, val size: Long, val files: List<FileRef>) {
    /** Bytes freed by keeping a single copy. */
    val wasted: Long get() = size * (files.size - 1).coerceAtLeast(0)

    /** The copy kept by "garder le plus récent". */
    val newest: FileRef get() = files.maxBy { it.lastModified }
}

/**
 * Three-stage duplicate detection:
 * 1. group by exact size (free),
 * 2. partial hash (first + last 64 KB) to discard most false candidates cheaply,
 * 3. full SHA-256 to confirm.
 *
 * Hash functions are injected so the grouping logic is pure and testable; they return null
 * when a file cannot be read (it is then ignored).
 */
class DuplicateFinder(
    private val partialHash: (FileRef) -> String?,
    private val fullHash: (FileRef) -> String?,
    /** Files smaller than this are ignored (empty files are never duplicates worth showing). */
    private val minSize: Long = 1,
) {
    /**
     * @param onProgress called with (hashed files, files to hash) during stages 2-3.
     * @param isActive polled between files; returning false aborts with CancellationException.
     */
    fun find(
        files: Collection<FileRef>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
        isActive: () -> Boolean = { true },
    ): List<DuplicateGroup> {
        val bySize = files.asSequence()
            .filter { it.size >= minSize }
            .distinctBy { it.path }
            .groupBy { it.size }
            .filterValues { it.size >= 2 }
        val total = bySize.values.sumOf { it.size }
        var done = 0
        val result = ArrayList<DuplicateGroup>()

        for ((size, sameSize) in bySize) {
            // Stage 2: partial hash.
            val byPartial = HashMap<String, MutableList<FileRef>>()
            for (f in sameSize) {
                if (!isActive()) throw CancellationException("Analyse annulée")
                partialHash(f)?.let { byPartial.getOrPut(it) { ArrayList() } += f }
                onProgress(++done, total)
            }
            // Stage 3: full hash only for files that still collide.
            for (candidates in byPartial.values) {
                if (candidates.size < 2) continue
                val byFull = HashMap<String, MutableList<FileRef>>()
                for (f in candidates) {
                    if (!isActive()) throw CancellationException("Analyse annulée")
                    fullHash(f)?.let { byFull.getOrPut(it) { ArrayList() } += f }
                }
                byFull.forEach { (hash, same) ->
                    if (same.size >= 2) result += DuplicateGroup(hash, size, same.sortedByDescending { it.lastModified })
                }
            }
        }
        return result.sortedByDescending { it.wasted }
    }

    companion object {
        const val PARTIAL_CHUNK = 64 * 1024

        /** Real-file finder using [FileHashing]. */
        fun forFiles(isActive: () -> Boolean = { true }, minSize: Long = 1) = DuplicateFinder(
            partialHash = { FileHashing.partial(it.path, it.size) },
            fullHash = { FileHashing.full(it.path, isActive) },
            minSize = minSize,
        )
    }
}

/** SHA-256 helpers over java.io (blocking: call from Dispatchers.IO). */
object FileHashing {
    /** Hash of size + first and last [DuplicateFinder.PARTIAL_CHUNK] bytes. */
    fun partial(path: String, size: Long): String? = runCatching {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(size.toString().toByteArray())
        RandomAccessFile(File(path), "r").use { raf ->
            val chunk = DuplicateFinder.PARTIAL_CHUNK
            val buf = ByteArray(chunk)
            val head = raf.read(buf, 0, minOf(chunk.toLong(), size).toInt())
            if (head > 0) md.update(buf, 0, head)
            if (size > chunk) {
                val tailStart = maxOf(chunk.toLong(), size - chunk)
                raf.seek(tailStart)
                val n = raf.read(buf, 0, (size - tailStart).toInt())
                if (n > 0) md.update(buf, 0, n)
            }
        }
        md.digest().toHex()
    }.getOrNull()

    fun full(path: String, isActive: () -> Boolean = { true }): String? = try {
        val md = MessageDigest.getInstance("SHA-256")
        File(path).inputStream().buffered(256 * 1024).use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                if (!isActive()) throw CancellationException("Analyse annulée")
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().toHex()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private fun ByteArray.toHex(): String {
        val hex = "0123456789abcdef"
        val sb = StringBuilder(size * 2)
        for (b in this) {
            val v = b.toInt() and 0xff
            sb.append(hex[v ushr 4]).append(hex[v and 0x0f])
        }
        return sb.toString()
    }
}
