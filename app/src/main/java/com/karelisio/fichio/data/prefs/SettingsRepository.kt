package com.karelisio.fichio.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.fichioDataStore: DataStore<Preferences> by preferencesDataStore(name = "fichio_prefs")

/** User preferences, persisted in DataStore. */
data class FichioSettings(
    val dynamicColor: Boolean = true,
    val trashRetentionDays: Int = 30,
    val largeFileThresholdMb: Int = 100,
    val openImagesInPolaro: Boolean = true,
    val serverAutoStopMinutes: Int = 10,
    val onboardingDone: Boolean = false,
    val lastUpdateCheck: Long = 0L
)

class SettingsRepository(private val context: Context) {

    private object Keys {
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val TRASH_RETENTION_DAYS = intPreferencesKey("trash_retention_days")
        val LARGE_FILE_THRESHOLD_MB = intPreferencesKey("large_file_threshold_mb")
        val OPEN_IN_POLARO = booleanPreferencesKey("open_in_polaro")
        val SERVER_AUTO_STOP_MINUTES = intPreferencesKey("server_auto_stop_minutes")
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        val LAST_UPDATE_CHECK = longPreferencesKey("last_update_check")
    }

    val settings: Flow<FichioSettings> = context.fichioDataStore.data.map { p ->
        FichioSettings(
            dynamicColor = p[Keys.DYNAMIC_COLOR] ?: true,
            trashRetentionDays = p[Keys.TRASH_RETENTION_DAYS] ?: 30,
            largeFileThresholdMb = p[Keys.LARGE_FILE_THRESHOLD_MB] ?: 100,
            openImagesInPolaro = p[Keys.OPEN_IN_POLARO] ?: true,
            serverAutoStopMinutes = p[Keys.SERVER_AUTO_STOP_MINUTES] ?: 10,
            onboardingDone = p[Keys.ONBOARDING_DONE] ?: false,
            lastUpdateCheck = p[Keys.LAST_UPDATE_CHECK] ?: 0L
        )
    }

    suspend fun setDynamicColor(value: Boolean) = context.fichioDataStore.edit { it[Keys.DYNAMIC_COLOR] = value }
    suspend fun setTrashRetentionDays(value: Int) = context.fichioDataStore.edit { it[Keys.TRASH_RETENTION_DAYS] = value }
    suspend fun setLargeFileThresholdMb(value: Int) = context.fichioDataStore.edit { it[Keys.LARGE_FILE_THRESHOLD_MB] = value }
    suspend fun setOpenInPolaro(value: Boolean) = context.fichioDataStore.edit { it[Keys.OPEN_IN_POLARO] = value }
    suspend fun setServerAutoStopMinutes(value: Int) = context.fichioDataStore.edit { it[Keys.SERVER_AUTO_STOP_MINUTES] = value }
    suspend fun setOnboardingDone(value: Boolean) = context.fichioDataStore.edit { it[Keys.ONBOARDING_DONE] = value }
    suspend fun setLastUpdateCheck(value: Long) = context.fichioDataStore.edit { it[Keys.LAST_UPDATE_CHECK] = value }
}
