package com.karelisio.fichio.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.karelisio.fichio.FichioApp
import com.karelisio.fichio.domain.search.SearchIndexer

/** Rebuilds the Room FTS search index in the background. */
class SearchIndexWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as FichioApp
        return try {
            SearchIndexer.rebuild(app.container.database, app.container.fileSystem.root)
            Result.success()
        } catch (t: Throwable) {
            Result.retry()
        }
    }

    companion object {
        const val UNIQUE_NAME = "fichio_search_index"
    }
}
