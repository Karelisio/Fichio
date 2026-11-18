package com.karelisio.fichio.shizuku

import com.karelisio.fichio.core.util.Strings
import com.karelisio.fichio.R
import android.os.DeadObjectException
import android.os.ParcelFileDescriptor
import com.karelisio.fichio.core.fs.FileBackend
import com.karelisio.fichio.core.fs.ShizukuUnavailableException
import com.karelisio.fichio.core.model.FileEntry
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * [FileBackend] delegating to [FichioUserService] through Binder. Listings are paginated
 * so that a single transaction never approaches the 1 MB Binder buffer; streams are backed by
 * ParcelFileDescriptors opened in the privileged process.
 */
class ShizukuBackend(
    private val service: IFileService,
    /** Called when the remote process died, so the manager can reconnect. */
    private val onDead: () -> Unit = {},
) : FileBackend {
    override val id: String = "shizuku"

    override fun list(path: String): List<FileEntry> = call {
        val base = path.trimEnd('/')
        val out = ArrayList<FileEntry>()
        var offset = 0
        var guard = 0
        while (true) {
            val page = FileServiceCodec.decodePage(service.list(path, offset, FileServiceCodec.DEFAULT_PAGE_SIZE) ?: "0\t0")
            page.entries.mapTo(out) { it.toFileEntry("$base/${it.name}") }
            if (page.nextOffset >= page.total || page.nextOffset <= offset || ++guard > 10_000) break
            offset = page.nextOffset
        }
        out
    }

    override fun stat(path: String): FileEntry? = runCatching {
        service.stat(path)?.let(FileServiceCodec::decodeEntry)?.toFileEntry(path)
    }.onFailure(::checkDead).getOrNull()

    override fun openInput(path: String): InputStream = call {
        val pfd = service.openRead(path) ?: throw IOException(Strings.get(R.string.fs_err_open, path))
        ParcelFileDescriptor.AutoCloseInputStream(pfd)
    }

    override fun openOutput(path: String, append: Boolean): OutputStream = call {
        val pfd = service.openWrite(path, append) ?: throw IOException(Strings.get(R.string.fs_err_write, path))
        ParcelFileDescriptor.AutoCloseOutputStream(pfd)
    }

    override fun mkdirs(path: String): Boolean = bool { service.mkdirs(path) }
    override fun createFile(path: String): Boolean = bool { service.createFile(path) }
    override fun deleteSingle(path: String): Boolean = bool { service.deleteSingle(path) }
    override fun deleteRecursively(path: String): Boolean = bool { service.deleteRecursively(path) }
    override fun rename(from: String, to: String): Boolean = bool { service.rename(from, to) }
    override fun setLastModified(path: String, time: Long): Boolean = bool { service.setLastModified(path, time) }
    override fun freeSpace(path: String): Long =
        runCatching { service.freeSpace(path) }.onFailure(::checkDead).getOrDefault(Long.MAX_VALUE)

    private fun FileServiceCodec.Entry.toFileEntry(fullPath: String) =
        FileEntry(path = fullPath, name = name, isDirectory = isDirectory, size = size, lastModified = lastModified)

    private inline fun bool(block: () -> Boolean): Boolean =
        runCatching(block).onFailure(::checkDead).getOrDefault(false)

    private inline fun <T> call(block: () -> T): T = try {
        block()
    } catch (e: IOException) {
        throw e
    } catch (e: Exception) {
        checkDead(e)
        if (e is DeadObjectException) throw ShizukuUnavailableException(Strings.get(R.string.shizuku_stopped))
        throw IOException(e.message ?: Strings.get(R.string.shizuku_error), e)
    }

    private fun checkDead(e: Throwable) {
        if (e is DeadObjectException) runCatching(onDead)
    }
}
