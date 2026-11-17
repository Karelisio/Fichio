package com.karelisio.fichio.domain.operations

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** Kind of a queued file operation. */
enum class OperationKind { COPY, MOVE, DELETE }

/** Live progress snapshot for a running operation. */
data class OperationProgress(
    val totalBytes: Long = 0L,
    val doneBytes: Long = 0L,
    val totalFiles: Int = 0,
    val doneFiles: Int = 0,
    val currentName: String = ""
) {
    val fraction: Float
        get() = if (totalBytes <= 0L) 0f else (doneBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
}

/** Summary returned when an operation finishes. */
data class OperationOutcome(
    val totalFiles: Int,
    val completedFiles: Int,
    val skippedFiles: Int,
    val failedFiles: Int,
    val bytesTransferred: Long,
    val cancelled: Boolean
)

/**
 * Executes copy/move/delete jobs with byte-accurate progress, cooperative
 * pause and cancellation, and per-file conflict resolution. Pure JVM logic so
 * it can be unit tested without a device.
 */
class FileTransferEngine {

    private val _paused = MutableStateFlow(false)
    val paused: StateFlow<Boolean> = _paused.asStateFlow()

    private val _progress = MutableStateFlow(OperationProgress())
    val progress: StateFlow<OperationProgress> = _progress.asStateFlow()

    fun setPaused(value: Boolean) {
        _paused.value = value
    }

    private suspend fun awaitResume() {
        while (_paused.value) {
            delay(50)
            currentCoroutineContext().ensureActive()
        }
        currentCoroutineContext().ensureActive()
    }

    suspend fun copy(
        sources: List<File>,
        destinationDir: File,
        defaultConflict: ConflictAction = ConflictAction.KEEP_BOTH,
        onConflict: suspend (File, File) -> ConflictAction = { _, _ -> defaultConflict }
    ): OperationOutcome = run(sources, destinationDir, OperationKind.COPY, defaultConflict, onConflict)

    suspend fun move(
        sources: List<File>,
        destinationDir: File,
        defaultConflict: ConflictAction = ConflictAction.KEEP_BOTH,
        onConflict: suspend (File, File) -> ConflictAction = { _, _ -> defaultConflict }
    ): OperationOutcome = run(sources, destinationDir, OperationKind.MOVE, defaultConflict, onConflict)

    suspend fun delete(
        sources: List<File>,
        defaultConflict: ConflictAction = ConflictAction.REPLACE,
        onConflict: suspend (File, File) -> ConflictAction = { _, _ -> defaultConflict }
    ): OperationOutcome = run(sources, null, OperationKind.DELETE, defaultConflict, onConflict)

    private suspend fun run(
        sources: List<File>,
        destinationDir: File?,
        kind: OperationKind,
        defaultConflict: ConflictAction,
        onConflict: suspend (File, File) -> ConflictAction
    ): OperationOutcome {
        val plan = Plan()
        sources.forEach { source ->
            if (kind == OperationKind.DELETE) {
                plan.addTree(source, null)
            } else {
                val target = File(destinationDir!!, source.name)
                plan.addTree(source, target)
            }
        }
        // Deletes must run children before their parents (post-order), while
        // copy/move need parents first (pre-order).
        if (kind == OperationKind.DELETE) plan.entries.reverse()

        _progress.value = OperationProgress(
            totalBytes = plan.totalBytes,
            totalFiles = plan.entries.size
        )

        var bytes = 0L
        var completed = 0
        var skipped = 0
        var failed = 0

        for (entry in plan.entries) {
            awaitResume()
            when (kind) {
                OperationKind.COPY -> when (copyEntry(entry, defaultConflict, onConflict)) {
                    EntryResult.DONE -> { completed++; bytes += entry.source.length().takeIf { entry.source.isFile } ?: 0L }
                    EntryResult.SKIPPED -> skipped++
                    EntryResult.FAILED -> failed++
                    EntryResult.CANCELLED -> return OperationOutcome(plan.entries.size, completed, skipped, failed, bytes, true)
                }
                OperationKind.MOVE -> when (moveEntry(entry, defaultConflict, onConflict)) {
                    EntryResult.DONE -> { completed++; bytes += entry.source.length().takeIf { entry.source.isFile } ?: 0L }
                    EntryResult.SKIPPED -> skipped++
                    EntryResult.FAILED -> failed++
                    EntryResult.CANCELLED -> return OperationOutcome(plan.entries.size, completed, skipped, failed, bytes, true)
                }
                OperationKind.DELETE -> when (deleteEntry(entry)) {
                    EntryResult.DONE -> { completed++; bytes += entry.source.length().takeIf { entry.source.isFile } ?: 0L }
                    EntryResult.FAILED -> failed++
                    EntryResult.SKIPPED -> skipped++
                    EntryResult.CANCELLED -> return OperationOutcome(plan.entries.size, completed, skipped, failed, bytes, true)
                }
            }
            _progress.value = _progress.value.copy(
                doneBytes = bytes,
                doneFiles = completed + skipped + failed,
                currentName = entry.source.name
            )
        }

        return OperationOutcome(plan.entries.size, completed, skipped, failed, bytes, false)
    }

    private suspend fun copyEntry(
        entry: PlanEntry,
        defaultConflict: ConflictAction,
        onConflict: suspend (File, File) -> ConflictAction
    ): EntryResult {
        val src = entry.source
        val dst = entry.target ?: return EntryResult.FAILED
        if (src.isDirectory) {
            if (!dst.exists() && !dst.mkdirs()) return EntryResult.FAILED
            return EntryResult.DONE
        }
        val target = resolveConflict(src, dst, defaultConflict, onConflict) ?: return EntryResult.SKIPPED
        return try {
            src.inputStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(COPY_BUFFER_SIZE)
                    while (true) {
                        awaitResume()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                    }
                }
            }
            EntryResult.DONE
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            EntryResult.FAILED
        }
    }

    private suspend fun moveEntry(
        entry: PlanEntry,
        defaultConflict: ConflictAction,
        onConflict: suspend (File, File) -> ConflictAction
    ): EntryResult {
        val src = entry.source
        val dst = entry.target ?: return EntryResult.FAILED
        val target = resolveConflict(src, dst, defaultConflict, onConflict) ?: return EntryResult.SKIPPED
        // Fast path: same filesystem rename.
        return try {
            if (src.renameTo(target)) {
                EntryResult.DONE
            } else {
                val copyResult = copyEntry(entry.copy(target = target), defaultConflict, onConflict)
                if (copyResult == EntryResult.DONE) {
                    src.deleteRecursively()
                    EntryResult.DONE
                } else copyResult
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            EntryResult.FAILED
        }
    }

    private suspend fun deleteEntry(entry: PlanEntry): EntryResult = try {
        awaitResume()
        if (entry.source.delete()) EntryResult.DONE else {
            if (entry.source.exists()) EntryResult.FAILED else EntryResult.DONE
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        EntryResult.FAILED
    }

    private suspend fun resolveConflict(
        src: File,
        dst: File,
        defaultConflict: ConflictAction,
        onConflict: suspend (File, File) -> ConflictAction
    ): File? {
        if (!dst.exists()) return dst
        return when (defaultConflict) {
            ConflictAction.REPLACE -> {
                if (dst.isDirectory) dst.deleteRecursively() else dst.delete()
                dst
            }
            ConflictAction.SKIP -> null
            ConflictAction.KEEP_BOTH -> File(uniquePath(dst.absolutePath))
            ConflictAction.ASK -> {
                when (onConflict(src, dst)) {
                    ConflictAction.REPLACE -> { dst.delete(); dst }
                    ConflictAction.SKIP -> null
                    ConflictAction.KEEP_BOTH -> File(uniquePath(dst.absolutePath))
                    ConflictAction.ASK -> File(uniquePath(dst.absolutePath))
                }
            }
        }
    }

    private data class PlanEntry(val source: File, val target: File?) {
        val isDirectory get() = source.isDirectory
    }

    private enum class EntryResult { DONE, SKIPPED, FAILED, CANCELLED }

    private class Plan {
        val entries = ArrayList<PlanEntry>()
        var totalBytes = 0L

        fun addTree(source: File, target: File?) {
            if (source.isDirectory) {
                entries.add(PlanEntry(source, target))
                source.listFiles()?.forEach { child ->
                    addTree(child, target?.let { File(it, child.name) })
                }
            } else {
                entries.add(PlanEntry(source, target))
                totalBytes += source.length()
            }
        }
    }

    companion object {
        private const val COPY_BUFFER_SIZE = 64 * 1024
    }
}
