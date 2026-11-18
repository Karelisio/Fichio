package com.karelisio.fichio.core

import com.karelisio.fichio.core.fs.LocalBackend
import com.karelisio.fichio.core.model.SortBy
import com.karelisio.fichio.core.model.SortSpec
import com.karelisio.fichio.core.util.Sorting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.ClassRule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Micro-benchmark (JVM): listing + sorting a 10 000-entry folder must stay under the 150 ms
 * budget of one folder opening. The best of several warmed-up runs is compared so that a busy
 * CI machine does not make the test flaky; the timings are printed for the record.
 */
class ListingPerformanceTest {
    companion object {
        private const val COUNT = 10_000
        private const val BUDGET_MS = 150.0

        @get:ClassRule @JvmStatic val tmp = TemporaryFolder()
        private lateinit var dir: File

        @BeforeClass @JvmStatic fun createFolder() {
            dir = tmp.newFolder("big")
            val exts = listOf("jpg", "mp4", "pdf", "txt", "kt", "zip", "apk", "mp3", "docx", "")
            for (i in 0 until COUNT) {
                when {
                    i % 10 == 0 -> File(dir, "Dossier ${i / 10}").mkdir()
                    else -> {
                        val ext = exts[i % exts.size]
                        val name = when (i % 3) {
                            0 -> "IMG_2026${i}"
                            1 -> "Rapport été n°$i"
                            else -> "photo ($i) copie"
                        } + if (ext.isEmpty()) "" else ".$ext"
                        File(dir, name).writeBytes(ByteArray(i % 7))
                    }
                }
            }
        }
    }

    private val backend = LocalBackend()

    /** Best wall time in ms of [runs] executions of [block], after [warmup] untimed ones. */
    private fun best(warmup: Int = 4, runs: Int = 6, block: () -> Unit): Double {
        repeat(warmup) { block() }
        return (1..runs).minOf {
            val t0 = System.nanoTime()
            block()
            (System.nanoTime() - t0) / 1e6
        }
    }

    @Test fun listingAndSortingTenThousandEntriesFitsTheBudget() {
        assertEquals(COUNT, backend.list(dir.path).size)
        val listMs = best { backend.list(dir.path) }
        val entries = backend.list(dir.path)
        val report = StringBuilder("LocalBackend.list($COUNT) = ${"%.1f".format(listMs)} ms")
        for (by in SortBy.entries) {
            val spec = SortSpec(by, ascending = by == SortBy.NAME, foldersFirst = true)
            val sortMs = best { Sorting.sort(entries, spec) }
            val totalMs = best { Sorting.sort(backend.list(dir.path), spec) }
            report.append(" | sort $by = ${"%.1f".format(sortMs)} ms, list+sort = ${"%.1f".format(totalMs)} ms")
            assertTrue("list + sort($by) took $totalMs ms (> $BUDGET_MS ms)", totalMs < BUDGET_MS)
        }
        println(report)
    }

    @Test fun sortingIsCorrectOnTheBigFolder() {
        val sorted = Sorting.sort(backend.list(dir.path), SortSpec(SortBy.NAME, true, foldersFirst = true))
        val firstFile = sorted.indexOfFirst { !it.isDirectory }
        assertTrue(sorted.subList(0, firstFile).all { it.isDirectory })
        assertTrue(sorted.subList(firstFile, sorted.size).none { it.isDirectory })
        assertEquals("Dossier 0", sorted.first().name)
        assertEquals("Dossier 1", sorted[1].name)
        assertEquals("Dossier 2", sorted[2].name)
    }
}
