package com.karelisio.fichio.ops

import com.karelisio.fichio.core.fs.FileBackend
import com.karelisio.fichio.core.fs.LocalBackend
import com.karelisio.fichio.core.model.FileEntry
import com.karelisio.fichio.ops.engine.ConflictAnswer
import com.karelisio.fichio.ops.engine.EngineListener
import com.karelisio.fichio.ops.engine.OperationEngine
import com.karelisio.fichio.ops.engine.OperationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

class OperationEngineTest {
    @get:Rule val tmp = TemporaryFolder()

    private val local = LocalBackend()
    private lateinit var root: File
    private lateinit var engine: OperationEngine

    @Before
    fun setUp() {
        root = tmp.newFolder("root")
        engine = OperationEngine({ local }, bufferSize = 1024)
    }

    private fun file(rel: String, content: String = rel, mtime: Long? = null): File =
        File(root, rel).apply {
            parentFile!!.mkdirs(); writeText(content)
            mtime?.let { setLastModified(it) }
        }

    private fun dir(rel: String) = File(root, rel).apply { mkdirs() }

    /** Listener answering conflicts with a fixed choice and counting questions. */
    private class Answering(val answer: ConflictAnswer) : EngineListener {
        var asked = 0
        val progress = mutableListOf<JobProgress>()
        override fun onProgress(progress: JobProgress) { this.progress += progress }
        override suspend fun onConflict(source: FileEntry, existing: FileEntry, remaining: Int): ConflictAnswer {
            asked++; return answer
        }
    }

    @Test
    fun copyFileKeepsContentAndDate() = runTest {
        val src = file("a/photo.jpg", "hello", mtime = 1_600_000_000_000)
        val dest = dir("b")
        val l = Answering(ConflictAnswer(ConflictChoice.SKIP))
        val r = engine.copy(listOf(src.path), dest.path, l)
        val out = File(dest, "photo.jpg")
        assertEquals("hello", out.readText())
        assertEquals(1_600_000_000_000, out.lastModified())
        assertTrue(src.exists())
        assertEquals(1, r.processed)
        assertEquals(listOf(out.path), r.transfers.map { it.to })
        val last = l.progress.last()
        assertEquals(last.totalBytes, last.doneBytes)
    }

    @Test
    fun copyDirectoryRecursively() = runTest {
        file("src/x/1.txt", "one"); file("src/x/y/2.txt", "two"); dir("src/empty")
        val dest = dir("dest")
        val r = engine.copy(listOf(File(root, "src").path), dest.path)
        assertEquals("one", File(dest, "src/x/1.txt").readText())
        assertEquals("two", File(dest, "src/x/y/2.txt").readText())
        assertTrue(File(dest, "src/empty").isDirectory)
        // One unit-level transfer for the whole folder.
        assertEquals(1, r.transfers.size)
        assertTrue(r.transfers[0].isDirectory)
        assertTrue(File(root, "src/x/1.txt").exists())
    }

    @Test
    fun copyBigFileInSmallChunksReportsBytes() = runTest {
        val big = File(root, "big.bin").apply { writeBytes(ByteArray(10_000) { (it % 251).toByte() }) }
        val dest = dir("d")
        val l = Answering(ConflictAnswer(ConflictChoice.SKIP))
        engine.copy(listOf(big.path), dest.path, l)
        assertTrue(File(dest, "big.bin").readBytes().contentEquals(big.readBytes()))
        assertEquals(10_000, l.progress.last().totalBytes)
        assertEquals(10_000, l.progress.last().doneBytes)
    }

    @Test
    fun moveFileAndDirectoryByRename() = runTest {
        val f = file("a/f.txt", "f"); file("a/sub/g.txt", "g")
        val dest = dir("b")
        val r = engine.move(listOf(f.path, File(root, "a/sub").path), dest.path)
        assertFalse(f.exists())
        assertFalse(File(root, "a/sub").exists())
        assertEquals("f", File(dest, "f.txt").readText())
        assertEquals("g", File(dest, "sub/g.txt").readText())
        assertEquals(2, r.processed)
        assertTrue(r.touchedDirs.contains(File(root, "a").path))
        assertTrue(r.touchedDirs.contains(dest.path))
    }

    /** A second backend id forces the copy + delete path (like SD card ↔ Android/data). */
    private class OtherBackend : LocalBackend() {
        override val id: String = "other"
    }

    @Test
    fun moveAcrossBackendsCopiesThenDeletes() = runTest {
        val other = OtherBackend()
        val otherRoot = tmp.newFolder("other")
        val e = OperationEngine({ p -> if (p.startsWith(otherRoot.path)) other else local }, bufferSize = 512)
        file("m/a.txt", "A", mtime = 1_500_000_000_000); file("m/deep/b.txt", "B")
        val r = e.move(listOf(File(root, "m").path), otherRoot.path)
        assertFalse(File(root, "m").exists())
        assertEquals("A", File(otherRoot, "m/a.txt").readText())
        assertEquals(1_500_000_000_000, File(otherRoot, "m/a.txt").lastModified())
        assertEquals("B", File(otherRoot, "m/deep/b.txt").readText())
        assertEquals(1, r.transfers.size)
    }

    @Test
    fun conflictReplace() = runTest {
        val src = file("s/doc.txt", "new")
        val dest = dir("d"); File(dest, "doc.txt").writeText("old")
        val l = Answering(ConflictAnswer(ConflictChoice.REPLACE))
        val r = engine.copy(listOf(src.path), dest.path, l)
        assertEquals("new", File(dest, "doc.txt").readText())
        assertEquals(1, r.replaced)
        assertEquals(1, l.asked)
        // No temporary leftovers.
        assertEquals(listOf("doc.txt"), dest.list()!!.toList())
    }

    @Test
    fun conflictSkip() = runTest {
        val src = file("s/doc.txt", "new")
        val dest = dir("d"); File(dest, "doc.txt").writeText("old")
        val r = engine.move(listOf(src.path), dest.path, Answering(ConflictAnswer(ConflictChoice.SKIP)))
        assertEquals("old", File(dest, "doc.txt").readText())
        assertTrue(src.exists())
        assertEquals(1, r.skipped)
        assertTrue(r.transfers.isEmpty())
    }

    @Test
    fun conflictKeepBothNumbersNames() = runTest {
        val src = file("s/doc.txt", "new")
        val dest = dir("d")
        File(dest, "doc.txt").writeText("old")
        File(dest, "doc (1).txt").writeText("older")
        engine.copy(listOf(src.path), dest.path, Answering(ConflictAnswer(ConflictChoice.KEEP_BOTH)))
        assertEquals("new", File(dest, "doc (2).txt").readText())
        assertEquals("old", File(dest, "doc.txt").readText())
        assertEquals("older", File(dest, "doc (1).txt").readText())
    }

    @Test
    fun applyToAllAsksOnce() = runTest {
        val srcs = (1..4).map { file("s/f$it.txt", "new$it") }
        val dest = dir("d")
        (1..4).forEach { File(dest, "f$it.txt").writeText("old") }
        val l = Answering(ConflictAnswer(ConflictChoice.REPLACE, applyToAll = true))
        engine.copy(srcs.map { it.path }, dest.path, l)
        assertEquals(1, l.asked)
        (1..4).forEach { assertEquals("new$it", File(dest, "f$it.txt").readText()) }
    }

    @Test
    fun withoutApplyToAllAsksEachTime() = runTest {
        val srcs = (1..3).map { file("s/f$it.txt") }
        val dest = dir("d")
        (1..3).forEach { File(dest, "f$it.txt").writeText("old") }
        val l = Answering(ConflictAnswer(ConflictChoice.SKIP))
        val r = engine.copy(srcs.map { it.path }, dest.path, l)
        assertEquals(3, l.asked)
        assertEquals(3, r.skipped)
    }

    @Test
    fun foldersAreMerged() = runTest {
        file("s/album/a.jpg", "a"); file("s/album/sub/b.jpg", "b")
        val dest = dir("d"); file("d/album/existing.jpg", "e")
        val l = Answering(ConflictAnswer(ConflictChoice.SKIP))
        engine.move(listOf(File(root, "s/album").path), dest.path, l)
        assertEquals(0, l.asked)
        assertEquals("a", File(dest, "album/a.jpg").readText())
        assertEquals("b", File(dest, "album/sub/b.jpg").readText())
        assertEquals("e", File(dest, "album/existing.jpg").readText())
        assertFalse(File(root, "s/album").exists())
    }

    @Test
    fun copyIntoSameFolderKeepsBoth() = runTest {
        val f = file("d/note.txt", "x")
        engine.copy(listOf(f.path), f.parent)
        assertEquals("x", File(root, "d/note (1).txt").readText())
        assertTrue(f.exists())
    }

    @Test
    fun folderIntoItselfIsRefused() = runTest {
        file("box/inner/a.txt")
        val box = File(root, "box")
        for (move in listOf(true, false)) {
            try {
                if (move) engine.move(listOf(box.path), File(box, "inner").path)
                else engine.copy(listOf(box.path), box.path + "/inner")
                fail("expected OperationException")
            } catch (e: OperationException) {
                assertTrue(e.message!!.contains("dans lui-même"))
            }
        }
        assertTrue(File(root, "box/inner/a.txt").exists())
        assertFalse(File(root, "box/inner/box").exists())
    }

    @Test
    fun notEnoughSpaceIsReported() = runTest {
        val tiny = object : LocalBackend() {
            override fun freeSpace(path: String): Long = 3
        }
        val e = OperationEngine({ tiny })
        val f = file("s/big.txt", "0123456789")
        try {
            e.copy(listOf(f.path), dir("d").path)
            fail("expected OperationException")
        } catch (ex: OperationException) {
            assertTrue(ex.message!!.startsWith("Espace insuffisant"))
        }
        assertFalse(File(root, "d/big.txt").exists())
    }

    @Test
    fun cancellationLeavesNoPartialFile() = runTest {
        val big = File(root, "big.bin").apply { writeBytes(ByteArray(50_000)) }
        val dest = dir("d")
        val l = object : EngineListener {
            var calls = 0
            override suspend fun checkpoint() {
                if (++calls > 10) throw CancellationException("stop")
            }
        }
        try {
            engine.copy(listOf(big.path), dest.path, l)
            fail("expected cancellation")
        } catch (_: CancellationException) {
        }
        assertFalse(File(dest, "big.bin").exists())
        assertTrue(big.exists())
    }

    @Test
    fun cancelledReplaceKeepsOriginal() = runTest {
        val src = File(root, "s/big.bin").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(20_000) { 1 }) }
        val dest = dir("d"); File(dest, "big.bin").writeText("original")
        val l = object : EngineListener {
            var calls = 0
            override suspend fun checkpoint() { if (++calls > 8) throw CancellationException("stop") }
            override suspend fun onConflict(source: FileEntry, existing: FileEntry, remaining: Int) = ConflictAnswer(ConflictChoice.REPLACE)
        }
        runCatching { engine.copy(listOf(src.path), dest.path, l) }
        assertEquals("original", File(dest, "big.bin").readText())
        assertEquals(listOf("big.bin"), dest.list()!!.toList())
    }

    @Test
    fun deleteRecursively() = runTest {
        file("t/a/b/c.txt"); file("t/d.txt")
        val r = engine.delete(listOf(File(root, "t").path))
        assertFalse(File(root, "t").exists())
        assertEquals(1, r.processed)
        assertEquals(0, r.failed)
    }

    @Test
    fun missingSourceIsCountedAsFailure() = runTest {
        val r = engine.copy(listOf(File(root, "nope.txt").path), dir("d").path)
        assertEquals(1, r.failed)
        assertTrue(r.errors[0].contains("introuvable"))
    }

    @Test
    fun moveExactKeepsBothOnConflict() = runTest {
        val f = file("x/a.txt", "moved")
        file("y/a.txt", "there")
        val r = engine.moveExact(listOf(f.path to File(root, "y/a.txt").path))
        assertEquals("moved", File(root, "y/a (1).txt").readText())
        assertEquals("there", File(root, "y/a.txt").readText())
        assertEquals(File(root, "y/a (1).txt").path, r.transfers.single().to)
    }

    @Test
    fun measureCountsEverything() = runTest {
        file("m/a.txt", "12345"); file("m/s/b.txt", "123")
        var last: com.karelisio.fichio.ops.engine.SizeStats? = null
        engine.measure(listOf(File(root, "m").path)).collect { last = it }
        assertEquals(8L, last!!.bytes)
        assertEquals(2, last!!.files)
        assertEquals(2, last!!.folders)
        assertTrue(last!!.complete)
    }

    @Suppress("unused")
    private fun FileBackend.dummy() = Unit
}
