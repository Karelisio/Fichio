package com.karelisio.fichio.trash

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.karelisio.fichio.core.fs.LocalBackend
import com.karelisio.fichio.data.db.FichioDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** The real Room DAO behind [TrashStore] (in-memory database). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RoomTrashMetaTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var db: FichioDatabase

    @Before
    fun setUp() {
        db = FichioDatabase.inMemory(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun roundTripThroughRoom() = runTest {
        val storage = tmp.newFolder("s")
        val root = File(storage, ".Fichio/trash").path
        val local = LocalBackend()
        val store = TrashStore({ root }, { local }, RoomTrashMeta(db.trash()))
        val f = File(storage, "Music/song.mp3").apply { parentFile!!.mkdirs(); writeText("la la") }

        val item = store.moveToTrash(f.path)
        assertEquals(1, db.trash().observeCount().first())
        assertEquals(5L, db.trash().observeTotalSize().first())
        assertEquals(item, db.trash().get(item.id))

        val restored = store.restore(item.id).getOrThrow()
        assertEquals(f.path, restored)
        assertTrue(f.exists())
        assertEquals(0, db.trash().observeCount().first())
    }
}
