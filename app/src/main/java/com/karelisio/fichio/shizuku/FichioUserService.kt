package com.karelisio.fichio.shizuku

import android.os.ParcelFileDescriptor
import androidx.annotation.Keep
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import kotlin.system.exitProcess

/**
 * Shizuku UserService: instantiated by reflection inside the Shevery/Shizuku process (shell
 * or root uid), where /sdcard/Android/data and /sdcard/Android/obb are readable and writable.
 * Plain java.io; every failure is reported as an [IllegalStateException] so that Binder can
 * parcel it back to the app.
 */
@Keep
class FichioUserService @Keep constructor() : IFileService.Stub() {

    /** Last listing, so that paginated calls do not re-list a 10 000-entry directory each time. */
    private var cachedPath: String? = null
    private var cachedNames: Array<String> = emptyArray()
    private var cachedAt = 0L
    private val lock = Any()

    override fun destroy() {
        exitProcess(0)
    }

    override fun exit() {
        destroy()
    }

    override fun list(path: String, offset: Int, limit: Int): String = try {
        val names = synchronized(lock) {
            val fresh = offset == 0 || cachedPath != path || System.currentTimeMillis() - cachedAt > 30_000
            if (fresh) {
                val listed = File(path).list() ?: throw IllegalStateException("Impossible de lire $path")
                listed.sort()
                cachedPath = path
                cachedNames = listed
                cachedAt = System.currentTimeMillis()
            }
            cachedNames
        }
        val base = path.trimEnd('/')
        FileServiceCodec.encodePage(names.size, offset, limit) { i -> entry("$base/${names[i]}", names[i]) }
    } catch (e: Exception) {
        FileServiceCodec.error(e.message)
    }

    override fun stat(path: String): String? = entry(path, path.substringAfterLast('/'))?.let(FileServiceCodec::encodeEntry)

    override fun openRead(path: String): ParcelFileDescriptor = guard {
        ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun openWrite(path: String, append: Boolean): ParcelFileDescriptor = guard {
        val f = File(path)
        f.parentFile?.mkdirs()
        val mode = ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or
            if (append) ParcelFileDescriptor.MODE_APPEND else ParcelFileDescriptor.MODE_TRUNCATE
        ParcelFileDescriptor.open(f, mode)
    }

    override fun mkdirs(path: String): Boolean = File(path).let { it.isDirectory || it.mkdirs() }

    override fun createFile(path: String): Boolean = runCatching {
        File(path).parentFile?.mkdirs(); File(path).createNewFile()
    }.getOrDefault(false)

    override fun deleteSingle(path: String): Boolean = File(path).delete()

    override fun deleteRecursively(path: String): Boolean {
        val f = File(path)
        invalidate(path)
        if (Files.isSymbolicLink(f.toPath())) return f.delete()
        return f.deleteRecursively()
    }

    override fun rename(from: String, to: String): Boolean {
        val dst = File(to)
        if (dst.exists() && !from.equals(to, ignoreCase = true)) return false
        dst.parentFile?.mkdirs()
        invalidate(from)
        return File(from).renameTo(dst)
    }

    override fun setLastModified(path: String, time: Long): Boolean = File(path).setLastModified(time)

    override fun freeSpace(path: String): Long {
        var f: File? = File(path)
        while (f != null && !f.exists()) f = f.parentFile
        return f?.usableSpace ?: Long.MAX_VALUE
    }

    private fun entry(path: String, name: String): FileServiceCodec.Entry? = try {
        val a = Files.readAttributes(File(path).toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        val dir = a.isDirectory || (a.isSymbolicLink && File(path).isDirectory)
        FileServiceCodec.Entry(name, dir, if (dir) 0 else a.size(), a.lastModifiedTime().toMillis())
    } catch (_: Exception) {
        val f = File(path)
        if (f.exists()) FileServiceCodec.Entry(name, f.isDirectory, if (f.isDirectory) 0 else f.length(), f.lastModified()) else null
    }

    private fun invalidate(path: String) = synchronized(lock) {
        if (cachedPath != null && (path.startsWith(cachedPath!!) || cachedPath!!.startsWith(path))) cachedPath = null
    }

    private inline fun <T> guard(block: () -> T): T = try {
        block()
    } catch (e: Exception) {
        throw IllegalStateException(e.message ?: e.javaClass.simpleName)
    }
}
