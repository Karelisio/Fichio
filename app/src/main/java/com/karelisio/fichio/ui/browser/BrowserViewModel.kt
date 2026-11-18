package com.karelisio.fichio.ui.browser

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.karelisio.fichio.container
import com.karelisio.fichio.core.fs.StoragePaths
import com.karelisio.fichio.data.db.TabEntity
import com.karelisio.fichio.data.prefs.FichioSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val MAX_TABS = 5

/** Holds the tabs (each a [PaneState]) and the secondary pane of the landscape dual view. */
class BrowserViewModel(app: Application) : AndroidViewModel(app) {
    private val c = app.container
    private val db = c.database

    val settings: StateFlow<FichioSettings> = c.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, FichioSettings())
    val favorites = c.favorites.favorites.map { list -> list.map { it.path }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())
    val volumes = c.volumes.volumes
    /** Path → colour tag slots, shared by every pane. */
    val tagSlots = c.tags.slotsByPath
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val tabs = mutableStateListOf<PaneState>()
    var selectedTab by mutableIntStateOf(0)
        private set

    /** Right pane in dual mode (independent from tabs). */
    var secondary: PaneState? by mutableStateOf(null)
        private set

    /** Which pane receives actions in dual mode: 0 = main (tab), 1 = secondary. */
    var activePane by mutableIntStateOf(0)

    private var nextId = 1L
    private var restored = false

    val current: PaneState? get() = tabs.getOrNull(selectedTab)

    fun activeState(dual: Boolean): PaneState? = if (dual && activePane == 1) secondary ?: current else current

    private fun newPane(path: String) = PaneState(nextId++, path, viewModelScope, c.fileSystems, db.folderSettings()) { settings.value }

    /** Restores persisted tabs, then applies the requested path (shortcut, home tile…). */
    fun init(initialPath: String?, newTab: Boolean) {
        // The route's path is applied once per browser entry. The screen re-enters composition
        // when coming back from a preview: re-applying it would jump back to the start folder.
        if (restored) return
        restored = true
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) { runCatching { db.tabs().all() }.getOrDefault(emptyList()) }
            if (saved.isNotEmpty() && initialPath == null) {
                saved.take(MAX_TABS).forEach { tabs += newPane(it.path) }
                selectedTab = saved.indexOfFirst { it.selected }.coerceAtLeast(0).coerceAtMost(tabs.lastIndex)
            } else if (saved.isNotEmpty() && newTab) {
                saved.take(MAX_TABS - 1).forEach { tabs += newPane(it.path) }
                tabs += newPane(initialPath!!)
                selectedTab = tabs.lastIndex
            } else {
                tabs += newPane(initialPath ?: StoragePaths.primary)
                selectedTab = 0
            }
            persist()
        }
        viewModelScope.launch {
            settings.map { it.showHidden }.distinctUntilChanged().drop(1).collect {
                (tabs + listOfNotNull(secondary)).forEach { it.refresh() }
            }
        }
        viewModelScope.launch {
            c.fileSystems.changes.collect { change ->
                (tabs + listOfNotNull(secondary)).forEach { pane ->
                    if (change.affects(pane.path)) pane.refresh()
                }
            }
        }
    }

    fun openPath(path: String, inNewTab: Boolean) {
        if (inNewTab && tabs.size < MAX_TABS) {
            tabs += newPane(path); selectedTab = tabs.lastIndex
        } else {
            current?.open(path) ?: run { tabs += newPane(path); selectedTab = 0 }
        }
        persist()
    }

    fun addTab(path: String = current?.path ?: StoragePaths.primary): Boolean {
        if (tabs.size >= MAX_TABS) return false
        tabs += newPane(path)
        selectedTab = tabs.lastIndex
        persist()
        return true
    }

    fun closeTab(index: Int) {
        if (tabs.size <= 1 || index !in tabs.indices) return
        tabs.removeAt(index)
        selectedTab = selectedTab.coerceAtMost(tabs.lastIndex).let { if (index < it) it - 1 else it }.coerceAtLeast(0)
        persist()
    }

    fun selectTab(index: Int) {
        if (index in tabs.indices) { selectedTab = index; activePane = 0; persist() }
    }

    fun ensureSecondary() {
        if (secondary == null) {
            val other = tabs.getOrNull(if (selectedTab == 0) 1 else 0)?.path ?: StoragePaths.downloads
            secondary = newPane(other)
        }
    }

    /** Persist tabs (paths only) so they come back at next launch. */
    fun persist() {
        val snapshot = tabs.mapIndexed { i, p -> TabEntity(p.id, p.path, i, i == selectedTab) }
        viewModelScope.launch(Dispatchers.IO) { runCatching { db.tabs().replaceAll(snapshot) } }
    }

    fun toggleFavorite(path: String) = viewModelScope.launch { c.favorites.toggle(path) }
    fun pin(path: String) = viewModelScope.launch { c.favorites.pin(path) }
    fun setShowHidden(v: Boolean) = viewModelScope.launch { c.settings.setShowHidden(v) }
}
