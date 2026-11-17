package com.karelisio.fichio.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.karelisio.fichio.FichioApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Deletes trash items older than the configured retention window (default 30
 * days). Runs periodically via WorkManager and re-scheduled on boot.
 */
class TrashPurgeWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as FichioApp
        val container = app.container
        val retentionDays = runCatching {
            container.settingsRepository.settings.first().trashRetentionDays
        }.getOrDefault(DEFAULT_RETENTION_DAYS)

        val cutoff = System.currentTimeMillis() - retentionDays * DAY_MILLIS
        val stale = container.database.trashDao().olderThan(cutoff)
        if (stale.isEmpty()) return Result.success()

        val trashDir = File(app.filesDir, TRASH_DIR_NAME)
        withContext(Dispatchers.IO) {
            stale.forEach { item ->
                runCatching { File(trashDir, item.id.toString()).deleteRecursively() }
            }
        }
        container.database.trashDao().deleteAll(stale.map { it.id })
        return Result.success()
    }

    companion object {
        const val UNIQUE_NAME = "fichio_trash_purge"
        const val TRASH_DIR_NAME = "trash"
        private const val DEFAULT_RETENTION_DAYS = 30
        private const val DAY_MILLIS = 24L * 60L * 60L * 1000L
    }
}
