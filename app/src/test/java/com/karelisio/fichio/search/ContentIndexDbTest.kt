package com.karelisio.fichio.search

import android.app.Application
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.core.app.ApplicationProvider
import com.karelisio.fichio.core.model.FileType
import com.karelisio.fichio.data.db.FichioDatabase
import com.karelisio.fichio.data.db.FileIndexEntity
import com.karelisio.fichio.data.db.FileTagEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Content index end to end: files on disk → content_fts → content search SQL + snippets, and tag filter. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ContentIndexDbTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var db: FichioDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var indexer: ContentIndexer
    private lateinit var root: File

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        db = FichioDatabase.inMemory(ctx)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        indexer = ContentIndexer(ctx, db, scope, ContentSearchPrefs(ctx).apply { set(true) }, pdfSupported = false)
        root = tmp.newFolder("s")
    }

    @After
    fun tearDown() {
        scope.cancel(); db.close()
    }

    private fun put(rel: String, text: String?, bytes: ByteArray? = null): File {
        val f = File(root, rel).apply { parentFile!!.mkdirs() }
        if (bytes != null) f.writeBytes(bytes) else f.writeText(text!!)
        val name = f.name
        val ext = name.substringAfterLast('.', "").lowercase()
        runBlocking {
            db.fileIndex().upsertAll(
                listOf(
                    FileIndexEntity(
                        path = f.path, name = name, parent = f.parent!!, extension = ext,
                        type = com.karelisio.fichio.core.model.FileTypes.typeOf(ext).name, isDirectory = false,
                        size = f.length(), modified = f.lastModified(), generation = 1,
                    ),
                ),
            )
        }
        return f
    }

    private fun content(q: SearchQuery) = runBlocking {
        val b = SearchSqlBuilder.buildContent(q)!!
        db.content().hits(SimpleSQLiteQuery(b.sql, b.argsArray))
    }

    @Test
    fun indexesTextFilesAndFindsTermsWithSnippet() = runBlocking {
        put("Docs/notes.md", "# Réunion\nLe budget prévisionnel a été validé par l'équipe.")
        put("Docs/page.html", "<html><head><style>.x{}</style><script>var budget=1</script></head><body><p>Rien &amp; personne</p></body></html>")
        put("Docs/data.csv", "ville;population\nLyon;522000")
        put("Photos/a.jpg", null, ByteArray(100) { 1 })
        put("Docs/fake.txt", null, byteArrayOf(0x50, 0, 0, 0x41))
        assertEquals(4, indexer.indexPending()) // the 4 text-like files, not the jpg

        val hits = content(SearchQuery(text = "previsionnel", inContent = true))
        assertEquals(listOf("notes.md"), hits.map { it.name })
        val snippet = hits.single().snippet!!
        val marked = Snippets.segments(snippet).filter { it.second }.map { it.first.lowercase() }
        assertEquals(listOf("prévisionnel"), marked)

        // HTML markup and scripts are not indexed; text is.
        assertTrue(content(SearchQuery(text = "budget", inContent = true)).map { it.name } == listOf("notes.md"))
        assertEquals(listOf("page.html"), content(SearchQuery(text = "personne", inContent = true)).map { it.name })
        assertEquals(listOf("data.csv"), content(SearchQuery(text = "lyon", inContent = true)).map { it.name })
        // Binary disguised as text: read but nothing stored.
        assertEquals(false, db.content().meta(File(root, "Docs/fake.txt").path)!!.indexed)

        // Nothing left to read on a second pass.
        assertEquals(0, indexer.indexPending())
    }

    @Test
    fun reindexesModifiedFilesOfTouchedFoldersAndPurgesOrphans() = runBlocking {
        val f = put("Docs/todo.txt", "acheter du pain")
        put("Other/x.txt", "pain perdu")
        indexer.indexPending()
        assertEquals(2, content(SearchQuery(text = "pain", inContent = true)).size)

        // Edited in the app: only its folder is re-read.
        f.writeText("acheter des croissants")
        f.setLastModified(f.lastModified() + 5_000)
        put("Docs/todo.txt", f.readText())
        assertEquals(1, indexer.indexPending(dirs = listOf(f.parent!!)))
        assertEquals(listOf("x.txt"), content(SearchQuery(text = "pain", inContent = true)).map { it.name })
        assertEquals(listOf("todo.txt"), content(SearchQuery(text = "croissants", inContent = true)).map { it.name })

        // Gone from the name index → gone from the content index.
        db.fileIndex().deleteTree(File(root, "Other").path)
        indexer.indexPending()
        assertTrue(content(SearchQuery(text = "perdu", inContent = true)).isEmpty())
        assertNull(db.content().meta(File(root, "Other/x.txt").path))
    }

    @Test
    fun contentQueryHonoursFiltersAndTags() = runBlocking {
        val a = put("Docs/a.txt", "facture électricité")
        put("Work/b.txt", "facture eau")
        indexer.indexPending()
        assertEquals(setOf("a.txt", "b.txt"), content(SearchQuery(text = "facture", inContent = true)).map { it.name }.toSet())
        assertEquals(listOf("a.txt"), content(SearchQuery(text = "facture", scopePath = File(root, "Docs").path, inContent = true)).map { it.name })

        db.tags().assign(listOf(FileTagEntity(a.path, 3)))
        assertEquals(listOf("a.txt"), content(SearchQuery(text = "facture", tagIds = setOf(3), inContent = true)).map { it.name })
        // Same tag filter on the name search.
        val b = SearchSqlBuilder.build(SearchQuery(tagIds = setOf(3)))
        assertEquals(listOf("a.txt"), db.fileIndex().search(SimpleSQLiteQuery(b.sql, b.argsArray)).map { it.name })
        assertTrue(SearchSqlBuilder.build(SearchQuery(tagIds = setOf(4))).let { q -> db.fileIndex().search(SimpleSQLiteQuery(q.sql, q.argsArray)) }.isEmpty())
    }

    @Test
    fun mergeKeepsNameMatchesFirstAndAddsContentOnlyHits() {
        val n = listOf(row("/s/budget.txt"), row("/s/budget 2.txt"))
        val hits = listOf(
            hit("/s/budget.txt", "le \u0002budget\u0003"),
            hit("/s/notes.md", "un \u0002budget\u0003 serré"),
        )
        val (items, snippets) = mergeContentResults(n, hits)
        assertEquals(listOf("/s/budget.txt", "/s/budget 2.txt", "/s/notes.md"), items.map { it.path })
        assertEquals(setOf("/s/budget.txt", "/s/notes.md"), snippets.keys)
        assertEquals(2, mergeContentResults(n, hits, limit = 2).first.size)
    }

    private fun row(path: String) = com.karelisio.fichio.data.db.IndexedFile(path, path.substringAfterLast('/'), "/s", "txt", FileType.TEXT.name, false, 1, 1)
    private fun hit(path: String, snippet: String) =
        com.karelisio.fichio.data.db.ContentHit(path, path.substringAfterLast('/'), "/s", "txt", FileType.TEXT.name, false, 1, 1, snippet)
}
