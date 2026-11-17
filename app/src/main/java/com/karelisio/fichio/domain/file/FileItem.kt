package com.karelisio.fichio.domain.file

import java.io.File

/** A lightweight, immutable description of a file or folder shown in the browser. */
data class FileItem(
    val file: File,
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val modifiedAt: Long,
    val extension: String,
    val category: FileCategory,
    val isHidden: Boolean
) {
    val isSymlink: Boolean = file.isAbsolute && runCatching { file.canonicalPath != file.absolutePath }.getOrDefault(false)

    companion object {
        fun from(file: File): FileItem {
            val name = file.name
            val ext = if (file.isDirectory) "" else extensionOf(name)
            return FileItem(
                file = file,
                name = name,
                path = file.absolutePath,
                isDirectory = file.isDirectory,
                sizeBytes = if (file.isDirectory) 0L else file.length(),
                modifiedAt = file.lastModified(),
                extension = ext,
                category = if (file.isDirectory) FileCategory.OTHER else FileCategory.fromExtension(ext),
                isHidden = name.startsWith(".")
            )
        }
    }
}

/** Aggregate storage usage for a volume. */
data class StorageStats(
    val totalBytes: Long,
    val usedBytes: Long,
    val freeBytes: Long
) {
    val usedFraction: Float get() = if (totalBytes <= 0) 0f else (usedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
}

/** Storage usage broken down by category (for the living gauge). */
data class CategoryUsage(
    val category: FileCategory,
    val bytes: Long,
    val count: Int
)
