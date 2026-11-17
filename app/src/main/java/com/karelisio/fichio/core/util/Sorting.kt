package com.karelisio.fichio.core.util

import com.karelisio.fichio.core.model.FileEntry
import com.karelisio.fichio.core.model.SortBy
import com.karelisio.fichio.core.model.SortSpec
import java.text.Collator
import java.util.Locale

/**
 * Natural, locale-aware name order: "photo 2" < "photo 10", accents and case ignored at the
 * primary level (French collator). Keys are precomputed once per sort for speed on 10k+ items.
 */
object NaturalOrder {
    private val collator: Collator = Collator.getInstance(Locale.FRANCE).apply { strength = Collator.SECONDARY }

    /** Splits into text / number chunks; numbers compare numerically. */
    private fun chunks(s: String): List<Any> {
        val out = ArrayList<Any>(4)
        var i = 0
        while (i < s.length) {
            val start = i
            if (s[i].isDigit()) {
                while (i < s.length && s[i].isDigit()) i++
                val digits = s.substring(start, i).trimStart('0')
                out += digits.ifEmpty { "0" }.let { if (it.length > 18) it else it.toLong() }
            } else {
                while (i < s.length && !s[i].isDigit()) i++
                out += collator.getCollationKey(s.substring(start, i))
            }
        }
        return out
    }

    private class Key(val parts: List<Any>, val raw: String) : Comparable<Key> {
        override fun compareTo(other: Key): Int {
            val n = minOf(parts.size, other.parts.size)
            for (i in 0 until n) {
                val a = parts[i]
                val b = other.parts[i]
                val c = when {
                    a is Long && b is Long -> a.compareTo(b)
                    a is java.text.CollationKey && b is java.text.CollationKey -> a.compareTo(b)
                    a is String && b is String -> a.length.compareTo(b.length).takeIf { it != 0 } ?: a.compareTo(b)
                    a is java.text.CollationKey -> 1 // text after numbers
                    b is java.text.CollationKey -> -1
                    a is String -> 1 // huge numbers after normal numbers
                    else -> -1
                }
                if (c != 0) return c
            }
            return parts.size.compareTo(other.parts.size).takeIf { it != 0 } ?: raw.compareTo(other.raw)
        }
    }

    fun compare(a: String, b: String): Int = Key(chunks(a), a).compareTo(Key(chunks(b), b))

    fun <T> sortedBy(items: List<T>, ascending: Boolean = true, name: (T) -> String): List<T> {
        val keyed = items.map { Key(chunks(name(it)), name(it)) to it }
        val sorted = keyed.sortedWith { x, y -> x.first.compareTo(y.first) }.map { it.second }
        return if (ascending) sorted else sorted.asReversed()
    }
}

object Sorting {
    fun sort(entries: List<FileEntry>, spec: SortSpec): List<FileEntry> {
        val byName = NaturalOrder.sortedBy(entries) { it.name }
        // Stable sorts on top of the name order keep ties alphabetical.
        val primary: List<FileEntry> = when (spec.by) {
            SortBy.NAME -> if (spec.ascending) byName else byName.asReversed()
            SortBy.DATE -> byName.sortedWith(compareBy<FileEntry> { it.lastModified }.let { if (spec.ascending) it else it.reversed() })
            SortBy.SIZE -> byName.sortedWith(compareBy<FileEntry> { it.size }.let { if (spec.ascending) it else it.reversed() })
            SortBy.TYPE -> byName.sortedWith(compareBy<FileEntry>({ it.type.ordinal }, { it.extension }).let { if (spec.ascending) it else it.reversed() })
        }
        return if (spec.foldersFirst) primary.filter { it.isDirectory } + primary.filter { !it.isDirectory } else primary
    }
}
