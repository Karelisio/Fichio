package com.karelisio.fichio.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.coroutines.cancellation.CancellationException

class DuplicateFinderTest {
    @get:Rule val tmp = TemporaryFolder()

    /** Fake content: path → (partial, full) hashes. */
    private fun finder(content: Map<String, Pair<String, String>>, calls: MutableList<String> = mutableListOf()) =
        DuplicateFinder(
            partialHash = { calls += "p:${it.path}"; content[it.path]?.first },
            fullHash = { calls += "f:${it.path}"; content[it.path]?.second },
        )

    @Test fun groupsIdenticalFilesBySizeThenHash() {
        val files = listOf(FileRef("/a", 100, 1), FileRef("/b", 100, 3), FileRef("/c", 100, 2))
        val groups = finder(files.associate { it.path to ("P" to "F") }).find(files)
        assertEquals(1, groups.size)
        assertEquals(3, groups[0].files.size)
        assertEquals(200L, groups[0].wasted)
        assertEquals("/b", groups[0].newest.path)
    }

    @Test fun differentSizesAreNeverHashed() {
        val calls = mutableListOf<String>()
        val files = listOf(FileRef("/a", 100, 1), FileRef("/b", 101, 1))
        val groups = finder(files.associate { it.path to ("P" to "F") }, calls).find(files)
        assertTrue(groups.isEmpty())
        assertTrue(calls.isEmpty())
    }

    @Test fun partialHashMismatchSkipsFullHash() {
        val calls = mutableListOf<String>()
        val files = listOf(FileRef("/a", 100, 1), FileRef("/b", 100, 1))
        val groups = finder(mapOf("/a" to ("P1" to "F"), "/b" to ("P2" to "F")), calls).find(files)
        assertTrue(groups.isEmpty())
        assertTrue(calls.none { it.startsWith("f:") })
    }

    @Test fun samePartialButDifferentContentIsAFalsePositive() {
        val files = listOf(FileRef("/a", 100, 1), FileRef("/b", 100, 1), FileRef("/c", 100, 1))
        val groups = finder(mapOf("/a" to ("P" to "F1"), "/b" to ("P" to "F2"), "/c" to ("P" to "F1"))).find(files)
        assertEquals(1, groups.size)
        assertEquals(setOf("/a", "/c"), groups[0].files.map { it.path }.toSet())
    }

    @Test fun unreadableFilesAndEmptyFilesAreIgnored() {
        val files = listOf(FileRef("/a", 100, 1), FileRef("/b", 100, 1), FileRef("/e1", 0, 1), FileRef("/e2", 0, 1))
        val groups = finder(mapOf("/a" to ("P" to "F"), "/e1" to ("P" to "F"), "/e2" to ("P" to "F"))).find(files)
        assertTrue(groups.isEmpty())
    }

    @Test fun duplicatePathsAreCountedOnce() {
        val files = listOf(FileRef("/a", 100, 1), FileRef("/a", 100, 1))
        assertTrue(finder(mapOf("/a" to ("P" to "F"))).find(files).isEmpty())
    }

    @Test fun groupsSortedByWastedSpace() {
        val files = listOf(FileRef("/s1", 10, 1), FileRef("/s2", 10, 1), FileRef("/b1", 1000, 1), FileRef("/b2", 1000, 1))
        val content = mapOf("/s1" to ("p" to "small"), "/s2" to ("p" to "small"), "/b1" to ("P" to "big"), "/b2" to ("P" to "big"))
        val groups = finder(content).find(files)
        assertEquals(listOf(1000L, 10L), groups.map { it.size })
    }

    @Test(expected = CancellationException::class)
    fun cancellationStopsTheSearch() {
        val files = listOf(FileRef("/a", 100, 1), FileRef("/b", 100, 1))
        finder(files.associate { it.path to ("P" to "F") }).find(files, isActive = { false })
    }

    @Test fun realHashingDistinguishesSameHeadAndTail() {
        val chunk = DuplicateFinder.PARTIAL_CHUNK
        val size = chunk * 3
        fun write(name: String, middle: Byte): FileRef {
            val bytes = ByteArray(size) { 7 }
            for (i in chunk until 2 * chunk) bytes[i] = middle
            val f = tmp.newFile(name).apply { writeBytes(bytes) }
            return FileRef(f.path, size.toLong(), f.lastModified())
        }
        val a = write("a.bin", 1)
        val b = write("b.bin", 1)
        val c = write("c.bin", 2) // same first/last 64 KB, different middle
        assertEquals(FileHashing.partial(a.path, a.size), FileHashing.partial(c.path, c.size))
        val groups = DuplicateFinder.forFiles().find(listOf(a, b, c))
        assertEquals(1, groups.size)
        assertEquals(setOf(a.path, b.path), groups[0].files.map { it.path }.toSet())
    }
}
