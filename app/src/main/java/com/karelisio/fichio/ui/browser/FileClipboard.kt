package com.karelisio.fichio.ui.browser

import androidx.compose.runtime.Immutable
import com.karelisio.fichio.core.model.FileEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Immutable
data class ClipContent(val entries: List<FileEntry>, val cut: Boolean)

/** In-app clipboard shared by every tab and pane (copy / cut → paste). */
object FileClipboard {
    private val _content = MutableStateFlow<ClipContent?>(null)
    val content: StateFlow<ClipContent?> = _content.asStateFlow()

    fun copy(entries: List<FileEntry>) { if (entries.isNotEmpty()) _content.value = ClipContent(entries, cut = false) }
    fun cut(entries: List<FileEntry>) { if (entries.isNotEmpty()) _content.value = ClipContent(entries, cut = true) }
    fun clear() { _content.value = null }
}
