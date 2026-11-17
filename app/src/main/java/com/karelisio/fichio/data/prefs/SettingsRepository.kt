package com.karelisio.fichio.data.prefs

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.karelisio.fichio.core.model.SortBy
import com.karelisio.fichio.core.model.SortSpec
import com.karelisio.fichio.core.model.ViewMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "fichio_settings")

enum class ThemeMode { SYSTEM, LIGHT, DARK }

@Immutable
data class FichioSettings(
    val onboardingDone: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    /** Default view/sort for folders without per-folder settings. */
    val defaultViewMode: ViewMode = ViewMode.LIST,
    val defaultSort: SortSpec = SortSpec(),
    val showHidden: Boolean = false,
    val trashEnabled: Boolean = true,
    val trashRetentionDays: Int = 30,
    val openImagesInPolaro: Boolean = false,
    val largeFileThresholdMb: Int = 100,
    val oldFileThresholdDays: Int = 365,
    val confirmDelete: Boolean = true,
    val haptics: Boolean = true,
    /** Extra animation toggle on top of the system "remove animations" setting. */
    val reduceMotion: Boolean = false,
    val autoCheckUpdates: Boolean = true,
    val lastUpdateCheck: Long = 0L,
    val skippedVersion: String = "",
    val transferPort: Int = 8080,
    val transferIdleMinutes: Int = 10,
    val dualPaneInLandscape: Boolean = true,
    val searchIndexEnabled: Boolean = true,
    val lastIndexRun: Long = 0L,
)

class SettingsRepository(private val context: Context) {
    private object K {
        val onboarding = booleanPreferencesKey("onboarding_done")
        val theme = stringPreferencesKey("theme_mode")
        val dynamic = booleanPreferencesKey("dynamic_color")
        val view = stringPreferencesKey("default_view")
        val sortBy = stringPreferencesKey("default_sort_by")
        val sortAsc = booleanPreferencesKey("default_sort_asc")
        val foldersFirst = booleanPreferencesKey("folders_first")
        val hidden = booleanPreferencesKey("show_hidden")
        val trash = booleanPreferencesKey("trash_enabled")
        val retention = intPreferencesKey("trash_retention_days")
        val polaro = booleanPreferencesKey("open_images_polaro")
        val largeMb = intPreferencesKey("large_file_threshold_mb")
        val oldDays = intPreferencesKey("old_file_threshold_days")
        val confirmDelete = booleanPreferencesKey("confirm_delete")
        val haptics = booleanPreferencesKey("haptics")
        val reduceMotion = booleanPreferencesKey("reduce_motion")
        val autoUpdate = booleanPreferencesKey("auto_check_updates")
        val lastUpdate = longPreferencesKey("last_update_check")
        val skipped = stringPreferencesKey("skipped_version")
        val port = intPreferencesKey("transfer_port")
        val idle = intPreferencesKey("transfer_idle_minutes")
        val dualPane = booleanPreferencesKey("dual_pane")
        val index = booleanPreferencesKey("search_index")
        val lastIndex = longPreferencesKey("last_index_run")
    }

    val settings: Flow<FichioSettings> = context.dataStore.data.map { p ->
        val d = FichioSettings()
        FichioSettings(
            onboardingDone = p[K.onboarding] ?: d.onboardingDone,
            themeMode = p[K.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: d.themeMode,
            dynamicColor = p[K.dynamic] ?: d.dynamicColor,
            defaultViewMode = p[K.view]?.let { runCatching { ViewMode.valueOf(it) }.getOrNull() } ?: d.defaultViewMode,
            defaultSort = SortSpec(
                by = p[K.sortBy]?.let { runCatching { SortBy.valueOf(it) }.getOrNull() } ?: SortBy.NAME,
                ascending = p[K.sortAsc] ?: true,
                foldersFirst = p[K.foldersFirst] ?: true,
            ),
            showHidden = p[K.hidden] ?: d.showHidden,
            trashEnabled = p[K.trash] ?: d.trashEnabled,
            trashRetentionDays = p[K.retention] ?: d.trashRetentionDays,
            openImagesInPolaro = p[K.polaro] ?: d.openImagesInPolaro,
            largeFileThresholdMb = p[K.largeMb] ?: d.largeFileThresholdMb,
            oldFileThresholdDays = p[K.oldDays] ?: d.oldFileThresholdDays,
            confirmDelete = p[K.confirmDelete] ?: d.confirmDelete,
            haptics = p[K.haptics] ?: d.haptics,
            reduceMotion = p[K.reduceMotion] ?: d.reduceMotion,
            autoCheckUpdates = p[K.autoUpdate] ?: d.autoCheckUpdates,
            lastUpdateCheck = p[K.lastUpdate] ?: d.lastUpdateCheck,
            skippedVersion = p[K.skipped] ?: d.skippedVersion,
            transferPort = p[K.port] ?: d.transferPort,
            transferIdleMinutes = p[K.idle] ?: d.transferIdleMinutes,
            dualPaneInLandscape = p[K.dualPane] ?: d.dualPaneInLandscape,
            searchIndexEnabled = p[K.index] ?: d.searchIndexEnabled,
            lastIndexRun = p[K.lastIndex] ?: d.lastIndexRun,
        )
    }

    suspend fun current(): FichioSettings = settings.first()

    suspend fun setOnboardingDone(v: Boolean) = context.dataStore.edit { it[K.onboarding] = v }
    suspend fun setThemeMode(v: ThemeMode) = context.dataStore.edit { it[K.theme] = v.name }
    suspend fun setDynamicColor(v: Boolean) = context.dataStore.edit { it[K.dynamic] = v }
    suspend fun setDefaultViewMode(v: ViewMode) = context.dataStore.edit { it[K.view] = v.name }
    suspend fun setDefaultSort(v: SortSpec) = context.dataStore.edit {
        it[K.sortBy] = v.by.name; it[K.sortAsc] = v.ascending; it[K.foldersFirst] = v.foldersFirst
    }
    suspend fun setShowHidden(v: Boolean) = context.dataStore.edit { it[K.hidden] = v }
    suspend fun setTrashEnabled(v: Boolean) = context.dataStore.edit { it[K.trash] = v }
    suspend fun setTrashRetentionDays(v: Int) = context.dataStore.edit { it[K.retention] = v.coerceIn(1, 365) }
    suspend fun setOpenImagesInPolaro(v: Boolean) = context.dataStore.edit { it[K.polaro] = v }
    suspend fun setLargeFileThresholdMb(v: Int) = context.dataStore.edit { it[K.largeMb] = v.coerceAtLeast(1) }
    suspend fun setOldFileThresholdDays(v: Int) = context.dataStore.edit { it[K.oldDays] = v.coerceAtLeast(1) }
    suspend fun setConfirmDelete(v: Boolean) = context.dataStore.edit { it[K.confirmDelete] = v }
    suspend fun setHaptics(v: Boolean) = context.dataStore.edit { it[K.haptics] = v }
    suspend fun setReduceMotion(v: Boolean) = context.dataStore.edit { it[K.reduceMotion] = v }
    suspend fun setAutoCheckUpdates(v: Boolean) = context.dataStore.edit { it[K.autoUpdate] = v }
    suspend fun setLastUpdateCheck(v: Long) = context.dataStore.edit { it[K.lastUpdate] = v }
    suspend fun setSkippedVersion(v: String) = context.dataStore.edit { it[K.skipped] = v }
    suspend fun setTransferPort(v: Int) = context.dataStore.edit { it[K.port] = v.coerceIn(1024, 65535) }
    suspend fun setTransferIdleMinutes(v: Int) = context.dataStore.edit { it[K.idle] = v.coerceIn(1, 120) }
    suspend fun setDualPaneInLandscape(v: Boolean) = context.dataStore.edit { it[K.dualPane] = v }
    suspend fun setSearchIndexEnabled(v: Boolean) = context.dataStore.edit { it[K.index] = v }
    suspend fun setLastIndexRun(v: Long) = context.dataStore.edit { it[K.lastIndex] = v }
}
