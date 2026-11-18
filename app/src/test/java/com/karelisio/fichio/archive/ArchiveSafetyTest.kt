package com.karelisio.fichio.archive

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ArchiveSafetyTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun normalizeResolvesDotsAndRejectsEscapes() {
        assertEquals("a/b.txt", ArchivePaths.normalize("a/./b.txt"))
        assertEquals("b.txt", ArchivePaths.normalize("a/../b.txt"))
        assertEquals("dir/f", ArchivePaths.normalize("dir\\f"))
        assertEquals("x", ArchivePaths.normalize("/x"))
        assertEquals("win/f", ArchivePaths.normalize("C:\\win\\f"))
        assertNull(ArchivePaths.normalize("../evil"))
        assertNull(ArchivePaths.normalize("a/../../evil"))
    }

    @Test fun safeTargetStaysInsideDestination() {
        val dest = tmp.newFolder("dest")
        assertEquals(File(dest.canonicalFile, "a/b.txt"), ArchivePaths.safeTarget(dest, "a/b.txt"))
        for (bad in listOf("../x", "a/../../x", "/etc/passwd", "C:\\x", "", "..")) {
            try {
                ArchivePaths.safeTarget(dest, bad)
                throw AssertionError("accepted $bad")
            } catch (_: ZipSlipException) {
            }
        }
    }

    @Test fun zipSlipEntriesAreRejectedAtExtraction() = runBlocking {
        val zip = File(tmp.root, "evil.zip")
        ZipOutputStream(zip.outputStream()).use { z ->
            for (name in listOf("../evil.txt", "ok/../../evil2.txt", "/abs.txt", "good/file.txt")) {
                z.putNextEntry(ZipEntry(name)); z.write(name.toByteArray()); z.closeEntry()
            }
        }
        val dest = File(tmp.root, "box/dest").apply { mkdirs() }
        Archives.open(zip).use { r ->
            val unsafe = r.entries().filter { it.isUnsafe }.map { it.path }.toSet()
            assertEquals(setOf("evil.txt", "ok/evil2.txt", "abs.txt"), unsafe)
            val res = r.extract(ExtractRequest(dest))
            assertEquals(1, res.extracted)
            assertEquals(3, res.rejected)
        }
        assertTrue(File(dest, "good/file.txt").isFile)
        assertFalse(File(tmp.root, "box/evil.txt").exists())
        assertFalse(File(tmp.root, "evil2.txt").exists())
        assertFalse(File(dest, "evil.txt").exists())
        assertFalse(File(dest, "abs.txt").exists())
    }

    @Test fun treeReconstructsAndSorts() {
        val tree = ArchiveTree(
            listOf(
                ArchiveItem.fromRaw("b/z.txt", false, 10)!!,
                ArchiveItem.fromRaw("a.txt", false, 5)!!,
                ArchiveItem.fromRaw("b/c/d/e.bin", false, 7)!!,
                ArchiveItem.fromRaw("B2/", true, 0)!!,
                ArchiveItem.fromRaw("b/z.txt", false, 11)!!, // duplicate: last wins
            ),
        )
        assertEquals(listOf("b", "B2", "a.txt"), tree.children("").map { it.name })
        assertEquals(listOf("c", "z.txt"), tree.children("b").map { it.name })
        assertTrue(tree["b/c/d"]!!.isImplicit)
        assertEquals(11L, tree["b/z.txt"]!!.size)
        assertEquals(3, tree.fileCount)
        assertEquals(23L, tree.totalSize)
        assertEquals(listOf("b/c", "b/c/d", "b/c/d/e.bin"), tree.select(listOf("b/c")).map { it.path }.sorted())
        assertEquals(18L, tree.sizeOf(listOf("b")))
        assertEquals(2, tree.countFiles("b"))
    }

    @Test fun uniqueFileNames() {
        val dir = tmp.newFolder("u")
        val f = File(dir, "photo.jpg").apply { writeText("x") }
        assertEquals("photo (1).jpg", ArchivePaths.uniqueFile(f).name)
        File(dir, "photo (1).jpg").writeText("y")
        assertEquals("photo (2).jpg", ArchivePaths.uniqueFile(f).name)
        assertEquals("neuf.txt", ArchivePaths.uniqueFile(File(dir, "neuf.txt")).name)
    }

    @Test fun levelLabelsAndSuggestedName() {
        assertEquals(com.karelisio.fichio.core.util.Strings.get(com.karelisio.fichio.R.string.archive_level_store), ArchiveWriter.levelLabel(0))
        assertEquals(com.karelisio.fichio.core.util.Strings.get(com.karelisio.fichio.R.string.archive_level_ultra), ArchiveWriter.levelLabel(9))
        val dir = tmp.newFolder("Vacances")
        val a = File(dir, "a.jpg").apply { writeText("a") }
        val b = File(dir, "b.jpg").apply { writeText("b") }
        assertEquals("a", ArchiveWriter.suggestedName(listOf(a)))
        assertEquals("Vacances", ArchiveWriter.suggestedName(listOf(a, b)))
        assertEquals("Vacances", ArchiveWriter.suggestedName(listOf(dir)))
    }
}
