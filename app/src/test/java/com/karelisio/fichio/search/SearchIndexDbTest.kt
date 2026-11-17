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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Runs the generated SQL against a real Room database (FTS4 + unicode61). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SearchIndexDbTest {
    private lateinit var db: FichioDatabase

    private fun row(path: String, type: FileType, size: Long = 10, modified: Long = 0, dir: Boolean = false): FileIndexEntity {
        val name = path.substringAfterLast('/')
        return FileIndexEntity(
            path = path, name = name, parent = path.substringBeforeLast('/'),
            extension = if (dir) "" else name.substringAfterLast('.', "").lowercase(),
            type = type.name, isDirectory = dir, size = size, modified = modified, generation = 1,
        )
    }

    @Before
    fun setUp() = runBlocking {
        db = FichioDatabase.inMemory(ApplicationProvider.getApplicationContext())
        db.fileIndex().upsertAll(
            listOf(
                row("/s/Docs/Rapport final.pdf", FileType.PDF, 5_000, modified = 3),
                row("/s/Docs/Ancien rapport.pdf", FileType.PDF, 50, modified = 9),
                row("/s/DCIM/IMG_2024_été.jpg", FileType.IMAGE, 2_000_000, modified = 5),
                row("/s/DCIM/IMG_2023.jpg", FileType.IMAGE, 1_000, modified = 1),
                row("/s/Music/Été indien.mp3", FileType.AUDIO, 3_000, modified = 2),
                row("/s/Rapports", FileType.FOLDER, 0, modified = 4, dir = true),
                row("/s/100%_promo.txt", FileType.TEXT, 1, modified = 0),
            ),
        )
    }

    @After
    fun tearDown() = db.close()

    private fun names(q: SearchQuery): List<String> = runBlocking {
        val b = SearchSqlBuilder.build(q)
        db.fileIndex().search(SimpleSQLiteQuery(b.sql, b.argsArray)).map { it.name }
    }

    @Test
    fun prefixMatchRanksNamesStartingWithQueryFirst() {
        assertEquals(listOf("Rapports", "Rapport final.pdf", "Ancien rapport.pdf"), names(SearchQuery(text = "rapport")))
    }

    @Test
    fun multipleTermsAreAnded() {
        assertEquals(listOf("Rapport final.pdf"), names(SearchQuery(text = "rap fin")))
    }

    @Test
    fun accentsAreFoldedByTheTokenizer() {
        assertEquals(setOf("IMG_2024_été.jpg", "Été indien.mp3"), names(SearchQuery(text = "ete")).toSet())
    }

    @Test
    fun separatorsUseSubstringToo() {
        assertEquals(listOf("IMG_2024_été.jpg"), names(SearchQuery(text = "IMG_2024")))
    }

    @Test
    fun shortAndSpecialTextUsesLike() {
        assertEquals(listOf("100%_promo.txt"), names(SearchQuery(text = "0%")))
    }

    @Test
    fun filtersAndScope() {
        assertEquals(listOf("IMG_2024_été.jpg"), names(SearchQuery(types = setOf(FileType.IMAGE), minSize = 1_000_000)))
        assertEquals(listOf("Ancien rapport.pdf", "Rapport final.pdf"), names(SearchQuery(scopePath = "/s/Docs")))
        assertEquals(listOf("Rapport final.pdf", "Ancien rapport.pdf"), names(SearchQuery(text = "rapport", includeFolders = false)))
        assertEquals(listOf("IMG_2023.jpg"), names(SearchQuery(extensions = setOf("jpg"), modifiedBefore = 2)))
    }

    @Test
    fun ftsSyntaxInQueryDoesNotBreak() {
        assertEquals(emptyList<String>(), names(SearchQuery(text = "\"rapport OR\" NEAR -x")))
    }
}
