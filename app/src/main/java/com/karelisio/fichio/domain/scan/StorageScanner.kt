package com.karelisio.fichio.domain.scan

import com.karelisio.fichio.domain.file.CategoryUsage
import com.karelisio.fichio.domain.file.FileCategory
import com.karelisio.fichio.domain.file.FileItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

/** Result of a full storage walk. */
data class ScanResult(
    val categoryUsage: List<CategoryUsage>,
    val recentFiles: List<FileItem>,
    val totalFiles: Int,
    val totalBytes: Long
)

/**
 * Walks the external storage tree collecting per-category usage and the most
 * recently modified files. Directories the app cannot read (e.g. Android/data
 * without Shevery) are silently skipped.
 */
object StorageScanner {

    private val excludedDirs = setOf(
        "Android", "lost+found", ".fichio"
    )

    suspend fun scan(root: File): ScanResult = withContext(Dispatchers.IO) {
        val usage = HashMap<FileCategory, Long>()
        val counts = HashMap<FileCategory, Int>()
        val recents = ArrayList<FileItem>()
        var totalFiles = 0
        var totalBytes = 0L

        val stack = ArrayDeque<File>()
        stack.add(root)
        var guard = 0
        while (stack.isNotEmpty() && guard < 500_000) {
            guard++
            coroutineContext.ensureActive()
            val dir = stack.removeLast()
            val children = dir.listFiles() ?: continue
            for (f in children) {
                val name = f.name
                if (f.isDirectory) {
                    if (name !in excludedDirs && !name.startsWith(".")) stack.add(f)
                } else {
                    totalFiles++
                    val size = f.length()
                    totalBytes += size
                    val item = FileItem.from(f)
                    val cat = item.category
                    usage[cat] = (usage[cat] ?: 0L) + size
                    counts[cat] = (counts[cat] ?: 0) + 1
                    recents.add(item)
                }
            }
        }

        val categoryUsage = FileCategory.entries.mapNotNull { cat ->
            val bytes = usage[cat] ?: 0L
            if (bytes <= 0L) null else CategoryUsage(cat, bytes, counts[cat] ?: 0)
        }.sortedByDescending { it.bytes }

        val recentFiles = recents
            .sortedByDescending { it.modifiedAt }
            .take(30)

        ScanResult(categoryUsage, recentFiles, totalFiles, totalBytes)
    }
}
