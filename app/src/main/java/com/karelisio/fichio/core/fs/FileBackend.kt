package com.karelisio.fichio.core.fs

import com.karelisio.fichio.core.util.Strings
import com.karelisio.fichio.R
import com.karelisio.fichio.core.model.FileEntry
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Minimal file-system abstraction. Implemented by [LocalBackend] (java.io, thanks to
 * MANAGE_EXTERNAL_STORAGE) and by the Shizuku/Shevery backend for Android/data and obb.
 * Everything here is blocking: call from Dispatchers.IO.
 *
 * Paths are absolute POSIX paths without trailing slash.
 */
interface FileBackend {
    /** Stable id, e.g. "local", "shizuku". */
    val id: String

    @Throws(IOException::class)
    fun list(path: String): List<FileEntry>

    fun stat(path: String): FileEntry?

    fun exists(path: String): Boolean = stat(path) != null

    @Throws(IOException::class)
    fun openInput(path: String): InputStream

    /** Opens [path] for writing, creating parent directories when needed. */
    @Throws(IOException::class)
    fun openOutput(path: String, append: Boolean = false): OutputStream

    fun mkdirs(path: String): Boolean

    fun createFile(path: String): Boolean

    /** Deletes a file or an *empty* directory. */
    fun deleteSingle(path: String): Boolean

    /** Deletes recursively. */
    fun deleteRecursively(path: String): Boolean

    /** Atomic rename/move within the same backend. Returns false if impossible (e.g. cross-device). */
    fun rename(from: String, to: String): Boolean

    fun setLastModified(path: String, time: Long): Boolean = false

    fun freeSpace(path: String): Long = Long.MAX_VALUE
}

/** Thrown when Android/data is requested but Shevery/Shizuku is not available. */
class ShizukuUnavailableException(message: String = Strings.get(R.string.shizuku_not_active)) : IOException(message)
