package com.karelisio.fichio.worker

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** Central place to register recurring background work. */
object WorkScheduler {

    fun schedule(context: Context) {
        val trash = PeriodicWorkRequestBuilder<TrashPurgeWorker>(1, TimeUnit.DAYS).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            TrashPurgeWorker.UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            trash
        )

        val index = PeriodicWorkRequestBuilder<SearchIndexWorker>(1, TimeUnit.DAYS).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            SearchIndexWorker.UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            index
        )
    }

    /** Runs a single index rebuild now (e.g. right after onboarding). */
    fun runIndexNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<SearchIndexWorker>().build()
        WorkManager.getInstance(context).enqueue(request)
    }
}
