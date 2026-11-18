package com.karelisio.fichio.transfer

import com.karelisio.fichio.transfer.HttpRange.Decision
import com.karelisio.fichio.transfer.HttpRange.Span
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpRangeTest {
    private fun partial(start: Long, end: Long) = Decision.Partial(Span(start, end))

    @Test fun boundedOpenAndSuffixRanges() {
        assertEquals(partial(0, 99), HttpRange.parse("bytes=0-99", 1000))
        assertEquals(partial(500, 999), HttpRange.parse("bytes=500-", 1000))
        assertEquals(partial(900, 999), HttpRange.parse("bytes=-100", 1000))
        assertEquals(partial(0, 0), HttpRange.parse("bytes=0-0", 1000))
        assertEquals(partial(999, 999), HttpRange.parse("bytes=999-", 1000))
        assertEquals(100L, (HttpRange.parse("bytes=0-99", 1000) as Decision.Partial).span.length)
    }

    @Test fun rangesAreClampedToTheFile() {
        assertEquals(partial(500, 999), HttpRange.parse("bytes=500-5000", 1000))
        assertEquals(partial(500, 999), HttpRange.parse("bytes=500-99999999999999999999999", 1000))
        // Suffix longer than the file: the whole file, still as 206.
        assertEquals(partial(0, 999), HttpRange.parse("bytes=-5000", 1000))
    }

    @Test fun toleratesSpacesAndCase() {
        assertEquals(partial(10, 20), HttpRange.parse("Bytes = 10 - 20", 1000))
        assertEquals(partial(10, 20), HttpRange.parse("BYTES=10-20", 1000))
    }

    @Test fun unsatisfiableRanges() {
        assertEquals(Decision.Unsatisfiable, HttpRange.parse("bytes=1000-", 1000))
        assertEquals(Decision.Unsatisfiable, HttpRange.parse("bytes=2000-3000", 1000))
        assertEquals(Decision.Unsatisfiable, HttpRange.parse("bytes=-0", 1000))
        assertEquals(Decision.Unsatisfiable, HttpRange.parse("bytes=0-", 0))
        assertEquals(Decision.Unsatisfiable, HttpRange.parse("bytes=-10", 0))
    }

    @Test fun invalidOrUnsupportedHeadersServeTheWholeFile() {
        listOf(
            null, "", "   ", "bytes", "bytes=", "bytes=-", "items=0-10", "bytes=abc-", "bytes=10-5",
            "bytes=1-2-3", "bytes=0-10,20-30", "bytes=-1-", "0-10", "bytes=+5-10", "bytes=99999999999999999999999-",
        ).forEach { assertEquals("header: $it", Decision.Full, HttpRange.parse(it, 1000)) }
    }

    @Test fun ifRangeWithEtagAndDate() {
        val mtime = 1_700_000_123_456L
        val tag = HttpRange.etag(1000, mtime)
        val date = HttpRange.httpDate(mtime)
        assertEquals("Tue, 14 Nov 2023 22:15:23 GMT", date)
        assertTrue(HttpRange.ifRangeMatches(tag, tag, mtime))
        assertTrue(HttpRange.ifRangeMatches(date, tag, mtime))
        assertFalse(HttpRange.ifRangeMatches(HttpRange.etag(1001, mtime), tag, mtime))
        assertFalse(HttpRange.ifRangeMatches("W/$tag", tag, mtime))
        assertFalse(HttpRange.ifRangeMatches(HttpRange.httpDate(mtime + 5_000), tag, mtime))
        assertFalse(HttpRange.ifRangeMatches("pas une date", tag, mtime))

        // Matching validator: the range applies; stale one: the whole (new) file is sent.
        assertEquals(partial(100, 999), HttpRange.decide("bytes=100-", tag, 1000, tag, mtime))
        assertEquals(partial(100, 999), HttpRange.decide("bytes=100-", null, 1000, tag, mtime))
        assertEquals(Decision.Full, HttpRange.decide("bytes=100-", "\"stale\"", 1000, tag, mtime))
        assertEquals(Decision.Full, HttpRange.decide(null, tag, 1000, tag, mtime))
    }

    @Test fun headerValues() {
        assertEquals("bytes 0-99/1000", HttpRange.contentRange(Span(0, 99), 1000))
        assertEquals("bytes */1000", HttpRange.unsatisfiedRange(1000))
        assertEquals("\"3e8-18bcfe56800\"", HttpRange.etag(1000, 1_700_000_000_000L))
    }
}
