package com.karelisio.fichio.ui.home

import android.app.Application
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.karelisio.fichio.container
import com.karelisio.fichio.core.fs.StoragePaths
import com.karelisio.fichio.core.fs.Volume
import com.karelisio.fichio.core.model.FileCategory
import com.karelisio.fichio.core.model.FileEntry
import com.karelisio.fichio.core.model.FileType
import com.karelisio.fichio.data.db.FavoriteEntity
import com.karelisio.fichio.data.db.PinnedEntity
import com.karelisio.fichio.ops.JobState
import com.karelisio.fichio.ops.OperationEvent
import com.karelisio.fichio.shizuku.ShizukuState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Immutable
data class CategoryStat(val category: FileCategory, val count: Int, val size: Long)

@Immutable
data class HomeState(
    val primary: Volume? = null,
    val otherVolumes: List<Volume> = emptyList(),
    val categories: List<CategoryStat> = FileCategory.entries.map { CategoryStat(it, 0, 0) },
    val indexedFiles: Int = -1,
)

class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val c = app.container
    private val dao = c.database.fileIndex()

    private val categoryStats = combine(
        dao.observeTypeStats(),
        dao.observeDirStats(StoragePaths.downloads),
    ) { types, downloads ->
        val byCat = HashMap<FileCategory, Pair<Int, Long>>()
        types.forEach { t ->
            val cat = runCatching { FileType.valueOf(t.type) }.getOrNull()?.category ?: return@forEach
            val (n, s) = byCat[cat] ?: (0 to 0L)
            byCat[cat] = (n + t.count) to (s + t.size)
        }
        FileCategory.entries.map { cat ->
            if (cat == FileCategory.DOWNLOADS) CategoryStat(cat, downloads.count, downloads.size)
            else byCat[cat].let { CategoryStat(cat, it?.first ?: 0, it?.second ?: 0) }
        }
    }

    val state: StateFlow<HomeState> = combine(c.volumes.volumes, categoryStats, dao.observeCount()) { vols, cats, count ->
        HomeState(vols.firstOrNull { it.isPrimary }, vols.filter { !it.isPrimary }, cats, count)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HomeState(primary = c.volumes.primary))

    val recents: StateFlow<List<FileEntry>> = c.recents.recents
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val favorites: StateFlow<List<FavoriteEntity>> = c.favorites.favorites
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val pinned: StateFlow<List<PinnedEntity>> = c.favorites.pinned
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val trashCount: StateFlow<Int> = c.trash.count.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    val shizuku: StateFlow<ShizukuState> = c.shizuku.state
    val busy: StateFlow<Boolean> = c.operations.jobs.map { jobs -> jobs.any { it.state == JobState.RUNNING } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    init {
        // Live gauge: refresh free space while operations run and when they finish.
        viewModelScope.launch {
            busy.collectLatest { running ->
                while (running && isActive) { c.volumes.refresh(); delay(800) }
                c.volumes.refresh()
            }
        }
        viewModelScope.launch {
            c.operations.events.collect { if (it is OperationEvent.Finished) c.volumes.refresh() }
        }
    }

    fun refresh() { c.volumes.refresh() }

    fun reorderFavorites(paths: List<String>) = viewModelScope.launch { c.favorites.reorder(paths) }
    fun removeFavorite(path: String) = viewModelScope.launch { c.favorites.remove(path) }
    fun unpin(path: String) = viewModelScope.launch { c.favorites.unpin(path) }
}
