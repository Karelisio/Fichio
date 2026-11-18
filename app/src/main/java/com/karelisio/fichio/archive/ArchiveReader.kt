package com.karelisio.fichio.archive

import com.github.junrar.Archive
import com.github.junrar.exception.CrcErrorException
import com.github.junrar.exception.RarException
import com.github.junrar.exception.UnsupportedRarEncryptedException
import com.github.junrar.exception.UnsupportedRarVersionException
import com.github.junrar.exception.WrongPasswordException as RarWrongPasswordException
import com.github.junrar.rarfile.FileHeader as RarHeader
import com.karelisio.fichio.ops.ConflictChoice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.exception.ZipException
import net.lingala.zip4j.model.FileHeader as ZipHeader
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.sevenz.SevenZMethod
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.tukaani.xz.SeekableFileInputStream
import org.tukaani.xz.SeekableXZInputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.zip.DataFormatException

/** Bytes of one entry, written to [out]. [onBytes] reports progress; [checkpoint] honours pause/cancel. */
fun interface EntryData {
    suspend fun writeTo(out: OutputStream, onBytes: (Long) -> Unit, checkpoint: suspend () -> Unit)
}

private const val BUFFER = 64 * 1024

/** Stream copy that reports every chunk and yields to pause/cancel about every megabyte. */
internal suspend fun copyStream(input: InputStream, out: OutputStream, onBytes: (Long) -> Unit, checkpoint: suspend () -> Unit) {
    val buf = ByteArray(BUFFER)
    var sinceCheck = 0L
    while (true) {
        val n = input.read(buf)
        if (n < 0) break
        if (n == 0) continue
        out.write(buf, 0, n)
        onBytes(n.toLong())
        sinceCheck += n
        if (sinceCheck >= 1_048_576) {
            sinceCheck = 0
            checkpoint()
        }
    }
}

/**
 * Common reader: lists the entries of an archive and extracts all or part of it with
 * progress, cancellation, conflict resolution and zip-slip protection.
 *
 * Instances are opened with [Archives.open]; all blocking calls must run off the main thread.
 */
abstract class ArchiveReader(val file: File, val format: ArchiveFormat, protected val password: CharArray?) : Closeable {
    private val lock = Mutex()

    /** Raw entries in storage order (implicit folders not included). */
    protected abstract fun readEntries(): List<ArchiveItem>

    /** Iterates the archive in storage order, handing the bytes of the wanted files to [block]. */
    protected abstract suspend fun forEachEntry(
        wanted: (ArchiveItem) -> Boolean,
        block: suspend (ArchiveItem, EntryData) -> Unit,
    )

    private val entriesCache: List<ArchiveItem> by lazy { readEntries() }

    fun entries(): List<ArchiveItem> = entriesCache

    /** Folder view of the archive. */
    val tree: ArchiveTree by lazy { ArchiveTree(entriesCache) }

    /** True when some content needs a password. */
    open val isEncrypted: Boolean get() = tree.hasEncrypted

    /**
     * Checks the password against a small encrypted entry. Throws [PasswordRequiredException]
     * or [WrongPasswordException]; returns normally when the archive is readable.
     */
    open suspend fun verifyPassword() {}

    override fun close() {}

    suspend fun extract(request: ExtractRequest, callbacks: ExtractCallbacks = object : ExtractCallbacks {}): ExtractResult =
        withContext(Dispatchers.IO) {
            lock.withLock { doExtract(request, callbacks) }
        }

    private suspend fun doExtract(request: ExtractRequest, cb: ExtractCallbacks): ExtractResult {
        val dest = request.destination
        if (!dest.exists() && !dest.mkdirs()) throw ArchiveException("Impossible de créer le dossier de destination")
        val selected = tree.select(request.selection)
        val filePaths = selected.filter { !it.isDirectory }.mapTo(HashSet()) { it.path }
        val totalBytes = selected.filter { !it.isDirectory }.sumOf { it.size.coerceAtLeast(0) }
        val totalItems = filePaths.size
        var doneBytes = 0L
        var doneItems = 0
        var extracted = 0
        var skipped = 0
        var rejected = 0

        // Folders first (including implicit ones and empty folders).
        for (dir in selected.filter { it.isDirectory }) {
            val rel = ArchivePaths.relativeTo(dir.path, request.stripPrefix)
            if (rel.isEmpty()) continue
            if (dir.isUnsafe) { rejected++; continue }
            val target = runCatching { ArchivePaths.safeTarget(dest, rel) }.getOrNull()
            if (target == null) { rejected++; continue }
            if (target.isFile) continue // a file with that name exists: resolved when files land inside
            target.mkdirs()
        }

        cb.onProgress(0, totalBytes, "", 0, totalItems)
        forEachEntry({ !it.isDirectory && it.path in filePaths }) { item, data ->
            cb.checkpoint()
            val size = item.size.coerceAtLeast(0)
            val rel = ArchivePaths.relativeTo(item.path, request.stripPrefix)
            val target = if (item.isUnsafe) null else runCatching { ArchivePaths.safeTarget(dest, rel) }.getOrNull()
            if (target == null) {
                rejected++; doneItems++; doneBytes += size
                cb.onProgress(doneBytes, totalBytes, item.name, doneItems, totalItems)
                return@forEachEntry
            }
            var out = target
            if (target.exists()) {
                when (cb.onConflict(item, target)) {
                    ConflictChoice.SKIP -> {
                        skipped++; doneItems++; doneBytes += size
                        cb.onProgress(doneBytes, totalBytes, item.name, doneItems, totalItems)
                        return@forEachEntry
                    }
                    ConflictChoice.KEEP_BOTH -> out = ArchivePaths.uniqueFile(target)
                    ConflictChoice.REPLACE -> if (target.isDirectory) target.deleteRecursively() else target.delete()
                }
            }
            out.parentFile?.let { if (!it.isDirectory) it.mkdirs() }
            val start = doneBytes
            try {
                BufferedOutputStream(FileOutputStream(out), BUFFER).use { stream ->
                    data.writeTo(stream, { n ->
                        doneBytes += n
                        cb.onProgress(doneBytes, totalBytes, item.name, doneItems, totalItems)
                    }, cb::checkpoint)
                }
            } catch (t: Throwable) {
                out.delete()
                throw t
            }
            // Unknown sizes (gz, bz2…): keep the byte counter consistent.
            if (size > 0 && doneBytes - start < size) doneBytes = start + size
            if (item.lastModified > 0) out.setLastModified(item.lastModified)
            extracted++; doneItems++
            cb.onProgress(doneBytes, totalBytes, item.name, doneItems, totalItems)
        }
        return ExtractResult(extracted, skipped, rejected, doneBytes)
    }

    /** Extracts one file to [target] (used to open a file of the archive). */
    suspend fun extractTo(item: ArchiveItem, targetDir: File, callbacks: ExtractCallbacks = object : ExtractCallbacks {}): File {
        extract(ExtractRequest(targetDir, listOf(item.path), stripPrefix = item.parent), callbacks)
        return File(targetDir, ArchivePaths.safeSegment(item.name))
    }
}

/** Entry point: picks the right reader for a file. */
object Archives {
    fun isSupported(name: String) = ArchiveFormat.fromName(name) != null

    /**
     * Opens [file]. Throws [PasswordRequiredException] when the very listing is encrypted
     * (7z / rar with encrypted headers) and no password was given, [WrongPasswordException],
     * [UnsupportedArchiveException] (unknown RAR version…) or [CorruptedArchiveException].
     */
    fun open(file: File, password: CharArray? = null): ArchiveReader {
        if (!file.isFile) throw ArchiveException("Archive introuvable")
        val pw = password?.takeIf { it.isNotEmpty() }
        val format = ArchiveFormat.detect(file) ?: throw UnsupportedArchiveException("Format d'archive non pris en charge")
        val reader = when (format) {
            ArchiveFormat.ZIP -> ZipArchiveReader(file, pw)
            ArchiveFormat.SEVEN_Z -> SevenZArchiveReader(file, pw)
            ArchiveFormat.RAR -> RarArchiveReader(file, pw)
            ArchiveFormat.TAR, ArchiveFormat.TAR_GZ, ArchiveFormat.TAR_BZ2, ArchiveFormat.TAR_XZ -> TarArchiveReader(file, format)
            ArchiveFormat.GZ, ArchiveFormat.BZ2, ArchiveFormat.XZ -> SingleFileReader(file, format)
        }
        // Force the listing now so that errors surface at open time.
        try {
            reader.entries()
        } catch (t: Throwable) {
            runCatching { reader.close() }
            throw t
        }
        return reader
    }
}

/* ---------- ZIP (zip4j: AES + ZipCrypto) ---------- */

internal class ZipArchiveReader(file: File, password: CharArray?) : ArchiveReader(file, ArchiveFormat.ZIP, password) {
    private val zip = ZipFile(file, password)
    private val headers: List<ZipHeader> by lazy {
        try {
            if (!zip.isValidZipFile) throw CorruptedArchiveException()
            zip.fileHeaders
        } catch (e: ZipException) {
            throw CorruptedArchiveException(cause = e)
        }
    }

    private fun toItem(h: ZipHeader) = ArchiveItem.fromRaw(
        rawName = h.fileName,
        isDirectory = h.isDirectory,
        size = h.uncompressedSize,
        compressedSize = h.compressedSize,
        lastModified = h.lastModifiedTimeEpoch,
        isEncrypted = h.isEncrypted,
    )

    override fun readEntries(): List<ArchiveItem> = headers.mapNotNull { toItem(it) }

    override val isEncrypted: Boolean get() = headers.any { it.isEncrypted }

    private fun mapError(e: Throwable, encrypted: Boolean): Throwable {
        if (e is CancellationException || e is ArchiveException) return e
        if (!encrypted) return if (e is ZipException) CorruptedArchiveException(cause = e) else e
        if (password == null) return PasswordRequiredException()
        val zipType = (e as? ZipException)?.type
        return when {
            zipType == ZipException.Type.WRONG_PASSWORD || zipType == ZipException.Type.CHECKSUM_MISMATCH -> WrongPasswordException(e)
            e.cause is DataFormatException || e is java.util.zip.ZipException -> WrongPasswordException(e)
            e.message?.contains("password", ignoreCase = true) == true -> WrongPasswordException(e)
            e is ZipException -> CorruptedArchiveException(cause = e)
            else -> e
        }
    }

    private suspend fun copyEntry(h: ZipHeader, out: OutputStream, onBytes: (Long) -> Unit, checkpoint: suspend () -> Unit) {
        if (h.isEncrypted && password == null) throw PasswordRequiredException()
        try {
            zip.getInputStream(h).use { copyStream(it, out, onBytes, checkpoint) }
        } catch (t: Throwable) {
            throw mapError(t, h.isEncrypted)
        }
    }

    override suspend fun forEachEntry(wanted: (ArchiveItem) -> Boolean, block: suspend (ArchiveItem, EntryData) -> Unit) {
        for (h in headers) {
            val item = toItem(h) ?: continue
            if (!wanted(item)) continue
            block(item) { out, onBytes, cp -> copyEntry(h, out, onBytes, cp) }
        }
    }

    override suspend fun verifyPassword() = withContext(Dispatchers.IO) {
        val h = headers.filter { it.isEncrypted && !it.isDirectory }.minByOrNull { it.compressedSize } ?: return@withContext
        if (password == null) throw PasswordRequiredException()
        copyEntry(h, NullOutputStream, {}, {})
    }

    override fun close() {
        runCatching { zip.close() }
    }
}

internal object NullOutputStream : OutputStream() {
    override fun write(b: Int) = Unit
    override fun write(b: ByteArray, off: Int, len: Int) = Unit
}

/* ---------- 7z (commons-compress) ---------- */

internal class SevenZArchiveReader(file: File, password: CharArray?) : ArchiveReader(file, ArchiveFormat.SEVEN_Z, password) {

    private fun openFile(): SevenZFile {
        try {
            return SevenZFile.builder().setFile(file).apply { if (password != null) setPassword(password) }.get()
        } catch (e: org.apache.commons.compress.PasswordRequiredException) {
            throw PasswordRequiredException()
        } catch (e: OutOfMemoryError) {
            throw ArchiveException("Mémoire insuffisante pour ouvrir cette archive 7z")
        } catch (e: IOException) {
            if (password != null) {
                // Encrypted headers + wrong password look like corruption: tell them apart.
                val headersEncrypted = try {
                    SevenZFile.builder().setFile(file).get().close(); false
                } catch (_: org.apache.commons.compress.PasswordRequiredException) {
                    true
                } catch (_: IOException) {
                    false
                }
                if (headersEncrypted) throw WrongPasswordException(e)
            }
            throw CorruptedArchiveException(cause = e)
        }
    }

    private fun SevenZArchiveEntry.encrypted(): Boolean =
        runCatching { contentMethods?.any { it.method == SevenZMethod.AES256SHA256 } == true }.getOrDefault(false)

    private fun toItem(e: SevenZArchiveEntry): ArchiveItem? {
        if (e.isAntiItem) return null
        return ArchiveItem.fromRaw(
            rawName = e.name ?: return null,
            isDirectory = e.isDirectory,
            size = if (e.hasStream()) e.size else 0,
            lastModified = if (e.hasLastModifiedDate) e.lastModifiedDate.time else 0,
            isEncrypted = e.encrypted(),
        )
    }

    override fun readEntries(): List<ArchiveItem> = openFile().use { sz -> sz.entries.mapNotNull { toItem(it) } }

    private fun mapError(e: Throwable, encrypted: Boolean): Throwable = when {
        e is CancellationException || e is ArchiveException -> e
        e is org.apache.commons.compress.PasswordRequiredException -> PasswordRequiredException()
        encrypted && password == null -> PasswordRequiredException()
        encrypted && e is IOException -> WrongPasswordException(e)
        e is IOException -> CorruptedArchiveException(cause = e)
        else -> e
    }

    override suspend fun forEachEntry(wanted: (ArchiveItem) -> Boolean, block: suspend (ArchiveItem, EntryData) -> Unit) {
        openFile().use { sz ->
            while (true) {
                val e = try {
                    sz.nextEntry ?: break
                } catch (t: Throwable) {
                    throw mapError(t, password != null)
                }
                val item = toItem(e) ?: continue
                if (!wanted(item)) continue
                block(item) { out, onBytes, cp ->
                    if (!e.hasStream()) return@block
                    try {
                        val buf = ByteArray(BUFFER)
                        var since = 0L
                        while (true) {
                            val n = sz.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            onBytes(n.toLong())
                            since += n
                            if (since >= 1_048_576) { since = 0; cp() }
                        }
                    } catch (t: Throwable) {
                        throw mapError(t, item.isEncrypted)
                    }
                }
            }
        }
    }

    override suspend fun verifyPassword() = withContext(Dispatchers.IO) {
        // Header-encrypted archives are already validated by openFile().
        val first = entries().firstOrNull { it.isEncrypted && !it.isDirectory && it.size > 0 } ?: return@withContext
        if (password == null) throw PasswordRequiredException()
        val cap = 8L * 1024 * 1024
        var read = 0L
        forEachEntry({ it.path == first.path }) { _, data ->
            try {
                data.writeTo(NullOutputStream, { n ->
                    read += n
                    if (read > cap) throw StopReading()
                }, {})
            } catch (_: StopReading) {
                // Enough bytes decoded without error: the password is right.
            }
        }
    }

    private class StopReading : RuntimeException()
}

/* ---------- RAR (junrar 8: RAR 1.5–4.x and RAR5, encrypted headers included) ---------- */

internal class RarArchiveReader(file: File, password: CharArray?) : ArchiveReader(file, ArchiveFormat.RAR, password) {
    private val pw: String? = password?.concatToString()
    private val archive: Archive = openArchive()

    private fun openArchive(): Archive {
        try {
            return Archive(file, pw)
        } catch (e: UnsupportedRarVersionException) {
            throw UnsupportedArchiveException(
                "Cette version de RAR n'est pas prise en charge. Recompressez-la en ZIP ou 7z, ou utilisez une autre appli.", e,
            )
        } catch (e: RarWrongPasswordException) {
            throw if (pw == null) PasswordRequiredException() else WrongPasswordException(e)
        } catch (e: UnsupportedRarEncryptedException) {
            throw if (pw == null) PasswordRequiredException() else WrongPasswordException(e)
        } catch (e: RarException) {
            if (pw != null) {
                val needsPassword = try {
                    Archive(file, null as String?).close(); false
                } catch (_: UnsupportedRarEncryptedException) {
                    true
                } catch (_: Exception) {
                    false
                }
                if (needsPassword) throw WrongPasswordException(e)
            }
            throw CorruptedArchiveException(cause = e)
        } catch (e: IOException) {
            throw CorruptedArchiveException(cause = e)
        }
    }

    private val headers: List<RarHeader> by lazy { archive.fileHeaders }

    private fun RarHeader.name(): String = (fileName ?: "").replace('\\', '/')

    private fun toItem(h: RarHeader) = ArchiveItem.fromRaw(
        rawName = h.name(),
        isDirectory = h.isDirectory,
        size = h.fullUnpackSize,
        compressedSize = h.fullPackSize,
        lastModified = h.mTime?.time ?: 0,
        isEncrypted = h.isEncrypted,
    )

    override fun readEntries(): List<ArchiveItem> = headers.mapNotNull { toItem(it) }

    override val isEncrypted: Boolean
        get() = runCatching { archive.isPasswordProtected }.getOrDefault(false) || super.isEncrypted

    private val solid: Boolean by lazy { runCatching { archive.mainHeader?.isSolid == true }.getOrDefault(false) }

    private fun extractBlocking(h: RarHeader, out: OutputStream, job: Job?) {
        if (h.isEncrypted && pw == null) throw PasswordRequiredException()
        val guarded = object : OutputStream() {
            override fun write(b: Int) {
                job?.ensureActive(); out.write(b)
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                job?.ensureActive(); out.write(b, off, len)
            }
        }
        try {
            archive.extractFile(h, guarded)
        } catch (t: Throwable) {
            job?.ensureActive()
            throw when {
                t is ArchiveException -> t
                t is CancellationException -> t
                t is RarWrongPasswordException -> WrongPasswordException(t)
                h.isEncrypted && (t is CrcErrorException || t is RarException) -> WrongPasswordException(t)
                t is RarException -> CorruptedArchiveException(cause = t)
                else -> t
            }
        }
    }

    override suspend fun forEachEntry(wanted: (ArchiveItem) -> Boolean, block: suspend (ArchiveItem, EntryData) -> Unit) {
        val job = currentCoroutineContext()[Job]
        val wantedHeaders = headers.mapNotNull { h -> toItem(h)?.takeIf { wanted(it) }?.let { h to it } }
        val last = wantedHeaders.lastOrNull()?.first ?: return
        val wantedSet = wantedHeaders.mapTo(HashSet()) { it.first }
        for (h in headers) {
            if (h in wantedSet) {
                val item = toItem(h) ?: continue
                block(item) { out, onBytes, _ ->
                    val counting = object : OutputStream() {
                        override fun write(b: Int) { out.write(b); onBytes(1) }
                        override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); onBytes(len.toLong()) }
                    }
                    extractBlocking(h, counting, job)
                }
            } else if (solid && !h.isDirectory) {
                // Solid archives must be decoded in order: run through the skipped files.
                currentCoroutineContext().ensureActive()
                extractBlocking(h, NullOutputStream, job)
            }
            if (h === last) break
        }
    }

    override suspend fun verifyPassword() = withContext(Dispatchers.IO) {
        val h = headers.filter { it.isEncrypted && !it.isDirectory }.minByOrNull { it.fullPackSize } ?: return@withContext
        if (pw == null) throw PasswordRequiredException()
        if (solid) {
            // Only the first file can be checked cheaply in a solid archive.
            val first = headers.firstOrNull { !it.isDirectory } ?: return@withContext
            extractBlocking(first, NullOutputStream, currentCoroutineContext()[Job])
        } else {
            extractBlocking(h, NullOutputStream, currentCoroutineContext()[Job])
        }
    }

    override fun close() {
        runCatching { archive.close() }
    }
}

/* ---------- TAR, TAR.GZ, TAR.BZ2, TAR.XZ ---------- */

internal fun decompressing(format: ArchiveFormat, input: InputStream): InputStream = when (format) {
    ArchiveFormat.TAR_GZ, ArchiveFormat.GZ -> GzipCompressorInputStream(input, true)
    ArchiveFormat.TAR_BZ2, ArchiveFormat.BZ2 -> BZip2CompressorInputStream(input, true)
    ArchiveFormat.TAR_XZ, ArchiveFormat.XZ -> XZCompressorInputStream(input, true)
    else -> input
}

internal class TarArchiveReader(file: File, format: ArchiveFormat) : ArchiveReader(file, format, null) {

    private fun open(): TarArchiveInputStream = try {
        TarArchiveInputStream(decompressing(format, BufferedInputStream(FileInputStream(file), BUFFER)))
    } catch (e: IOException) {
        throw CorruptedArchiveException(cause = e)
    }

    private fun TarArchiveEntry.isSupported() = !isSymbolicLink && !isLink && !isCharacterDevice && !isBlockDevice && !isFIFO

    private fun toItem(e: TarArchiveEntry) = ArchiveItem.fromRaw(
        rawName = e.name,
        isDirectory = e.isDirectory,
        size = e.size,
        lastModified = runCatching { e.modTime.time }.getOrDefault(0),
    )

    private inline fun <T> mapped(block: () -> T): T = try {
        block()
    } catch (e: ArchiveException) {
        throw e
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        throw CorruptedArchiveException(cause = e)
    } catch (e: IllegalArgumentException) {
        throw CorruptedArchiveException(cause = e)
    }

    override fun readEntries(): List<ArchiveItem> = mapped {
        open().use { tin ->
            val out = ArrayList<ArchiveItem>()
            while (true) {
                val e = tin.nextEntry ?: break
                if (!e.isSupported()) continue
                toItem(e)?.let(out::add)
            }
            out
        }
    }

    override suspend fun forEachEntry(wanted: (ArchiveItem) -> Boolean, block: suspend (ArchiveItem, EntryData) -> Unit) {
        open().use { tin ->
            while (true) {
                val e = mapped { tin.nextEntry } ?: break
                if (!e.isSupported()) continue
                val item = toItem(e) ?: continue
                if (!wanted(item)) continue
                block(item) { out, onBytes, cp -> mapped { copyStream(tin, out, onBytes, cp) } }
            }
        }
    }
}

/* ---------- Single compressed file: .gz, .bz2, .xz ---------- */

internal class SingleFileReader(file: File, format: ArchiveFormat) : ArchiveReader(file, format, null) {
    val innerName: String = run {
        val n = file.name
        val stripped = when {
            n.endsWith(".gz", true) -> n.dropLast(3)
            n.endsWith(".bz2", true) -> n.dropLast(4)
            n.endsWith(".xz", true) -> n.dropLast(3)
            else -> "$n.out"
        }
        stripped.ifEmpty { "contenu" }
    }

    private fun uncompressedSize(): Long = when (format) {
        ArchiveFormat.GZ -> runCatching {
            // ISIZE trailer: size modulo 2^32 of the (last) member.
            RandomAccessFile(file, "r").use { raf ->
                if (raf.length() < 18) return@use -1L
                raf.seek(raf.length() - 4)
                val b = ByteArray(4).also { raf.readFully(it) }
                (b[0].toLong() and 0xFF) or ((b[1].toLong() and 0xFF) shl 8) or
                    ((b[2].toLong() and 0xFF) shl 16) or ((b[3].toLong() and 0xFF) shl 24)
            }
        }.getOrDefault(-1L)
        ArchiveFormat.XZ -> runCatching { SeekableXZInputStream(SeekableFileInputStream(file)).use { it.length() } }.getOrDefault(-1L)
        else -> -1L
    }

    override fun readEntries(): List<ArchiveItem> = listOfNotNull(
        ArchiveItem.fromRaw(innerName, false, uncompressedSize(), file.length(), file.lastModified()),
    )

    override suspend fun forEachEntry(wanted: (ArchiveItem) -> Boolean, block: suspend (ArchiveItem, EntryData) -> Unit) {
        val item = entries().firstOrNull() ?: return
        if (!wanted(item)) return
        block(item) { out, onBytes, cp ->
            try {
                decompressing(format, BufferedInputStream(FileInputStream(file), BUFFER)).use { copyStream(it, out, onBytes, cp) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                throw CorruptedArchiveException(cause = e)
            }
        }
    }
}
