package com.karelisio.fichio.search

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.karelisio.fichio.container
import kotlinx.coroutines.CancellationException

/** Periodic / one-shot full scan of the storage into the search index. */
class IndexWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = try {
        val indexed = applicationContext.container.indexer.runFullScan()
        if (indexed >= 0) runCatching { applicationContext.container.contentIndexer.onFullScanDone() }
        Result.success()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w("IndexWorker", "Scan failed", e)
        Result.retry()
    }
}
