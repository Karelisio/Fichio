package com.karelisio.fichio.preview

/**
 * Small, dependency-free syntax highlighter. Works line by line with a carried state (block
 * comments, multi-line strings, open tags), so a viewer can highlight only the visible lines once
 * [startStates] has been computed in the background. Pure Kotlin: unit-tested.
 */
enum class TokenKind { KEYWORD, TYPE, STRING, COMMENT, NUMBER, TAG, ATTRIBUTE, KEY, ANNOTATION, PUNCTUATION, COLUMN }

data class HlSpan(val start: Int, val end: Int, val kind: TokenKind)

data class LineHighlight(val spans: List<HlSpan>, val endState: Int)

enum class LangFamily { C_LIKE, PYTHON, SHELL, SQL, LUA, RUBY, MARKUP, CSS, JSON, YAML, INI, MARKDOWN, CSV, PLAIN }

class Language(
    val name: String,
    val family: LangFamily,
    val keywords: Set<String> = emptySet(),
    val types: Set<String> = emptySet(),
    val lineComments: List<String> = emptyList(),
    val blockComment: Pair<String, String>? = null,
    val tripleQuotes: Boolean = false,
    val backtickStrings: Boolean = false,
    val caseInsensitive: Boolean = false,
    val separator: Char = ',',
)

object SyntaxHighlighter {
    // Line states.
    const val NORMAL = 0
    const val BLOCK_COMMENT = 1
    const val TRIPLE_DQ = 2
    const val TRIPLE_SQ = 3
    const val BACKTICK = 4
    const val MARKUP_COMMENT = 5
    const val IN_TAG = 6
    const val MD_FENCE = 7
    const val CSV_QUOTE = 8

    private val C_KEYWORDS = setOf(
        // Kotlin / Java / C# / Swift / Dart / Scala
        "abstract", "actual", "annotation", "as", "break", "by", "case", "catch", "class", "companion", "const",
        "constructor", "continue", "crossinline", "data", "default", "delegate", "do", "dynamic", "else", "enum",
        "expect", "extends", "external", "false", "final", "finally", "for", "fun", "get", "if", "implements",
        "import", "in", "infix", "init", "inline", "inner", "instanceof", "interface", "internal", "is", "lateinit",
        "native", "new", "noinline", "null", "object", "open", "operator", "out", "override", "package", "private",
        "protected", "public", "reified", "return", "sealed", "set", "static", "super", "suspend", "switch",
        "synchronized", "tailrec", "this", "throw", "throws", "transient", "true", "try", "typealias", "val", "value",
        "var", "vararg", "void", "volatile", "when", "where", "while", "yield", "async", "await",
        // JS / TS
        "const", "let", "function", "export", "from", "typeof", "undefined", "delete", "debugger", "with", "of",
        "declare", "namespace", "type", "keyof", "readonly", "satisfies", "implements",
        // C / C++ / Go / Rust
        "auto", "char", "double", "float", "goto", "int", "long", "register", "short", "signed", "sizeof", "struct",
        "union", "unsigned", "include", "define", "template", "typename", "virtual", "friend", "using", "nullptr",
        "func", "go", "chan", "defer", "fallthrough", "map", "range", "select", "fn", "impl", "let", "mut", "pub",
        "crate", "mod", "trait", "unsafe", "use", "loop", "match", "move", "ref", "self", "Self", "bool",
        "guard", "let", "extension", "protocol", "struct", "inout", "nil", "foreach", "elseif", "endif", "echo",
    )
    private val C_TYPES = setOf(
        "String", "Int", "Long", "Float", "Double", "Boolean", "Char", "Byte", "Short", "Unit", "Any", "Nothing",
        "List", "Map", "Set", "Array", "Object", "Integer", "number", "string", "boolean", "any", "unknown", "never",
        "i8", "i16", "i32", "i64", "u8", "u16", "u32", "u64", "usize", "isize", "f32", "f64", "str", "Vec", "Option",
        "Result", "size_t", "uint8_t", "int32_t", "int64_t", "byte", "rune", "error",
    )
    private val PY_KEYWORDS = setOf(
        "and", "as", "assert", "async", "await", "break", "class", "continue", "def", "del", "elif", "else", "except",
        "False", "finally", "for", "from", "global", "if", "import", "in", "is", "lambda", "None", "nonlocal", "not",
        "or", "pass", "raise", "return", "True", "try", "while", "with", "yield", "match", "case", "self",
    )
    private val SH_KEYWORDS = setOf(
        "if", "then", "else", "elif", "fi", "for", "while", "until", "do", "done", "case", "esac", "in", "function",
        "return", "exit", "export", "local", "readonly", "echo", "set", "unset", "source", "alias", "cd", "true", "false",
        // PowerShell / batch
        "param", "foreach", "goto", "call", "rem",
    )
    private val SQL_KEYWORDS = setOf(
        "select", "from", "where", "and", "or", "not", "insert", "into", "values", "update", "set", "delete", "create",
        "table", "index", "view", "drop", "alter", "add", "primary", "key", "foreign", "references", "join", "left",
        "right", "inner", "outer", "on", "as", "group", "by", "order", "having", "limit", "offset", "distinct", "union",
        "all", "null", "is", "in", "like", "between", "exists", "case", "when", "then", "else", "end", "default",
        "integer", "text", "real", "blob", "varchar", "int", "begin", "commit", "rollback", "transaction", "trigger",
        "asc", "desc", "unique", "check", "constraint", "if", "replace", "with", "true", "false",
    )
    private val LUA_KEYWORDS = setOf(
        "and", "break", "do", "else", "elseif", "end", "false", "for", "function", "goto", "if", "in", "local", "nil",
        "not", "or", "repeat", "return", "then", "true", "until", "while",
    )
    private val RUBY_KEYWORDS = setOf(
        "alias", "and", "begin", "break", "case", "class", "def", "defined?", "do", "else", "elsif", "end", "ensure",
        "false", "for", "if", "in", "module", "next", "nil", "not", "or", "redo", "rescue", "retry", "return", "self",
        "super", "then", "true", "undef", "unless", "until", "when", "while", "yield", "require", "puts", "my", "sub",
        "use", "print",
    )
    private val LITERALS = setOf("true", "false", "null", "yes", "no", "on", "off", "~", "True", "False", "None")

    private val cLike = Language("C", LangFamily.C_LIKE, C_KEYWORDS, C_TYPES, listOf("//"), "/*" to "*/", tripleQuotes = true, backtickStrings = true)
    private val python = Language("Python", LangFamily.PYTHON, PY_KEYWORDS, setOf("int", "str", "float", "bool", "list", "dict", "set", "tuple", "bytes", "object"), listOf("#"), null, tripleQuotes = true)
    private val shell = Language("Shell", LangFamily.SHELL, SH_KEYWORDS, emptySet(), listOf("#"), null)
    private val batch = Language("Batch", LangFamily.SHELL, SH_KEYWORDS + setOf("echo", "off", "setlocal", "endlocal"), emptySet(), listOf("::", "REM ", "rem "), null, caseInsensitive = true)
    private val sql = Language("SQL", LangFamily.SQL, SQL_KEYWORDS, emptySet(), listOf("--"), "/*" to "*/", caseInsensitive = true)
    private val lua = Language("Lua", LangFamily.LUA, LUA_KEYWORDS, emptySet(), listOf("--"), "--[[" to "]]")
    private val ruby = Language("Ruby", LangFamily.RUBY, RUBY_KEYWORDS, emptySet(), listOf("#"), null)
    private val r = Language("R", LangFamily.PYTHON, setOf("if", "else", "for", "while", "function", "return", "TRUE", "FALSE", "NULL", "NA", "in", "library", "repeat", "break", "next"), emptySet(), listOf("#"), null)
    private val markup = Language("XML", LangFamily.MARKUP)
    private val css = Language("CSS", LangFamily.CSS, setOf("important", "inherit", "initial", "none", "auto"), emptySet(), listOf("//"), "/*" to "*/")
    private val json = Language("JSON", LangFamily.JSON, setOf("true", "false", "null"), emptySet(), listOf("//"), "/*" to "*/")
    private val yaml = Language("YAML", LangFamily.YAML, LITERALS, emptySet(), listOf("#"), null)
    private val ini = Language("INI", LangFamily.INI, LITERALS, emptySet(), listOf("#", ";"), null)
    private val markdown = Language("Markdown", LangFamily.MARKDOWN)
    private val csv = Language("CSV", LangFamily.CSV, separator = ',')
    private val tsv = Language("TSV", LangFamily.CSV, separator = '\t')
    val plain = Language("Texte", LangFamily.PLAIN)

    fun languageFor(extension: String): Language = when (extension.lowercase()) {
        "kt", "kts", "java", "js", "mjs", "cjs", "ts", "tsx", "jsx", "c", "h", "cpp", "hpp", "cc", "cs", "go", "rs",
        "swift", "dart", "scala", "groovy", "gradle", "php", "m", "mm", "smali", "svelte" -> cLike
        "py", "pyw" -> python
        "r" -> r
        "sh", "bash", "zsh", "fish", "ps1", "env" -> shell
        "bat", "cmd" -> batch
        "sql" -> sql
        "lua" -> lua
        "rb", "pl", "pm" -> ruby
        "xml", "html", "htm", "xhtml", "svg", "vue", "plist", "xsd", "xsl" -> markup
        "css", "scss", "sass", "less" -> css
        "json", "jsonc", "geojson", "webmanifest" -> json
        "yaml", "yml" -> yaml
        "toml", "ini", "cfg", "conf", "properties", "prop", "editorconfig", "gitconfig" -> ini
        "md", "markdown" -> markdown
        "csv" -> csv
        "tsv" -> tsv
        else -> plain
    }

    /** State at the start of each line (index i = state before line i). */
    fun startStates(lines: List<String>, lang: Language): IntArray {
        val out = IntArray(lines.size)
        if (lang.family == LangFamily.PLAIN) return out
        var s = NORMAL
        for (i in lines.indices) {
            out[i] = s
            s = highlightLine(lines[i], lang, s).endState
        }
        return out
    }

    fun highlightLine(line: String, lang: Language, state: Int = NORMAL): LineHighlight = when (lang.family) {
        LangFamily.PLAIN -> LineHighlight(emptyList(), NORMAL)
        LangFamily.MARKUP -> markupLine(line, state)
        LangFamily.JSON -> jsonLine(line, lang, state)
        LangFamily.YAML, LangFamily.INI -> configLine(line, lang)
        LangFamily.MARKDOWN -> markdownLine(line, state)
        LangFamily.CSV -> csvLine(line, lang.separator, state)
        LangFamily.CSS -> cssLine(line, lang, state)
        else -> codeLine(line, lang, state)
    }

    // -----------------------------------------------------------------------------------------
    // Code (C-like, Python, shell, SQL, Lua, Ruby)
    // -----------------------------------------------------------------------------------------

    private fun isIdentStart(c: Char) = c.isLetter() || c == '_' || c == '$'
    private fun isIdentPart(c: Char) = c.isLetterOrDigit() || c == '_' || c == '$'

    private fun codeLine(line: String, lang: Language, startState: Int): LineHighlight {
        val spans = ArrayList<HlSpan>()
        var i = 0
        val n = line.length
        var state = startState

        fun closeMultiline(delim: String, kind: TokenKind): Boolean {
            val end = line.indexOf(delim, i)
            return if (end < 0) {
                spans += HlSpan(i, n, kind); i = n; true
            } else {
                spans += HlSpan(i, end + delim.length, kind); i = end + delim.length; state = NORMAL; false
            }
        }

        when (state) {
            BLOCK_COMMENT -> if (closeMultiline(lang.blockComment?.second ?: "*/", TokenKind.COMMENT)) return LineHighlight(spans, state)
            TRIPLE_DQ -> if (closeMultiline("\"\"\"", TokenKind.STRING)) return LineHighlight(spans, state)
            TRIPLE_SQ -> if (closeMultiline("'''", TokenKind.STRING)) return LineHighlight(spans, state)
            BACKTICK -> {
                val end = findClosing(line, i, '`')
                if (end < 0) return LineHighlight(listOf(HlSpan(0, n, TokenKind.STRING)), BACKTICK)
                spans += HlSpan(0, end + 1, TokenKind.STRING); i = end + 1; state = NORMAL
            }
        }

        while (i < n) {
            val c = line[i]
            // Line comments.
            val lc = lang.lineComments.firstOrNull { line.startsWith(it, i) }
            if (lc != null && (lc != "#" || i == 0 || line[i - 1].isWhitespace() || lang.family != LangFamily.SHELL)) {
                // Block comment opener sharing the prefix (Lua "--[[") wins.
                val bc = lang.blockComment
                if (bc == null || !line.startsWith(bc.first, i)) {
                    spans += HlSpan(i, n, TokenKind.COMMENT); i = n; break
                }
            }
            val bc = lang.blockComment
            if (bc != null && line.startsWith(bc.first, i)) {
                val end = line.indexOf(bc.second, i + bc.first.length)
                if (end < 0) { spans += HlSpan(i, n, TokenKind.COMMENT); return LineHighlight(spans, BLOCK_COMMENT) }
                spans += HlSpan(i, end + bc.second.length, TokenKind.COMMENT); i = end + bc.second.length; continue
            }
            if (lang.tripleQuotes && (line.startsWith("\"\"\"", i) || line.startsWith("'''", i))) {
                val delim = line.substring(i, i + 3)
                if (delim == "'''" && lang.family != LangFamily.PYTHON) { /* only Python has ''' */ } else {
                    val end = line.indexOf(delim, i + 3)
                    if (end < 0) {
                        spans += HlSpan(i, n, TokenKind.STRING)
                        return LineHighlight(spans, if (delim == "\"\"\"") TRIPLE_DQ else TRIPLE_SQ)
                    }
                    spans += HlSpan(i, end + 3, TokenKind.STRING); i = end + 3; continue
                }
            }
            if (c == '"' || c == '\'' || (c == '`' && (lang.backtickStrings || lang.family == LangFamily.SHELL))) {
                val end = findClosing(line, i + 1, c)
                if (end < 0) {
                    spans += HlSpan(i, n, TokenKind.STRING)
                    return LineHighlight(spans, if (c == '`' && lang.backtickStrings) BACKTICK else NORMAL)
                }
                spans += HlSpan(i, end + 1, TokenKind.STRING); i = end + 1; continue
            }
            if (c.isDigit() && (i == 0 || !isIdentPart(line[i - 1]))) {
                var j = i + 1
                while (j < n && (line[j].isLetterOrDigit() || line[j] == '.' || line[j] == '_') &&
                    !(line[j] == '.' && (j + 1 >= n || !line[j + 1].isDigit()))
                ) j++
                spans += HlSpan(i, j, TokenKind.NUMBER); i = j; continue
            }
            if (c == '@' && i + 1 < n && isIdentStart(line[i + 1]) && lang.family in setOf(LangFamily.C_LIKE, LangFamily.PYTHON)) {
                var j = i + 1
                while (j < n && (isIdentPart(line[j]) || line[j] == '.')) j++
                spans += HlSpan(i, j, TokenKind.ANNOTATION); i = j; continue
            }
            if (c == '#' && lang.family == LangFamily.C_LIKE && line.substring(0, i).isBlank()) {
                // C preprocessor directive.
                var j = i + 1
                while (j < n && line[j].isLetter()) j++
                spans += HlSpan(i, j, TokenKind.ANNOTATION); i = j; continue
            }
            if (c == '$' && lang.family == LangFamily.SHELL && i + 1 < n && (isIdentStart(line[i + 1]) || line[i + 1] == '{')) {
                var j = i + 1
                if (line[j] == '{') { val e = line.indexOf('}', j); j = if (e < 0) n else e + 1 } else while (j < n && isIdentPart(line[j])) j++
                spans += HlSpan(i, j, TokenKind.ATTRIBUTE); i = j; continue
            }
            if (isIdentStart(c)) {
                var j = i + 1
                while (j < n && isIdentPart(line[j])) j++
                if (lang.family == LangFamily.RUBY && j < n && line[j] == '?') j++
                val word = line.substring(i, j)
                val key = if (lang.caseInsensitive) word.lowercase() else word
                val kind = when {
                    key in lang.keywords -> TokenKind.KEYWORD
                    word in lang.types -> TokenKind.TYPE
                    lang.family == LangFamily.C_LIKE && word[0].isUpperCase() && word.length > 1 && word.any { it.isLowerCase() } -> TokenKind.TYPE
                    else -> null
                }
                if (kind != null) spans += HlSpan(i, j, kind)
                i = j; continue
            }
            i++
        }
        return LineHighlight(spans, state)
    }

    /** Index of the closing [quote] from [from], honouring backslash escapes, or -1. */
    private fun findClosing(line: String, from: Int, quote: Char): Int {
        var j = from
        while (j < line.length) {
            val ch = line[j]
            if (ch == '\\') { j += 2; continue }
            if (ch == quote) return j
            j++
        }
        return -1
    }

    // -----------------------------------------------------------------------------------------
    // XML / HTML
    // -----------------------------------------------------------------------------------------

    private fun markupLine(line: String, startState: Int): LineHighlight {
        val spans = ArrayList<HlSpan>()
        val n = line.length
        var i = 0
        var state = startState
        while (i < n) {
            when (state) {
                MARKUP_COMMENT -> {
                    val end = line.indexOf("-->", i)
                    if (end < 0) { spans += HlSpan(i, n, TokenKind.COMMENT); return LineHighlight(spans, MARKUP_COMMENT) }
                    spans += HlSpan(i, end + 3, TokenKind.COMMENT); i = end + 3; state = NORMAL
                }
                IN_TAG -> {
                    val c = line[i]
                    when {
                        c == '>' -> { spans += HlSpan(i, i + 1, TokenKind.TAG); i++; state = NORMAL }
                        c == '/' && i + 1 < n && line[i + 1] == '>' -> { spans += HlSpan(i, i + 2, TokenKind.TAG); i += 2; state = NORMAL }
                        c == '?' && i + 1 < n && line[i + 1] == '>' -> { spans += HlSpan(i, i + 2, TokenKind.TAG); i += 2; state = NORMAL }
                        c == '"' || c == '\'' -> {
                            val end = line.indexOf(c, i + 1)
                            val e = if (end < 0) n else end + 1
                            spans += HlSpan(i, e, TokenKind.STRING); i = e
                        }
                        c.isLetter() || c == '_' || c == ':' || c == '@' -> {
                            var j = i + 1
                            while (j < n && (line[j].isLetterOrDigit() || line[j] in "_:-.@")) j++
                            spans += HlSpan(i, j, TokenKind.ATTRIBUTE); i = j
                        }
                        else -> i++
                    }
                }
                else -> {
                    val lt = line.indexOf('<', i)
                    val amp = line.indexOf('&', i)
                    if (amp in i until (if (lt < 0) n else lt)) {
                        val semi = line.indexOf(';', amp)
                        if (semi in amp + 2..amp + 10) {
                            spans += HlSpan(amp, semi + 1, TokenKind.KEYWORD); i = semi + 1; continue
                        }
                    }
                    if (lt < 0) { i = n; break }
                    i = lt
                    if (line.startsWith("<!--", i)) { state = MARKUP_COMMENT; spans += HlSpan(i, i + 4, TokenKind.COMMENT); i += 4; continue }
                    var j = i + 1
                    if (j < n && (line[j] == '/' || line[j] == '!' || line[j] == '?')) j++
                    val nameStart = j
                    while (j < n && (line[j].isLetterOrDigit() || line[j] in "_:-.")) j++
                    if (j == nameStart) { i++; continue } // a lone '<' in text
                    spans += HlSpan(i, j, TokenKind.TAG)
                    i = j; state = IN_TAG
                }
            }
        }
        return LineHighlight(spans, state)
    }

    // -----------------------------------------------------------------------------------------
    // JSON
    // -----------------------------------------------------------------------------------------

    private fun jsonLine(line: String, lang: Language, startState: Int): LineHighlight {
        val spans = ArrayList<HlSpan>()
        val n = line.length
        var i = 0
        if (startState == BLOCK_COMMENT) {
            val end = line.indexOf("*/")
            if (end < 0) return LineHighlight(listOf(HlSpan(0, n, TokenKind.COMMENT)), BLOCK_COMMENT)
            spans += HlSpan(0, end + 2, TokenKind.COMMENT); i = end + 2
        }
        while (i < n) {
            val c = line[i]
            when {
                c == '"' -> {
                    val end = findClosing(line, i + 1, '"')
                    val e = if (end < 0) n else end + 1
                    var k = e
                    while (k < n && line[k].isWhitespace()) k++
                    spans += HlSpan(i, e, if (k < n && line[k] == ':') TokenKind.KEY else TokenKind.STRING)
                    i = e
                }
                c == '-' || c.isDigit() -> {
                    var j = i + 1
                    while (j < n && (line[j].isDigit() || line[j] in ".eE+-")) j++
                    if (c == '-' && j == i + 1) { i++; continue }
                    spans += HlSpan(i, j, TokenKind.NUMBER); i = j
                }
                c.isLetter() -> {
                    var j = i + 1
                    while (j < n && line[j].isLetter()) j++
                    if (line.substring(i, j) in lang.keywords) spans += HlSpan(i, j, TokenKind.KEYWORD)
                    i = j
                }
                line.startsWith("//", i) -> { spans += HlSpan(i, n, TokenKind.COMMENT); i = n }
                line.startsWith("/*", i) -> {
                    val end = line.indexOf("*/", i + 2)
                    if (end < 0) { spans += HlSpan(i, n, TokenKind.COMMENT); return LineHighlight(spans, BLOCK_COMMENT) }
                    spans += HlSpan(i, end + 2, TokenKind.COMMENT); i = end + 2
                }
                c in "{}[]:," -> { spans += HlSpan(i, i + 1, TokenKind.PUNCTUATION); i++ }
                else -> i++
            }
        }
        return LineHighlight(spans, NORMAL)
    }

    // -----------------------------------------------------------------------------------------
    // YAML / TOML / INI / properties
    // -----------------------------------------------------------------------------------------

    private fun configLine(line: String, lang: Language): LineHighlight {
        val spans = ArrayList<HlSpan>()
        val n = line.length
        var i = 0
        while (i < n && line[i].isWhitespace()) i++
        if (i >= n) return LineHighlight(spans, NORMAL)
        val first = line[i]
        if (lang.lineComments.any { line.startsWith(it, i) }) return LineHighlight(listOf(HlSpan(i, n, TokenKind.COMMENT)), NORMAL)
        if (lang.family == LangFamily.YAML && (line.startsWith("---", i) || line.startsWith("...", i)) && i == 0) {
            return LineHighlight(listOf(HlSpan(0, minOf(3, n), TokenKind.PUNCTUATION)), NORMAL)
        }
        if (lang.family == LangFamily.INI && first == '[') {
            val end = line.indexOf(']', i)
            return LineHighlight(listOf(HlSpan(i, if (end < 0) n else end + 1, TokenKind.TAG)), NORMAL)
        }
        if (lang.family == LangFamily.YAML && first == '-' && i + 1 < n && line[i + 1] == ' ') {
            spans += HlSpan(i, i + 1, TokenKind.PUNCTUATION); i += 2
            while (i < n && line[i] == ' ') i++
        }
        // Key.
        val sepChars = if (lang.family == LangFamily.YAML) ":" else "=:"
        var k = i
        var quoted = false
        if (k < n && (line[k] == '"' || line[k] == '\'')) {
            val e = line.indexOf(line[k], k + 1); if (e > 0) { k = e + 1; quoted = true }
        }
        if (!quoted) while (k < n && line[k] !in sepChars && !(lang.family == LangFamily.YAML && line[k] == ' ' && k + 1 < n && line[k + 1] == '#')) k++
        val isKey = k < n && line[k] in sepChars && (lang.family != LangFamily.YAML || k + 1 >= n || line[k + 1] == ' ')
        var v = i
        if (isKey && k > i) {
            spans += HlSpan(i, k, TokenKind.KEY)
            spans += HlSpan(k, k + 1, TokenKind.PUNCTUATION)
            v = k + 1
        }
        // Value.
        while (v < n && line[v] == ' ') v++
        valueSpans(line, v, lang, spans)
        return LineHighlight(spans, NORMAL)
    }

    private fun valueSpans(line: String, from: Int, lang: Language, spans: MutableList<HlSpan>) {
        val n = line.length
        var i = from
        while (i < n) {
            val c = line[i]
            when {
                c == '#' && (i == 0 || line[i - 1] == ' ') -> { spans += HlSpan(i, n, TokenKind.COMMENT); return }
                c == ';' && lang.family == LangFamily.INI && (i == 0 || line[i - 1] == ' ') -> { spans += HlSpan(i, n, TokenKind.COMMENT); return }
                c == '"' || c == '\'' -> {
                    val end = findClosing(line, i + 1, c)
                    val e = if (end < 0) n else end + 1
                    spans += HlSpan(i, e, TokenKind.STRING); i = e
                }
                (c == '&' || c == '*') && lang.family == LangFamily.YAML && i + 1 < n && line[i + 1].isLetterOrDigit() -> {
                    var j = i + 1
                    while (j < n && !line[j].isWhitespace()) j++
                    spans += HlSpan(i, j, TokenKind.ANNOTATION); i = j
                }
                c.isDigit() || (c == '-' && i + 1 < n && line[i + 1].isDigit()) -> {
                    var j = i + 1
                    while (j < n && (line[j].isLetterOrDigit() || line[j] in ".:_-+")) j++
                    val word = line.substring(i, j)
                    if (word.all { it.isDigit() || it in ".-+_eE" }) spans += HlSpan(i, j, TokenKind.NUMBER)
                    i = j
                }
                c.isLetter() || c == '~' -> {
                    var j = i + 1
                    while (j < n && (line[j].isLetterOrDigit() || line[j] in "_-.")) j++
                    val word = line.substring(i, j)
                    if (word in lang.keywords && (j >= n || line[j].isWhitespace() || line[j] in ",]}")) spans += HlSpan(i, j, TokenKind.KEYWORD)
                    i = j
                }
                else -> i++
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // CSS
    // -----------------------------------------------------------------------------------------

    private fun cssLine(line: String, lang: Language, startState: Int): LineHighlight {
        val spans = ArrayList<HlSpan>()
        val n = line.length
        var i = 0
        if (startState == BLOCK_COMMENT) {
            val end = line.indexOf("*/")
            if (end < 0) return LineHighlight(listOf(HlSpan(0, n, TokenKind.COMMENT)), BLOCK_COMMENT)
            spans += HlSpan(0, end + 2, TokenKind.COMMENT); i = end + 2
        }
        while (i < n) {
            val c = line[i]
            when {
                line.startsWith("/*", i) -> {
                    val end = line.indexOf("*/", i + 2)
                    if (end < 0) { spans += HlSpan(i, n, TokenKind.COMMENT); return LineHighlight(spans, BLOCK_COMMENT) }
                    spans += HlSpan(i, end + 2, TokenKind.COMMENT); i = end + 2
                }
                line.startsWith("//", i) && (i == 0 || line[i - 1] != ':') -> { spans += HlSpan(i, n, TokenKind.COMMENT); i = n }
                c == '"' || c == '\'' -> {
                    val end = findClosing(line, i + 1, c)
                    val e = if (end < 0) n else end + 1
                    spans += HlSpan(i, e, TokenKind.STRING); i = e
                }
                c == '@' -> {
                    var j = i + 1
                    while (j < n && (line[j].isLetterOrDigit() || line[j] == '-')) j++
                    spans += HlSpan(i, j, TokenKind.ANNOTATION); i = j
                }
                c == '#' && i + 1 < n && line[i + 1].isLetterOrDigit() -> {
                    var j = i + 1
                    while (j < n && (line[j].isLetterOrDigit() || line[j] in "-_")) j++
                    val word = line.substring(i + 1, j)
                    val isColor = word.length in setOf(3, 4, 6, 8) && word.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
                    spans += HlSpan(i, j, if (isColor) TokenKind.NUMBER else TokenKind.TAG); i = j
                }
                c.isDigit() || (c == '.' && i + 1 < n && line[i + 1].isDigit()) -> {
                    var j = i + 1
                    while (j < n && (line[j].isLetterOrDigit() || line[j] == '.' || line[j] == '%')) j++
                    spans += HlSpan(i, j, TokenKind.NUMBER); i = j
                }
                c.isLetter() || c == '-' || c == '$' -> {
                    var j = i + 1
                    while (j < n && (line[j].isLetterOrDigit() || line[j] in "-_")) j++
                    var k = j
                    while (k < n && line[k] == ' ') k++
                    val word = line.substring(i, j)
                    when {
                        k < n && line[k] == ':' && !(k + 1 < n && line[k + 1].isLetter() && line.indexOf('{', k) >= 0) -> spans += HlSpan(i, j, TokenKind.KEY)
                        word in lang.keywords -> spans += HlSpan(i, j, TokenKind.KEYWORD)
                    }
                    i = j
                }
                c == '.' && i + 1 < n && line[i + 1].isLetter() -> {
                    var j = i + 1
                    while (j < n && (line[j].isLetterOrDigit() || line[j] in "-_")) j++
                    spans += HlSpan(i, j, TokenKind.TAG); i = j
                }
                c == '!' -> {
                    var j = i + 1
                    while (j < n && line[j].isLetter()) j++
                    spans += HlSpan(i, j, TokenKind.KEYWORD); i = j
                }
                else -> i++
            }
        }
        return LineHighlight(spans, NORMAL)
    }

    // -----------------------------------------------------------------------------------------
    // Markdown
    // -----------------------------------------------------------------------------------------

    private fun markdownLine(line: String, startState: Int): LineHighlight {
        val n = line.length
        val trimmed = line.trimStart()
        val indent = n - trimmed.length
        if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
            return LineHighlight(listOf(HlSpan(0, n, TokenKind.PUNCTUATION)), if (startState == MD_FENCE) NORMAL else MD_FENCE)
        }
        if (startState == MD_FENCE) return LineHighlight(listOf(HlSpan(0, n, TokenKind.STRING)), MD_FENCE)
        val spans = ArrayList<HlSpan>()
        if (trimmed.startsWith("#")) return LineHighlight(listOf(HlSpan(indent, n, TokenKind.KEYWORD)), NORMAL)
        if (trimmed.startsWith(">")) return LineHighlight(listOf(HlSpan(indent, n, TokenKind.COMMENT)), NORMAL)
        var i = 0
        if (trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ")) {
            spans += HlSpan(indent, indent + 1, TokenKind.PUNCTUATION); i = indent + 2
        } else {
            val num = trimmed.takeWhile { it.isDigit() }
            if (num.isNotEmpty() && trimmed.getOrNull(num.length) == '.') {
                spans += HlSpan(indent, indent + num.length + 1, TokenKind.PUNCTUATION); i = indent + num.length + 1
            }
        }
        while (i < n) {
            val c = line[i]
            when {
                c == '`' -> {
                    val end = line.indexOf('`', i + 1)
                    val e = if (end < 0) n else end + 1
                    spans += HlSpan(i, e, TokenKind.STRING); i = e
                }
                (line.startsWith("**", i) || line.startsWith("__", i)) -> {
                    val d = line.substring(i, i + 2)
                    val end = line.indexOf(d, i + 2)
                    if (end < 0) { i += 2; continue }
                    spans += HlSpan(i, end + 2, TokenKind.TYPE); i = end + 2
                }
                c == '[' -> {
                    val close = line.indexOf(']', i + 1)
                    if (close > 0 && close + 1 < n && line[close + 1] == '(') {
                        val pe = line.indexOf(')', close + 2)
                        spans += HlSpan(i, close + 1, TokenKind.KEY)
                        if (pe > 0) { spans += HlSpan(close + 1, pe + 1, TokenKind.ATTRIBUTE); i = pe + 1 } else i = close + 1
                    } else i++
                }
                else -> i++
            }
        }
        return LineHighlight(spans, NORMAL)
    }

    // -----------------------------------------------------------------------------------------
    // CSV / TSV: alternate columns are tinted, quoted fields may span lines.
    // -----------------------------------------------------------------------------------------

    private fun csvLine(line: String, sep: Char, startState: Int): LineHighlight {
        val spans = ArrayList<HlSpan>()
        val n = line.length
        var i = 0
        var col = 0
        var inQuote = startState == CSV_QUOTE
        var fieldStart = 0
        while (i < n) {
            val c = line[i]
            if (inQuote) {
                if (c == '"') {
                    if (i + 1 < n && line[i + 1] == '"') { i += 2; continue }
                    inQuote = false
                }
                i++; continue
            }
            when (c) {
                '"' -> inQuote = true
                sep -> {
                    if (col % 2 == 1 && i > fieldStart) spans += HlSpan(fieldStart, i, TokenKind.COLUMN)
                    spans += HlSpan(i, i + 1, TokenKind.PUNCTUATION)
                    col++; fieldStart = i + 1
                }
            }
            i++
        }
        if (col % 2 == 1 && n > fieldStart) spans += HlSpan(fieldStart, n, TokenKind.COLUMN)
        return LineHighlight(spans, if (inQuote) CSV_QUOTE else NORMAL)
    }
}
