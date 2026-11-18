package com.karelisio.fichio.ops

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.karelisio.fichio.core.fs.FileSystems
import com.karelisio.fichio.core.fs.LocalBackend
import com.karelisio.fichio.data.db.FichioDatabase
import com.karelisio.fichio.trash.TrashRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Queue, conflicts, renames and undo through the real manager (Robolectric for the Context). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "fr")
class OperationManagerTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var db: FichioDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var ops: OperationManager
    private lateinit var root: File
    private lateinit var ctx: Application
    private lateinit var fs: FileSystems

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext<Application>()
        com.karelisio.fichio.core.util.Strings.init(ctx)
        db = FichioDatabase.inMemory(ctx)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        fs = FileSystems(LocalBackend())
        ops = OperationManager(ctx, fs, TrashRepository(ctx, db, fs), UndoManager(), scope)
        root = tmp.newFolder("r")
    }

    @After
    fun tearDown() {
        scope.cancel(); db.close()
    }

    private fun file(rel: String, text: String = rel) = File(root, rel).apply { parentFile!!.mkdirs(); writeText(text) }

    private fun <T> blocking(block: suspend () -> T): T = runBlocking { withTimeout(20_000) { block() } }

    @Test
    fun copyWithConflictWaitsForTheUser() = blocking {
        val src = file("a/x.txt", "new")
        file("b/x.txt", "old")
        val id = ops.copy(listOf(src.path), File(root, "b").path)
        val req = ops.conflict.filterNotNull().first()
        assertEquals(id, req.jobId)
        assertEquals(JobState.WAITING_CONFLICT, ops.jobs.value.first { it.id == id }.state)
        ops.resolveConflict(ConflictChoice.KEEP_BOTH, applyToAll = false)
        val job = ops.await(id)!!
        assertEquals(JobState.DONE, job.state)
        assertEquals("new", File(root, "b/x (1).txt").readText())
        assertEquals("old", File(root, "b/x.txt").readText())
    }

    @Test
    fun jobsRunOneAtATimeAndFinishedCanBeCleared() = blocking {
        val a = file("s/a.txt"); val b = file("s/b.txt")
        val dest = File(root, "d").apply { mkdirs() }
        val id1 = ops.copy(listOf(a.path), dest.path)
        val id2 = ops.copy(listOf(b.path), dest.path)
        assertEquals(JobState.DONE, ops.await(id1)!!.state)
        assertEquals(JobState.DONE, ops.await(id2)!!.state)
        assertEquals(2, ops.jobs.value.size)
        ops.clearFinished()
        assertTrue(ops.jobs.value.isEmpty())
    }

    @Test
    fun folderIntoItselfFailsWithMessage() = blocking {
        file("box/in/a.txt")
        val id = ops.move(listOf(File(root, "box").path), File(root, "box/in").path)
        val job = ops.await(id)!!
        assertEquals(JobState.FAILED, job.state)
        assertTrue(job.error!!.contains("dans lui-même"))
    }

    @Test
    fun moveThenUndo() = blocking {
        val f = file("from/doc.txt", "d")
        val dest = File(root, "to").apply { mkdirs() }
        assertEquals(JobState.DONE, ops.await(ops.move(listOf(f.path), dest.path))!!.state)
        assertFalse(f.exists())
        assertTrue(ops.undo.last.value is UndoAction.Moved)
        assertTrue(ops.undo.undo())
        assertEquals("d", f.readText())
        assertFalse(File(dest, "doc.txt").exists())
    }

    @Test
    fun renameValidatesAndUndoes() = blocking {
        val f = file("n/old.txt")
        assertTrue(ops.rename(f.path, "a/b").isFailure)
        file("n/taken.txt")
        assertTrue(ops.rename(f.path, "taken.txt").isFailure)
        val newPath = ops.rename(f.path, "new.txt").getOrThrow()
        assertTrue(File(newPath).exists())
        assertTrue(ops.undo.undo())
        assertTrue(f.exists())
    }

    @Test
    fun batchRenameSwapsNames() = blocking {
        val a = file("bt/a.txt", "A"); val b = file("bt/b.txt", "B")
        val n = ops.batchRename(listOf(a.path to "b.txt", b.path to "a.txt")).getOrThrow()
        assertEquals(2, n)
        assertEquals("B", a.readText())
        assertEquals("A", b.readText())
        assertTrue(File(root, "bt").list()!!.none { it.startsWith(".fichio") })
    }

    @Test
    fun batchRenameRefusesExistingTarget() = blocking {
        val a = file("bx/a.txt"); file("bx/c.txt")
        assertTrue(ops.batchRename(listOf(a.path to "c.txt")).isFailure)
        assertTrue(a.exists())
    }

    @Test
    fun createFolderAndFile() = blocking {
        val dir = ops.createFolder(root.path, "Nouveau dossier").getOrThrow()
        assertTrue(File(dir).isDirectory)
        assertTrue(ops.createFolder(root.path, "Nouveau dossier").isFailure)
        assertTrue(ops.createFile(root.path, "").isFailure)
        assertTrue(ops.createFile(root.path, "x*y.txt").isFailure)
        val f = ops.createFile(dir, "note.txt").getOrThrow()
        assertTrue(File(f).isFile)
    }

    @Test
    fun cancelQueuedJob() = blocking {
        val src = file("c/x.txt", "new"); file("d/x.txt", "old")
        // The first job blocks on its conflict; the second stays queued and is cancelled.
        val first = ops.copy(listOf(src.path), File(root, "d").path)
        ops.conflict.filterNotNull().first()
        val second = ops.copy(listOf(src.path), File(root, "e").apply { mkdirs() }.path)
        assertEquals(JobState.QUEUED, ops.jobs.value.first { it.id == second }.state)
        ops.cancel(second)
        assertEquals(JobState.CANCELLED, ops.await(second)!!.state)
        ops.resolveConflict(ConflictChoice.SKIP, false)
        assertEquals(JobState.DONE, ops.await(first)!!.state)
        assertFalse(File(root, "e/x.txt").exists())
    }

    private fun parallelManager(limit: MutableStateFlow<Int>) =
        OperationManager(ctx, fs, TrashRepository(ctx, db, fs), UndoManager(), scope, limit)

    @Test
    fun jobsRunInParallelUpToTheLiveLimit() = blocking {
        val limit = MutableStateFlow(1)
        val par = parallelManager(limit)
        val src = file("p/x.txt", "new"); file("q/x.txt", "old")
        val other = file("p/y.txt", "y")
        // The first job holds its slot while waiting for the user on a conflict.
        val first = par.copy(listOf(src.path), File(root, "q").path)
        par.conflict.filterNotNull().first()
        val second = par.copy(listOf(other.path), File(root, "r").apply { mkdirs() }.path)
        delay(300)
        assertEquals(JobState.QUEUED, par.jobs.value.first { it.id == second }.state)
        // Raising the limit admits the queued job at once: it completes while the first still waits.
        limit.value = 2
        assertEquals(JobState.DONE, par.await(second)!!.state)
        assertEquals("y", File(root, "r/y.txt").readText())
        assertEquals(JobState.WAITING_CONFLICT, par.jobs.value.first { it.id == first }.state)
        par.resolveConflict(ConflictChoice.REPLACE, applyToAll = false)
        assertEquals(JobState.DONE, par.await(first)!!.state)
        assertEquals("new", File(root, "q/x.txt").readText())
    }

    @Test
    fun conflictsOfParallelJobsAreAskedOneAtATime() = blocking {
        val par = parallelManager(MutableStateFlow(2))
        val a = file("s1/a.txt", "A"); file("t1/a.txt", "old")
        val b = file("s2/b.txt", "B"); file("t2/b.txt", "old")
        val j1 = par.copy(listOf(a.path), File(root, "t1").path)
        val j2 = par.copy(listOf(b.path), File(root, "t2").path)
        val firstAsk = par.conflict.filterNotNull().first()
        delay(300)
        // Both jobs run, but only one dialog is shown.
        assertEquals(firstAsk.jobId, par.conflict.value?.jobId)
        assertTrue(par.jobs.value.none { it.state == JobState.QUEUED })
        assertEquals(1, par.jobs.value.count { it.state == JobState.WAITING_CONFLICT })
        par.resolveConflict(ConflictChoice.REPLACE, applyToAll = false)
        val secondAsk = par.conflict.filterNotNull().first { it.jobId != firstAsk.jobId }
        assertEquals(setOf(j1, j2), setOf(firstAsk.jobId, secondAsk.jobId))
        par.resolveConflict(ConflictChoice.REPLACE, applyToAll = false)
        assertEquals(JobState.DONE, par.await(j1)!!.state)
        assertEquals(JobState.DONE, par.await(j2)!!.state)
        assertEquals("A", File(root, "t1/a.txt").readText())
        assertEquals("B", File(root, "t2/b.txt").readText())
    }
}
