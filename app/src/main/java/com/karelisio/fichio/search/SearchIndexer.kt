package com.karelisio.fichio.search

import android.content.Context
import android.os.Environment
import android.util.Log
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.karelisio.fichio.container
import com.karelisio.fichio.core.fs.FileSystems
import com.karelisio.fichio.core.fs.StoragePaths
import com.karelisio.fichio.core.model.FileEntry
import com.karelisio.fichio.data.db.FichioDatabase
import com.karelisio.fichio.data.db.FileIndexEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Which paths the index skips. Pure, so it is unit-tested. */
object IndexRules {
    /** True when [path] (or one of its ancestors below a volume root) must not be indexed. */
    fun isExcluded(path: String, volumeRoots: Collection<String>, showHidden: Boolean): Boolean {
        val root = volumeRoots.filter { path == it || path.startsWith("$it/") }.maxByOrNull { it.length }
        val rel = if (root != null) path.removePrefix(root).trimStart('/') else path.trimStart('/')
        if (rel.isEmpty()) return false
        val segs = rel.split('/')
        if (segs.size >= 2 && segs[0] == "Android" && (segs[1] == "data" || segs[1] == "obb")) return true
        if (segs[0] == ".Fichio") return true
        if (!showHidden && segs.any { it.startsWith(".") }) return true
        return false
    }
}

/**
 * WorkManager requests of the *full* scan (walks every volume: minutes of I/O on a full phone).
 * The incremental re-index of folders touched in the app does not go through WorkManager and
 * stays immediate (see [SearchIndexer.start]).
 *
 * Periodic scan: battery not low + **charging**. Charging was chosen over `setRequiresDeviceIdle`:
 * the idle state depends on Doze maintenance windows that aggressive OEM battery managers
 * (MagicOS/Honor, the target device) rarely grant to third-party apps, so an idle-only job could
 * starve for days, and idle jobs cannot use backoff. Phones charge almost every night, which
 * gives a predictable window where the scan costs no battery. The in-app incremental index keeps
 * results fresh in between.
 *
 * One-shot scan (first launch, manual rescan): battery not low only, so that it starts quickly.
 */
object IndexScheduling {
    fun periodicConstraints(): Constraints = Constraints.Builder()
        .setRequiresBatteryNotLow(true)
        .setRequiresCharging(true)
        .setRequiresStorageNotLow(true)
        .build()

    fun oneShotConstraints(): Constraints = Constraints.Builder()
        .setRequiresBatteryNotLow(true)
        .build()

    fun periodicRequest(): PeriodicWorkRequest =
        PeriodicWorkRequestBuilder<IndexWorker>(6, TimeUnit.HOURS, 1, TimeUnit.HOURS)
            .setConstraints(periodicConstraints())
            .build()

    fun oneShotRequest(): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<IndexWorker>()
            .setConstraints(oneShotConstraints())
            .build()
}

/**
 * Local Room FTS index: full scans in WorkManager ([IndexWorker]) + incremental updates driven
 * by [FileSystems.changes]. All I/O runs on Dispatchers.IO.
 */
class SearchIndexer(
    private val context: Context,
    private val db: FichioDatabase,
    private val fs: FileSystems,
    private val scope: CoroutineScope,
) {
    private val dao get() = db.fileIndex()
    private val settings get() = context.container.settings
    private val pending = ConcurrentHashMap.newKeySet<String>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val scanLock = Mutex()
    @Volatile private var started = false

    private val _reindexed = kotlinx.coroutines.flow.MutableSharedFlow<Collection<String>>(extraBufferCapacity = 16)
    /** Folders just re-listed after an in-app change (drives the on-the-fly content index). */
    val reindexed: kotlinx.coroutines.flow.SharedFlow<Collection<String>> = _reindexed

    /** Starts listening to in-app changes and schedules the periodic full scan. */
    fun start() {
        if (started) return
        started = true
        // (a) incremental re-index of touched folders, debounced and coalesced.
        scope.launch(Dispatchers.IO) {
            fs.changes.collect { change ->
                pending.addAll(change.dirs)
                wake.trySend(Unit)
            }
        }
        scope.launch(Dispatchers.IO) {
            for (signal in wake) {
                delay(DEBOUNCE_MS)
                val dirs = pending.toList().also { pending.removeAll(it.toSet()) }
                if (dirs.isEmpty()) continue
                runCatching {
                    val s = settings.current()
                    if (s.searchIndexEnabled) {
                        reindexDirs(dirs, s.showHidden)
                        _reindexed.tryEmit(dirs)
                    }
                }.onFailure { Log.w(TAG, "Incremental index failed", it) }
            }
        }
        // (b) WorkManager scheduling follows the user setting.
        scope.launch(Dispatchers.IO) {
            settings.settings.map { it.searchIndexEnabled }.distinctUntilChanged().collect { enabled ->
                runCatching {
                    val wm = WorkManager.getInstance(context)
                    if (enabled) {
                        schedulePeriodic(wm)
                        if (hasStorageAccess() && dao.count() == 0) enqueueOnce(wm, ExistingWorkPolicy.KEEP)
                    } else {
                        wm.cancelUniqueWork(PERIODIC_WORK)
                        wm.cancelUniqueWork(ONCE_WORK)
                    }
                }.onFailure { Log.w(TAG, "Scheduling failed", it) }
            }
        }
    }

    /** Full rescan now (used on first launch after permission grant). */
    fun requestFullScan() {
        runCatching { enqueueOnce(WorkManager.getInstance(context), ExistingWorkPolicy.REPLACE) }
            .onFailure { Log.w(TAG, "Cannot enqueue scan", it) }
    }

    private fun schedulePeriodic(wm: WorkManager) {
        // UPDATE (not KEEP): installs that scheduled the work with older constraints pick up the
        // new ones without losing their period.
        wm.enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.UPDATE, IndexScheduling.periodicRequest())
    }

    private fun enqueueOnce(wm: WorkManager, policy: ExistingWorkPolicy) {
        wm.enqueueUniqueWork(ONCE_WORK, policy, IndexScheduling.oneShotRequest())
    }

    private fun hasStorageAccess(): Boolean = runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)

    private fun volumeRoots(): List<String> =
        runCatching { context.container.volumes.refresh().map { it.path } }.getOrNull()?.ifEmpty { null }
            ?: listOf(StoragePaths.primary)

    /**
     * Walks every mounted volume and refreshes the index. Rows not seen by this scan are purged
     * at the end. Returns the number of indexed entries, or -1 when skipped.
     */
    suspend fun runFullScan(): Int = scanLock.withLock {
        val s = settings.current()
        if (!s.searchIndexEnabled || !hasStorageAccess()) return -1
        val generation = System.currentTimeMillis()
        val roots = volumeRoots()
        var total = 0
        for (root in roots) {
            val batch = ArrayList<FileIndexEntity>(BATCH)
            val ok = runCatching {
                total += walk(root, roots, s.showHidden, generation, batch)
                flush(batch)
            }.isSuccess
            // Purge only after a complete walk, otherwise an interrupted scan would empty the index.
            if (ok) dao.purgeStale(root, generation)
        }
        settings.setLastIndexRun(System.currentTimeMillis())
        total
    }

    /** Iterative DFS under [start] (not included), upserting by batches. */
    private suspend fun walk(
        start: String,
        roots: List<String>,
        showHidden: Boolean,
        generation: Long,
        batch: ArrayList<FileIndexEntity>,
    ): Int {
        var count = 0
        val stack = ArrayDeque<String>().apply { addLast(start) }
        while (stack.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val dir = stack.removeLast()
            val children = runCatching { fs.local.list(dir) }.getOrNull() ?: continue
            for (c in children) {
                if (IndexRules.isExcluded(c.path, roots, showHidden)) continue
                batch += c.toIndex(generation)
                count++
                if (batch.size >= BATCH) flush(batch)
                if (c.isDirectory) stack.addLast(c.path)
            }
        }
        return count
    }

    private suspend fun flush(batch: ArrayList<FileIndexEntity>) {
        if (batch.isEmpty()) return
        dao.upsertAll(ArrayList(batch))
        batch.clear()
    }

    /** Re-lists [dirs] (non recursive), drops vanished rows and walks newly appeared folders. */
    internal suspend fun reindexDirs(dirs: Collection<String>, showHidden: Boolean) {
        val roots = volumeRoots()
        val generation = System.currentTimeMillis()
        // Parents first so that a deleted tree is removed once.
        for (dir in dirs.map { it.trimEnd('/') }.filter { it.isNotEmpty() }.distinct().sortedBy { it.length }) {
            currentCoroutineContext().ensureActive()
            if (StoragePaths.isRestricted(dir) || IndexRules.isExcluded(dir, roots, showHidden)) continue
            val stat = fs.local.stat(dir)
            if (stat == null || !stat.isDirectory) {
                dao.deleteTree(dir)
                continue
            }
            val children = runCatching { fs.local.list(dir) }.getOrNull() ?: continue
            val kept = children.filterNot { IndexRules.isExcluded(it.path, roots, showHidden) }
            val existing = dao.search(
                SimpleSQLiteQuery(
                    "SELECT path, name, parent, extension, type, isDirectory, size, modified FROM file_index WHERE parent = ?",
                    arrayOf(dir),
                ),
            ).associateBy { it.path }
            val batch = ArrayList<FileIndexEntity>(BATCH)
            kept.forEach {
                batch += it.toIndex(generation)
                if (batch.size >= BATCH) flush(batch)
            }
            flush(batch)
            val now = kept.mapTo(HashSet()) { it.path }
            existing.keys.filter { it !in now }.forEach { dao.deleteTree(it) }
            // A folder that just appeared was probably moved/copied/extracted here: index its tree.
            kept.filter { it.isDirectory && (existing[it.path]?.isDirectory != true) }.forEach { newDir ->
                walk(newDir.path, roots, showHidden, generation, batch)
                flush(batch)
            }
        }
    }

    companion object {
        private const val TAG = "SearchIndexer"
        const val PERIODIC_WORK = "fichio-index-periodic"
        const val ONCE_WORK = "fichio-index-once"
        private const val BATCH = 500
        private const val DEBOUNCE_MS = 250L

        fun FileEntry.toIndex(generation: Long) = FileIndexEntity(
            path = path,
            name = name,
            parent = parentPath,
            extension = extension,
            type = type.name,
            isDirectory = isDirectory,
            size = size,
            modified = lastModified,
            generation = generation,
        )
    }
}
