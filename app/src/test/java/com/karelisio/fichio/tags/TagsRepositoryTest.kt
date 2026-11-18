package com.karelisio.fichio.tags

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.karelisio.fichio.data.db.FichioDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TagsRepositoryTest {
    private lateinit var db: FichioDatabase
    private lateinit var repo: TagsRepository

    @Before
    fun setUp() {
        db = FichioDatabase.inMemory(ApplicationProvider.getApplicationContext())
        repo = TagsRepository(db)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun sevenNameableTagsWithCounts() = runBlocking {
        val initial = repo.tags.first()
        assertEquals(7, initial.size)
        assertEquals(listOf("Rouge", "Orange", "Jaune", "Vert", "Bleu", "Violet", "Gris"), initial.map { it.displayName })
        repo.assign(1, listOf("/s/a.txt", "/s/b.txt"))
        repo.assign(5, listOf("/s/a.txt"))
        repo.rename(5, "  Travail  ")
        val tags = repo.tags.first()
        assertEquals("Travail", tags.first { it.id == 5L }.displayName)
        assertEquals(2, tags.first { it.id == 1L }.count)
        assertEquals(mapOf("/s/a.txt" to listOf(0, 4), "/s/b.txt" to listOf(0)), repo.slotsByPath.first())
        assertEquals(mapOf("/s/a.txt" to setOf(1L, 5L)), repo.tagsOf(listOf("/s/a.txt")))
        repo.unassign(1, listOf("/s/a.txt", "/s/b.txt"))
        assertEquals(mapOf("/s/a.txt" to listOf(4)), repo.slotsByPath.first())
    }

    @Test
    fun followsMovesOfFilesAndFolders() = runBlocking {
        repo.assign(1, listOf("/s/Projets", "/s/Projets/plan.pdf", "/s/Projets/x/y.txt", "/s/Projets2/z.txt", "/s/note.txt"))
        repo.onMoved("/s/Projets", "/s/Archives/Projets")
        repo.onMoved("/s/note.txt", "/s/Docs/note.txt")
        assertEquals(
            listOf("/s/Archives/Projets", "/s/Archives/Projets/plan.pdf", "/s/Archives/Projets/x/y.txt", "/s/Docs/note.txt", "/s/Projets2/z.txt"),
            db.tags().pathsWith(listOf(1L)).sorted(),
        )
    }

    @Test
    fun likeWildcardsInNamesAreEscapedAndConflictsMerge() = runBlocking {
        repo.assign(2, listOf("/s/100%_a/f.txt", "/s/100xxa/g.txt"))
        repo.onMoved("/s/100%_a", "/s/B")
        assertEquals(listOf("/s/100xxa/g.txt", "/s/B/f.txt"), db.tags().pathsWith(listOf(2L)).sorted())
        // Destination already tagged: the move keeps one row.
        repo.assign(3, listOf("/s/old.txt", "/s/new.txt"))
        repo.onMoved("/s/old.txt", "/s/new.txt")
        assertEquals(listOf("/s/new.txt"), db.tags().pathsWith(listOf(3L)))
    }

    @Test
    fun hueHarmonisation() {
        assertEquals(10f, TagPalette.harmonizedHue(0f, 100f, 0.1f), 0.001f)
        // Shortest arc across 0°.
        assertEquals(353f, TagPalette.harmonizedHue(5f, 305f, 0.2f), 0.001f)
        assertEquals(12f, TagPalette.harmonizedHue(350f, 100f, 0.2f), 0.001f)
        assertTrue(TagPalette.HUES.size == TagPalette.COUNT - 1)
    }
}
