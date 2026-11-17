package com.karelisio.fichio.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseParserTest {

    @Test fun `compares versions semantically`() {
        assertTrue(ReleaseParser.isNewer("0.10.0", "0.9.9"))
        assertTrue(ReleaseParser.isNewer("v1.0.0", "0.99.99"))
        assertTrue(ReleaseParser.isNewer("0.1.2", "0.1.1-debug"))
        assertFalse(ReleaseParser.isNewer("0.1.1", "0.1.1-debug"))
        assertFalse(ReleaseParser.isNewer("v0.9.9", "0.10.0"))
        assertEquals(0, ReleaseParser.compare("1.2", "1.2.0"))
        assertEquals(0, ReleaseParser.compare("v2.0.0-rc1", "2.0.0"))
        assertTrue(ReleaseParser.compare("1.2.10", "1.2.9") > 0)
    }

    @Test fun `normalizes tags`() {
        assertEquals("1.4.0", ReleaseParser.normalize("v1.4.0"))
        assertEquals("1.4.0", ReleaseParser.normalize(" 1.4.0-debug "))
    }

    private val sample = """
        {
          "url": "https://api.github.com/repos/Karelisio/Fichio/releases/1",
          "html_url": "https://github.com/Karelisio/Fichio/releases/tag/v0.2.0",
          "tag_name": "v0.2.0",
          "name": "Fichio v0.2.0",
          "draft": false,
          "prerelease": false,
          "published_at": "2026-11-20T10:00:00Z",
          "body": "### Added\n- **Widget** amélioré\n- Mise à jour in-app",
          "author": { "login": "x" },
          "assets": [
            { "name": "checksums.txt", "size": 10, "browser_download_url": "https://example.com/c.txt" },
            { "name": "Fichio-v0.2.0.apk", "size": 12345678, "content_type": "application/vnd.android.package-archive",
              "browser_download_url": "https://github.com/Karelisio/Fichio/releases/download/v0.2.0/Fichio-v0.2.0.apk" }
          ]
        }
    """.trimIndent()

    @Test fun `parses a release and picks the apk asset`() {
        val update = ReleaseParser.toUpdate(ReleaseParser.parse(sample))
        assertNotNull(update)
        update!!
        assertEquals("0.2.0", update.version)
        assertEquals("Fichio-v0.2.0.apk", update.apkName)
        assertEquals(12345678L, update.apkSize)
        assertTrue(update.apkUrl.endsWith(".apk"))
        assertTrue(update.changelog.startsWith("### Added"))
    }

    @Test fun `release without apk or marked draft is ignored`() {
        val noApk = ReleaseParser.parse("""{"tag_name":"v1.0.0","assets":[]}""")
        assertNull(ReleaseParser.toUpdate(noApk))
        val draft = ReleaseParser.parse(sample.replace("\"draft\": false", "\"draft\": true"))
        assertNull(ReleaseParser.toUpdate(draft))
    }

    private val changelog = """
        # Changelog

        ## [0.2.0] - 2026-11-20

        ### Added
        - Widget

        ## [0.1.1] - unreleased

        ### Fixed
        - Crash au lancement.

        ## [0.1.0] - 2026-11-17
        - Première version
    """.trimIndent()

    @Test fun `extracts the changelog section of a version`() {
        assertEquals("### Added\n- Widget", ReleaseParser.changelogSection(changelog, "0.2.0"))
        assertEquals("### Fixed\n- Crash au lancement.", ReleaseParser.changelogSection(changelog, "v0.1.1"))
        assertEquals("- Première version", ReleaseParser.changelogSection(changelog, "0.1.0"))
        assertNull(ReleaseParser.changelogSection(changelog, "9.9.9"))
        // 0.1.1 must not match 0.1.10
        assertNull(ReleaseParser.changelogSection("## [0.1.10]\n- x", "0.1.1"))
    }

    @Test fun `parses simple markdown`() {
        val blocks = SimpleMarkdown.parse("## Titre\n### Sous-titre\n- **gras** puce\n  - sous-puce\n\nTexte\nsuite")
        assertEquals(MdBlock.Heading(2, "Titre"), blocks[0])
        assertEquals(MdBlock.Heading(3, "Sous-titre"), blocks[1])
        assertEquals(MdBlock.Bullet("**gras** puce", 0), blocks[2])
        assertEquals(MdBlock.Bullet("sous-puce", 1), blocks[3])
        assertEquals(MdBlock.Paragraph("Texte suite"), blocks[4])
        assertEquals(listOf("gras" to true, " puce" to false), SimpleMarkdown.boldRuns("**gras** puce"))
        assertEquals(listOf("a " to false, "**b" to false), SimpleMarkdown.boldRuns("a **b"))
    }
}
