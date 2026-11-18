package com.karelisio.fichio.ops

import android.content.Context
import com.karelisio.fichio.container
import com.karelisio.fichio.core.fs.FileSystems
import com.karelisio.fichio.core.model.FileEntry
import com.karelisio.fichio.ops.engine.ConflictAnswer
import com.karelisio.fichio.ops.engine.EngineListener
import com.karelisio.fichio.ops.engine.EngineResult
import com.karelisio.fichio.ops.engine.Names
import com.karelisio.fichio.ops.engine.OperationEngine
import com.karelisio.fichio.ops.engine.PathUtil
import com.karelisio.fichio.ops.engine.ProgressTracker
import com.karelisio.fichio.ops.engine.Transfer
import com.karelisio.fichio.trash.TrashRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException

/**
 * Queue of long-running file operations (copy, move, trash, delete, custom tasks), executed
 * in a foreground service with pause / cancel / conflict resolution.
 *
 * Jobs run one at a time (FIFO); the following ones wait as QUEUED.
 */
class OperationManager(
    private val context: Context,
    private val fs: FileSystems,
    private val trash: TrashRepository,
    val undo: UndoManager,
    private val scope: CoroutineScope,
) : UndoExecutor {
    private val _jobs = MutableStateFlow<List<Job>>(emptyList())
    val jobs: StateFlow<List<Job>> = _jobs

    private val _conflict = MutableStateFlow<ConflictRequest?>(null)
    val conflict: StateFlow<ConflictRequest?> = _conflict

    private val _events = MutableSharedFlow<OperationEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<OperationEvent> = _events

    val engine = OperationEngine(fs::backendFor)

    private val ids = AtomicLong(0)
    private val records = ConcurrentHashMap<Long, Record>()
    private val queue = Semaphore(1)
    private val conflictMutex = Mutex()
    @Volatile private var pendingConflict: CompletableDeferred<ConflictAnswer>? = null

    init {
        undo.bind(this)
    }

    private class Record(val id: Long) {
        val paused = MutableStateFlow(false)
        val finished = CompletableDeferred<Job>()
        var coroutine: kotlinx.coroutines.Job? = null
    }

    /** What a job reports back when it completes. */
    private data class Outcome(
        val undo: UndoAction? = null,
        val touched: Collection<String> = emptyList(),
        val failed: Int = 0,
        val skipped: Int = 0,
        val error: String? = null,
        /** In-app moves/renames to propagate to favourites (folders) and tags (everything). */
        val moved: List<Moved> = emptyList(),
        /** True when nothing at all succeeded: the job is reported FAILED. */
        val totalFailure: Boolean = false,
    )

    private data class Moved(val from: String, val to: String, val isDirectory: Boolean)

    // ---------------------------------------------------------------- public queue API

    fun copy(sources: List<String>, destinationDir: String): Long {
        val id = enqueue(JobKind.COPY, titleFor("Copie", sources), sources, destinationDir) { l ->
            val r = engine.copy(sources, destinationDir, l)
            val created = r.transfers.map { it.to }
            r.toOutcome(
                undo = if (created.isNotEmpty() && r.replaced == 0)
                    UndoAction.Created(created, "${Format2.items(created.size)} copié${plural(created.size)}") else null,
            )
        }
        _events.tryEmit(OperationEvent.Transfer(id, JobKind.COPY, sources, destinationDir))
        return id
    }

    fun move(sources: List<String>, destinationDir: String): Long {
        val id = enqueue(JobKind.MOVE, titleFor("Déplacement", sources), sources, destinationDir) { l ->
            val r = engine.move(sources, destinationDir, l)
            val pairs = r.transfers.map { it.from to it.to }
            r.toOutcome(
                undo = if (pairs.isNotEmpty())
                    UndoAction.Moved(pairs, "${Format2.items(pairs.size)} déplacé${plural(pairs.size)}") else null,
                moved = r.transfers.moves(),
            )
        }
        _events.tryEmit(OperationEvent.Transfer(id, JobKind.MOVE, sources, destinationDir))
        return id
    }

    /** Moves to the Fichio trash (undoable). Falls back to permanent delete when trash is disabled. */
    fun trash(paths: List<String>): Long =
        enqueue(JobKind.TRASH, titleFor("Mise à la corbeille", paths), paths, null) { l ->
            val enabled = runCatching { context.container.settings.current().trashEnabled }.getOrDefault(true)
            val (inTrash, others) = paths.partition { trash.isInTrash(it) }
            if (!enabled || others.isEmpty()) {
                update(l.id) { it.copy(kind = JobKind.DELETE, title = titleFor("Suppression", paths)) }
                return@enqueue engine.delete(paths, l).toOutcome()
            }
            val tracker = ProgressTracker(l::onProgress)
            tracker.start(0, paths.size)
            val trashed = ArrayList<String>()
            val errors = ArrayList<String>()
            for (p in others) {
                l.checkpoint()
                tracker.setName(PathUtil.name(p))
                try {
                    trashed += trash.moveToTrash(p, CheckpointOnly(l)).id
                } catch (c: CancellationException) {
                    throw c
                } catch (e: Exception) {
                    errors += "${PathUtil.name(p)} : ${e.message}"
                }
                tracker.itemDone()
            }
            val deleted = if (inTrash.isNotEmpty()) engine.delete(inTrash, CheckpointOnly(l)) else EngineResult()
            val failed = errors.size + deleted.failed
            Outcome(
                undo = if (trashed.isNotEmpty())
                    UndoAction.Trashed(trashed, "${Format2.items(trashed.size)} mis à la corbeille") else null,
                touched = paths.map { PathUtil.parent(it) },
                failed = failed,
                error = (errors + deleted.errors).firstOrNull(),
                totalFailure = failed > 0 && trashed.isEmpty() && deleted.processed == 0,
            )
        }

    fun deletePermanently(paths: List<String>): Long =
        enqueue(JobKind.DELETE, titleFor("Suppression", paths), paths, null) { l ->
            engine.delete(paths, l).toOutcome()
        }

    /** Restores trash items (queued job). */
    fun restoreFromTrash(ids: List<String>): Long =
        enqueue(JobKind.RESTORE, if (ids.size == 1) "Restauration" else "Restauration de ${Format2.items(ids.size)}", emptyList(), null) { l ->
            restoreIds(ids, l)
        }

    /** Permanently deletes trash items (queued job). */
    fun deleteFromTrash(ids: List<String>): Long =
        enqueue(JobKind.DELETE, "Suppression définitive de ${Format2.items(ids.size)}", emptyList(), null) { l ->
            val tracker = ProgressTracker(l::onProgress)
            tracker.start(0, ids.size)
            for (id in ids) {
                l.checkpoint()
                trash.deleteForever(id)
                tracker.itemDone()
            }
            Outcome()
        }

    /** Empties the trash (queued job). */
    fun emptyTrash(): Long = enqueue(JobKind.DELETE, "Vidage de la corbeille", emptyList(), null) { _ ->
        trash.empty()
        Outcome()
    }

    fun runTask(
        kind: JobKind,
        title: String,
        sources: List<String> = emptyList(),
        destination: String? = null,
        block: suspend TaskScope.() -> Unit,
    ): Long = enqueue(kind, title, sources, destination) { l ->
        TaskScopeImpl(l).block()
        Outcome(touched = listOfNotNull(destination) + sources.map { PathUtil.parent(it) })
    }

    fun pause(id: Long) {
        val rec = records[id] ?: return
        rec.paused.value = true
        update(id) { if (it.state == JobState.RUNNING) it.copy(state = JobState.PAUSED) else it }
    }

    fun resume(id: Long) {
        val rec = records[id] ?: return
        rec.paused.value = false
        update(id) { if (it.state == JobState.PAUSED) it.copy(state = JobState.RUNNING) else it }
    }

    fun cancel(id: Long) {
        records[id]?.coroutine?.cancel()
    }

    fun clearFinished() {
        _jobs.update { list -> list.filterNot { it.state.isFinished } }
    }

    fun resolveConflict(choice: ConflictChoice, applyToAll: Boolean) {
        pendingConflict?.complete(ConflictAnswer(choice, applyToAll))
    }

    /** Suspends until job [id] is finished; returns its final state (null if unknown). */
    suspend fun await(id: Long): Job? = records[id]?.finished?.await() ?: _jobs.value.firstOrNull { it.id == id }

    /** Posts a one-shot message for the app's snackbar. */
    fun message(text: String) {
        _events.tryEmit(OperationEvent.Message(text))
    }

    // ---------------------------------------------------------------- immediate operations

    /** Renames in place; returns the new path. Undoable. */
    suspend fun rename(path: String, newName: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val name = newName.trim()
            Names.validate(name)?.let { throw IllegalArgumentException(it) }
            val b = fs.backendFor(path)
            val entry = b.stat(path) ?: throw IOException("« ${PathUtil.name(path)} » est introuvable")
            val dest = PathUtil.join(PathUtil.parent(path), name)
            if (dest == path) return@runCatching path
            renameOne(path, dest)
            val parent = PathUtil.parent(path)
            fs.notifyChanged(parent)
            onPathMoved(path, dest, entry.isDirectory)
            undo.record(UndoAction.Renamed(listOf(path to dest), "« ${entry.name} » renommé"))
            dest
        }
    }

    /**
     * Renames many items atomically-ish (rolls back on failure); returns the count. Undoable as one
     * step. Each pair is (path, new name) — a value containing '/' is taken as a full new path.
     */
    suspend fun batchRename(renames: List<Pair<String, String>>): Result<Int> =
        batchRenameInternal(renames, recordUndo = true)

    suspend fun createFolder(parent: String, name: String): Result<String> = create(parent, name, folder = true)

    suspend fun createFile(parent: String, name: String): Result<String> = create(parent, name, folder = false)

    // ---------------------------------------------------------------- undo

    override suspend fun revert(action: UndoAction): Boolean = when (action) {
        is UndoAction.Moved -> {
            val back = action.pairs.map { (from, to) -> to to from }
            val id = enqueue(JobKind.MOVE, "Annulation du déplacement", back.map { it.first }, null) { l ->
                val r = engine.moveExact(back, l)
                r.toOutcome(moved = r.transfers.moves())
            }
            await(id)?.state == JobState.DONE
        }
        is UndoAction.Renamed -> batchRenameInternal(action.pairs.map { (old, new) -> new to old }, recordUndo = false).isSuccess
        is UndoAction.Trashed -> {
            val id = enqueue(JobKind.RESTORE, "Annulation de la mise à la corbeille", emptyList(), null) { l -> restoreIds(action.trashIds, l) }
            await(id)?.state == JobState.DONE
        }
        is UndoAction.Created -> {
            val id = enqueue(JobKind.DELETE, "Annulation", action.paths, null) { l -> engine.delete(action.paths, l).toOutcome() }
            await(id)?.state == JobState.DONE
        }
    }

    // ---------------------------------------------------------------- internals

    /** Listener bound to one job: pause/cancel, progress into [jobs], conflicts into [conflict]. */
    private inner class JobListener(val id: Long, val rec: Record) : EngineListener {
        override suspend fun checkpoint() {
            currentCoroutineContext().ensureActive()
            if (rec.paused.value) {
                update(id) { it.copy(state = JobState.PAUSED) }
                rec.paused.first { !it }
                update(id) { it.copy(state = JobState.RUNNING) }
            }
        }

        override fun onProgress(progress: JobProgress) {
            update(id) { it.copy(progress = progress) }
        }

        override suspend fun onConflict(source: FileEntry, existing: FileEntry, remaining: Int): ConflictAnswer =
            askConflict(id, source, existing, remaining)
    }

    /** Forwards pause/cancel only (nested work reporting its own progress elsewhere). */
    private class CheckpointOnly(private val l: EngineListener) : EngineListener {
        override suspend fun checkpoint() = l.checkpoint()
        override suspend fun onConflict(source: FileEntry, existing: FileEntry, remaining: Int) =
            l.onConflict(source, existing, remaining)
    }

    private inner class TaskScopeImpl(private val l: JobListener) : TaskScope {
        override val jobId: Long get() = l.id
        private var sticky: ConflictChoice? = null
        private var lastTime = System.nanoTime()
        private var lastDone = 0L
        private var speed = 0L

        override fun progress(done: Long, total: Long, currentName: String, doneItems: Int, totalItems: Int) {
            val now = System.nanoTime()
            val dt = now - lastTime
            if (dt >= 500_000_000L) {
                val inst = ((done - lastDone).coerceAtLeast(0) * 1_000_000_000.0 / dt).toLong()
                speed = if (speed == 0L) inst else (0.35 * inst + 0.65 * speed).toLong()
                lastTime = now; lastDone = done
            }
            l.onProgress(JobProgress(done, total, doneItems, totalItems, currentName, speed))
        }

        override suspend fun checkpoint() = l.checkpoint()

        override suspend fun askConflict(source: FileEntry, existing: FileEntry): ConflictChoice {
            sticky?.let { return it }
            val a = askConflict(l.id, source, existing, 0)
            if (a.applyToAll) sticky = a.choice
            return a.choice
        }
    }

    private suspend fun askConflict(id: Long, source: FileEntry, existing: FileEntry, remaining: Int): ConflictAnswer =
        conflictMutex.withLock {
            val answer = CompletableDeferred<ConflictAnswer>()
            pendingConflict = answer
            update(id) { it.copy(state = JobState.WAITING_CONFLICT) }
            _conflict.value = ConflictRequest(id, source, existing, remaining)
            try {
                answer.await()
            } finally {
                _conflict.value = null
                pendingConflict = null
                update(id) { if (it.state == JobState.WAITING_CONFLICT) it.copy(state = JobState.RUNNING) else it }
            }
        }

    private fun enqueue(
        kind: JobKind,
        title: String,
        sources: List<String>,
        destination: String?,
        work: suspend (JobListener) -> Outcome,
    ): Long {
        val id = ids.incrementAndGet()
        val rec = Record(id)
        records[id] = rec
        _jobs.update { it + Job(id = id, kind = kind, title = title, sources = sources, destination = destination) }
        FileOperationService.start(context)
        val listener = JobListener(id, rec)
        // ATOMIC: the body always starts, so a job cancelled while still queued runs its
        // finally block and is marked CANCELLED (a LAZY/DEFAULT job cancelled before dispatch
        // would never complete and stay stuck in the queue).
        val c = scope.launch(Dispatchers.IO, start = CoroutineStart.ATOMIC) {
            var outcome: Outcome? = null
            var error: Throwable? = null
            try {
                queue.withPermit {
                    update(id) { it.copy(state = if (rec.paused.value) JobState.PAUSED else JobState.RUNNING) }
                    listener.checkpoint()
                    outcome = work(listener)
                }
            } catch (t: Throwable) {
                error = t
            } finally {
                withContext(NonCancellable) { complete(id, rec, outcome, error) }
            }
        }
        rec.coroutine = c
        return id
    }

    private fun complete(id: Long, rec: Record, outcome: Outcome?, error: Throwable?) {
        val cancelled = error is CancellationException
        val state = when {
            cancelled -> JobState.CANCELLED
            error != null -> JobState.FAILED
            outcome?.totalFailure == true -> JobState.FAILED
            else -> JobState.DONE
        }
        val message = when {
            cancelled -> null
            error != null -> userMessage(error)
            else -> outcome?.error
        }
        update(id) {
            it.copy(
                state = state,
                error = message,
                failed = outcome?.failed ?: it.failed,
                skipped = outcome?.skipped ?: it.skipped,
                progress = if (state == JobState.DONE) it.progress.let { p ->
                    p.copy(doneBytes = maxOf(p.doneBytes, p.totalBytes), doneItems = maxOf(p.doneItems, p.totalItems), speed = 0)
                } else it.progress.copy(speed = 0),
            )
        }
        val job = _jobs.value.firstOrNull { it.id == id }
        // Even a cancelled/failed job may have changed things: always refresh the listings.
        val touched = LinkedHashSet<String>()
        outcome?.touched?.let { touched += it }
        job?.destination?.let { touched += it }
        job?.sources?.forEach { touched += PathUtil.parent(it) }
        if (touched.isNotEmpty()) fs.notifyChanged(touched)
        outcome?.moved?.takeIf { it.isNotEmpty() }?.let { moved ->
            scope.launch { moved.forEach { onPathMoved(it.from, it.to, it.isDirectory) } }
        }
        val undoAction = outcome?.undo?.takeIf { state == JobState.DONE }
        undoAction?.let { undo.record(it) }
        records.remove(id)
        job?.let {
            rec.finished.complete(it)
            _events.tryEmit(OperationEvent.Finished(it, undoAction?.label))
        } ?: rec.finished.cancel()
    }

    private fun update(id: Long, transform: (Job) -> Job) {
        _jobs.update { list -> list.map { if (it.id == id) transform(it) else it } }
    }

    private suspend fun restoreIds(ids: List<String>, l: JobListener): Outcome {
        val tracker = ProgressTracker(l::onProgress)
        tracker.start(0, ids.size)
        val errors = ArrayList<String>()
        var ok = 0
        for (id in ids) {
            l.checkpoint()
            val item = trash.get(id)
            tracker.setName(item?.name.orEmpty())
            trash.restore(id, CheckpointOnly(l))
                .onSuccess { ok++ }
                .onFailure { if (it is CancellationException) throw it; errors += it.message ?: "erreur" }
            tracker.itemDone()
        }
        return Outcome(failed = errors.size, error = errors.firstOrNull(), totalFailure = ok == 0 && errors.isNotEmpty())
    }

    private suspend fun batchRenameInternal(renames: List<Pair<String, String>>, recordUndo: Boolean): Result<Int> =
        withContext(Dispatchers.IO) {
            runCatching {
                val pairs = renames.map { (path, target) ->
                    val dest = if ('/' in target) target else PathUtil.join(PathUtil.parent(path), target.trim())
                    Names.validate(PathUtil.name(dest))?.let { throw IllegalArgumentException("${PathUtil.name(dest)} : $it") }
                    path to dest
                }.filter { it.first != it.second }
                if (pairs.isEmpty()) return@runCatching 0

                val sourcesLower = pairs.map { it.first.lowercase() }.toSet()
                val targetsLower = HashSet<String>()
                for ((from, to) in pairs) {
                    if (!targetsLower.add(to.lowercase())) throw IOException("Deux éléments recevraient le nom « ${PathUtil.name(to)} »")
                    val b = fs.backendFor(to)
                    if (b.exists(to) && to.lowercase() !in sourcesLower && !to.equals(from, ignoreCase = true)) {
                        throw IOException("« ${PathUtil.name(to)} » existe déjà")
                    }
                }
                val dirs = pairs.filter { fs.backendFor(it.first).stat(it.first)?.isDirectory == true }.map { it.first }.toSet()

                // Two-phase (via temporary names) when targets overlap sources: swaps, chains, case changes.
                val twoPhase = pairs.any { it.second.lowercase() in sourcesLower }
                val done = ArrayList<Pair<String, String>>()
                try {
                    if (!twoPhase) {
                        for ((from, to) in pairs) { renameOne(from, to); done += from to to }
                    } else {
                        val temps = pairs.mapIndexed { i, (from, _) ->
                            PathUtil.join(PathUtil.parent(from), ".fichio-rn-${System.nanoTime() % 1_000_000}-$i")
                        }
                        pairs.forEachIndexed { i, (from, _) -> renameOne(from, temps[i]); done += from to temps[i] }
                        pairs.forEachIndexed { i, (_, to) -> renameOne(temps[i], to); done += temps[i] to to }
                    }
                } catch (e: Exception) {
                    // Roll back in reverse order.
                    done.asReversed().forEach { (from, to) -> runCatching { fs.backendFor(to).rename(to, from) } }
                    throw e
                }
                fs.notifyChanged(pairs.map { PathUtil.parent(it.first) })
                pairs.forEach { (from, to) -> onPathMoved(from, to, from in dirs) }
                if (recordUndo) undo.record(UndoAction.Renamed(pairs, "${Format2.items(pairs.size)} renommé${plural(pairs.size)}"))
                pairs.size
            }
        }

    /** Renames one item, handling case-only changes on case-insensitive storage. */
    private fun renameOne(from: String, to: String) {
        val b = fs.backendFor(from)
        if (to.equals(from, ignoreCase = true) && to != from) {
            if (b.rename(from, to)) return
            val tmp = PathUtil.join(PathUtil.parent(from), ".fichio-case-${System.nanoTime() % 1_000_000}")
            if (b.rename(from, tmp) && b.rename(tmp, to)) return
            if (b.exists(tmp)) b.rename(tmp, from)
            throw IOException("Impossible de renommer « ${PathUtil.name(from)} »")
        }
        if (b.exists(to)) throw IOException("« ${PathUtil.name(to)} » existe déjà")
        if (!b.rename(from, to)) throw IOException("Impossible de renommer « ${PathUtil.name(from)} »")
    }

    private suspend fun create(parent: String, rawName: String, folder: Boolean): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val name = rawName.trim()
            Names.validate(name)?.let { throw IllegalArgumentException(it) }
            val b = fs.backendFor(parent)
            val path = PathUtil.join(parent, name)
            if (b.exists(path)) throw IOException("« $name » existe déjà")
            val ok = if (folder) b.mkdirs(path) else b.createFile(path)
            if (!ok) throw IOException(if (folder) "Impossible de créer le dossier" else "Impossible de créer le fichier")
            fs.notifyChanged(parent)
            undo.record(UndoAction.Created(listOf(path), if (folder) "Dossier « $name » créé" else "Fichier « $name » créé"))
            path
        }
    }

    /** Keeps favourites (folders) and colour tags (files and folders) attached to moved items. */
    private suspend fun onPathMoved(from: String, to: String, isDirectory: Boolean) {
        val c = runCatching { context.container }.getOrNull() ?: return
        if (isDirectory) runCatching { c.favorites.onMoved(from, to) }
        runCatching { c.tags.onMoved(from, to) }
    }

    private fun EngineResult.toOutcome(undo: UndoAction? = null, moved: List<Moved> = emptyList()) = Outcome(
        undo = undo,
        touched = touchedDirs,
        failed = failed,
        skipped = skipped,
        error = errors.firstOrNull(),
        moved = moved,
        totalFailure = failed > 0 && processed == 0 && transfers.isEmpty() && skipped == 0,
    )

    private fun List<Transfer>.moves() = map { Moved(it.from, it.to, it.isDirectory) }

    private fun titleFor(verb: String, paths: List<String>) =
        if (paths.size == 1) "$verb de « ${PathUtil.name(paths[0])} »" else "$verb de ${Format2.items(paths.size)}"

    private fun plural(n: Int) = if (n > 1) "s" else ""

    private fun userMessage(t: Throwable): String = when (t) {
        is com.karelisio.fichio.core.fs.ShizukuUnavailableException ->
            "Android/data nécessite Shevery : lancez-le puis réessayez"
        is SecurityException -> "Accès refusé"
        else -> t.message ?: t.javaClass.simpleName
    }

    /** Local alias to keep the call sites short. */
    private object Format2 {
        fun items(n: Int) = com.karelisio.fichio.core.util.Format.items(n)
    }
}
