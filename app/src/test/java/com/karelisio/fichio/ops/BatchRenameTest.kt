package com.karelisio.fichio.ops

import com.karelisio.fichio.ops.engine.Names
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchRenameTest {
    private fun src(vararg names: String, dir: String = "/s") = names.map { RenameSource("$dir/$it", it, !it.contains('.')) }

    private fun names(rules: BatchRenameRules, vararg n: String) =
        BatchRename.preview(src(*n), rules).items.map { it.newName }

    @Test
    fun prefixAndSuffixKeepExtension() {
        assertEquals(listOf("IMG_photo_v2.jpg"), names(BatchRenameRules(prefix = "IMG_", suffix = "_v2"), "photo.jpg"))
    }

    @Test
    fun extensionCanBeIncluded() {
        assertEquals(listOf("photo.jpg-bak"), names(BatchRenameRules(suffix = "-bak", keepExtension = false), "photo.jpg"))
    }

    @Test
    fun numberingStartStepDigitsAndPosition() {
        val end = BatchRenameRules(numbering = true, numberStart = 5, numberStep = 5, numberDigits = 3)
        assertEquals(listOf("a 005.txt", "b 010.txt", "c 015.txt"), names(end, "a.txt", "b.txt", "c.txt"))
        val start = end.copy(numberPosition = NumberPosition.START, numberSeparator = "_", numberStart = 1, numberStep = 1, numberDigits = 2)
        assertEquals(listOf("01_a.txt", "02_b.txt"), names(start, "a.txt", "b.txt"))
        val replace = start.copy(numberPosition = NumberPosition.REPLACE)
        assertEquals(listOf("01.txt", "02.txt"), names(replace, "a.txt", "b.txt"))
    }

    @Test
    fun findReplacePlainIsCaseInsensitiveByDefault() {
        assertEquals(listOf("holiday-SUMMER.jpg"), names(BatchRenameRules(find = "vacances", replace = "holiday"), "VACANCES-SUMMER.jpg"))
        assertEquals(
            listOf("VACANCES-SUMMER.jpg"),
            names(BatchRenameRules(find = "vacances", replace = "x", caseSensitive = true), "VACANCES-SUMMER.jpg"),
        )
    }

    @Test
    fun findReplaceRegexWithGroups() {
        val r = BatchRenameRules(find = """(\d{4})(\d{2})(\d{2})""", replace = "$3-$2-$1", regex = true)
        assertEquals(listOf("IMG_31-12-2024.jpg"), names(r, "IMG_20241231.jpg"))
    }

    @Test
    fun invalidRegexIsReported() {
        val p = BatchRename.preview(src("a.txt"), BatchRenameRules(find = "(", regex = true))
        assertNotNull(p.patternError)
        assertFalse(p.canApply)
    }

    @Test
    fun caseChanges() {
        assertEquals(listOf("mon fichier.TXT"), names(BatchRenameRules(case = CaseChange.LOWER), "Mon FICHIER.TXT"))
        assertEquals(listOf("MON FICHIER.txt"), names(BatchRenameRules(case = CaseChange.UPPER), "mon fichier.txt"))
        assertEquals(listOf("Le Petit Chat-Noir.jpg"), names(BatchRenameRules(case = CaseChange.TITLE), "le petit CHAT-noir.jpg"))
        assertEquals("L'été Indien", BatchRename.titleCase("l'été indien"))
    }

    @Test
    fun rulesApplyInOrder() {
        val r = BatchRenameRules(prefix = "P-", find = "old", replace = "new", case = CaseChange.UPPER, numbering = true, numberDigits = 2)
        assertEquals(listOf("P-NEW 01.md"), names(r, "old.md"))
    }

    @Test
    fun foldersHaveNoExtension() {
        val p = BatchRename.preview(listOf(RenameSource("/s/v1.0", "v1.0", true)), BatchRenameRules(suffix = "_x"))
        assertEquals("v1.0_x", p.items[0].newName)
    }

    @Test
    fun collisionsInsideTheBatch() {
        val p = BatchRename.preview(src("a.txt", "b.txt"), BatchRenameRules(numbering = true, numberPosition = NumberPosition.REPLACE, numberStep = 0))
        assertTrue(p.items.all { it.status == RenameStatus.COLLISION })
        assertFalse(p.canApply)
    }

    @Test
    fun collisionsWithExistingSiblingsIgnoringCase() {
        val p = BatchRename.preview(src("a.txt"), BatchRenameRules(prefix = "x")) { listOf("a.txt", "XA.TXT") }
        assertEquals(RenameStatus.COLLISION, p.items[0].status)
    }

    @Test
    fun swappingNamesIsNotACollision() {
        // a → b and b → a: both old names are freed by the batch itself.
        val rules = BatchRenameRules(find = "", prefix = "")
        val p = BatchRename.preview(src("1.txt", "2.txt"), rules.copy(numbering = true, numberPosition = NumberPosition.REPLACE, numberStart = 2, numberStep = -1)) {
            listOf("1.txt", "2.txt")
        }
        assertEquals(listOf("2.txt", "1.txt"), p.items.map { it.newName })
        assertTrue(p.items.all { it.status == RenameStatus.OK })
        assertTrue(p.canApply)
    }

    @Test
    fun unchangedAndInvalidStatuses() {
        val p = BatchRename.preview(src("a.txt", "b.txt"), BatchRenameRules(find = "b", replace = "b:"))
        assertEquals(RenameStatus.UNCHANGED, p.items[0].status)
        assertEquals(RenameStatus.INVALID, p.items[1].status)
        assertEquals(1, p.problems)
        assertEquals(listOf<Pair<String, String>>(), p.renames())
    }

    @Test
    fun renamesListsOnlyChanged() {
        val p = BatchRename.preview(src("a.txt", "b.jpg"), BatchRenameRules(find = "a", replace = "z"))
        assertEquals(listOf("/s/a.txt" to "z.txt"), p.renames())
    }

    @Test
    fun namesHelpers() {
        assertEquals("doc (1).pdf", Names.numbered("doc.pdf", false, 1))
        assertEquals("doc (3).pdf", Names.numbered("doc (2).pdf", false, 3))
        assertEquals("backup (1).tar.gz", Names.numbered("backup.tar.gz", false, 1))
        assertEquals(".bashrc (1)", Names.numbered(".bashrc", false, 1))
        assertEquals("Photos 2024 (1)", Names.numbered("Photos 2024", true, 1))
        assertEquals("a (2).txt", Names.unique("a.txt", false) { it == "a (1).txt" })
        assertNull(Names.validate("ok.txt"))
        assertNotNull(Names.validate(""))
        assertNotNull(Names.validate("a/b"))
        assertNotNull(Names.validate("a?b"))
        assertNotNull(Names.validate(".."))
        assertNotNull(Names.validate("x".repeat(300)))
    }
}
