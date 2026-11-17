package com.karelisio.fichio.domain.operations

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class FileTransferEngineTest {

    private fun tempDir(name: String): File =
        Files.createTempDirectory("fichio-$name").toFile()

    @Test
    fun copyFileToDirectory() = runBlocking {
        val src = tempDir("src")
        val dst = tempDir("dst")
        val origin = File(src, "a.txt").apply { writeText("hello") }

        val engine = FileTransferEngine()
        val outcome = engine.copy(listOf(origin), dst)

        assertTrue(origin.exists()) // copy keeps source
        assertEquals("hello", File(dst, "a.txt").readText())
        assertEquals(1, outcome.completedFiles)
        assertEquals(0, outcome.failedFiles)
    }

    @Test
    fun copyConflictKeepBoth() = runBlocking {
        val src = tempDir("src")
        val dst = tempDir("dst")
        File(src, "a.txt").apply { writeText("new") }
        File(dst, "a.txt").apply { writeText("old") }

        val outcome = FileTransferEngine().copy(
            listOf(File(src, "a.txt")), dst,
            defaultConflict = ConflictAction.KEEP_BOTH
        )

        assertEquals("old", File(dst, "a.txt").readText())
        assertEquals("new", File(dst, "a (1).txt").readText())
        assertEquals(1, outcome.completedFiles)
    }

    @Test
    fun copyConflictSkip() = runBlocking {
        val src = tempDir("src")
        val dst = tempDir("dst")
        File(src, "a.txt").apply { writeText("new") }
        File(dst, "a.txt").apply { writeText("old") }

        val outcome = FileTransferEngine().copy(
            listOf(File(src, "a.txt")), dst,
            defaultConflict = ConflictAction.SKIP
        )

        assertEquals("old", File(dst, "a.txt").readText())
        assertFalse(File(dst, "a (1).txt").exists())
        assertEquals(0, outcome.completedFiles)
        assertEquals(1, outcome.skippedFiles)
    }

    @Test
    fun moveRemovesSource() = runBlocking {
        val src = tempDir("src")
        val dst = tempDir("dst")
        val origin = File(src, "a.txt").apply { writeText("hello") }

        FileTransferEngine().move(listOf(origin), dst)

        assertFalse(origin.exists())
        assertEquals("hello", File(dst, "a.txt").readText())
    }

    @Test
    fun deleteRecursive() = runBlocking {
        val root = tempDir("root")
        val dir = File(root, "dir").apply { mkdirs() }
        File(dir, "a.txt").apply { writeText("x") }

        FileTransferEngine().delete(listOf(dir))

        assertFalse(dir.exists())
    }

    @Test
    fun copyDirectoryRecursively() = runBlocking {
        val src = tempDir("src")
        val dst = tempDir("dst")
        val sub = File(src, "sub").apply { mkdirs() }
        File(sub, "a.txt").apply { writeText("hello") }

        val outcome = FileTransferEngine().copy(listOf(sub), dst)

        assertTrue(File(dst, "sub").isDirectory)
        assertEquals("hello", File(dst, "sub/a.txt").readText())
        assertEquals(2, outcome.completedFiles) // dir + file
    }
}
