package com.karelisio.fichio.ui.trash

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.karelisio.fichio.FichioApp
import com.karelisio.fichio.data.db.TrashItemEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TrashViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as FichioApp).container
    private val dao = container.database.trashDao()

    val items: StateFlow<List<TrashItemEntity>> = dao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun restore(item: TrashItemEntity) {
        viewModelScope.launch { container.trashManager.restore(listOf(item)) }
    }

    fun restoreAll() {
        viewModelScope.launch { container.trashManager.restore(items.value) }
    }

    fun delete(item: TrashItemEntity) {
        viewModelScope.launch { container.trashManager.permanentlyDelete(listOf(item)) }
    }

    fun emptyTrash() {
        viewModelScope.launch { container.trashManager.permanentlyDelete(items.value) }
    }
}
