package com.karelisio.fichio.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class FileServiceCodecTest {
    @Test fun escapesTabsAndNewlinesInNames() {
        val e = FileServiceCodec.Entry("we\tird\nna\\me", true, 0, 42)
        assertEquals(e, FileServiceCodec.decodeEntry(FileServiceCodec.encodeEntry(e)))
    }

    @Test fun paginatesWithinCharBudget() {
        val names = List(5_000) { "f".repeat(200) + it }
        val pages = mutableListOf<FileServiceCodec.Page>()
        var offset = 0
        do {
            val text = FileServiceCodec.encodePage(names.size, offset, 10_000) { FileServiceCodec.Entry(names[it], false, 1, 2) }
            assertTrue(text.length < FileServiceCodec.MAX_PAGE_CHARS + 300)
            val p = FileServiceCodec.decodePage(text)
            pages += p
            offset = p.nextOffset
        } while (offset < names.size)
        assertTrue(pages.size > 1)
        assertEquals(names, pages.flatMap { it.entries }.map { it.name })
    }

    @Test(expected = IOException::class)
    fun errorsAreRaised() {
        FileServiceCodec.decodePage(FileServiceCodec.error("Permission refusée"))
    }
}
