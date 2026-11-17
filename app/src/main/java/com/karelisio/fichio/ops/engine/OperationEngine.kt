package com.karelisio.fichio.ops.engine

import com.karelisio.fichio.core.fs.FileBackend
import com.karelisio.fichio.core.model.FileEntry
import com.karelisio.fichio.core.util.Format
import com.karelisio.fichio.ops.ConflictChoice
import com.karelisio.fichio.ops.JobProgress
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random

/** Fatal, user-facing error that aborts a whole operation (message is shown as is). */
class OperationException(message: String) : IOException(message)

data class ConflictAnswer(val choice: ConflictChoice, val applyToAll: Boolean = false)

/** Hooks the engine calls while working. All methods are invoked from the engine's IO thread. */
interface EngineListener {
    /** Suspends while paused; throws [CancellationException] when cancelled. */
    suspend fun checkpoint() {}

    fun onProgress(progress: JobProgress) {}

    suspend fun onConflict(source: FileEntry, existing: FileEntry, remaining: Int): ConflictAnswer =
        ConflictAnswer(ConflictChoice.SKIP)

    companion object {
        val None: EngineListener = object : EngineListener {}
    }
}

/** An item that now lives at [to] as a whole (used for undo and favourites). */
data class Transfer(val from: String, val to: String, val isDirectory: Boolean)

data class EngineResult(
    /** Unit-level transfers: a fully moved/copied folder appears once, not per child. */
    val transfers: List<Transfer> = emptyList(),
    /** Top-level items fully handled. */
    val processed: Int = 0,
    val skipped: Int = 0,
    val failed: Int = 0,
    val replaced: Int = 0,
    val errors: List<String> = emptyList(),
    /** Directories whose listing changed. */
    val touchedDirs: Set<String> = emptySet(),
)

data class SizeStats(val bytes: Long = 0, val files: Int = 0, val folders: Int = 0, val complete: Boolean = false)

/**
 * Pure-JVM file operation engine working on [FileBackend]s: recursive copy / move / delete with
 * byte progress, smoothed speed, cooperative pause & cancel (partial files are removed),
 * modification dates kept, conflict resolution and folder merging.
 *
 * Blocking IO runs on [io]; every public function is main-safe.
 */
class OperationEngine(
    private val backendFor: (String) -> FileBackend,
    private val bufferSize: Int = DEFAULT_BUFFER,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::nanoTime,
) {
    companion object {
        const val DEFAULT_BUFFER = 256 * 1024
        private const val MAX_DEPTH = 256
    }

    private enum class Outcome { DONE, PARTIAL, SKIPPED, FAILED }

    suspend fun copy(sources: List<String>, destinationDir: String, listener: EngineListener = EngineListener.None) =
        transfer(sources.map { it to PathUtil.join(destinationDir, PathUtil.name(it)) }, move = false, exact = false, listener, destinationDir)

    suspend fun move(sources: List<String>, destinationDir: String, listener: EngineListener = EngineListener.None) =
        transfer(sources.map { it to PathUtil.join(destinationDir, PathUtil.name(it)) }, move = true, exact = false, listener, destinationDir)

    /**
     * Moves every `from` to exactly `to` (parents created; when `to` already exists both are kept).
     * Used by undo and the trash.
     */
    suspend fun moveExact(pairs: List<Pair<String, String>>, listener: EngineListener = EngineListener.None) =
        transfer(pairs, move = true, exact = true, listener, null)

    /** Copies every `from` to exactly `to` (keeps both on conflict). */
    suspend fun copyExact(pairs: List<Pair<String, String>>, listener: EngineListener = EngineListener.None) =
        transfer(pairs, move = false, exact = true, listener, null)

    /** Permanently deletes [paths] recursively. */
    suspend fun delete(paths: List<String>, listener: EngineListener = EngineListener.None): EngineResult = withContext(io) {
        val run = Run(listener, move = false)
        val items = ArrayList<Pair<FileBackend, FileEntry>>()
        for (p in paths) {
            val b = backendFor(p)
            val e = b.stat(p) ?: if (isSymlink(b, p)) FileEntry(p, PathUtil.name(p), false, 0, 0) else null
            if (e == null) run.fail(PathUtil.name(p), "introuvable") else items += b to e
        }
        var total = 0
        for ((b, e) in items) total += if (b.id == "local") run.scan(b, e, 0).items else 1
        run.tracker.start(0, total)
        for ((b, e) in items) {
            try {
                if (b.id == "local") run.deleteTree(b, e, 0)
                else {
                    listener.checkpoint()
                    run.tracker.setName(e.name)
                    if (!b.deleteRecursively(e.path)) throw IOException("suppression impossible")
                    run.tracker.itemDone()
                }
                run.processed++
            } catch (c: CancellationException) {
                throw c
            } catch (ex: Exception) {
                run.fail(e.name, ex.message)
            }
            run.touched += PathUtil.parent(e.path)
        }
        run.tracker.emit(force = true)
        run.result()
    }

    /** Recursive size/count of [paths], emitted progressively (~10 times per second). */
    fun measure(paths: List<String>): Flow<SizeStats> = flow {
        var bytes = 0L
        var files = 0
        var folders = 0
        var last = clock()
        suspend fun visit(b: FileBackend, e: FileEntry, depth: Int) {
            if (e.isDirectory) {
                folders++
                if (depth < MAX_DEPTH && !isSymlink(b, e.path)) {
                    val children = runCatching { b.list(e.path) }.getOrDefault(emptyList())
                    for (c in children) visit(b, c, depth + 1)
                }
            } else {
                files++
                bytes += e.size
            }
            val now = clock()
            if (now - last > 100_000_000L) {
                last = now
                emit(SizeStats(bytes, files, folders, false))
            }
        }
        for (p in paths) {
            val b = runCatching { backendFor(p) }.getOrNull() ?: continue
            val e = b.stat(p) ?: continue
            visit(b, e, 0)
        }
        emit(SizeStats(bytes, files, folders, true))
    }.flowOn(io)

    // ---------------------------------------------------------------------------------------

    private class Item(val srcB: FileBackend, val dstB: FileBackend, val entry: FileEntry, var target: String) {
        var needsCopy = false
    }

    private suspend fun transfer(
        pairs: List<Pair<String, String>>,
        move: Boolean,
        exact: Boolean,
        listener: EngineListener,
        destinationDir: String?,
    ): EngineResult = withContext(io) {
        val run = Run(listener, move)
        val verb = if (move) "déplacer" else "copier"

        if (destinationDir != null) {
            val db = backendFor(destinationDir)
            val st = db.stat(destinationDir)
            if (st == null) {
                if (!db.mkdirs(destinationDir)) throw OperationException("Impossible de créer le dossier de destination")
            } else if (!st.isDirectory) {
                throw OperationException("La destination n'est pas un dossier")
            }
        }

        // 1. Resolve and validate sources.
        val items = ArrayList<Item>()
        for ((from, to) in pairs) {
            listener.checkpoint()
            val srcB = backendFor(from)
            val entry = srcB.stat(from)
            if (entry == null) {
                run.fail(PathUtil.name(from), "introuvable"); continue
            }
            if (entry.isDirectory && to != from && PathUtil.isSameOrInside(to, from)) {
                throw OperationException("Impossible de $verb « ${entry.name} » dans lui-même")
            }
            if (to == from && move) {
                run.processed++; continue // Moving onto itself: nothing to do.
            }
            val dstB = backendFor(to)
            val item = Item(srcB, dstB, entry, to)
            // Copy into its own folder, or exact placement onto an existing path: keep both.
            if (to == from || (exact && dstB.exists(to))) item.target = run.uniqueIn(dstB, to, entry.isDirectory)
            if (exact) dstB.mkdirs(PathUtil.parent(item.target))
            items += item
        }

        // 2. Moves: try atomic renames first (instant, no scan needed).
        val pending = ArrayList<Item>()
        if (move) {
            for (it in items) {
                listener.checkpoint()
                if (it.srcB.id == it.dstB.id) {
                    if (!it.dstB.exists(it.target)) {
                        if (it.srcB.rename(it.entry.path, it.target)) {
                            run.transfers += Transfer(it.entry.path, it.target, it.entry.isDirectory)
                            run.processed++
                            run.touch(it)
                            continue
                        }
                        it.needsCopy = true // Cross-device (e.g. SD card ↔ internal).
                    }
                } else {
                    it.needsCopy = true
                }
                pending += it
            }
        } else {
            items.forEach { it.needsCopy = true; pending += it }
        }

        // 3. Scan what is left, check free space.
        var totalBytes = 0L
        var totalItems = 0
        val copyBytes = LinkedHashMap<Pair<FileBackend, String>, Long>()
        for (it in pending) {
            val s = run.scan(it.srcB, it.entry, 0)
            totalBytes += s.bytes
            totalItems += s.items
            if (it.needsCopy) {
                val key = it.dstB to PathUtil.parent(it.target)
                copyBytes[key] = (copyBytes[key] ?: 0L) + s.bytes
            }
        }
        for ((key, needed) in copyBytes) {
            val free = key.first.freeSpace(key.second)
            if (needed > free) {
                throw OperationException(
                    "Espace insuffisant : ${Format.size(needed)} nécessaires, ${Format.size(free)} disponibles",
                )
            }
        }

        // 4. Do the work.
        run.tracker.start(totalBytes, totalItems)
        for (it in pending) {
            if (run.transferEntry(it.srcB, it.entry, it.dstB, it.target, 0) == Outcome.DONE) run.processed++
            run.touch(it)
        }
        run.tracker.emit(force = true)
        run.result()
    }

    private fun isSymlink(b: FileBackend, path: String): Boolean =
        b.id == "local" && runCatching { Files.isSymbolicLink(Paths.get(path)) }.getOrDefault(false)

    /** State of one operation. */
    private inner class Run(val listener: EngineListener, val move: Boolean) {
        val tracker = ProgressTracker(listener::onProgress, clock)
        val transfers = ArrayList<Transfer>()
        val errors = ArrayList<String>()
        val touched = LinkedHashSet<String>()
        var processed = 0
        var skipped = 0
        var failed = 0
        var replaced = 0
        private var sticky: ConflictChoice? = null
        private val buffer by lazy { ByteArray(bufferSize) }
        /** Scanned folder sizes, reused when a sub-folder is renamed during a merge. */
        private val dirStats = HashMap<String, Stats>()

        fun result() = EngineResult(transfers.toList(), processed, skipped, failed, replaced, errors.toList(), touched.toSet())

        fun fail(name: String, message: String?) {
            failed++
            errors += "$name : ${message ?: "erreur inconnue"}"
        }

        fun touch(it: Item) {
            touched += PathUtil.parent(it.target)
            if (move) touched += PathUtil.parent(it.entry.path)
        }

        fun uniqueIn(b: FileBackend, target: String, isDir: Boolean): String {
            val dir = PathUtil.parent(target)
            return PathUtil.join(dir, Names.unique(PathUtil.name(target), isDir) { b.exists(PathUtil.join(dir, it)) })
        }

        suspend fun scan(b: FileBackend, e: FileEntry, depth: Int): Stats {
            if (!e.isDirectory) return Stats(e.size, 1)
            listener.checkpoint()
            var bytes = 0L
            var count = 1
            if (depth < MAX_DEPTH && !isSymlink(b, e.path)) {
                for (c in b.list(e.path)) {
                    val s = scan(b, c, depth + 1)
                    bytes += s.bytes; count += s.items
                }
            }
            return Stats(bytes, count).also { dirStats[e.path] = it }
        }

        private fun statsOf(e: FileEntry) = if (e.isDirectory) dirStats[e.path] ?: Stats(0, 1) else Stats(e.size, 1)

        private suspend fun resolve(src: FileEntry, existing: FileEntry): ConflictChoice {
            sticky?.let { return it }
            val remaining = (tracker.totalItems - tracker.doneItems - 1).coerceAtLeast(0)
            val answer = listener.onConflict(src, existing, remaining)
            if (answer.applyToAll) sticky = answer.choice
            return answer.choice
        }

        suspend fun transferEntry(srcB: FileBackend, src: FileEntry, dstB: FileBackend, target: String, depth: Int): Outcome {
            listener.checkpoint()
            if (depth > MAX_DEPTH) {
                fail(src.name, "arborescence trop profonde"); return Outcome.FAILED
            }
            val existing = dstB.stat(target)
            val merging = existing != null && src.isDirectory && existing.isDirectory
            var dest = target
            var replacing: FileEntry? = null
            if (existing != null && !merging) {
                when (resolve(src, existing)) {
                    ConflictChoice.SKIP -> {
                        skipped++
                        val s = statsOf(src)
                        tracker.advance(s.bytes, s.items)
                        return Outcome.SKIPPED
                    }
                    ConflictChoice.KEEP_BOTH -> dest = uniqueIn(dstB, target, src.isDirectory)
                    ConflictChoice.REPLACE -> replacing = existing
                }
            }
            return try {
                // Fast path for moves on the same backend: a rename (or a swap when replacing).
                if (move && srcB.id == dstB.id && !merging) {
                    val renamed = if (replacing == null) srcB.rename(src.path, dest)
                    else swapIn(dstB, dest) { srcB.rename(src.path, dest) }.also { if (it) replaced++ }
                    if (renamed) {
                        val s = statsOf(src)
                        tracker.setName(src.name)
                        tracker.advance(s.bytes, s.items)
                        transfers += Transfer(src.path, dest, src.isDirectory)
                        return Outcome.DONE
                    }
                }
                if (src.isDirectory) transferDir(srcB, src, dstB, dest, merging, replacing, depth)
                else transferFile(srcB, src, dstB, dest, replacing)
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                fail(src.name, e.message)
                Outcome.FAILED
            }
        }

        private suspend fun transferDir(
            srcB: FileBackend, src: FileEntry, dstB: FileBackend, dest: String,
            merging: Boolean, replacing: FileEntry?, depth: Int,
        ): Outcome {
            if (replacing != null) {
                if (!dstB.deleteRecursively(dest)) throw IOException("impossible de remplacer « ${replacing.name} »")
                replaced++
            }
            if (!merging && !dstB.mkdirs(dest)) throw IOException("impossible de créer le dossier")
            tracker.setName(src.name)
            val mark = transfers.size
            var complete = true
            if (!isSymlink(srcB, src.path)) {
                for (child in srcB.list(src.path)) {
                    if (transferEntry(srcB, child, dstB, PathUtil.join(dest, child.name), depth + 1) != Outcome.DONE) complete = false
                }
            }
            if (!merging) dstB.setLastModified(dest, src.lastModified)
            tracker.itemDone()
            if (move && complete && !srcB.deleteSingle(src.path)) {
                errors += "${src.name} : le dossier d'origine n'a pas pu être supprimé"
                complete = false
            }
            if (!merging && complete) {
                // The whole folder is the unit: drop its children's records.
                while (transfers.size > mark) transfers.removeAt(transfers.size - 1)
                transfers += Transfer(src.path, dest, true)
            }
            return if (complete) Outcome.DONE else Outcome.PARTIAL
        }

        private suspend fun transferFile(
            srcB: FileBackend, src: FileEntry, dstB: FileBackend, dest: String, replacing: FileEntry?,
        ): Outcome {
            tracker.beginFile(src.name, src.size)
            try {
                if (replacing != null && replacing.isDirectory) {
                    if (!dstB.deleteRecursively(dest)) throw IOException("impossible de remplacer « ${replacing.name} »")
                    replaced++
                    copyBytes(srcB, src, dstB, dest)
                } else if (replacing != null) {
                    // Write next to the existing file, then swap: the original survives a failure.
                    val tmp = tempSibling(dest)
                    copyBytes(srcB, src, dstB, tmp)
                    dstB.setLastModified(tmp, src.lastModified)
                    if (!swapIn(dstB, dest) { dstB.rename(tmp, dest) }) {
                        dstB.deleteSingle(tmp)
                        throw IOException("impossible de remplacer « ${replacing.name} »")
                    }
                    replaced++
                } else {
                    copyBytes(srcB, src, dstB, dest)
                }
                dstB.setLastModified(dest, src.lastModified)
            } catch (t: Throwable) {
                if (t !is CancellationException) tracker.abortFile()
                throw t
            }
            tracker.endFile()
            if (move && !srcB.deleteSingle(src.path)) {
                errors += "${src.name} : copié mais l'original n'a pas pu être supprimé"
                transfers += Transfer(src.path, dest, false)
                return Outcome.PARTIAL
            }
            transfers += Transfer(src.path, dest, false)
            return Outcome.DONE
        }

        private suspend fun copyBytes(srcB: FileBackend, src: FileEntry, dstB: FileBackend, dest: String) {
            try {
                srcB.openInput(src.path).use { input ->
                    dstB.openOutput(dest).use { out ->
                        val buf = buffer
                        while (true) {
                            listener.checkpoint()
                            val n = input.read(buf)
                            if (n < 0) break
                            if (n > 0) {
                                out.write(buf, 0, n)
                                tracker.addBytes(n)
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                // Never leave a partial file behind (cancel, IO error, disk full).
                runCatching { dstB.deleteSingle(dest) }
                throw t
            }
        }

        /** Moves [dest] aside, runs [place]; deletes the old one on success, restores it otherwise. */
        private fun swapIn(b: FileBackend, dest: String, place: () -> Boolean): Boolean {
            val aside = tempSibling(dest)
            if (!b.rename(dest, aside)) return false
            return if (place()) {
                b.deleteRecursively(aside); true
            } else {
                b.rename(aside, dest); false
            }
        }

        private fun tempSibling(path: String): String =
            PathUtil.join(PathUtil.parent(path), ".${PathUtil.name(path).take(64)}.fichio-${Random.nextInt(1_000_000, 9_999_999)}")

        suspend fun deleteTree(b: FileBackend, e: FileEntry, depth: Int) {
            listener.checkpoint()
            if (e.isDirectory && depth < MAX_DEPTH && !isSymlink(b, e.path)) {
                for (c in b.list(e.path)) deleteTree(b, c, depth + 1)
            }
            tracker.setName(e.name)
            if (!b.deleteSingle(e.path) && b.exists(e.path)) throw IOException("impossible de supprimer « ${e.name} »")
            tracker.itemDone()
        }
    }

    private data class Stats(val bytes: Long, val items: Int)
}
