package com.karelisio.fichio.search

import android.app.Application
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.core.app.ApplicationProvider
import com.karelisio.fichio.core.model.FileType
import com.karelisio.fichio.data.db.FichioDatabase
import com.karelisio.fichio.data.db.FileIndexEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SmartFoldersTest {
    private lateinit var db: FichioDatabase
    private val roots = listOf("/s", "/sd")
    private val downloads = "/s/Download"
    private val now = 100L * 24 * 3600 * 1000
    private val day = 24L * 3600 * 1000

    private fun row(path: String, type: FileType, size: Long = 10, modified: Long = now) = FileIndexEntity(
        path = path, name = path.substringAfterLast('/'), parent = path.substringBeforeLast('/'),
        extension = path.substringAfterLast('.', "").lowercase(), type = type.name, isDirectory = false,
        size = size, modified = modified, generation = 1,
    )

    @Before
    fun setUp() = runBlocking {
        db = FichioDatabase.inMemory(ApplicationProvider.getApplicationContext())
        db.fileIndex().upsertAll(
            listOf(
                row("/s/DCIM/Screenshots/Screenshot_1.png", FileType.IMAGE),
                row("/s/Pictures/Screenshots/Screenshot_2.png", FileType.IMAGE),
                row("/s/Pictures/Screenshots/notes.txt", FileType.TEXT),
                row("/s/DCIM/Camera/IMG_1.jpg", FileType.IMAGE),
                row("/s/DCIM/Camera/VID_1.mp4", FileType.VIDEO, size = 300L shl 20),
                row("/sd/DCIM/Camera/IMG_2.jpg", FileType.IMAGE),
                row("/s/DCIM/Camera/Sub/IMG_3.jpg", FileType.IMAGE),
                row("/s/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/IMG-1.jpg", FileType.IMAGE),
                row("/s/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/Sent/IMG-2.jpg", FileType.IMAGE),
                row("/s/WhatsApp/Media/WhatsApp Documents/facture.pdf", FileType.PDF),
                row("/s/Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Video/v.mp4", FileType.VIDEO),
                row("/s/Android/media/com.whatsapp/WhatsApp/Media/.nomedia", FileType.OTHER),
                row("/s/WhatsAppX/Media/x.jpg", FileType.IMAGE),
                row("/s/Download/new.zip", FileType.ARCHIVE, modified = now - 2 * day),
                row("/s/Download/sub/new2.pdf", FileType.PDF, modified = now - 6 * day),
                row("/s/Download/old.zip", FileType.ARCHIVE, modified = now - 30 * day),
                row("/s/Downloads2/other.zip", FileType.ARCHIVE),
                row("/s/Movies/film.mkv", FileType.VIDEO, size = 2L shl 30),
                row("/s/Movies/clip.mp4", FileType.VIDEO, size = 5L shl 20),
            ),
        )
    }

    @After
    fun tearDown() = db.close()

    private fun names(kind: SmartFolder): List<String> = runBlocking {
        val q = SmartFolders.listQuery(kind, roots, downloads, now)
        db.fileIndex().search(SimpleSQLiteQuery(q.sql, q.argsArray)).map { it.name }.sorted()
    }

    @Test
    fun screenshotsAndCamera() {
        assertEquals(listOf("Screenshot_1.png", "Screenshot_2.png"), names(SmartFolder.SCREENSHOTS))
        assertEquals(listOf("IMG_1.jpg", "IMG_2.jpg", "VID_1.mp4"), names(SmartFolder.CAMERA))
    }

    @Test
    fun whatsAppReceivedMediaOnly() {
        assertEquals(listOf("IMG-1.jpg", "facture.pdf", "v.mp4"), names(SmartFolder.WHATSAPP))
    }

    @Test
    fun recentDownloadsAndLargeVideos() {
        assertEquals(listOf("new.zip", "new2.pdf"), names(SmartFolder.RECENT_DOWNLOADS))
        assertEquals(listOf("VID_1.mp4", "film.mkv"), names(SmartFolder.LARGE_VIDEOS))
        val q = SmartFolders.listQuery(SmartFolder.LARGE_VIDEOS, roots, downloads, now)
        assertEquals(listOf("film.mkv", "VID_1.mp4"), runBlocking { db.fileIndex().search(SimpleSQLiteQuery(q.sql, q.argsArray)).map { it.name } })
    }

    @Test
    fun countsMatchLists() = runBlocking {
        val q = SmartFolders.countQuery(roots, downloads, now)
        val counts = db.collections().counts(SimpleSQLiteQuery(q.sql, q.argsArray)).associate { it.kind to it.count }
        assertEquals(SmartFolder.entries.map { it.name }, counts.keys.toList())
        SmartFolder.entries.forEach { assertEquals(it.name, names(it).size, counts[it.name]) }
    }

    @Test
    fun whatsAppPathRules() {
        val r = listOf("/storage/emulated/0")
        assertEquals(4, SmartFolders.whatsAppRoots(r).size)
        assertTrue(SmartFolders.isWhatsAppReceived("/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/IMG.jpg", r))
        assertTrue(SmartFolders.isWhatsAppReceived("/storage/emulated/0/WhatsApp/Media/WhatsApp Audio/PTT.opus", r))
        assertTrue(SmartFolders.isWhatsAppReceived("/storage/emulated/0/WhatsApp Business/Media/WhatsApp Video/a.mp4", r))
        assertFalse(SmartFolders.isWhatsAppReceived("/storage/emulated/0/WhatsApp/Media/WhatsApp Images/Sent/IMG.jpg", r))
        assertFalse(SmartFolders.isWhatsAppReceived("/storage/emulated/0/WhatsApp/Media/.nomedia", r))
        assertFalse(SmartFolders.isWhatsAppReceived("/storage/emulated/0/WhatsApp/Databases/msgstore.db", r))
        assertFalse(SmartFolders.isWhatsAppReceived("/storage/emulated/0/Pictures/IMG.jpg", r))
        assertEquals("/a/b0", SmartFolders.rangeEnd("/a/b/"))
    }
}
