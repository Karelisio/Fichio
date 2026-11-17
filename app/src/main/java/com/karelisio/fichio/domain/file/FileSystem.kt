package com.karelisio.fichio.domain.file

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Direct file-system access. With MANAGE_EXTERNAL_STORAGE granted (API 30+)
 * we can read and write everywhere using plain java.io.File.
 */
class FileSystem(private val context: Context) {

    val root: File get() = Environment.getExternalStorageDirectory()

    fun isAllFilesAccessGranted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    suspend fun listDirectory(path: String, showHidden: Boolean = false): List<FileItem> =
        withContext(Dispatchers.IO) {
            val dir = File(path)
            if (!dir.isDirectory) return@withContext emptyList()
            val children = dir.listFiles() ?: return@withContext emptyList()
            children
                .asSequence()
                .filter { showHidden || !it.name.startsWith(".") }
                .map { FileItem.from(it) }
                .toList()
        }

    suspend fun listDirectoriesOnly(path: String, showHidden: Boolean = false): List<FileItem> =
        listDirectory(path, showHidden).filter { it.isDirectory }

    suspend fun storageStats(): StorageStats? = withContext(Dispatchers.IO) {
        runCatching {
            val stat = StatFs(root.absolutePath)
            val total = stat.totalBytes
            val free = stat.availableBytes
            StorageStats(totalBytes = total, usedBytes = total - free, freeBytes = free)
        }.getOrNull()
    }

    suspend fun exists(path: String): Boolean = withContext(Dispatchers.IO) { File(path).exists() }

    suspend fun mkdir(path: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { File(path).mkdirs() }.getOrDefault(false)
    }

    suspend fun createFile(path: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { File(path).createNewFile() }.getOrDefault(false)
    }

    suspend fun rename(from: String, to: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { File(from).renameTo(File(to)) }.getOrDefault(false)
    }

    suspend fun delete(path: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { File(path).deleteRecursively() }.getOrDefault(false)
    }

    suspend fun directorySize(path: String): Long = withContext(Dispatchers.IO) {
        var total = 0L
        val stack = ArrayDeque<File>()
        stack.add(File(path))
        while (stack.isNotEmpty()) {
            val f = stack.removeLast()
            if (f.isFile) {
                total += f.length()
            } else {
                f.listFiles()?.forEach { stack.add(it) }
            }
        }
        total
    }
}
