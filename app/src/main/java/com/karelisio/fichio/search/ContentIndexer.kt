package com.karelisio.fichio.search

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.graphics.pdf.PdfRendererPreV
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.ext.SdkExtensions
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresExtension
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.karelisio.fichio.container
import com.karelisio.fichio.core.model.FileType
import com.karelisio.fichio.data.db.FichioDatabase
import com.karelisio.fichio.data.db.IndexedFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Text extraction from PDFs with the platform renderer (no third-party library):
 * `PdfRenderer.Page.getTextContents()` on Android 15+, `PdfRendererPreV` on Android 12–14 with
 * the S extension 13. Older systems: PDFs are not content-indexed.
 *
 * pdfbox-android was considered and not used: its last release (2.0.27.0) dates from January
 * 2023 and pulls BouncyCastle 1.72, which has known vulnerabilities.
 */
object PdfText {
    val supported: Boolean by lazy {
        Build.VERSION.SDK_INT >= 35 ||
            (Build.VERSION.SDK_INT >= 31 && runCatching { SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 13 }.getOrDefault(false))
    }

    @SuppressLint("NewApi")
    fun extract(file: File, maxChars: Int = ContentRules.MAX_PDF_CHARS, maxPages: Int = ContentRules.MAX_PDF_PAGES): String? {
        if (!supported) return null
        return runCatching {
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val raw = if (Build.VERSION.SDK_INT >= 35) modern(pfd, maxChars, maxPages) else legacy(pfd, maxChars, maxPages)
            raw?.let { ContentRules.clean(it, maxChars) }
        }.onFailure { if (it is CancellationException) throw it }.getOrNull()
    }

    @RequiresApi(35)
    private fun modern(pfd: ParcelFileDescriptor, maxChars: Int, maxPages: Int): String? =
        PdfRenderer(pfd).use { r ->
            val sb = StringBuilder()
            for (i in 0 until minOf(r.pageCount, maxPages)) {
                if (sb.length >= maxChars) break
                r.openPage(i).use { p -> p.textContents.forEach { sb.append(it.text).append(' ') } }
            }
            sb.toString()
        }

    @RequiresExtension(extension = Build.VERSION_CODES.S, version = 13)
    private fun legacy(pfd: ParcelFileDescriptor, maxChars: Int, maxPages: Int): String? =
        PdfRendererPreV(pfd).use { r ->
            val sb = StringBuilder()
            for (i in 0 until minOf(r.pageCount, maxPages)) {
                if (sb.length >= maxChars) break
                r.openPage(i).use { p -> p.textContents.forEach { sb.append(it.text).append(' ') } }
            }
            sb.toString()
        }
}

/** Remembers whether "Rechercher aussi dans le contenu" is on (private SharedPreferences). */
class ContentSearchPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("fichio_content_search", Context.MODE_PRIVATE)
    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY, false))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun set(value: Boolean) {
        prefs.edit().putBoolean(KEY, value).apply()
        _enabled.value = value
    }

    private companion object {
        const val KEY = "enabled"
    }
}

/**
 * Content index (table content_fts): filled in the background by [ContentIndexWorker] in
 * batches (battery not low), and on the fly for the folders touched in the app (right after the
 * name index re-listed them). Only active while the search option is on.
 */
class ContentIndexer(
    private val context: Context,
    private val db: FichioDatabase,
    private val scope: CoroutineScope,
    val prefs: ContentSearchPrefs,
    private val pdfSupported: Boolean = PdfText.supported,
) {
    private val dao get() = db.content()
    private val lock = Mutex()
    private val pendingDirs = ConcurrentHashMap.newKeySet<String>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var started = false

    val indexedCount get() = dao.observeIndexedCount()

    /** Hooks the on-the-fly updates and the periodic work. [reindexed] = folders the name index just refreshed. */
    fun start(reindexed: kotlinx.coroutines.flow.Flow<Collection<String>>) {
        if (started) return
        started = true
        scope.launch(Dispatchers.IO) {
            reindexed.collect { dirs ->
                if (prefs.enabled.value) { pendingDirs.addAll(dirs); wake.trySend(Unit) }
            }
        }
        scope.launch(Dispatchers.IO) {
            for (signal in wake) {
                delay(400)
                val dirs = pendingDirs.toList().also { pendingDirs.removeAll(it.toSet()) }
                if (dirs.isEmpty()) continue
                runCatching { indexPending(dirs = dirs, maxFiles = 400) }
                    .onFailure { if (it !is CancellationException) Log.w(TAG, "On-the-fly content index failed", it) }
            }
        }
        scope.launch(Dispatchers.IO) {
            prefs.enabled.collect { on -> runCatching { schedule(on) }.onFailure { Log.w(TAG, "Scheduling failed", it) } }
        }
    }

    fun setEnabled(value: Boolean) = prefs.set(value)

    /** After a full name scan: read what changed. */
    fun onFullScanDone() {
        if (prefs.enabled.value) runCatching { enqueueOnce(WorkManager.getInstance(context)) }
    }

    private fun schedule(enabled: Boolean) {
        val wm = WorkManager.getInstance(context)
        if (!enabled) {
            wm.cancelUniqueWork(PERIODIC_WORK); wm.cancelUniqueWork(ONCE_WORK); return
        }
        val req = PeriodicWorkRequestBuilder<ContentIndexWorker>(12, TimeUnit.HOURS, 2, TimeUnit.HOURS)
            .setConstraints(constraints())
            .build()
        wm.enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP, req)
        enqueueOnce(wm)
    }

    private fun constraints() = Constraints.Builder().setRequiresBatteryNotLow(true).setRequiresStorageNotLow(true).build()

    private fun enqueueOnce(wm: WorkManager) {
        val req = OneTimeWorkRequestBuilder<ContentIndexWorker>().setConstraints(constraints()).build()
        wm.enqueueUniqueWork(ONCE_WORK, ExistingWorkPolicy.KEEP, req)
    }

    /**
     * Reads pending files by batches of [BATCH] until none is left (or [maxFiles] were read).
     * Cancellable between files. Returns the number of files read.
     */
    suspend fun indexPending(dirs: Collection<String>? = null, maxFiles: Int = Int.MAX_VALUE): Int = lock.withLock {
        if (!prefs.enabled.value) return 0
        withContext(Dispatchers.IO) {
            if (dirs == null) dao.purgeOrphans()
            var done = 0
            var lastBatch: List<String> = emptyList()
            while (done < maxFiles) {
                currentCoroutineContext().ensureActive()
                if (!prefs.enabled.value) break
                val q = ContentRules.pendingQuery(minOf(BATCH, maxFiles - done), pdfSupported, dirs)
                val batch = dao.pending(SimpleSQLiteQuery(q.sql, q.argsArray))
                if (batch.isEmpty()) break
                val paths = batch.map { it.path }
                if (paths == lastBatch) break // nothing could be stored: avoid spinning
                lastBatch = paths
                for (f in batch) {
                    currentCoroutineContext().ensureActive()
                    val body = runCatching { read(f) }.onFailure { if (it is CancellationException) throw it }.getOrNull()
                    dao.replace(f.path, f.modified, f.size, body)
                    done++
                }
            }
            done
        }
    }

    private fun read(f: IndexedFile): String? {
        val type = runCatching { FileType.valueOf(f.type) }.getOrDefault(FileType.OTHER)
        val file = File(f.path)
        if (!file.isFile || !file.canRead()) return null
        if (ContentRules.isPdf(type)) return PdfText.extract(file)
        if (!ContentRules.isText(type, f.extension)) return null
        val buf = ByteArray(ContentRules.MAX_TEXT_BYTES)
        var n = 0
        file.inputStream().use { input ->
            while (n < buf.size) {
                val r = input.read(buf, n, buf.size - n)
                if (r < 0) break
                n += r
            }
        }
        return ContentRules.textOf(buf, n, f.extension)
    }

    companion object {
        private const val TAG = "ContentIndexer"
        const val PERIODIC_WORK = "fichio-content-periodic"
        const val ONCE_WORK = "fichio-content-once"
        private const val BATCH = 40
    }
}

/** Background batch of the content index (constraints: battery and storage not low). */
class ContentIndexWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = try {
        applicationContext.container.contentIndexer.indexPending()
        Result.success()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w("ContentIndexWorker", "Content indexing failed", e)
        Result.retry()
    }
}
