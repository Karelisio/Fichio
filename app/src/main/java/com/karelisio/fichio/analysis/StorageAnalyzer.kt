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
    /** Files >= [StorageAnalyzer.Config.largeMinBytes], biggest first (filtered by the threshold in the UI). */
    val large: List<FileRef>,
    /** Files older than [StorageAnalyzer.Config.oldMinAgeMs], biggest first (filtered by age + recents in the UI). */
    val old: List<FileRef>,
    /** Top-most directories whose subtree contains no file. */
    val emptyDirs: List<String>,
    val duplicates: List<DuplicateGroup>,
) {
    /** Copy without items that were just deleted (keeps the cached result in sync, no rescan). */
    fun removing(paths: Collection<String>): AnalysisResult {
        if (paths.isEmpty()) return this
        val set = paths.toHashSet()
        fun gone(p: String) = p in set || set.any { p.startsWith("$it/") }
        val sizes = HashMap<String, Long>()
        (large.asSequence() + old.asSequence() + duplicates.asSequence().flatMap { it.files.asSequence() })
            .filter { it.path in set }.forEach { sizes[it.path] = it.size }
        val removedDirs = set.filterTo(HashSet()) { it !in sizes }
        val newRoot = root.minus(sizes, removedDirs) ?: root
        return AnalysisResult(
            root = newRoot,
            scannedAt = scannedAt,
            durationMs = durationMs,
            totalFiles = newRoot.fileCount,
            totalBytes = newRoot.size,
            large = large.filterNot { gone(it.path) },
            old = old.filterNot { gone(it.path) },
            emptyDirs = emptyDirs.filterNot { gone(it) },
            duplicates = duplicates.mapNotNull { g ->
                val left = g.files.filterNot { gone(it.path) }
                if (left.size >= 2) g.copy(files = left) else null
            },
        )
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
        val largeMinBytes: Long = 10L * 1024 * 1024,
        val oldMinBytes: Long = 512L * 1024,
        val oldMinAgeMs: Long = 30L * 24 * 3600 * 1000,
        val duplicateMinBytes: Long = 100L * 1024,
        val maxCandidates: Int = 5_000,
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
    private class Acc {
        val large = ArrayList<FileRef>()
        val old = ArrayList<FileRef>()
        val dupCandidates = ArrayList<FileRef>()
        val empty = ArrayList<String>()
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
        forEachEntry(rootPath) { p, a ->
            when {
                a.isSymbolicLink -> Unit
                a.isDirectory -> if (p.toString() !in excludedNorm) topDirs.add(p)
                a.isRegularFile -> {
                    val s = a.size()
                    rootFilesSize += s; rootFilesCount++
                    collect(rootAcc, p.toString(), s, a.lastModifiedTime().toMillis(), progress)
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

        // Duplicates.
        progress.phase = Phase.DUPLICATES
        val dupCandidates = accs.flatMap { it.dupCandidates }
        val duplicates = hashFinder(isActive).find(
            dupCandidates,
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
            large = accs.flatMap { it.large }.sortedByDescending { it.size }.take(config.maxCandidates),
            old = accs.flatMap { it.old }.sortedByDescending { it.size }.take(config.maxCandidates),
            emptyDirs = accs.flatMap { it.empty }.sorted(),
            duplicates = duplicates,
        )
    }

    private fun walk(dir: Path, acc: Acc, excluded: Set<String>, progress: Progress, isActive: () -> Boolean): DirNode {
        if (!isActive()) throw CancellationException("Analyse annulée")
        val dirStr = dir.toString()
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
                    collect(acc, p.toString(), s, a.lastModifiedTime().toMillis(), progress)
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

    private fun collect(acc: Acc, path: String, size: Long, mtime: Long, progress: Progress) {
        progress.files.incrementAndGet()
        progress.bytes.addAndGet(size)
        if (size >= config.largeMinBytes) acc.large += FileRef(path, size, mtime)
        if (size >= config.oldMinBytes && mtime > 0 && config.now - mtime >= config.oldMinAgeMs) acc.old += FileRef(path, size, mtime)
        if (size >= config.duplicateMinBytes) acc.dupCandidates += FileRef(path, size, mtime)
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
