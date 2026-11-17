package com.karelisio.fichio.trash

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.karelisio.fichio.container
import java.util.concurrent.TimeUnit

/** Daily purge of trash items older than the retention set in the settings. */
class TrashPurgeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = runCatching {
        val c = applicationContext.container
        val settings = c.settings.current()
        c.trash.purgeOlderThan(settings.trashRetentionDays)
        Result.success()
    }.getOrElse { Result.retry() }

    companion object {
        private const val WORK_NAME = "fichio_trash_purge"

        /** Schedules the periodic purge (no-op when already scheduled). */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<TrashPurgeWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .setInitialDelay(15, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
