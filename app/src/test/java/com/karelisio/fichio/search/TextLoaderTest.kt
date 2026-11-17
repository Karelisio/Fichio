package com.karelisio.fichio.search

import com.karelisio.fichio.preview.TextLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class TextLoaderTest {
    @Test
    fun readBoundedReportsTruncation() {
        val data = ByteArray(1000) { 'a'.code.toByte() }
        val (b, more) = TextLoader.readBounded(ByteArrayInputStream(data), 100)
        assertEquals(100, b.size)
        assertTrue(more)
        val (all, more2) = TextLoader.readBounded(ByteArrayInputStream(data), 1000)
        assertEquals(1000, all.size)
        assertFalse(more2)
    }

    @Test
    fun decodeUtf8WithBomAndLegacyFallback() {
        val utf8 = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "Été".toByteArray()
        assertEquals("Été" to Charsets.UTF_8, TextLoader.decode(utf8))
        val legacy = byteArrayOf(0x45, 0x74, 0xE9.toByte()) // "Eté" in Windows-1252
        val (text, cs) = TextLoader.decode(legacy)
        assertEquals("Eté", text)
        assertTrue(cs != Charsets.UTF_8)
    }

    @Test
    fun truncatedMultibyteTailIsDropped() {
        val bytes = "aé".toByteArray().copyOf(2) // 'a' + first byte of 'é'
        assertEquals("a", TextLoader.decode(bytes, truncated = true).first)
    }

    @Test
    fun documentSplitsLinesAndLongRows() {
        val doc = TextLoader.toDocument("a\r\nb\n" + "x".repeat(TextLoader.MAX_ROW + 5), false, Charsets.UTF_8)
        assertEquals(3, doc.lineCount)
        assertEquals(listOf("a", "b"), doc.rows.take(2))
        assertEquals(4, doc.rows.size)
        assertEquals(listOf(1, 2, 3, 0), doc.lineNumbers.toList())
    }

    @Test
    fun findAllIsCaseInsensitive() {
        val m = TextLoader.findAll(listOf("Foo foo", "bar", "FOO"), "foo")
        assertEquals(listOf(0 to 0, 0 to 4, 2 to 0), m)
        assertTrue(TextLoader.findAll(listOf("x"), "").isEmpty())
    }

    @Test
    fun binaryDetection() {
        assertTrue(TextLoader.looksBinary(byteArrayOf(1, 0, 2)))
        assertFalse(TextLoader.looksBinary("texte".toByteArray()))
    }
}
