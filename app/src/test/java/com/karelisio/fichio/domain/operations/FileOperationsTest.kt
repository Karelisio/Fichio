package com.karelisio.fichio.domain.operations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class BatchRenamerTest {

    @Test
    fun prefixNumberingKeepsExtension() {
        val plan = BatchRenamer.preview(
            listOf("a.txt" to false),
            BatchRenameSpec(prefix = "IMG_", numbering = true, startNumber = 1, padding = 3)
        )
        assertEquals("IMG_a_001.txt", plan.single().renamed)
    }

    @Test
    fun searchReplace() {
        val plan = BatchRenamer.preview(
            listOf("hello.txt" to false),
            BatchRenameSpec(find = "hello", replace = "bye")
        )
        assertEquals("bye.txt", plan.single().renamed)
    }

    @Test
    fun titleCaseWords() {
        val plan = BatchRenamer.preview(
            listOf("hello world.txt" to false),
            BatchRenameSpec(case = CaseTransform.TITLE)
        )
        assertEquals("Hello World.txt", plan.single().renamed)
    }

    @Test
    fun directoryHasNoExtension() {
        val plan = BatchRenamer.preview(
            listOf("docs" to true),
            BatchRenameSpec(case = CaseTransform.UPPER, suffix = "_bak")
        )
        assertEquals("DOCS_bak", plan.single().renamed)
    }

    @Test
    fun avoidsDuplicateOutputs() {
        val plan = BatchRenamer.preview(
            listOf("a.txt" to false, "a.txt" to false),
            BatchRenameSpec()
        )
        assertEquals("a.txt", plan[0].renamed)
        assertEquals("a (1).txt", plan[1].renamed)
    }

    @Test
    fun identityWhenSpecIsEmpty() {
        val names = listOf("a.txt" to false, "b.png" to false)
        assertTrue(BatchRenamer.isIdentity(names, BatchRenameSpec()))
    }

    @Test
    fun notIdentityWhenRenaming() {
        val names = listOf("a.txt" to false)
        assertFalse(BatchRenamer.isIdentity(names, BatchRenameSpec(prefix = "p_")))
    }
}

class DuplicateDetectorTest {

    @Test
    fun groupsSameSizeOnly() {
        val files = listOf(
            "/a" to 10L,
            "/b" to 10L,
            "/c" to 20L,
            "/d" to 10L
        )
        val groups = DuplicateDetector.groupBySize(files)
        assertEquals(setOf("/a", "/b", "/d"), groups[10L]?.toSet())
        assertEquals(null, groups[20L])
    }

    @Test
    fun ignoresZeroByteAndSingletons() {
        val files = listOf("/a" to 0L, "/b" to 5L, "/c" to 9L)
        assertTrue(DuplicateDetector.groupBySize(files).isEmpty())
    }
}

class TransferTokenTest {

    @Test
    fun generatesRequestedLength() {
        assertEquals(8, TransferToken.generate(length = 8).length)
        assertEquals(6, TransferToken.generate(length = 6).length)
    }

    @Test
    fun constantTimeEqualsMatchesAndRejects() {
        val token = TransferToken.generate()
        assertTrue(TransferToken.constantTimeEquals(token, token))
        assertFalse(TransferToken.constantTimeEquals(token, "DIFFERENT"))
        assertFalse(TransferToken.constantTimeEquals(token, token + "X"))
    }
}

class UniquePathTest {

    @Test
    fun insertsCounterBeforeExtension() {
        val dir = Files.createTempDirectory("fichio").toFile()
        val existing = File(dir, "photo.jpg")
        assertTrue(existing.createNewFile())

        val candidate = uniquePath(existing.absolutePath)
        assertEquals(File(dir, "photo (1).jpg").absolutePath, candidate)
        assertFalse(File(candidate).exists())

        dir.deleteRecursively()
    }
}

class HashingTest {

    @Test
    fun md5KnownVector() {
        assertEquals("900150983cd24fb0d6963f7d28e17f72", Hashing.md5("abc".toByteArray()))
    }

    @Test
    fun sha256KnownVector() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Hashing.sha256("abc".toByteArray())
        )
    }

    @Test
    fun fileHashingMatchesBytes() {
        val file = File.createTempFile("fichio", ".txt")
        file.writeText("abc")
        assertEquals(Hashing.md5("abc".toByteArray()), Hashing.md5(file))
        assertEquals(Hashing.sha256("abc".toByteArray()), Hashing.sha256(file))
        file.delete()
    }
}
