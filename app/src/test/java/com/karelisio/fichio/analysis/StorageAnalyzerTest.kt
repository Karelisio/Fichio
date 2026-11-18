package com.karelisio.fichio.analysis

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class StorageAnalyzerTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun file(rel: String, size: Int, byte: Byte = 1, ageDays: Int = 0): File {
        val f = File(tmp.root, rel)
        f.parentFile.mkdirs()
        f.writeBytes(ByteArray(size) { byte })
        if (ageDays > 0) f.setLastModified(System.currentTimeMillis() - ageDays * 24L * 3600 * 1000)
        return f
    }

    private fun scan(excluded: Set<String> = emptySet(), dupMin: Long = 10, cap: Int = 400_000): AnalysisResult = runBlocking {
        StorageAnalyzer(
            root = tmp.root.path,
            excluded = excluded,
            config = StorageAnalyzer.Config(poolMinBytes = 10, poolCap = cap, duplicateMinBytes = dupMin, protectedEmpty = setOf("dcim")),
        ).scan()
    }

    private val day = 24L * 3600 * 1000

    @Test fun buildsSizeTreeAndLists() {
        file("Music/a.mp3", 2000, 3)
        file("Music/copy/a.mp3", 2000, 3)
        file("Docs/old.txt", 50, ageDays = 400)
        file("top.bin", 100)
        File(tmp.root, "Docs/empty/inner").mkdirs()
        File(tmp.root, "DCIM").mkdirs()
        File(tmp.root, "Lonely").mkdirs()
        file("Android/data/secret.bin", 5000)

        val r = scan(setOf(File(tmp.root, "Android/data").path))
        assertEquals(4150L, r.totalBytes)
        assertEquals(4, r.totalFiles)
        val music = r.root.children.first { it.name == "Music" }
        assertEquals(4000L, music.size)
        assertTrue(r.root.children.any { it.kind == NodeKind.FILES && it.size == 100L })
        assertEquals(2, r.large(1000).size)
        assertEquals(listOf("old.txt"), r.old(10, 30 * day).map { it.name })
        assertEquals(1, r.duplicates.size)
        // Top-most empty subtree only, standard folders protected.
        assertTrue(r.emptyDirs.contains(File(tmp.root, "Docs/empty").path))
        assertFalse(r.emptyDirs.contains(File(tmp.root, "Docs/empty/inner").path))
        assertTrue(r.emptyDirs.contains(File(tmp.root, "Lonely").path))
        assertFalse(r.emptyDirs.contains(File(tmp.root, "DCIM").path))
        // Excluded folder never entered.
        assertTrue(r.large(1).none { it.path.contains("secret") })
    }

    @Test fun removingUpdatesTreeAndLists() {
        val a = file("Music/a.mp3", 2000, 3)
        file("Music/copy/a.mp3", 2000, 3)
        File(tmp.root, "Lonely").mkdirs()
        file("x.bin", 10)
        val r = scan()
        val after = r.removing(listOf(a.path, File(tmp.root, "Lonely").path))
        assertEquals(r.totalBytes - 2000, after.totalBytes)
        assertTrue(after.duplicates.isEmpty())
        assertTrue(after.emptyDirs.isEmpty())
        assertTrue(after.large(1000).none { it.path == a.path })
        assertEquals(1, after.large(1000).size)
        val music = after.root.children.first { it.name == "Music" }
        assertEquals(2000L, music.size)
    }

    @Test fun sunburstAggregatesSmallChildren() {
        repeat(30) { i -> file("Big/f$i/x.bin", if (i == 0) 100_000 else 10) }
        val r = scan()
        val model = SunburstModel(r.root)
        val big = model.displayChildren(r.root).first { it.name == "Big" }
        val kids = model.displayChildren(big)
        val others = kids.firstOrNull { it.kind == NodeKind.OTHERS }
        assertNotNull(others)
        assertEquals(big.size, kids.sumOf { it.size })
        // Memoised: same identity for the zoom transitions.
        assertTrue(kids === model.displayChildren(big))
        // Layout covers [0,1] at depth 1 and nests children inside their parent.
        val arcs = model.layout(r.root)
        val depth1 = arcs.filter { it.depth == 1 }
        assertEquals(1.0, depth1.sumOf { it.x1 - it.x0 }, 1e-9)
        val parent = arcs.first { it.node === big }
        arcs.filter { it.depth == 2 && it.family == parent.family }.forEach {
            assertTrue(it.x0 >= parent.x0 - 1e-9 && it.x1 <= parent.x1 + 1e-9)
        }
        assertTrue(arcs.all { it.depth <= 3 })
    }

    @Test fun thresholdsAreRecomputedWithoutRescanning() {
        file("A/big.bin", 5000)
        file("A/mid.bin", 1500, ageDays = 10)
        file("B/small.bin", 200, ageDays = 100)
        file("B/tiny.bin", 5)
        val r = scan()
        // Any threshold down to the pool floor, from the same result.
        assertEquals(listOf("big.bin"), r.large(3000).map { it.name })
        assertEquals(listOf("big.bin", "mid.bin", "small.bin"), r.large(100).map { it.name })
        assertEquals(listOf("mid.bin", "small.bin"), r.old(100, 7 * day).map { it.name })
        assertEquals(listOf("small.bin"), r.old(100, 30 * day).map { it.name })
        assertEquals(listOf("small.bin"), r.old(100, 7 * day, accept = { !it.endsWith("mid.bin") }).map { it.name })
        assertEquals(2 to 6500L, r.pool.stats(1000))
        // Below the floor: not in the pool, but counted by the histogram.
        assertTrue(r.large(1).none { it.name == "tiny.bin" })
        assertEquals(4L, r.histogram.countAtLeast(0))
        assertEquals(1L, r.histogram.countAtLeast(4096))
    }

    @Test fun smallerDuplicatesAreHashedOnDemand() {
        file("x/a.bin", 3000, 7); file("y/a.bin", 3000, 7)
        file("x/s.txt", 50, 9); file("y/s.txt", 50, 9)
        val r = scan(dupMin = 1000)
        assertEquals(1000L, r.duplicatesFrom)
        assertEquals(1, r.duplicates(1).size)
        // Only the same-size files not hashed yet are candidates for the extension.
        val extra = r.pool.sameSizeCandidates(10, r.duplicatesFrom)
        assertEquals(setOf("s.txt"), extra.map { it.name }.toSet())
        val groups = DuplicateFinder.forFiles(minSize = 10).find(extra)
        val extended = r.withDuplicates(groups, 10)
        assertEquals(10L, extended.duplicatesFrom)
        assertEquals(2, extended.duplicates(10).size)
        assertEquals(1, extended.duplicates(1000).size)
        // Deleted meanwhile: filtered out of the groups added later.
        val afterDelete = r.removing(listOf(File(tmp.root, "x/s.txt").path)).withDuplicates(groups, 10)
        assertEquals(1, afterDelete.duplicates(10).size)
    }

    @Test fun poolStaysBoundedAndKeepsTheBiggest() {
        repeat(40) { i -> file("many/f$i.bin", 100 + i * 10) }
        val r = scan(cap = 10)
        assertTrue(r.pool.size <= 10)
        assertTrue(r.pool.floor > 10)
        // Everything at or above the floor is still there, biggest first.
        val kept = r.large(1)
        assertEquals(kept.size, r.pool.size)
        assertEquals(490L, kept.first().size)
        assertTrue(kept.all { it.size >= r.pool.floor })
        assertEquals((0 until 40).count { 100 + it * 10 >= r.pool.floor }, kept.size)
        // The tree still counts every file.
        assertEquals(40, r.totalFiles)
    }
}
