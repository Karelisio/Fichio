package com.karelisio.fichio.ops

import android.content.Context
import com.karelisio.fichio.core.fs.FileSystems
import com.karelisio.fichio.trash.TrashRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Queue of long-running file operations (copy, move, trash, delete, custom tasks), executed
 * in a foreground service with pause / cancel / conflict resolution.
 * CONTRACT STUB — implemented by the operations module.
 */
class OperationManager(
    private val context: Context,
    private val fs: FileSystems,
    private val trash: TrashRepository,
    val undo: UndoManager,
    private val scope: CoroutineScope,
) {
    private val _jobs = MutableStateFlow<List<Job>>(emptyList())
    val jobs: StateFlow<List<Job>> = _jobs

    private val _conflict = MutableStateFlow<ConflictRequest?>(null)
    val conflict: StateFlow<ConflictRequest?> = _conflict

    private val _events = MutableSharedFlow<OperationEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<OperationEvent> = _events

    fun copy(sources: List<String>, destinationDir: String): Long = TODO()
    fun move(sources: List<String>, destinationDir: String): Long = TODO()
    /** Moves to the Fichio trash (undoable). Falls back to permanent delete when trash is disabled. */
    fun trash(paths: List<String>): Long = TODO()
    fun deletePermanently(paths: List<String>): Long = TODO()
    fun runTask(
        kind: JobKind,
        title: String,
        sources: List<String> = emptyList(),
        destination: String? = null,
        block: suspend TaskScope.() -> Unit,
    ): Long = TODO()

    fun pause(id: Long) {}
    fun resume(id: Long) {}
    fun cancel(id: Long) {}
    fun clearFinished() {}
    fun resolveConflict(choice: ConflictChoice, applyToAll: Boolean) {}

    /** Renames in place; returns the new path. Undoable. */
    suspend fun rename(path: String, newName: String): Result<String> = TODO()
    /** Renames many items atomically-ish (rolls back on failure); returns the count. Undoable as one step. */
    suspend fun batchRename(renames: List<Pair<String, String>>): Result<Int> = TODO()
    suspend fun createFolder(parent: String, name: String): Result<String> = TODO()
    suspend fun createFile(parent: String, name: String): Result<String> = TODO()
}
