package com.karelisio.fichio.ui.browser

import com.karelisio.fichio.core.util.Strings
import com.karelisio.fichio.R
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.karelisio.fichio.core.fs.FileSystems
import com.karelisio.fichio.core.fs.ShizukuUnavailableException
import com.karelisio.fichio.core.fs.StoragePaths
import com.karelisio.fichio.core.model.FileEntry
import com.karelisio.fichio.core.model.SortSpec
import com.karelisio.fichio.core.model.ViewMode
import com.karelisio.fichio.core.util.Sorting
import com.karelisio.fichio.data.db.FolderSettingsDao
import com.karelisio.fichio.data.db.FolderSettingsEntity
import com.karelisio.fichio.data.prefs.FichioSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Result of listing one folder. */
@Immutable
sealed interface Listing {
    data object Loading : Listing
    data class Loaded(
        val entries: List<FileEntry>,
        val folders: Int,
        val files: Int,
        val totalFileSize: Long,
        val hiddenCount: Int,
    ) : Listing
    data class Error(val message: String, val restricted: Boolean) : Listing
}

@Immutable
data class FolderView(val mode: ViewMode, val sort: SortSpec, val custom: Boolean)

/**
 * State of one browser pane (a tab): a stack of folders for back navigation "folder by folder",
 * cached listings (so going back is instant and the drawer animation has both sides), per-folder
 * view settings, scroll positions and the multi-selection.
 */
@Stable
class PaneState(
    val id: Long,
    initialPath: String,
    private val scope: CoroutineScope,
    private val fs: FileSystems,
    private val folderDao: FolderSettingsDao,
    private val settingsProvider: () -> FichioSettings,
) {
    /** Navigation stack; the last element is the current folder. */
    var stack by mutableStateOf(listOf(initialPath))
        private set

    val path: String get() = stack.last()
    val canGoBack: Boolean get() = stack.size > 1
    val previousPath: String? get() = stack.getOrNull(stack.size - 2)

    /** Listings cache (path → listing). Snapshot-backed so the UI recomposes on load. */
    private val listings = mutableStateMapOf<String, Listing>()
    private val views = mutableStateMapOf<String, FolderView>()
    private val loadJobs = HashMap<String, Job>()

    /** Remembered scroll positions per folder (restored on back). */
    private val listStates = HashMap<String, LazyListState>()
    private val gridStates = HashMap<String, LazyGridState>()

    /** Selected paths of the *current* folder. */
    val selection = mutableStateMapOf<String, FileEntry>()
    // Derived: readers (top bar, FAB, back handlers) are invalidated only when selection mode
    // starts or ends, not on every toggled item.
    private val inSelectionState = derivedStateOf { selection.isNotEmpty() }
    val inSelection: Boolean get() = inSelectionState.value

    /** Folder that just received files (drives the spring "pop" of its header). */
    var receivedTick by mutableStateOf(0)

    init {
        load(initialPath)
    }

    fun listing(path: String): Listing = listings[path] ?: Listing.Loading

    fun view(path: String): FolderView {
        views[path]?.let { return it }
        val s = settingsProvider()
        return FolderView(s.defaultViewMode, s.defaultSort, custom = false)
    }

    fun listState(path: String): LazyListState = listStates.getOrPut(path) { LazyListState() }
    /** Grid scroll state; the large-thumbnail grid has its own so both can live during the pinch transition. */
    fun gridState(path: String, large: Boolean = false): LazyGridState =
        gridStates.getOrPut(if (large) "$path#large" else path) { LazyGridState() }

    /** First visible index (header = 0) of [path] in [mode]. */
    fun firstVisible(path: String, mode: ViewMode): Int = when (mode) {
        ViewMode.LIST -> listState(path).firstVisibleItemIndex
        ViewMode.GRID -> gridState(path).firstVisibleItemIndex
        ViewMode.LARGE -> gridState(path, large = true).firstVisibleItemIndex
    }

    // ---- Navigation -------------------------------------------------------------------------

    fun open(path: String) {
        if (path == this.path) return
        selection.clear()
        val idx = stack.indexOf(path)
        stack = if (idx >= 0) stack.subList(0, idx + 1).toList() else stack + path
        trimCaches()
        load(path)
    }

    /** Breadcrumb jump: ancestors pop the stack, anything else replaces it. */
    fun jumpTo(path: String) {
        if (path == this.path) return
        selection.clear()
        val idx = stack.indexOf(path)
        stack = when {
            idx >= 0 -> stack.subList(0, idx + 1).toList()
            this.path.startsWith("$path/") -> listOf(path)
            else -> listOf(path)
        }
        load(path)
    }

    fun back(): Boolean {
        if (!canGoBack) return false
        selection.clear()
        stack = stack.dropLast(1)
        load(path, silent = true)
        return true
    }

    /** Replaces the whole stack (tab restore, shortcut). */
    fun reset(path: String) {
        selection.clear()
        stack = listOf(path)
        load(path)
    }

    // ---- Loading ----------------------------------------------------------------------------

    fun refresh() = load(path, silent = true)

    fun load(path: String, silent: Boolean = false) {
        loadJobs[path]?.cancel()
        if (!silent || listings[path] == null || listings[path] is Listing.Error) {
            if (listings[path] !is Listing.Loaded) listings[path] = Listing.Loading
        }
        loadJobs[path] = scope.launch {
            val saved = withContext(Dispatchers.IO) { runCatching { folderDao.get(path) }.getOrNull() }
            if (saved != null) {
                views[path] = FolderView(
                    runCatching { ViewMode.valueOf(saved.viewMode) }.getOrDefault(ViewMode.LIST),
                    SortSpec(
                        runCatching { com.karelisio.fichio.core.model.SortBy.valueOf(saved.sortBy) }.getOrDefault(com.karelisio.fichio.core.model.SortBy.NAME),
                        saved.ascending, saved.foldersFirst,
                    ),
                    custom = true,
                )
            }
            listings[path] = withContext(Dispatchers.IO) { readListing(path) }
            // Keep selection consistent with what still exists.
            if (path == this@PaneState.path) {
                val loaded = listings[path] as? Listing.Loaded
                if (loaded != null && selection.isNotEmpty()) {
                    val present = loaded.entries.associateBy { it.path }
                    selection.keys.toList().forEach { if (it !in present) selection.remove(it) }
                }
            }
        }
    }

    private suspend fun readListing(path: String): Listing = try {
        val showHidden = settingsProvider().showHidden
        val all = fs.list(path)
        val visible = all.filter { (showHidden || !it.isHidden) && !(StoragePaths.isInsideFichioDir(it.path) && !showHidden) }
        val sorted = Sorting.sort(visible, view(path).sort)
        var folders = 0
        var size = 0L
        for (e in sorted) if (e.isDirectory) folders++ else size += e.size
        Listing.Loaded(sorted, folders, sorted.size - folders, size, all.size - visible.size)
    } catch (e: ShizukuUnavailableException) {
        Listing.Error(e.message ?: Strings.get(R.string.shizuku_inactive), restricted = true)
    } catch (e: Exception) {
        Listing.Error(e.message ?: Strings.get(R.string.browser_open_folder_failed), restricted = StoragePaths.isRestricted(path))
    }

    private fun trimCaches() {
        // Keep the stack + a few recent folders.
        val keep = stack.toSet()
        if (listings.size > 24) listings.keys.filter { it !in keep }.take(listings.size - 24).forEach { listings.remove(it) }
    }

    // ---- View settings ----------------------------------------------------------------------

    fun setView(mode: ViewMode? = null, sort: SortSpec? = null) {
        val p = path
        val current = view(p)
        val next = FolderView(mode ?: current.mode, sort ?: current.sort, custom = true)
        if (next.mode != current.mode) {
            // Keep the same items on screen when switching view (menu or pinch).
            val anchor = firstVisible(p, current.mode)
            when (next.mode) {
                ViewMode.LIST -> listState(p).requestScrollToItem(anchor)
                ViewMode.GRID -> gridState(p).requestScrollToItem(anchor)
                ViewMode.LARGE -> gridState(p, large = true).requestScrollToItem(anchor)
            }
        }
        views[p] = next
        (listings[p] as? Listing.Loaded)?.let { l -> listings[p] = l.copy(entries = Sorting.sort(l.entries, next.sort)) }
        scope.launch(Dispatchers.IO) {
            folderDao.upsert(FolderSettingsEntity(p, next.mode.name, next.sort.by.name, next.sort.ascending, next.sort.foldersFirst))
        }
    }

    // ---- Selection --------------------------------------------------------------------------

    fun toggle(entry: FileEntry) {
        if (selection.containsKey(entry.path)) selection.remove(entry.path) else selection[entry.path] = entry
    }

    fun select(entry: FileEntry) { selection[entry.path] = entry }

    fun selectAll() {
        (listing(path) as? Listing.Loaded)?.entries?.forEach { selection[it.path] = it }
    }

    fun invertSelection() {
        val all = (listing(path) as? Listing.Loaded)?.entries ?: return
        val current = selection.keys.toSet()
        selection.clear()
        all.filter { it.path !in current }.forEach { selection[it.path] = it }
    }

    fun clearSelection() = selection.clear()

    /** Range selection between two indexes of the current listing (drag after long press). */
    fun selectRange(base: Set<String>, from: Int, to: Int) {
        val entries = (listing(path) as? Listing.Loaded)?.entries ?: return
        val lo = minOf(from, to).coerceAtLeast(0)
        val hi = maxOf(from, to).coerceAtMost(entries.lastIndex)
        val range = if (lo <= hi) entries.subList(lo, hi + 1) else emptyList()
        val wanted = base + range.map { it.path }
        selection.keys.toList().forEach { if (it !in wanted) selection.remove(it) }
        entries.forEach { if (it.path in wanted && !selection.containsKey(it.path)) selection[it.path] = it }
    }

    fun selectedEntries(): List<FileEntry> = selection.values.toList()
}
