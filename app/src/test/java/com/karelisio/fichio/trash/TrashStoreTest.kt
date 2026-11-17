package com.karelisio.fichio.trash

import com.karelisio.fichio.core.fs.LocalBackend
import com.karelisio.fichio.data.db.TrashEntity
import com.karelisio.fichio.ops.engine.OperationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** In-memory metadata, standing in for Room. */
class FakeTrashMeta : TrashMetaStore {
    val rows = LinkedHashMap<String, TrashEntity>()
    override suspend fun insert(item: TrashEntity) { rows[item.id] = item }
    override suspend fun get(id: String) = rows[id]
    override suspend fun all() = rows.values.sortedByDescending { it.deletedAt }
    override suspend fun olderThan(before: Long) = rows.values.filter { it.deletedAt < before }
    override suspend fun delete(id: String) { rows.remove(id) }
}

class TrashStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private val local = LocalBackend()
    private lateinit var storage: File
    private lateinit var trashRoot: String
    private val meta = FakeTrashMeta()
    private var clock = 1_000_000_000_000L
    private var nextId = 0
    private lateinit var store: TrashStore

    @Before
    fun setUp() {
        storage = tmp.newFolder("storage")
        trashRoot = File(storage, ".Fichio/trash").path
        store = TrashStore(
            root = { trashRoot },
            backendFor = { local },
            meta = meta,
            now = { clock },
            newId = { "id${++nextId}" },
        )
    }

    private fun file(rel: String, content: String = rel) =
        File(storage, rel).apply { parentFile!!.mkdirs(); writeText(content) }

    @Test
    fun moveToTrashUsesUuidFolderAndNomedia() = runTest {
        val f = file("Documents/cv.pdf", "pdf!")
        val item = store.moveToTrash(f.path)
        assertFalse(f.exists())
        assertEquals("$trashRoot/id1/cv.pdf", item.trashPath)
        assertEquals("pdf!", File(item.trashPath).readText())
        assertEquals(f.path, item.originalPath)
        assertEquals(4L, item.size)
        assertEquals(clock, item.deletedAt)
        assertTrue(File(storage, ".Fichio/.nomedia").exists())
        assertEquals(item, meta.rows["id1"])
    }

    @Test
    fun folderSizeIsRecursive() = runTest {
        file("Album/a.jpg", "12345"); file("Album/sub/b.jpg", "123")
        val item = store.moveToTrash(File(storage, "Album").path)
        assertTrue(item.isDirectory)
        assertEquals(8L, item.size)
        assertTrue(File(item.trashPath, "sub/b.jpg").exists())
    }

    @Test
    fun restoreRecreatesParents() = runTest {
        val f = file("Deep/er/x.txt", "x")
        val item = store.moveToTrash(f.path)
        File(storage, "Deep").deleteRecursively()
        val restored = store.restore(item.id).getOrThrow()
        assertEquals(f.path, restored)
        assertEquals("x", f.readText())
        assertNull(meta.rows[item.id])
        assertFalse(File(trashRoot, item.id).exists())
    }

    @Test
    fun restoreKeepsBothOnConflict() = runTest {
        val f = file("Docs/a.txt", "old")
        val item = store.moveToTrash(f.path)
        f.writeText("new")
        val restored = store.restore(item.id).getOrThrow()
        assertEquals(File(storage, "Docs/a (1).txt").path, restored)
        assertEquals("old", File(restored).readText())
        assertEquals("new", f.readText())
    }

    @Test
    fun restoreOfVanishedItemFailsAndForgetsIt() = runTest {
        val item = store.moveToTrash(file("z.txt").path)
        File(item.trashPath).delete()
        assertTrue(store.restore(item.id).isFailure)
        assertNull(meta.rows[item.id])
    }

    @Test
    fun deleteForeverAndEmpty() = runTest {
        val a = store.moveToTrash(file("a.txt").path)
        val b = store.moveToTrash(file("b.txt").path)
        store.deleteForever(a.id)
        assertFalse(File(a.trashPath).exists())
        assertEquals(setOf(b.id), meta.rows.keys)
        // An orphan folder (no metadata) is removed by empty().
        File(trashRoot, "orphan").mkdirs()
        assertEquals(1, store.empty())
        assertTrue(meta.rows.isEmpty())
        assertEquals(listOf(".nomedia"), File(trashRoot).list()!!.toList())
    }

    @Test
    fun purgeOlderThan() = runTest {
        val old = store.moveToTrash(file("old.txt").path)
        clock += 20 * TrashStore.DAY_MS
        val recent = store.moveToTrash(file("recent.txt").path)
        clock += 15 * TrashStore.DAY_MS
        assertEquals(1, store.purgeOlderThan(30))
        assertFalse(File(old.trashPath).exists())
        assertTrue(File(recent.trashPath).exists())
        assertEquals(setOf(recent.id), meta.rows.keys)
        assertEquals(15, TrashStore.daysLeft(recent, 30, clock))
    }

    @Test
    fun refusesTrashInsideTrash() = runTest {
        val item = store.moveToTrash(file("t.txt").path)
        try {
            store.moveToTrash(item.trashPath)
            fail("expected OperationException")
        } catch (_: OperationException) {
        }
    }

    @Test
    fun neverDeletesOutsideTheTrash() = runTest {
        val outside = file("keep/me.txt")
        meta.insert(TrashEntity("evil", outside.path, outside.path, "me.txt", false, 1, 0))
        store.deleteForever("evil")
        assertTrue(outside.exists())
    }
}
