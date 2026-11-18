package com.karelisio.fichio.archive

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32

/**
 * RAR5 archives (refused before junrar 8): a minimal "stored" RAR5 file is written by hand
 * following the RAR 5.0 technote (no rar tool needed), then listed and extracted through Fichio's reader.
 */
class Rar5ReadTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun ByteArrayOutputStream.vint(value: Long) {
        var v = value
        do {
            var b = (v and 0x7f).toInt()
            v = v ushr 7
            if (v != 0L) b = b or 0x80
            write(b)
        } while (v != 0L)
    }

    private fun ByteArrayOutputStream.u32(value: Long) {
        for (i in 0 until 4) write(((value ushr (8 * i)) and 0xff).toInt())
    }

    /** One RAR5 block: CRC32, header size, then type, flags, [data size], fields; followed by its data. */
    private fun ByteArrayOutputStream.block(type: Int, fields: ByteArrayOutputStream.() -> Unit, data: ByteArray? = null) {
        val header = ByteArrayOutputStream().apply {
            vint(type.toLong())
            vint(if (data != null) 0x0002 else 0) // header flags: data area present
            if (data != null) vint(data.size.toLong())
            fields()
        }.toByteArray()
        val sized = ByteArrayOutputStream().apply { vint(header.size.toLong()); write(header) }.toByteArray()
        u32(CRC32().apply { update(sized) }.value)
        write(sized)
        data?.let { write(it) }
    }

    private fun ByteArrayOutputStream.fileBlock(name: String, content: ByteArray?, mtime: Long) {
        val dir = content == null
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        block(2, {
            vint(if (dir) 0x0003 else 0x0006) // directory + mtime / mtime + CRC32
            vint(content?.size?.toLong() ?: 0) // unpacked size
            vint(if (dir) 0x10 else 0x20) // attributes
            u32(mtime)
            if (!dir) u32(CRC32().apply { update(content!!) }.value)
            vint(0) // compression: RAR 5.0 algorithm, method 0 (store)
            vint(1) // host OS: Unix
            vint(nameBytes.size.toLong())
            write(nameBytes)
        }, content)
    }

    private fun rar5(entries: List<Pair<String, ByteArray?>>): File {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00))
        out.block(1, { vint(0) }) // main archive header, no flags
        entries.forEach { (name, content) -> out.fileBlock(name, content, 1_700_000_000L) }
        out.block(5, { vint(0) }) // end of archive
        return File(tmp.root, "sample.rar").apply { writeBytes(out.toByteArray()) }
    }

    @Test fun rar5IsListedAndExtracted() = runBlocking {
        val hello = "Bonjour depuis un RAR5 !".toByteArray()
        val notes = ("ligne\n".repeat(200)).toByteArray()
        val file = rar5(listOf("docs" to null, "hello.txt" to hello, "docs/notes.md" to notes))
        Archives.open(file).use { r ->
            assertEquals(ArchiveFormat.RAR, r.format)
            assertFalse(r.isEncrypted)
            assertEquals(listOf("docs", "hello.txt"), r.tree.children("").map { it.name })
            assertEquals(listOf("notes.md"), r.tree.children("docs").map { it.name })
            assertEquals((hello.size + notes.size).toLong(), r.tree.totalSize)
            val dest = tmp.newFolder("out")
            val res = r.extract(ExtractRequest(dest))
            assertEquals(2, res.extracted)
            assertEquals(String(hello), File(dest, "hello.txt").readText())
            assertEquals(String(notes), File(dest, "docs/notes.md").readText())
        }
    }
}
