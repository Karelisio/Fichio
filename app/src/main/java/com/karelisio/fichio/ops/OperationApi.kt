package com.karelisio.fichio.ops

import androidx.compose.runtime.Immutable
import com.karelisio.fichio.core.model.FileEntry

/*
 * Public contract of the operations layer, used by the browser, archives, APK tools, analysis…
 * Keep these types stable: other packages compile against them.
 */

enum class JobKind { COPY, MOVE, DELETE, TRASH, RESTORE, EXTRACT, COMPRESS, INSTALL, EXPORT, OTHER }

enum class JobState { QUEUED, RUNNING, PAUSED, WAITING_CONFLICT, DONE, FAILED, CANCELLED;
    val isFinished get() = this == DONE || this == FAILED || this == CANCELLED
}

@Immutable
data class JobProgress(
    val doneBytes: Long = 0,
    val totalBytes: Long = 0,
    val doneItems: Int = 0,
    val totalItems: Int = 0,
    val currentName: String = "",
    /** Bytes per second, smoothed. */
    val speed: Long = 0,
) {
    val fraction: Float
        get() = when {
            totalBytes > 0 -> (doneBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
            totalItems > 0 -> (doneItems.toFloat() / totalItems).coerceIn(0f, 1f)
            else -> 0f
        }
    val indeterminate get() = totalBytes <= 0 && totalItems <= 0
}

@Immutable
data class Job(
    val id: Long,
    val kind: JobKind,
    val title: String,
    val sources: List<String> = emptyList(),
    val destination: String? = null,
    val state: JobState = JobState.QUEUED,
    val progress: JobProgress = JobProgress(),
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    /** Number of items that failed / were skipped (for the summary). */
    val failed: Int = 0,
    val skipped: Int = 0,
)

enum class ConflictChoice { REPLACE, SKIP, KEEP_BOTH }

/** A pending "file already exists" question. The UI answers through [OperationManager.resolveConflict]. */
@Immutable
data class ConflictRequest(
    val jobId: Long,
    val source: FileEntry,
    val existing: FileEntry,
    /** How many conflicts may still follow in this job (for "apply to all"). */
    val remainingHint: Int = 0,
)

/** One-shot events for snackbars and animations. */
sealed interface OperationEvent {
    /** A job finished. [undoLabel] is non-null when [UndoManager.undo] can revert it. */
    data class Finished(val job: Job, val undoLabel: String?) : OperationEvent

    /** Fired right when a copy/move starts, so the UI can play the "flying file" animation. */
    data class Transfer(val jobId: Long, val kind: JobKind, val sources: List<String>, val destination: String) : OperationEvent

    data class Message(val text: String) : OperationEvent
}

/** Scope given to custom tasks ([OperationManager.runTask]) to report progress and honour pause/cancel. */
interface TaskScope {
    val jobId: Long
    fun progress(done: Long, total: Long, currentName: String = "", doneItems: Int = 0, totalItems: Int = 0)
    /** Suspends while paused, throws CancellationException when cancelled. Call often. */
    suspend fun checkpoint()
    /** Ask the user how to resolve a name conflict. */
    suspend fun askConflict(source: FileEntry, existing: FileEntry): ConflictChoice
}

/** Undoable action recorded after a successful move / rename / trash. */
sealed interface UndoAction {
    val label: String

    data class Moved(val pairs: List<Pair<String, String>>, override val label: String) : UndoAction
    data class Renamed(val pairs: List<Pair<String, String>>, override val label: String) : UndoAction
    data class Trashed(val trashIds: List<String>, override val label: String) : UndoAction
    data class Created(val paths: List<String>, override val label: String) : UndoAction
}
