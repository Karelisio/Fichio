package com.karelisio.fichio.analysis

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException

/** Kind of a node of the size tree. */
enum class NodeKind {
    /** A real directory. */
    DIR,

    /** The files directly inside the parent directory, grouped in one slice. */
    FILES,

    /** Small siblings aggregated for display ("Autres"). Its children are the aggregated nodes. */
    OTHERS,
}

/**
 * Node of the size tree. Identity-compared (no data class) so that Compose and the sunburst
 * cache never deep-compare a 50 000-node tree. [children] are sorted by size, descending.
 */
@Immutable
class DirNode(
    val path: String,
    val name: String,
    val size: Long,
    val fileCount: Int,
    val children: List<DirNode>,
    val kind: NodeKind = NodeKind.DIR,
    /** Real directory to open for this node (the parent for FILES / OTHERS). */
    val dirPath: String = path,
) {
    val isDir: Boolean get() = kind == NodeKind.DIR
    val canZoom: Boolean get() = children.isNotEmpty() && (kind != NodeKind.FILES)
    override fun toString() = "DirNode($path, $size, ${children.size} children)"
}

/** Everything one analysis run produced. */
@Immutable
class AnalysisResult(
    val root: DirNode,
    val scannedAt: Long,
    val durationMs: Long,
    val totalFiles: Int,
    val totalBytes: Long,
    /** Every file of at least [FilePool.floor] bytes: the lists below are computed from it on demand. */
    val pool: FilePool,
    /** Sizes of all scanned files, including the ones below the pool floor. */
    val histogram: SizeHistogram,
    /** Top-most directories whose subtree contains no file. */
    val emptyDirs: List<String>,
    /** Confirmed duplicate groups for files of at least [duplicatesFrom] bytes, most wasted first. */
    val duplicates: List<DuplicateGroup>,
    val duplicatesFrom: Long,
    /** Everything deleted since the scan (to filter results computed later, e.g. extra duplicates). */
    val removed: Set<String> = emptySet(),
) {
    /** Files of at least [minBytes], biggest first. */
    fun large(minBytes: Long, accept: (String) -> Boolean = { true }, limit: Int = MAX_LIST): List<FileRef> =
        pool.select(minBytes.coerceAtLeast(1), limit, accept = accept)

    /** Files of at least [minBytes] not modified for [minAgeMs] (at scan time), biggest first. */
    fun old(minBytes: Long, minAgeMs: Long, accept: (String) -> Boolean = { true }, limit: Int = MAX_LIST): List<FileRef> =
        pool.select(minBytes.coerceAtLeast(1), limit, match = { _, m -> m > 0 && scannedAt - m >= minAgeMs }, accept = accept)

    /** Duplicate groups of files of at least [minBytes] (complete when [minBytes] >= [duplicatesFrom]). */
    fun duplicates(minBytes: Long): List<DuplicateGroup> = duplicates.filter { it.size >= minBytes }

    private fun gone(p: String, set: Set<String>) = p in set || set.any { p.startsWith("$it/") }

    /** Adds groups found for smaller files (sizes in [from, duplicatesFrom)). */
    fun withDuplicates(extra: List<DuplicateGroup>, from: Long): AnalysisResult {
        if (from >= duplicatesFrom) return this
        val fresh = extra.mapNotNull { g ->
            val left = g.files.filterNot { gone(it.path, removed) }
            if (left.size >= 2) g.copy(files = left) else null
        }
        return AnalysisResult(
            root, scannedAt, durationMs, totalFiles, totalBytes, pool, histogram, emptyDirs,
            (duplicates + fresh).sortedByDescending { it.wasted }, from, removed,
        )
    }

    /** Copy without items that were just deleted (keeps the cached result in sync, no rescan). */
    fun removing(paths: Collection<String>): AnalysisResult {
        if (paths.isEmpty()) return this
        val set = paths.toHashSet()
        val sizes = pool.sizesOf(set)
        val removedDirs = set.filterTo(HashSet()) { it !in sizes }
        val newRoot = root.minus(sizes, removedDirs) ?: root
        return AnalysisResult(
            root = newRoot,
            scannedAt = scannedAt,
            durationMs = durationMs,
            totalFiles = newRoot.fileCount,
            totalBytes = newRoot.size,
            pool = pool.without(sizes.keys, removedDirs),
            histogram = histogram,
            emptyDirs = emptyDirs.filterNot { gone(it, set) },
            duplicates = duplicates.mapNotNull { g ->
                val left = g.files.filterNot { gone(it.path, set) }
                if (left.size >= 2) g.copy(files = left) else null
            },
            duplicatesFrom = duplicatesFrom,
            removed = removed + set,
        )
    }

    companion object {
        /** Longest list shown by a tab. */
        const val MAX_LIST = 5_000
    }
}

/** Subtracts removed files (path → size) and directories from the tree. Returns null if [this] is removed. */
internal fun DirNode.minus(files: Map<String, Long>, dirs: Set<String>): DirNode? {
    if (kind == NodeKind.DIR && path in dirs) return null
    val prefix = "$dirPath/"
    val myFiles = files.filterKeys { it.startsWith(prefix) }
    val myDirs = dirs.filterTo(HashSet()) { it.startsWith(prefix) }
    if (myFiles.isEmpty() && myDirs.isEmpty()) return this
    val newChildren = children.mapNotNull { c ->
        when (c.kind) {
            NodeKind.DIR -> c.minus(myFiles, myDirs)
            NodeKind.FILES -> {
                val direct = myFiles.filterKeys { it.substringBeforeLast('/') == dirPath }
                if (direct.isEmpty()) c
                else {
                    val size = (c.size - direct.values.sum()).coerceAtLeast(0)
                    val count = (c.fileCount - direct.size).coerceAtLeast(0)
                    if (count == 0) null else DirNode(c.path, c.name, size, count, emptyList(), NodeKind.FILES, c.dirPath)
                }
            }
            NodeKind.OTHERS -> c
        }
    }.sortedByDescending { it.size }
    return DirNode(path, name, newChildren.sumOf { it.size }, newChildren.sumOf { it.fileCount }, newChildren, kind, dirPath)
}

/**
 * Recursive size scanner over java.nio (no symlink following). Top-level folders are walked
 * in parallel on a bounded dispatcher; each walk is sequential to stay gentle on the
 * (FUSE-backed) shared storage. Cancellable between directories.
 */
class StorageAnalyzer(
    private val root: String,
    /** Absolute paths never entered (Android/data, Android/obb, .Fichio…). */
    private val excluded: Set<String>,
    private val config: Config = Config(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(3),
) {
    data class Config(
        /**
         * Smallest file kept in the [FilePool]: every threshold of the analysis screen (big, old,
         * duplicates) can go down to it and is recomputed without rescanning.
         */
        val poolMinBytes: Long = POOL_MIN_BYTES,
        /** Memory bound of the pool (~50 bytes per file); past it, the smallest files are dropped. */
        val poolCap: Int = 400_000,
        /** Duplicates are hashed during the scan from this size; smaller ones on demand. */
        val duplicateMinBytes: Long = 100L * 1024,
        /** Top-level folders that apps/the system expect to exist even when empty. */
        val protectedEmpty: Set<String> = setOf(
            "alarms", "audiobooks", "dcim", "documents", "download", "movies", "music", "notifications",
            "pictures", "podcasts", "recordings", "ringtones", "android",
        ),
        val now: Long = System.currentTimeMillis(),
    )

    /** Live counters, polled by the UI. */
    class Progress {
        val files = AtomicInteger()
        val bytes = AtomicLong()
        @Volatile var phase: Phase = Phase.SCANNING
        @Volatile var hashed: Int = 0
        @Volatile var hashTotal: Int = 0
    }

    enum class Phase { SCANNING, DUPLICATES }

    /** Per-walker collectors (merged at the end, so no locking during the walk). */
    private inner class Acc {
        val pool = PoolBuilder(config.poolMinBytes, config.poolCap)
        val histogram = SizeHistogram()
        val empty = ArrayList<String>()
    }

    companion object {
        const val POOL_MIN_BYTES = 64L * 1024
    }

    suspend fun scan(
        progress: Progress = Progress(),
        hashFinder: (isActive: () -> Boolean) -> DuplicateFinder = { DuplicateFinder.forFiles(it, config.duplicateMinBytes) },
    ): AnalysisResult = coroutineScope {
        val started = System.currentTimeMillis()
        val ctx = currentCoroutineContext()
        val isActive = { ctx.isActive }
        val rootPath = Paths.get(root)
        val excludedNorm = excluded.map { it.trimEnd('/') }.toHashSet()

        // Root level: directories fan out, files are counted here.
        val topDirs = ArrayList<Path>()
        val rootAcc = Acc()
        var rootFilesSize = 0L
        var rootFilesCount = 0
        val rootDir = rootAcc.pool.addDir(root.trimEnd('/'))
        forEachEntry(rootPath) { p, a ->
            when {
                a.isSymbolicLink -> Unit
                a.isDirectory -> if (p.toString() !in excludedNorm) topDirs.add(p)
                a.isRegularFile -> {
                    val s = a.size()
                    rootFilesSize += s; rootFilesCount++
                    collect(rootAcc, rootDir, p, s, a.lastModifiedTime().toMillis(), progress)
                }
            }
        }

        val walked = topDirs.map { dir ->
            async(dispatcher) {
                val acc = Acc()
                walk(dir, acc, excludedNorm, progress, isActive) to acc
            }
        }.awaitAll()

        val accs = walked.map { it.second } + rootAcc
        val children = ArrayList<DirNode>(walked.size + 1)
        walked.forEach { (node, _) -> children += node }
        // Empty top-level folders (except the standard ones).
        walked.forEach { (node, _) ->
            if (node.fileCount == 0 && node.name.lowercase() !in config.protectedEmpty) rootAcc.empty += node.path
        }
        if (rootFilesCount > 0) children += filesNode(root, rootFilesSize, rootFilesCount)
        children.sortByDescending { it.size }
        val tree = DirNode(root, "Stockage interne", children.sumOf { it.size }, children.sumOf { it.fileCount }, children)

        val pool = FilePool.merge(accs.map { it.pool }, config.poolCap)
        val histogram = SizeHistogram().also { h -> accs.forEach { h.addAll(it.histogram) } }

        // Duplicates.
        progress.phase = Phase.DUPLICATES
        val dupFrom = maxOf(config.duplicateMinBytes, pool.floor, 1)
        val duplicates = hashFinder(isActive).find(
            pool.sameSizeCandidates(dupFrom),
            onProgress = { done, total -> progress.hashed = done; progress.hashTotal = total },
            isActive = isActive,
        )
        if (!isActive()) throw CancellationException("Analyse annulée")

        AnalysisResult(
            root = tree,
            scannedAt = config.now,
            durationMs = System.currentTimeMillis() - started,
            totalFiles = tree.fileCount,
            totalBytes = tree.size,
            pool = pool,
            histogram = histogram,
            emptyDirs = accs.flatMap { it.empty }.sorted(),
            duplicates = duplicates,
            duplicatesFrom = dupFrom,
        )
    }

    private fun walk(dir: Path, acc: Acc, excluded: Set<String>, progress: Progress, isActive: () -> Boolean): DirNode {
        if (!isActive()) throw CancellationException("Analyse annulée")
        val dirStr = dir.toString()
        val dirId = acc.pool.addDir(dirStr)
        val subDirs = ArrayList<DirNode>()
        var filesSize = 0L
        var filesCount = 0
        val toVisit = ArrayList<Path>()
        forEachEntry(dir) { p, a ->
            when {
                a.isSymbolicLink -> Unit
                a.isDirectory -> if (p.toString() !in excluded) toVisit.add(p)
                a.isRegularFile -> {
                    val s = a.size()
                    filesSize += s; filesCount++
                    collect(acc, dirId, p, s, a.lastModifiedTime().toMillis(), progress)
                }
            }
        }
        for (p in toVisit) subDirs += walk(p, acc, excluded, progress, isActive)
        val total = filesCount + subDirs.sumOf { it.fileCount }
        // Report top-most empty subtrees only: an empty child of a non-empty folder.
        if (total > 0) subDirs.forEach { if (it.fileCount == 0) acc.empty += it.path }
        val children = ArrayList<DirNode>(subDirs.size + 1)
        children += subDirs
        if (filesCount > 0) children += filesNode(dirStr, filesSize, filesCount)
        children.sortByDescending { it.size }
        children.trimToSize()
        return DirNode(dirStr, dir.fileName?.toString() ?: dirStr, children.sumOf { it.size }, total, children)
    }

    private fun filesNode(dir: String, size: Long, count: Int) =
        DirNode("$dir/*", "Fichiers", size, count, emptyList(), NodeKind.FILES, dirPath = dir)

    private fun collect(acc: Acc, dirId: Int, path: Path, size: Long, mtime: Long, progress: Progress) {
        progress.files.incrementAndGet()
        progress.bytes.addAndGet(size)
        acc.histogram.add(size)
        if (size >= acc.pool.floor) acc.pool.add(dirId, path.fileName.toString(), size, mtime)
    }

    private inline fun forEachEntry(dir: Path, block: (Path, BasicFileAttributes) -> Unit) {
        val stream: DirectoryStream<Path> = try {
            Files.newDirectoryStream(dir)
        } catch (_: Exception) {
            return
        }
        stream.use {
            for (p in it) {
                val a = try {
                    Files.readAttributes(p, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                } catch (_: Exception) {
                    continue
                }
                block(p, a)
            }
        }
    }
}
