package com.karelisio.fichio.ops

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Remembers the last undoable action (move, rename, trash, create). CONTRACT STUB. */
class UndoManager {
    private val _last = MutableStateFlow<UndoAction?>(null)
    val last: StateFlow<UndoAction?> = _last
    fun record(action: UndoAction) { _last.value = action }
    /** Reverts [last]. Returns true on success. */
    suspend fun undo(): Boolean = false
}
