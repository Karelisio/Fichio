package com.karelisio.fichio.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Checks that the English translation is complete and consistent with the French source:
 * same string and plural keys, same format arguments (position and type) in every text.
 */
class TranslationsTest {
    private val res = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }

    /** key -> list of texts (one for a string, one per quantity for a plural). */
    private fun load(dir: String): Pair<Map<String, String>, Map<String, Map<String, String>>> {
        val file = File(res, "$dir/strings.xml")
        assertTrue("missing $file", file.isFile)
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val strings = LinkedHashMap<String, String>()
        val plurals = LinkedHashMap<String, Map<String, String>>()
        val root = doc.documentElement
        val nodes = root.childNodes
        for (i in 0 until nodes.length) {
            val n = nodes.item(i) as? Element ?: continue
            if (n.getAttribute("translatable") == "false") continue
            when (n.tagName) {
                "string" -> strings[n.getAttribute("name")] = n.textContent
                "plurals" -> {
                    val items = n.getElementsByTagName("item")
                    plurals[n.getAttribute("name")] = (0 until items.length).associate {
                        val item = items.item(it) as Element
                        item.getAttribute("quantity") to item.textContent
                    }
                }
            }
        }
        return strings to plurals
    }

    /** Format arguments of a text, e.g. ["1$s", "2$d"] (an escaped %% is not an argument). */
    private fun args(text: String): List<String> {
        val out = ArrayList<String>()
        val m = Regex("%(%|(\\d+\\$)?[-#+ 0,(]*\\d*(\\.\\d+)?[a-zA-Z])").findAll(text)
        var implicit = 0
        for (r in m) {
            val token = r.value
            if (token == "%%") continue
            val pos = r.groups[2]?.value ?: "${++implicit}$"
            out += pos + token.last()
        }
        return out.sorted().distinct()
    }

    private val fr = load("values")
    private val en = load("values-en")

    @Test fun everyFrenchStringIsTranslated() {
        val missing = fr.first.keys - en.first.keys
        assertTrue("strings missing in values-en: $missing", missing.isEmpty())
        val missingPlurals = fr.second.keys - en.second.keys
        assertTrue("plurals missing in values-en: $missingPlurals", missingPlurals.isEmpty())
    }

    @Test fun noEnglishOnlyStrings() {
        val extra = en.first.keys - fr.first.keys
        assertTrue("strings only in values-en: $extra", extra.isEmpty())
        val extraPlurals = en.second.keys - fr.second.keys
        assertTrue("plurals only in values-en: $extraPlurals", extraPlurals.isEmpty())
    }

    @Test fun formatArgumentsMatch() {
        val errors = ArrayList<String>()
        for ((key, text) in fr.first) {
            val other = en.first[key] ?: continue
            if (args(text) != args(other)) errors += "$key: ${args(text)} ≠ ${args(other)}"
        }
        for ((key, quantities) in fr.second) {
            val other = en.second[key] ?: continue
            val frArgs = quantities.values.map(::args).toSet()
            val enArgs = other.values.map(::args).toSet()
            // Every quantity of both languages uses the same arguments (or none, e.g. a bare noun).
            if (frArgs.flatten().toSet() != enArgs.flatten().toSet()) errors += "$key: $frArgs ≠ $enArgs"
        }
        assertEquals("format arguments differ:\n" + errors.joinToString("\n"), 0, errors.size)
    }

    @Test fun pluralsHaveOneAndOther() {
        for ((name, set) in listOf("fr" to fr, "en" to en)) {
            for ((key, q) in set.second) {
                assertTrue("$name/$key lacks 'one'", "one" in q)
                assertTrue("$name/$key lacks 'other'", "other" in q)
            }
        }
    }

    @Test fun noEmptyTranslation() {
        val empty = en.first.filterValues { it.isBlank() }.keys + fr.first.filterValues { it.isBlank() }.keys
        assertTrue("empty texts: $empty", empty.isEmpty())
    }

    @Test fun localeConfigListsBothLanguages() {
        val config = File(res, "xml/locales_config.xml").readText()
        assertTrue(config.contains("android:name=\"fr\""))
        assertTrue(config.contains("android:name=\"en\""))
    }
}
