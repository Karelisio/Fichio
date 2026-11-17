package com.karelisio.fichio.archive

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.lingala.zip4j.io.outputstream.ZipOutputStream
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.CompressionLevel
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.apache.commons.compress.archivers.sevenz.SevenZMethod
import org.apache.commons.compress.archivers.sevenz.SevenZMethodConfiguration
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.tukaani.xz.LZMA2Options
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/** Formats Fichio can create. */
enum class OutputFormat(val extension: String, val label: String, val supportsPassword: Boolean) {
    ZIP("zip", "ZIP", true),
    SEVEN_Z("7z", "7z", false),
}

data class CompressOptions(
    val format: OutputFormat = OutputFormat.ZIP,
    /** 0 = store … 9 = ultra. */
    val level: Int = 5,
    /** AES-256 password (ZIP only); null or empty = no encryption. */
    val password: CharArray? = null,
) {
    val encrypted: Boolean get() = format.supportsPassword && password != null && password.isNotEmpty()
}

/** One file or folder to write, with its name inside the archive. */
data class PlanEntry(val file: File, val entryName: String, val isDirectory: Boolean, val size: Long)

/** Creates ZIP (zip4j, optional AES-256) and 7z (LZMA2) archives, recursively, with byte progress. */
object ArchiveWriter {
    const val MIN_LEVEL = 0
    const val MAX_LEVEL = 9

    fun levelLabel(level: Int): String = when (level.coerceIn(MIN_LEVEL, MAX_LEVEL)) {
        0 -> "Stockage"
        1 -> "Le plus rapide"
        2, 3 -> "Rapide"
        4, 5, 6 -> "Normal"
        7, 8 -> "Maximum"
        else -> "Ultra"
    }

    /**
     * Lists what [sources] expand to. Entry names are relative to each source's parent so that a
     * selected folder keeps its own name at the root of the archive. Symlink loops are cut and
     * [exclude] (the archive being written) is skipped.
     */
    fun plan(sources: List<File>, exclude: File? = null): List<PlanEntry> {
        val out = ArrayList<PlanEntry>()
        val visited = HashSet<String>()
        val excluded = exclude?.absoluteFile
        val usedNames = HashSet<String>()
        fun walk(file: File, name: String) {
            if (excluded != null && file.absoluteFile == excluded) return
            if (file.isDirectory) {
                val canon = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
                if (!visited.add(canon)) return
                if (usedNames.add("$name/")) out += PlanEntry(file, "$name/", true, 0)
                file.listFiles()?.sortedBy { it.name.lowercase() }?.forEach { walk(it, "$name/${it.name}") }
            } else if (file.isFile) {
                if (usedNames.add(name)) out += PlanEntry(file, name, false, file.length())
            }
        }
        sources.forEach { walk(it, it.name) }
        return out
    }

    fun totalSize(plan: List<PlanEntry>): Long = plan.sumOf { it.size }

    /** Suggested archive name (without extension) for a selection. */
    fun suggestedName(sources: List<File>): String {
        val single = sources.singleOrNull()
        val base = when {
            single == null -> sources.firstOrNull()?.parentFile?.name?.takeIf { it.isNotBlank() } ?: "Archive"
            single.isDirectory -> single.name
            else -> single.name.substringBeforeLast('.').ifEmpty { single.name }
        }
        return base.ifBlank { "Archive" }
    }

    /**
     * Writes [sources] into [output]. The partial file is deleted on failure or cancellation.
     * Returns the size of the created archive.
     */
    suspend fun write(
        sources: List<File>,
        output: File,
        options: CompressOptions,
        callbacks: ProgressCallbacks = object : ProgressCallbacks {},
    ): Long = withContext(Dispatchers.IO) {
        val plan = plan(sources, exclude = output)
        if (plan.isEmpty()) throw ArchiveException("Rien à compresser")
        output.parentFile?.mkdirs()
        try {
            when (options.format) {
                OutputFormat.ZIP -> writeZip(plan, output, options, callbacks)
                OutputFormat.SEVEN_Z -> writeSevenZ(plan, output, options, callbacks)
            }
        } catch (t: Throwable) {
            output.delete()
            throw t
        }
        output.length()
    }

    private fun zipLevel(level: Int): CompressionLevel = when (level.coerceIn(1, 9)) {
        1 -> CompressionLevel.FASTEST
        2 -> CompressionLevel.FASTER
        3 -> CompressionLevel.FAST
        4 -> CompressionLevel.MEDIUM_FAST
        5 -> CompressionLevel.NORMAL
        6 -> CompressionLevel.HIGHER
        7 -> CompressionLevel.MAXIMUM
        8 -> CompressionLevel.PRE_ULTRA
        else -> CompressionLevel.ULTRA
    }

    private suspend fun writeZip(plan: List<PlanEntry>, output: File, options: CompressOptions, cb: ProgressCallbacks) {
        val total = totalSize(plan)
        val files = plan.count { !it.isDirectory }
        var done = 0L
        var doneItems = 0
        val password = if (options.encrypted) options.password else null
        ZipOutputStream(BufferedOutputStream(FileOutputStream(output), 256 * 1024), password).use { zos ->
            for (e in plan) {
                cb.checkpoint()
                val params = ZipParameters().apply {
                    fileNameInZip = e.entryName
                    lastModifiedFileTime = e.file.lastModified().coerceAtLeast(DOS_EPOCH)
                    if (e.isDirectory || options.level <= 0) {
                        compressionMethod = CompressionMethod.STORE
                        entrySize = if (e.isDirectory) 0 else e.size
                    } else {
                        compressionMethod = CompressionMethod.DEFLATE
                        compressionLevel = zipLevel(options.level)
                    }
                    if (password != null && !e.isDirectory) {
                        isEncryptFiles = true
                        encryptionMethod = EncryptionMethod.AES
                        aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
                    }
                }
                zos.putNextEntry(params)
                if (!e.isDirectory) {
                    FileInputStream(e.file).use { input ->
                        copyStream(input, zos, { n ->
                            done += n
                            cb.onProgress(done, total, e.file.name, doneItems, files)
                        }, cb::checkpoint)
                    }
                    doneItems++
                }
                zos.closeEntry()
                cb.onProgress(done, total, e.file.name, doneItems, files)
            }
        }
    }

    /** LZMA2 options per level. Presets above 6 need 180–700 MB to encode: capped, tuned instead. */
    internal fun lzma2Options(level: Int): LZMA2Options {
        val l = level.coerceIn(1, 9)
        return LZMA2Options(l.coerceAtMost(6)).apply {
            when (l) {
                7 -> niceLen = 128
                8 -> niceLen = 192
                9 -> { niceLen = LZMA2Options.NICE_LEN_MAX; depthLimit = 0 }
            }
        }
    }

    private suspend fun writeSevenZ(plan: List<PlanEntry>, output: File, options: CompressOptions, cb: ProgressCallbacks) {
        val total = totalSize(plan)
        val files = plan.count { !it.isDirectory }
        var done = 0L
        var doneItems = 0
        SevenZOutputFile(output).use { out ->
            out.setContentMethods(
                listOf(
                    if (options.level <= 0) SevenZMethodConfiguration(SevenZMethod.COPY)
                    else SevenZMethodConfiguration(SevenZMethod.LZMA2, lzma2Options(options.level)),
                ),
            )
            val buf = ByteArray(64 * 1024)
            for (e in plan) {
                cb.checkpoint()
                val entry = out.createArchiveEntry(e.file, e.entryName.trimEnd('/'))
                out.putArchiveEntry(entry)
                if (!e.isDirectory) {
                    FileInputStream(e.file).use { input ->
                        var since = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            since += n
                            cb.onProgress(done, total, e.file.name, doneItems, files)
                            if (since >= 1_048_576) { since = 0; cb.checkpoint() }
                        }
                    }
                    doneItems++
                }
                out.closeArchiveEntry()
            }
            out.finish()
        }
        cb.onProgress(done, total, "", doneItems, files)
    }

    /** 1980-01-01, the earliest date a ZIP (DOS) timestamp can hold. */
    private const val DOS_EPOCH = 315_532_800_000L
}
