package com.karelisio.fichio.search

import com.karelisio.fichio.core.model.FileType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchSqlBuilderTest {

    @Test
    fun tokenizeSplitsOnSeparatorsAndLowercases() {
        assertEquals(listOf("img", "2024", "vacances"), SearchSqlBuilder.tokenize("IMG_2024-Vacances"))
        assertEquals(listOf("été", "photo", "jpg"), SearchSqlBuilder.tokenize("  Été  photo.jpg "))
        assertEquals(emptyList<String>(), SearchSqlBuilder.tokenize("*** \"' ()"))
        // Duplicates collapse, order kept.
        assertEquals(listOf("a1", "b"), SearchSqlBuilder.tokenize("a1 b A1"))
    }

    @Test
    fun ftsExpressionQuotesEveryTermWithPrefixStar() {
        assertEquals("\"rapport*\" \"final*\"", SearchSqlBuilder.ftsExpression(listOf("rapport", "final")))
        // FTS operators are neutralised by the quotes.
        assertEquals("\"or*\" \"near*\"", SearchSqlBuilder.ftsExpression(listOf("or", "near")))
        // Embedded quotes are doubled.
        assertEquals("\"a\"\"b*\"", SearchSqlBuilder.ftsExpression(listOf("a\"b")))
    }

    @Test
    fun escapeLikeEscapesWildcards() {
        assertEquals("100\\%", SearchSqlBuilder.escapeLike("100%"))
        assertEquals("a\\_b", SearchSqlBuilder.escapeLike("a_b"))
        assertEquals("c:\\\\x", SearchSqlBuilder.escapeLike("c:\\x"))
    }

    @Test
    fun textModeChoosesFtsLikeOrBoth() {
        assertEquals(SearchSqlBuilder.TextMode.NONE, SearchSqlBuilder.textMode("   "))
        assertEquals(SearchSqlBuilder.TextMode.LIKE, SearchSqlBuilder.textMode("ab"))
        assertEquals(SearchSqlBuilder.TextMode.LIKE, SearchSqlBuilder.textMode("%%%"))
        assertEquals(SearchSqlBuilder.TextMode.FTS, SearchSqlBuilder.textMode("rapport final"))
        assertEquals(SearchSqlBuilder.TextMode.FTS_AND_LIKE, SearchSqlBuilder.textMode("IMG_2024.jpg"))
    }

    @Test
    fun plainTextUsesMatchWithBoundArgument() {
        val q = SearchSqlBuilder.build(SearchQuery(text = "rapport final"))
        assertTrue(q.sql.contains("JOIN file_index_fts ON file_index_fts.rowid = f.rowid"))
        assertTrue(q.sql.contains("file_index_fts MATCH ?"))
        assertFalse(q.sql.contains("rapport")) // user text never reaches the SQL
        assertEquals("\"rapport*\" \"final*\"", q.args[0])
        // Relevance ordering: prefix first, then date; last arg is the prefix pattern.
        assertTrue(q.sql.contains("ORDER BY CASE WHEN f.name LIKE ? ESCAPE '\\' THEN 0 ELSE 1 END, f.modified DESC"))
        assertEquals("rapport final%", q.args.last())
        assertTrue(q.sql.endsWith("LIMIT 500"))
    }

    @Test
    fun shortTextFallsBackToLike() {
        val q = SearchSqlBuilder.build(SearchQuery(text = "a_"))
        assertFalse(q.sql.contains("MATCH"))
        assertTrue(q.sql.contains("f.name LIKE ? ESCAPE '\\'"))
        assertEquals("%a\\_%", q.args[0])
    }

    @Test
    fun separatorsCombineFtsAndLike() {
        val q = SearchSqlBuilder.build(SearchQuery(text = "IMG_2024"))
        assertTrue(q.sql.contains("MATCH ?"))
        assertTrue(q.sql.contains("f.name LIKE ?"))
        assertEquals(listOf<Any>("\"img*\" \"2024*\"", "%IMG\\_2024%"), q.args.take(2))
    }

    @Test
    fun quotesAndInjectionAttemptsStayInArguments() {
        val evil = "x' OR 1=1; DROP TABLE file_index; --\""
        val q = SearchSqlBuilder.build(SearchQuery(text = evil))
        assertFalse(q.sql.contains("DROP"))
        assertFalse(q.sql.contains("1=1"))
        assertTrue(q.args.any { it is String && it.contains("DROP") })
    }

    @Test
    fun filtersAreAllBound() {
        val q = SearchSqlBuilder.build(
            SearchQuery(
                text = "",
                scopePath = "/storage/emulated/0/My_Docs/",
                types = setOf(FileType.PDF, FileType.IMAGE),
                minSize = 10, maxSize = 20,
                modifiedAfter = 100, modifiedBefore = 200,
                extensions = setOf(".PDF", "jpg"),
                includeFolders = false,
            ),
        )
        val sql = q.sql
        assertFalse(sql.contains("MATCH"))
        assertTrue(sql.contains("f.path LIKE ? ESCAPE '\\'"))
        assertTrue(sql.contains("f.type IN (?,?)"))
        assertTrue(sql.contains("f.isDirectory = 0"))
        assertTrue(sql.contains("f.size >= ?") && sql.contains("f.size <= ?"))
        assertTrue(sql.contains("f.modified >= ?") && sql.contains("f.modified <= ?"))
        assertTrue(sql.contains("f.extension IN (?,?)"))
        assertTrue(sql.contains("ORDER BY f.modified DESC"))
        assertEquals(
            listOf<Any>("/storage/emulated/0/My\\_Docs/%", "IMAGE", "PDF", 10L, 20L, 100L, 200L, "jpg", "pdf"),
            q.args,
        )
    }

    @Test
    fun folderTypeImpliesFolders() {
        val q = SearchSqlBuilder.build(SearchQuery(types = setOf(FileType.FOLDER), includeFolders = false))
        assertFalse(q.sql.contains("isDirectory = 0"))
    }

    @Test
    fun inactiveQueryHasNoWhere() {
        assertFalse(SearchQuery().isActive)
        assertTrue(SearchQuery(includeFolders = false).isActive)
        assertFalse(SearchSqlBuilder.build(SearchQuery()).sql.contains("WHERE"))
    }

    @Test
    fun normalizeRemovesAccentsAndCase() {
        assertEquals("ete a noel", SearchSqlBuilder.normalize("Été à Noël"))
        assertEquals("plain", SearchSqlBuilder.normalize("PLAIN"))
    }

    @Test
    fun inMemoryMatcherMirrorsFilters() {
        val q = SearchQuery(text = "été", types = setOf(FileType.IMAGE), minSize = 5, extensions = setOf("jpg"))
        val n = SearchSqlBuilder.normalize("été")
        assertTrue(SearchSqlBuilder.matches(q, "Photos ETE 2024.jpg", false, FileType.IMAGE, "jpg", 10, 0, n))
        assertFalse(SearchSqlBuilder.matches(q, "Photos ETE 2024.jpg", false, FileType.IMAGE, "jpg", 1, 0, n))
        assertFalse(SearchSqlBuilder.matches(q, "Photos ETE 2024.png", false, FileType.IMAGE, "png", 10, 0, n))
        assertFalse(SearchSqlBuilder.matches(q, "hiver.jpg", false, FileType.IMAGE, "jpg", 10, 0, n))
        assertFalse(SearchSqlBuilder.matches(SearchQuery(text = "x", includeFolders = false), "x", true, FileType.FOLDER, "", 0, 0))
    }

    @Test
    fun highlightNameMarksAccentInsensitiveMatches() {
        val s = highlightName("Été_2024.jpg", highlightTerms("ete"), androidx.compose.ui.text.SpanStyle())
        assertEquals(1, s.spanStyles.size)
        assertEquals(0, s.spanStyles[0].start)
        assertEquals(3, s.spanStyles[0].end)
    }

    @Test
    fun indexRulesExcludeRestrictedAndHidden() {
        val roots = listOf("/storage/emulated/0", "/storage/ABCD-1234")
        assertTrue(IndexRules.isExcluded("/storage/emulated/0/Android/data/com.x", roots, true))
        assertTrue(IndexRules.isExcluded("/storage/emulated/0/Android/obb", roots, true))
        assertFalse(IndexRules.isExcluded("/storage/emulated/0/Android/media", roots, false))
        assertTrue(IndexRules.isExcluded("/storage/emulated/0/.Fichio/trash/a", roots, true))
        assertTrue(IndexRules.isExcluded("/storage/ABCD-1234/.thumbnails/x.jpg", roots, false))
        assertFalse(IndexRules.isExcluded("/storage/ABCD-1234/.thumbnails/x.jpg", roots, true))
        assertFalse(IndexRules.isExcluded("/storage/emulated/0/DCIM/Camera/a.jpg", roots, false))
        assertFalse(IndexRules.isExcluded("/storage/emulated/0", roots, false))
    }
}
