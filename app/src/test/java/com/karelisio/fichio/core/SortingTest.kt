package com.karelisio.fichio.core

import com.karelisio.fichio.core.model.FileEntry
import com.karelisio.fichio.core.model.SortBy
import com.karelisio.fichio.core.model.SortSpec
import com.karelisio.fichio.core.util.NaturalOrder
import com.karelisio.fichio.core.util.Sorting
import org.junit.Assert.assertEquals
import org.junit.Test

class SortingTest {
    private fun f(name: String, dir: Boolean = false, size: Long = 0, time: Long = 0) = FileEntry("/r/$name", name, dir, size, time)

    @Test fun naturalOrderComparesNumbersNumerically() {
        val sorted = NaturalOrder.sortedBy(listOf("photo10", "photo2", "Photo1", "photo02b")) { it }
        assertEquals(listOf("Photo1", "photo2", "photo02b", "photo10"), sorted)
    }

    @Test fun accentsAndCaseAreIgnoredAtPrimaryLevel() {
        val sorted = NaturalOrder.sortedBy(listOf("éte", "Etat", "zèbre", "abc")) { it }
        assertEquals(listOf("abc", "Etat", "éte", "zèbre"), sorted)
    }

    @Test fun foldersFirstThenBySize() {
        val list = listOf(f("b", size = 10), f("dir", dir = true), f("a", size = 50), f("c", size = 5))
        val out = Sorting.sort(list, SortSpec(SortBy.SIZE, ascending = false, foldersFirst = true))
        assertEquals(listOf("dir", "a", "b", "c"), out.map { it.name })
    }

    @Test fun dateAscendingKeepsNameTieBreak() {
        val list = listOf(f("z", time = 1), f("a", time = 1), f("m", time = 0))
        val out = Sorting.sort(list, SortSpec(SortBy.DATE, ascending = true, foldersFirst = false))
        assertEquals(listOf("m", "a", "z"), out.map { it.name })
    }

    @Test fun largeNumbersDoNotOverflow() {
        val sorted = NaturalOrder.sortedBy(listOf("v99999999999999999999", "v1")) { it }
        assertEquals("v1", sorted.first())
    }
}
