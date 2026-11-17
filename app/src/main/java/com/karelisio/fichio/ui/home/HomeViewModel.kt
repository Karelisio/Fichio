package com.karelisio.fichio.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.karelisio.fichio.FichioApp
import com.karelisio.fichio.data.db.FavoriteEntity
import com.karelisio.fichio.domain.file.CategoryUsage
import com.karelisio.fichio.domain.file.FileItem
import com.karelisio.fichio.domain.file.StorageStats
import com.karelisio.fichio.domain.scan.StorageScanner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as FichioApp).container

    private val _storageStats = MutableStateFlow<StorageStats?>(null)
    val storageStats: StateFlow<StorageStats?> = _storageStats.asStateFlow()

    private val _categories = MutableStateFlow<List<CategoryUsage>>(emptyList())
    val categories: StateFlow<List<CategoryUsage>> = _categories.asStateFlow()

    private val _recentFiles = MutableStateFlow<List<FileItem>>(emptyList())
    val recentFiles: StateFlow<List<FileItem>> = _recentFiles.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    val favorites: StateFlow<List<FavoriteEntity>> = container.database.favoriteDao()
        .observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _storageStats.value = container.fileSystem.storageStats()
            _isScanning.value = true
            runCatching {
                val result = StorageScanner.scan(container.fileSystem.root)
                _categories.value = result.categoryUsage
                _recentFiles.value = result.recentFiles
            }
            _isScanning.value = false
        }
    }
}
