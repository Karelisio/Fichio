package com.karelisio.fichio.ui.browser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import com.karelisio.fichio.FichioApp
import com.karelisio.fichio.data.db.FolderSettingsEntity
import com.karelisio.fichio.data.db.SortMode
import com.karelisio.fichio.data.db.ViewMode
import com.karelisio.fichio.domain.file.FileItem
import com.karelisio.fichio.domain.file.FileSorter
import com.karelisio.fichio.domain.operations.Clipboard
import com.karelisio.fichio.domain.operations.OperationKind
import com.karelisio.fichio.domain.operations.UndoStack
import com.karelisio.fichio.service.FileOperationService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

class BrowserViewModel(private val app: FichioApp, val path: String) : ViewModel() {

    private val container = app.container

    private val _items = MutableStateFlow<List<FileItem>>(emptyList())
    val items: StateFlow<List<FileItem>> = _items.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    var viewMode = MutableStateFlow(ViewMode.LIST)
    var sortMode = MutableStateFlow(SortMode.NAME)
    var sortAscending = MutableStateFlow(true)
    var foldersFirst = MutableStateFlow(true)
    var showHidden = MutableStateFlow(false)

    private val _selection = MutableStateFlow<Set<String>>(emptySet())
    val selection: StateFlow<Set<String>> = _selection.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _clipboard = MutableStateFlow(Clipboard.hasItems)
    val clipboard: StateFlow<Boolean> = _clipboard.asStateFlow()

    private val _undoMessage = MutableStateFlow<String?>(null)
    val undoMessage: StateFlow<String?> = _undoMessage.asStateFlow()

    init {
        viewModelScope.launch {
            container.database.folderSettingsDao().get(path)?.let { s ->
                viewMode.value = s.viewMode
                sortMode.value = s.sortMode
                sortAscending.value = s.sortAscending
                foldersFirst.value = s.foldersFirst
                showHidden.value = s.showHidden
            }
            load()
        }
    }

    fun load() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            runCatching { container.fileSystem.listDirectory(path, showHidden.value) }
                .onSuccess { _items.value = applySort(it) }
                .onFailure { _error.value = it.message }
            _isLoading.value = false
        }
    }

    private fun applySort(items: List<FileItem>): List<FileItem> =
        FileSorter.sort(items, sortMode.value, sortAscending.value, foldersFirst.value)

    fun setViewMode(mode: ViewMode) {
        viewMode.value = mode
        persistSettings()
    }

    fun setSortMode(mode: SortMode) {
        sortMode.value = mode
        _items.value = applySort(_items.value)
        persistSettings()
    }

    fun toggleSortDirection() {
        sortAscending.value = !sortAscending.value
        _items.value = applySort(_items.value)
        persistSettings()
    }

    fun toggleFoldersFirst() {
        foldersFirst.value = !foldersFirst.value
        _items.value = applySort(_items.value)
        persistSettings()
    }

    fun toggleHidden() {
        showHidden.value = !showHidden.value
        persistSettings()
        load()
    }

    private fun persistSettings() {
        viewModelScope.launch {
            container.database.folderSettingsDao().upsert(
                FolderSettingsEntity(
                    path = path,
                    viewMode = viewMode.value,
                    sortMode = sortMode.value,
                    sortAscending = sortAscending.value,
                    foldersFirst = foldersFirst.value,
                    showHidden = showHidden.value
                )
            )
        }
    }

    fun toggleSelection(itemPath: String) {
        _selection.value = _selection.value.let {
            if (itemPath in it) it - itemPath else it + itemPath
        }
    }

    fun clearSelection() {
        _selection.value = emptySet()
    }

    fun selectAll() {
        _selection.value = _items.value.map { it.path }.toSet()
    }

    fun invertSelection() {
        val all = _items.value.map { it.path }.toSet()
        _selection.value = all - _selection.value
    }

    fun isSelected(path: String): Boolean = path in _selection.value

    fun copySelection() {
        Clipboard.copy(_selection.value.toList())
        _selection.value = emptySet()
        _clipboard.value = true
    }

    fun cutSelection() {
        Clipboard.cut(_selection.value.toList())
        _selection.value = emptySet()
        _clipboard.value = true
    }

    fun paste() {
        if (!Clipboard.hasItems) return
        FileOperationService.enqueue(
            app,
            if (Clipboard.isCut) OperationKind.MOVE else OperationKind.COPY,
            Clipboard.sources,
            path
        )
        Clipboard.clear()
        _clipboard.value = false
        viewModelScope.launch {
            kotlinx.coroutines.delay(1500)
            load()
        }
    }

    fun deleteSelection() {
        val paths = _selection.value.toList()
        _selection.value = emptySet()
        viewModelScope.launch {
            val trashed = container.trashManager.moveToTrash(paths.map(::File))
            if (trashed.isNotEmpty()) {
                UndoStack.offer("${trashed.size} élément(s) déplacé(s) vers la corbeille") {
                    container.trashManager.restore(trashed)
                }
                _undoMessage.value = "${trashed.size} élément(s) déplacé(s) vers la corbeille"
            }
            load()
        }
    }

    fun renameItem(itemPath: String, newName: String) {
        viewModelScope.launch {
            val from = File(itemPath)
            val to = File(from.parentFile ?: return@launch, newName)
            val ok = container.fileSystem.rename(from.absolutePath, to.absolutePath)
            if (ok) {
                UndoStack.offer("Renommé") {
                    container.fileSystem.rename(to.absolutePath, from.absolutePath)
                }
                _undoMessage.value = "Renommé"
                load()
            }
        }
    }

    fun undo() {
        viewModelScope.launch {
            UndoStack.undo()
            _undoMessage.value = null
            load()
        }
    }

    fun clearClipboard() {
        Clipboard.clear()
        _clipboard.value = false
    }

    fun dismissUndo() {
        _undoMessage.value = null
    }

    companion object {
        fun factory(path: String): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as FichioApp
                BrowserViewModel(app, path)
            }
        }
    }
}
