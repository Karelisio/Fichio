package com.karelisio.fichio.archive

import com.karelisio.fichio.ops.ConflictChoice
import kotlinx.coroutines.runBlocking
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.GZIPOutputStream
import kotlin.random.Random

class ArchiveRoundTripTest {
    @get:Rule val tmp = TemporaryFolder()

    /** src/{hello.txt, docs/notes.md, docs/deep/data.bin (300 KB), empty/} */
    private fun sampleTree(): File {
        val src = tmp.newFolder("src")
        File(src, "hello.txt").writeText("Bonjour Fichio !")
        File(src, "docs/deep").mkdirs()
        File(src, "docs/notes.md").writeText("# Notes\n" + "ligne\n".repeat(500))
        File(src, "docs/deep/data.bin").writeBytes(Random(42).nextBytes(300_000))
        File(src, "empty").mkdirs()
        return src
    }

    private fun assertSameTree(expected: File, actual: File) {
        val exp = expected.walkTopDown().filter { it.isFile }.map { it.relativeTo(expected).path }.sorted().toList()
        val act = actual.walkTopDown().filter { it.isFile }.map { it.relativeTo(actual).path }.sorted().toList()
        assertEquals(exp, act)
        exp.forEach { assertArrayEquals(it, File(expected, it).readBytes(), File(actual, it).readBytes()) }
        assertTrue("empty folder kept", File(actual, "empty").isDirectory || !File(expected, "empty").exists())
    }

    @Test fun zipRoundTripWithoutPassword() = runBlocking {
        val src = sampleTree()
        val out = File(tmp.root, "a.zip")
        ArchiveWriter.write(listOf(src), out, CompressOptions(OutputFormat.ZIP, level = 6))
        Archives.open(out).use { r ->
            assertEquals(ArchiveFormat.ZIP, r.format)
            assertFalse(r.isEncrypted)
            assertEquals(listOf("src"), r.tree.children("").map { it.name })
            assertEquals(listOf("docs", "empty", "hello.txt"), r.tree.children("src").map { it.name })
            assertEquals(3, r.tree.fileCount)
            assertEquals(src.walkTopDown().filter { it.isFile }.sumOf { it.length() }, r.tree.totalSize)
            assertTrue(r.tree.compressedSize in 1 until r.tree.totalSize)
            val dest = tmp.newFolder("out")
            val res = r.extract(ExtractRequest(dest))
            assertEquals(3, res.extracted)
            assertSameTree(src, File(dest, "src"))
        }
    }

    @Test fun zipStoreLevelRoundTrip() = runBlocking {
        val src = sampleTree()
        val out = File(tmp.root, "store.zip")
        ArchiveWriter.write(listOf(src), out, CompressOptions(OutputFormat.ZIP, level = 0))
        Archives.open(out).use { r ->
            val dest = tmp.newFolder("out")
            r.extract(ExtractRequest(dest))
            assertSameTree(src, File(dest, "src"))
        }
    }

    @Test fun zipAesPassword() = runBlocking {
        val src = sampleTree()
        val out = File(tmp.root, "secret.zip")
        ArchiveWriter.write(listOf(src), out, CompressOptions(OutputFormat.ZIP, 5, "s3cret".toCharArray()))

        // Listing works without password; content does not.
        Archives.open(out).use { r ->
            assertTrue(r.isEncrypted)
            assertTrue(r.tree["src/hello.txt"]!!.isEncrypted)
            expect<PasswordRequiredException> { r.verifyPassword() }
            expect<PasswordRequiredException> { r.extract(ExtractRequest(tmp.newFolder("nopw"))) }
        }
        Archives.open(out, "wrong".toCharArray()).use { r ->
            expect<WrongPasswordException> { r.verifyPassword() }
            expect<WrongPasswordException> { r.extract(ExtractRequest(tmp.newFolder("bad"))) }
        }
        Archives.open(out, "s3cret".toCharArray()).use { r ->
            r.verifyPassword()
            val dest = tmp.newFolder("good")
            r.extract(ExtractRequest(dest))
            assertSameTree(src, File(dest, "src"))
        }
    }

    @Test fun zipCryptoLegacyPassword() = runBlocking {
        val f = File(tmp.root, "legacy.txt").apply { writeText("ancien chiffrement ".repeat(100)) }
        val out = File(tmp.root, "legacy.zip")
        ZipFile(out, "pw".toCharArray()).use {
            it.addFile(f, ZipParameters().apply { isEncryptFiles = true; encryptionMethod = EncryptionMethod.ZIP_STANDARD })
        }
        Archives.open(out, "nope".toCharArray()).use { r -> expect<WrongPasswordException> { r.verifyPassword() } }
        Archives.open(out, "pw".toCharArray()).use { r ->
            val dest = tmp.newFolder("o")
            r.extract(ExtractRequest(dest))
            assertEquals(f.readText(), File(dest, "legacy.txt").readText())
        }
    }

    @Test fun sevenZRoundTrip() = runBlocking {
        val src = sampleTree()
        for (level in listOf(0, 5, 9)) {
            val out = File(tmp.root, "a$level.7z")
            ArchiveWriter.write(listOf(src), out, CompressOptions(OutputFormat.SEVEN_Z, level))
            Archives.open(out).use { r ->
                assertEquals(ArchiveFormat.SEVEN_Z, r.format)
                assertEquals(3, r.tree.fileCount)
                assertTrue(r.tree["src/empty"]!!.isDirectory)
                val dest = tmp.newFolder("out$level")
                r.extract(ExtractRequest(dest))
                assertSameTree(src, File(dest, "src"))
            }
        }
    }

    @Test fun tarGzRoundTripWithImplicitFolders() = runBlocking {
        val out = File(tmp.root, "a.tar.gz")
        val payload = Random(7).nextBytes(50_000)
        TarArchiveOutputStream(GzipCompressorOutputStream(out.outputStream())).use { tar ->
            // No directory entries at all: x/ and x/y/ must be reconstructed.
            for ((name, bytes) in listOf("x/y/z.bin" to payload, "x/readme.txt" to "salut".toByteArray(), "top.txt" to "t".toByteArray())) {
                val e = TarArchiveEntry(name).apply { size = bytes.size.toLong() }
                tar.putArchiveEntry(e); tar.write(bytes); tar.closeArchiveEntry()
            }
        }
        Archives.open(out).use { r ->
            assertEquals(ArchiveFormat.TAR_GZ, r.format)
            assertEquals(listOf("x", "top.txt"), r.tree.children("").map { it.name })
            assertTrue(r.tree["x"]!!.isImplicit)
            assertTrue(r.tree["x/y"]!!.isDirectory)
            assertEquals(listOf("y", "readme.txt"), r.tree.children("x").map { it.name })
            val dest = tmp.newFolder("out")
            r.extract(ExtractRequest(dest))
            assertArrayEquals(payload, File(dest, "x/y/z.bin").readBytes())
            assertEquals("salut", File(dest, "x/readme.txt").readText())
        }
    }

    @Test fun singleGzFile() = runBlocking {
        val out = File(tmp.root, "journal.log.gz")
        val text = "ligne de journal\n".repeat(1000)
        GZIPOutputStream(out.outputStream()).use { it.write(text.toByteArray()) }
        Archives.open(out).use { r ->
            assertEquals(ArchiveFormat.GZ, r.format)
            val item = r.entries().single()
            assertEquals("journal.log", item.name)
            assertEquals(text.length.toLong(), item.size)
            val dest = tmp.newFolder("o")
            r.extract(ExtractRequest(dest))
            assertEquals(text, File(dest, "journal.log").readText())
        }
    }

    @Test fun extractSelectionRelativeToBrowsedFolder() = runBlocking {
        val src = sampleTree()
        val out = File(tmp.root, "a.zip")
        ArchiveWriter.write(listOf(src), out, CompressOptions())
        Archives.open(out).use { r ->
            val dest = tmp.newFolder("sel")
            val res = r.extract(ExtractRequest(dest, listOf("src/docs/deep"), stripPrefix = "src/docs"))
            assertEquals(1, res.extracted)
            assertTrue(File(dest, "deep/data.bin").isFile)
            assertFalse(File(dest, "src").exists())
            assertFalse(File(dest, "notes.md").exists())
        }
    }

    @Test fun conflictKeepBothAndSkip() = runBlocking {
        val src = sampleTree()
        val out = File(tmp.root, "a.zip")
        ArchiveWriter.write(listOf(File(src, "hello.txt")), out, CompressOptions())
        Archives.open(out).use { r ->
            val dest = tmp.newFolder("c")
            File(dest, "hello.txt").writeText("déjà là")
            val keep = object : ExtractCallbacks {
                override suspend fun onConflict(item: ArchiveItem, existing: File) = ConflictChoice.KEEP_BOTH
            }
            r.extract(ExtractRequest(dest), keep)
            assertEquals("déjà là", File(dest, "hello.txt").readText())
            assertEquals("Bonjour Fichio !", File(dest, "hello (1).txt").readText())
            val skip = object : ExtractCallbacks {
                override suspend fun onConflict(item: ArchiveItem, existing: File) = ConflictChoice.SKIP
            }
            assertEquals(1, r.extract(ExtractRequest(dest), skip).skipped)
        }
    }

    @Test fun progressReachesTotal() = runBlocking {
        val src = sampleTree()
        val out = File(tmp.root, "p.zip")
        var last = 0L
        var total = -1L
        val cb = object : ProgressCallbacks {
            override fun onProgress(doneBytes: Long, totalBytes: Long, currentName: String, doneItems: Int, totalItems: Int) {
                assertTrue(doneBytes >= last); last = doneBytes; total = totalBytes
            }
        }
        ArchiveWriter.write(listOf(src), out, CompressOptions(), cb)
        assertEquals(total, last)
        assertEquals(src.walkTopDown().filter { it.isFile }.sumOf { it.length() }, total)
    }

    @Test fun detectsFormatFromContent() {
        val src = sampleTree()
        val out = File(tmp.root, "renamed.bin")
        runBlocking { ArchiveWriter.write(listOf(src), out, CompressOptions(OutputFormat.SEVEN_Z, 1)) }
        assertEquals(ArchiveFormat.SEVEN_Z, ArchiveFormat.detect(out))
        assertEquals(ArchiveFormat.TAR_XZ, ArchiveFormat.fromName("a.tar.xz"))
        assertEquals(ArchiveFormat.TAR_GZ, ArchiveFormat.fromName("A.TGZ"))
        assertEquals(ArchiveFormat.ZIP, ArchiveFormat.fromName("bundle.apks"))
    }

    private suspend inline fun <reified T : Throwable> expect(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected ${T::class.simpleName}")
        } catch (t: Throwable) {
            if (t !is T) throw AssertionError("Expected ${T::class.simpleName} but got $t", t)
        }
    }
}
