package com.karelisio.fichio.core.fs

import com.karelisio.fichio.core.model.FileEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext

/**
 * Routes a path to the right backend: Android/data and Android/obb go through
 * Shevery (Shizuku UserService), removable volumes with a granted SAF tree through
 * [SafBackend], everything else through java.io.
 *
 * Also broadcasts [changes] so that open listings, the search index and the home
 * screen refresh after any operation performed inside the app.
 */
class FileSystems(
    val local: FileBackend = LocalBackend(),
    /** Returns the connected Shizuku backend, or null when Shevery isn't running/authorised. */
    private val shizukuProvider: () -> FileBackend? = { null },
    /** SAF backend for paths of a removable volume whose tree was granted, null for java.io. */
    private val safProvider: (String) -> FileBackend? = { null },
) {
    private val _changes = MutableSharedFlow<FsChange>(extraBufferCapacity = 64)
    val changes: SharedFlow<FsChange> = _changes.asSharedFlow()

    fun backendFor(path: String): FileBackend =
        if (StoragePaths.isRestricted(path)) shizukuProvider() ?: throw ShizukuUnavailableException()
        else safProvider(path) ?: local

    fun backendOrNull(path: String): FileBackend? = runCatching { backendFor(path) }.getOrNull()

    suspend fun list(path: String): List<FileEntry> = withContext(Dispatchers.IO) { backendFor(path).list(path) }

    suspend fun stat(path: String): FileEntry? = withContext(Dispatchers.IO) { backendOrNull(path)?.stat(path) }

    /** Notify listeners that the content of these directories changed. */
    fun notifyChanged(vararg dirs: String) {
        _changes.tryEmit(FsChange(dirs.toSet()))
    }

    fun notifyChanged(dirs: Collection<String>) {
        _changes.tryEmit(FsChange(dirs.toSet()))
    }
}

/** Directories (absolute paths) whose listing changed. */
data class FsChange(val dirs: Set<String>) {
    fun affects(dir: String) = dirs.any { it == dir || it.startsWith("$dir/") || dir.startsWith("$it/") }
}
