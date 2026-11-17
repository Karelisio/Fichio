package com.karelisio.fichio.domain.file

import com.karelisio.fichio.data.db.SortMode
import java.util.Locale

/** Sorts file listings (folders first, then by the selected mode). */
object FileSorter {
    fun sort(
        items: List<FileItem>,
        mode: SortMode,
        ascending: Boolean,
        foldersFirst: Boolean
    ): List<FileItem> {
        val base = if (foldersFirst) {
            items.sortedBy { if (it.isDirectory) 0 else 1 }
        } else items

        val comparator: Comparator<FileItem> = when (mode) {
            SortMode.NAME -> Comparator { a, b -> String.CASE_INSENSITIVE_ORDER.compare(a.name, b.name) }
            SortMode.DATE -> Comparator { a, b -> a.modifiedAt.compareTo(b.modifiedAt) }
            SortMode.SIZE -> Comparator { a, b -> a.sizeBytes.compareTo(b.sizeBytes) }
            SortMode.TYPE -> Comparator { a, b ->
                val extCmp = String.CASE_INSENSITIVE_ORDER.compare(a.extension, b.extension)
                if (extCmp != 0) extCmp else String.CASE_INSENSITIVE_ORDER.compare(a.name, b.name)
            }
        }

        val sorted = if (foldersFirst) {
            base.sortedWith { a, b ->
                val dirCmp = (if (a.isDirectory) 0 else 1).compareTo(if (b.isDirectory) 0 else 1)
                if (dirCmp != 0) dirCmp else comparator.compare(a, b)
            }
        } else {
            base.sortedWith(comparator)
        }
        return if (ascending) sorted else sorted.reversed()
    }
}

/** Human-readable byte sizes. */
fun formatBytes(bytes: Long): String {
    if (bytes < 0) return "—"
    if (bytes < 1024) return "$bytes o"
    val units = arrayOf("Ko", "Mo", "Go", "To", "Po")
    var value = bytes.toDouble()
    var unit = -1
    while (value >= 1024 && unit < units.size - 1) {
        value /= 1024
        unit++
    }
    return if (unit < 0) "$bytes o" else String.format(Locale.getDefault(), "%.1f %s", value, units[unit])
}

/** Human-readable dates. */
fun formatDate(millis: Long): String {
    if (millis <= 0) return "—"
    return java.text.DateFormat.getDateTimeInstance(
        java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT
    ).format(java.util.Date(millis))
}
