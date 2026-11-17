package com.karelisio.fichio.search

import com.karelisio.fichio.preview.HlSpan
import com.karelisio.fichio.preview.SyntaxHighlighter
import com.karelisio.fichio.preview.TokenKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lives with the search tests so that the module's test filter runs it too. */
class SyntaxHighlighterTest {
    private fun spans(line: String, ext: String, state: Int = 0) =
        SyntaxHighlighter.highlightLine(line, SyntaxHighlighter.languageFor(ext), state)

    private fun List<HlSpan>.text(line: String, kind: TokenKind) = filter { it.kind == kind }.map { line.substring(it.start, it.end) }

    @Test
    fun kotlinKeywordsStringsCommentsNumbers() {
        val line = "val x = \"hi\" + 42 // note"
        val r = spans(line, "kt")
        assertEquals(listOf("val"), r.spans.text(line, TokenKind.KEYWORD))
        assertEquals(listOf("\"hi\""), r.spans.text(line, TokenKind.STRING))
        assertEquals(listOf("42"), r.spans.text(line, TokenKind.NUMBER))
        assertEquals(listOf("// note"), r.spans.text(line, TokenKind.COMMENT))
        assertEquals(SyntaxHighlighter.NORMAL, r.endState)
    }

    @Test
    fun escapedQuotesStayInsideString() {
        val line = "s = \"a\\\"b\" ; y"
        assertEquals(listOf("\"a\\\"b\""), spans(line, "js").spans.text(line, TokenKind.STRING))
    }

    @Test
    fun blockCommentsCarryAcrossLines() {
        val first = spans("int a; /* start", "c")
        assertEquals(SyntaxHighlighter.BLOCK_COMMENT, first.endState)
        val line = "still */ return 1;"
        val second = spans(line, "c", first.endState)
        assertEquals(listOf("still */"), second.spans.text(line, TokenKind.COMMENT))
        assertEquals(listOf("return"), second.spans.text(line, TokenKind.KEYWORD))
        assertEquals(SyntaxHighlighter.NORMAL, second.endState)
    }

    @Test
    fun startStatesFollowMultilineStrings() {
        val lines = listOf("x = \"\"\"", "inside", "\"\"\"", "y = 1")
        val states = SyntaxHighlighter.startStates(lines, SyntaxHighlighter.languageFor("py"))
        assertEquals(listOf(0, SyntaxHighlighter.TRIPLE_DQ, SyntaxHighlighter.TRIPLE_DQ, 0), states.toList())
    }

    @Test
    fun annotationsAndTypes() {
        val line = "@Composable fun Foo(s: String)"
        val r = spans(line, "kt").spans
        assertEquals(listOf("@Composable"), r.text(line, TokenKind.ANNOTATION))
        assertTrue("String" in r.text(line, TokenKind.TYPE))
        assertTrue("Foo" in r.text(line, TokenKind.TYPE))
    }

    @Test
    fun pythonAndShellComments() {
        val py = "def f(): # comment"
        assertEquals(listOf("# comment"), spans(py, "py").spans.text(py, TokenKind.COMMENT))
        val sh = "echo \$HOME a#b # real"
        val r = spans(sh, "sh").spans
        assertEquals(listOf("# real"), r.text(sh, TokenKind.COMMENT))
        assertEquals(listOf("\$HOME"), r.text(sh, TokenKind.ATTRIBUTE))
    }

    @Test
    fun xmlTagsAttributesAndComments() {
        val line = "<item name=\"a\" /><!-- c -->"
        val r = spans(line, "xml")
        assertEquals(listOf("<item", "/>"), r.spans.text(line, TokenKind.TAG))
        assertEquals(listOf("name"), r.spans.text(line, TokenKind.ATTRIBUTE))
        assertEquals(listOf("\"a\""), r.spans.text(line, TokenKind.STRING))
        assertEquals(SyntaxHighlighter.NORMAL, r.endState)
        // Attributes on the next line keep the tag state.
        val open = spans("<LinearLayout", "xml")
        assertEquals(SyntaxHighlighter.IN_TAG, open.endState)
        val next = "    android:id=\"@+id/x\">"
        val r2 = spans(next, "xml", open.endState)
        assertEquals(listOf("android:id"), r2.spans.text(next, TokenKind.ATTRIBUTE))
        assertEquals(SyntaxHighlighter.NORMAL, r2.endState)
    }

    @Test
    fun jsonKeysVersusValues() {
        val line = "  \"name\": \"Fichio\", \"n\": -1.5e3, \"ok\": true"
        val r = spans(line, "json").spans
        assertEquals(listOf("\"name\"", "\"n\"", "\"ok\""), r.text(line, TokenKind.KEY))
        assertEquals(listOf("\"Fichio\""), r.text(line, TokenKind.STRING))
        assertEquals(listOf("-1.5e3"), r.text(line, TokenKind.NUMBER))
        assertEquals(listOf("true"), r.text(line, TokenKind.KEYWORD))
    }

    @Test
    fun yamlAndIni() {
        val y = "- name: \"x\" # c"
        val ry = spans(y, "yaml").spans
        assertEquals(listOf("name"), ry.text(y, TokenKind.KEY))
        assertEquals(listOf("\"x\""), ry.text(y, TokenKind.STRING))
        assertEquals(listOf("# c"), ry.text(y, TokenKind.COMMENT))
        val section = "[server]"
        assertEquals(listOf("[server]"), spans(section, "toml").spans.text(section, TokenKind.TAG))
        val kv = "port = 8080"
        val rk = spans(kv, "ini").spans
        assertEquals(listOf("port "), rk.text(kv, TokenKind.KEY))
        assertEquals(listOf("8080"), rk.text(kv, TokenKind.NUMBER))
    }

    @Test
    fun sqlIsCaseInsensitive() {
        val line = "SELECT * FROM t -- all"
        val r = spans(line, "sql").spans
        assertEquals(listOf("SELECT", "FROM"), r.text(line, TokenKind.KEYWORD))
        assertEquals(listOf("-- all"), r.text(line, TokenKind.COMMENT))
    }

    @Test
    fun csvAlternatesColumnsAndHandlesQuotes() {
        val line = "a,\"b,c\",d,e"
        val r = spans(line, "csv")
        assertEquals(listOf("\"b,c\"", "e"), r.spans.text(line, TokenKind.COLUMN))
        assertEquals(SyntaxHighlighter.CSV_QUOTE, spans("x,\"open", "csv").endState)
    }

    @Test
    fun markdownHeadingsAndCode() {
        assertEquals(listOf("# Titre"), spans("# Titre", "md").spans.text("# Titre", TokenKind.KEYWORD))
        val line = "texte `code` ici"
        assertEquals(listOf("`code`"), spans(line, "md").spans.text(line, TokenKind.STRING))
        assertEquals(SyntaxHighlighter.MD_FENCE, spans("```kotlin", "md").endState)
    }

    @Test
    fun plainTextHasNoSpans() {
        assertTrue(spans("val x = 1", "txt").spans.isEmpty())
    }
}
