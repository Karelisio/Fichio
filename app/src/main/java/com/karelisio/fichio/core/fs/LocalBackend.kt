package com.karelisio.fichio.core.fs

import com.karelisio.fichio.core.util.Strings
import com.karelisio.fichio.R
import com.karelisio.fichio.core.model.FileEntry
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes

/** Plain java.io / java.nio backend. Pure JVM so the operation engine is unit-testable. */
open class LocalBackend : FileBackend {
    override val id: String = "local"

    override fun list(path: String): List<FileEntry> {
        val dir = File(path)
        val names = dir.list() ?: throw IOException(Strings.get(R.string.fs_err_read, path))
        val out = ArrayList<FileEntry>(names.size)
        val base = if (path.endsWith("/")) path else "$path/"
        for (name in names) {
            val child = base + name
            out += statOrNull(child, name) ?: continue
        }
        return out
    }

    /** One syscall per entry thanks to readAttributes (vs isDirectory + length + lastModified). */
    private fun statOrNull(path: String, name: String): FileEntry? = try {
        val attrs = Files.readAttributes(Paths.get(path), BasicFileAttributes::class.java)
        FileEntry(
            path = path,
            name = name,
            isDirectory = attrs.isDirectory,
            size = if (attrs.isDirectory) 0L else attrs.size(),
            lastModified = attrs.lastModifiedTime().toMillis(),
        )
    } catch (_: Exception) {
        // Broken symlink or race: fall back to java.io.
        val f = File(path)
        if (f.exists()) FileEntry(path, name, f.isDirectory, if (f.isDirectory) 0 else f.length(), f.lastModified())
        else if (Files.isSymbolicLink(Paths.get(path))) FileEntry(path, name, false, 0, 0)
        else null
    }

    override fun stat(path: String): FileEntry? = statOrNull(path, path.substringAfterLast('/'))

    override fun exists(path: String): Boolean = Files.exists(Paths.get(path), LinkOption.NOFOLLOW_LINKS)

    override fun openInput(path: String): InputStream = try {
        FileInputStream(path)
    } catch (e: FileNotFoundException) {
        throw IOException(e.message, e)
    }

    override fun openOutput(path: String, append: Boolean): OutputStream {
        File(path).parentFile?.mkdirs()
        return FileOutputStream(path, append)
    }

    override fun mkdirs(path: String): Boolean = File(path).let { it.isDirectory || it.mkdirs() }

    override fun createFile(path: String): Boolean = runCatching {
        File(path).parentFile?.mkdirs(); File(path).createNewFile()
    }.getOrDefault(false)

    override fun deleteSingle(path: String): Boolean = File(path).delete()

    override fun deleteRecursively(path: String): Boolean {
        val f = File(path)
        // Do not follow symlinks into their targets.
        if (Files.isSymbolicLink(f.toPath())) return f.delete()
        return f.deleteRecursively()
    }

    override fun rename(from: String, to: String): Boolean {
        val dst = File(to)
        if (dst.exists() && !from.equals(to, ignoreCase = true)) return false
        dst.parentFile?.mkdirs()
        return File(from).renameTo(dst)
    }

    override fun setLastModified(path: String, time: Long): Boolean = File(path).setLastModified(time)

    override fun freeSpace(path: String): Long {
        var f: File? = File(path)
        while (f != null && !f.exists()) f = f.parentFile
        return f?.usableSpace ?: Long.MAX_VALUE
    }
}
