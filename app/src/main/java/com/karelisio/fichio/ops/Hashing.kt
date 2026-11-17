package com.karelisio.fichio.ops

import com.karelisio.fichio.core.fs.FileBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.security.MessageDigest

enum class HashAlgorithm(val label: String, internal val jca: String) {
    MD5("MD5", "MD5"),
    SHA256("SHA-256", "SHA-256"),
}

/** Checksums computed in a single pass over the file, cancellable, with progress. */
object Hashing {
    private const val BUFFER = 256 * 1024

    suspend fun compute(
        input: InputStream,
        size: Long,
        algorithms: Set<HashAlgorithm> = HashAlgorithm.entries.toSet(),
        onProgress: (Float) -> Unit = {},
    ): Map<HashAlgorithm, String> = withContext(Dispatchers.IO) {
        val digests = algorithms.associateWith { MessageDigest.getInstance(it.jca) }
        val buf = ByteArray(BUFFER)
        var done = 0L
        var lastReport = -1
        input.use { s ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = s.read(buf)
                if (n < 0) break
                digests.values.forEach { it.update(buf, 0, n) }
                done += n
                if (size > 0) {
                    val pct = (done * 1000 / size).toInt()
                    if (pct != lastReport) { lastReport = pct; onProgress((done.toFloat() / size).coerceIn(0f, 1f)) }
                }
            }
        }
        onProgress(1f)
        digests.mapValues { hex(it.value.digest()) }
    }

    suspend fun compute(
        backend: FileBackend,
        path: String,
        algorithms: Set<HashAlgorithm> = HashAlgorithm.entries.toSet(),
        onProgress: (Float) -> Unit = {},
    ): Map<HashAlgorithm, String> = withContext(Dispatchers.IO) {
        val size = backend.stat(path)?.size ?: 0L
        compute(backend.openInput(path), size, algorithms, onProgress)
    }

    fun hex(bytes: ByteArray): String {
        val chars = "0123456789abcdef"
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xff
            sb.append(chars[v ushr 4]).append(chars[v and 0x0f])
        }
        return sb.toString()
    }
}
