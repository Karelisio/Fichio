package com.karelisio.fichio.search

import com.karelisio.fichio.core.model.FileType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentRulesTest {
    @Test
    fun indexableTypes() {
        assertTrue(ContentRules.isIndexable(FileType.TEXT, "md", 10, pdfSupported = false))
        assertTrue(ContentRules.isIndexable(FileType.CODE, "json", 10, pdfSupported = false))
        assertTrue(ContentRules.isIndexable(FileType.SPREADSHEET, "csv", 10, pdfSupported = false))
        assertFalse(ContentRules.isIndexable(FileType.SPREADSHEET, "xlsx", 10, pdfSupported = true))
        assertFalse(ContentRules.isIndexable(FileType.PDF, "pdf", 10, pdfSupported = false))
        assertTrue(ContentRules.isIndexable(FileType.PDF, "pdf", 10, pdfSupported = true))
        assertFalse(ContentRules.isIndexable(FileType.PDF, "pdf", ContentRules.MAX_PDF_BYTES + 1, pdfSupported = true))
        assertFalse(ContentRules.isIndexable(FileType.IMAGE, "jpg", 10, pdfSupported = true))
    }

    @Test
    fun textDecodingDropsBomCollapsesSpacesAndRejectsBinary() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "Bonjour\n\n  été\t!".toByteArray()
        assertEquals("Bonjour été !", ContentRules.textOf(bytes, bytes.size, "txt"))
        assertNull(ContentRules.textOf(byteArrayOf(65, 0, 66), 3, "txt"))
        assertNull(ContentRules.textOf("   \n ".toByteArray(), 5, "txt"))
    }

    @Test
    fun truncatedMultibyteCharacterIsNotMangled() {
        val text = "a".repeat(ContentRules.MAX_TEXT_BYTES - 1) + "é" // é = 2 bytes, cut in half at the limit
        val bytes = text.toByteArray().copyOf(ContentRules.MAX_TEXT_BYTES)
        val out = ContentRules.textOf(bytes, bytes.size, "txt")!!
        assertFalse(out.contains('�'))
    }

    @Test
    fun htmlIsStripped() {
        val html = "<html><style>p{color:red}</style><!-- note --><p class=\"x\">Café &amp; thé</p><script>alert(1)</script></html>"
        assertEquals("Café & thé", ContentRules.clean(ContentRules.stripHtml(html)))
        assertEquals("Café & thé", ContentRules.textOf(html.toByteArray(), html.toByteArray().size, "html"))
        // Other markup keeps its tags (names are meaningful in XML/JSON).
        assertTrue(ContentRules.textOf("<a>b</a>".toByteArray(), 8, "xml")!!.contains("<a>"))
    }

    @Test
    fun pendingQueryBindsEverything() {
        val q = ContentRules.pendingQuery(limit = 20, pdfSupported = true, dirs = listOf("/s/b", "/s/a"))
        assertTrue(q.sql.contains("LEFT JOIN content_meta"))
        assertTrue(q.sql.contains("m.modified != f.modified"))
        assertTrue(q.args.containsAll(listOf("TEXT", "CODE", "PDF", "csv", "tsv", "/s/a", "/s/b")))
        assertTrue(q.sql.endsWith("LIMIT 20"))
        val noPdf = ContentRules.pendingQuery(limit = 5, pdfSupported = false)
        assertFalse(noPdf.args.contains("PDF"))
        assertFalse(noPdf.sql.contains("f.parent IN"))
        assertTrue(ContentRules.pendingQuery(5, false, dirs = emptyList()).sql.contains("AND 0"))
    }

    @Test
    fun snippetSegments() {
        val raw = "…le \u0002budget\u0003 est\nvalidé \u0002ici\u0003"
        assertEquals(
            listOf("…le " to false, "budget" to true, " est validé " to false, "ici" to true),
            Snippets.segments(raw),
        )
        assertEquals(listOf("plain" to false), Snippets.segments("plain"))
    }

    @Test
    fun contentQueryNeedsAnIndexableToken() {
        assertNull(SearchSqlBuilder.buildContent(SearchQuery(text = "a")))
        assertNull(SearchSqlBuilder.buildContent(SearchQuery(text = "--")))
        val b = SearchSqlBuilder.buildContent(SearchQuery(text = "Rapport 2024", minSize = 5))!!
        assertTrue(b.sql.contains("content_fts MATCH ?"))
        assertEquals(listOf(SearchSqlBuilder.MARK_START, SearchSqlBuilder.MARK_END, "\"rapport*\" \"2024*\"", 5L), b.args)
    }
}
