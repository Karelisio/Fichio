package com.karelisio.fichio.domain.operations

import java.io.File
import java.security.MessageDigest

/**
 * Digest helpers for file properties (MD5 and SHA-256 on demand) and duplicate
 * detection. Streaming versions avoid loading whole files into memory.
 */
object Hashing {

    fun md5(bytes: ByteArray): String = digest("MD5", bytes)
    fun sha256(bytes: ByteArray): String = digest("SHA-256", bytes)

    fun md5(file: File): String = digestFile("MD5", file)
    fun sha256(file: File): String = digestFile("SHA-256", file)

    private fun digest(algorithm: String, bytes: ByteArray): String =
        MessageDigest.getInstance(algorithm).digest(bytes).toHex()

    private fun digestFile(algorithm: String, file: File): String {
        val md = MessageDigest.getInstance(algorithm)
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                md.update(buffer, 0, read)
            }
        }
        return md.digest().toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
