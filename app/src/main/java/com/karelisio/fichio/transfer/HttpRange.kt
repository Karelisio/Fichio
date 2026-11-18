package com.karelisio.fichio.transfer

import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * HTTP byte ranges (RFC 9110 §14) for resumable downloads from the transfer server: a single
 * `Range: bytes=…` spec (first-last, first-, -suffix) and a simple `If-Range` (strong ETag or
 * exact Last-Modified date). Multiple ranges are not served as multipart: the whole file is sent
 * instead, which the RFC allows. Pure JVM, unit-tested.
 */
object HttpRange {
    /** Inclusive byte span. */
    data class Span(val start: Long, val endInclusive: Long) {
        val length: Long get() = endInclusive - start + 1
    }

    sealed interface Decision {
        /** 200 with the whole file (no / ignored / mismatched-If-Range request). */
        data object Full : Decision

        /** 206 Partial Content. */
        data class Partial(val span: Span) : Decision

        /** 416 Range Not Satisfiable. */
        data object Unsatisfiable : Decision
    }

    private val rfc1123: DateTimeFormatter = DateTimeFormatter.RFC_1123_DATE_TIME

    /** Strong validator for a file version: length + modification time. */
    fun etag(length: Long, lastModified: Long): String = "\"${length.toString(16)}-${lastModified.toString(16)}\""

    /** IMF-fixdate (seconds precision) for Last-Modified. */
    fun httpDate(millis: Long): String =
        rfc1123.format(ZonedDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneOffset.UTC))

    /** Whole decision: Range applied only when If-Range is absent or still matches the file. */
    fun decide(range: String?, ifRange: String?, total: Long, etag: String, lastModified: Long): Decision {
        if (range.isNullOrBlank()) return Decision.Full
        if (ifRange != null && !ifRangeMatches(ifRange, etag, lastModified)) return Decision.Full
        return parse(range, total)
    }

    /**
     * `If-Range`: a strong entity tag compared exactly, or an HTTP date equal to the file's
     * Last-Modified (to the second). Weak tags and unparsable values never match.
     */
    fun ifRangeMatches(ifRange: String, etag: String, lastModified: Long): Boolean {
        val v = ifRange.trim()
        if (v.startsWith("W/")) return false
        if (v.startsWith("\"")) return v == etag
        val date = runCatching { ZonedDateTime.parse(v, rfc1123).toInstant().epochSecond }.getOrNull() ?: return false
        return date == Math.floorDiv(lastModified, 1000L)
    }

    /** Parses a `Range` header for a representation of [total] bytes. */
    fun parse(header: String?, total: Long): Decision {
        if (header.isNullOrBlank()) return Decision.Full
        val eq = header.indexOf('=')
        if (eq <= 0) return Decision.Full
        if (!header.substring(0, eq).trim().equals("bytes", ignoreCase = true)) return Decision.Full
        val spec = header.substring(eq + 1).trim()
        // Several ranges (multipart/byteranges) are not supported: ignoring Range is allowed.
        if (spec.isEmpty() || ',' in spec) return Decision.Full
        val dash = spec.indexOf('-')
        if (dash < 0 || spec.indexOf('-', dash + 1) >= 0) return Decision.Full
        val first = spec.substring(0, dash).trim()
        val last = spec.substring(dash + 1).trim()
        if (!first.all(Char::isDigit) || !last.all(Char::isDigit)) return Decision.Full

        if (first.isEmpty()) {
            // Suffix: the last N bytes.
            val n = last.toLongOrNull() ?: return Decision.Full
            if (n == 0L || total <= 0) return Decision.Unsatisfiable
            return Decision.Partial(Span(maxOf(0, total - n), total - 1))
        }
        val start = first.toLongOrNull() ?: return Decision.Full
        val end = if (last.isEmpty()) null else (last.toLongOrNull() ?: Long.MAX_VALUE)
        if (end != null && end < start) return Decision.Full // syntactically invalid: ignored
        if (start >= total) return Decision.Unsatisfiable
        return Decision.Partial(Span(start, minOf(end ?: (total - 1), total - 1)))
    }

    fun contentRange(span: Span, total: Long) = "bytes ${span.start}-${span.endInclusive}/$total"

    fun unsatisfiedRange(total: Long) = "bytes */$total"
}
