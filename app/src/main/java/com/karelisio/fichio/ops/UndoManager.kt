package com.karelisio.fichio.ops

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Knows how to revert an [UndoAction] (implemented by [OperationManager]). */
fun interface UndoExecutor {
    suspend fun revert(action: UndoAction): Boolean
}

/** Remembers the last undoable action (move, rename, trash, create). */
class UndoManager {
    private val _last = MutableStateFlow<UndoAction?>(null)
    val last: StateFlow<UndoAction?> = _last
    private val mutex = Mutex()

    @Volatile
    private var executor: UndoExecutor? = null

    /** Called by [OperationManager] at construction. */
    fun bind(executor: UndoExecutor) {
        this.executor = executor
    }

    fun record(action: UndoAction) { _last.value = action }

    fun clear() { _last.value = null }

    /** Reverts [last]. Returns true on success. */
    suspend fun undo(): Boolean = mutex.withLock {
        val action = _last.value ?: return false
        val exec = executor ?: return false
        _last.value = null
        val ok = runCatching { exec.revert(action) }.getOrDefault(false)
        ok
    }
}
