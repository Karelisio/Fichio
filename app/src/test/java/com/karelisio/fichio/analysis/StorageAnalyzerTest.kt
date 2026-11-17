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

    private fun scan(excluded: Set<String> = emptySet()): AnalysisResult = runBlocking {
        StorageAnalyzer(
            root = tmp.root.path,
            excluded = excluded,
            config = StorageAnalyzer.Config(largeMinBytes = 1000, oldMinBytes = 10, duplicateMinBytes = 10, protectedEmpty = setOf("dcim")),
        ).scan()
    }

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
        assertEquals(2, r.large.size)
        assertEquals(listOf("old.txt"), r.old.map { it.name })
        assertEquals(1, r.duplicates.size)
        // Top-most empty subtree only, standard folders protected.
        assertTrue(r.emptyDirs.contains(File(tmp.root, "Docs/empty").path))
        assertFalse(r.emptyDirs.contains(File(tmp.root, "Docs/empty/inner").path))
        assertTrue(r.emptyDirs.contains(File(tmp.root, "Lonely").path))
        assertFalse(r.emptyDirs.contains(File(tmp.root, "DCIM").path))
        // Excluded folder never entered.
        assertTrue(r.large.none { it.path.contains("secret") })
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
        assertTrue(after.large.none { it.path == a.path })
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
}
