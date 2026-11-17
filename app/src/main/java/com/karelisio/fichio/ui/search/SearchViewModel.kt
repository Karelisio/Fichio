package com.karelisio.fichio.ui.search

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.karelisio.fichio.FichioApp
import com.karelisio.fichio.data.db.SearchIndexEntity
import com.karelisio.fichio.domain.file.FileCategory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SearchViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = (app as FichioApp).container.database.searchDao()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _typeFilter = MutableStateFlow<FileCategory?>(null)
    val typeFilter: StateFlow<FileCategory?> = _typeFilter.asStateFlow()

    private val _results = MutableStateFlow<List<SearchIndexEntity>>(emptyList())
    val results: StateFlow<List<SearchIndexEntity>> = _results.asStateFlow()

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    fun setQuery(value: String) {
        _query.value = value
        run()
    }

    fun setTypeFilter(value: FileCategory?) {
        _typeFilter.value = value
        run()
    }

    fun run() {
        viewModelScope.launch {
            val raw = _query.value.trim()
            if (raw.isEmpty()) {
                _results.value = emptyList()
                return@launch
            }
            _searching.value = true
            val entries = runCatching { dao.search(ftsQuery(raw), 200) }.getOrDefault(emptyList())
            _results.value = entries.filter { matchesType(it) }
            _searching.value = false
        }
    }

    private fun matchesType(entry: SearchIndexEntity): Boolean {
        val filter = _typeFilter.value ?: return true
        return entry.category == filter.name
    }

    private fun ftsQuery(raw: String): String =
        raw.trim()
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.isNotBlank() }
            .joinToString(" ") { "$it*" }
}
