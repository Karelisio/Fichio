package com.karelisio.fichio

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.karelisio.fichio.TestEnv.FIXTURE_NAME
import com.karelisio.fichio.TestEnv.PDF_FILE
import com.karelisio.fichio.TestEnv.SUB_FOLDER
import com.karelisio.fichio.TestEnv.TEXT_CONTENT
import com.karelisio.fichio.TestEnv.TEXT_FILE
import com.karelisio.fichio.TestEnv.waitForText
import com.karelisio.fichio.core.util.CrashLog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Home → browser → folders → previews and back, on real storage. */
@RunWith(AndroidJUnit4::class)
@LargeTest
class NavigationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before fun setUp() {
        TestEnv.prepare(onboardingDone = true)
        TestEnv.createFixture()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitForText("Rechercher dans vos fichiers")
    }

    @After fun tearDown() {
        scenario.close()
        TestEnv.deleteFixture()
    }

    /** Scrolls the first lazy list that contains [text] when it is not composed yet. */
    private fun reveal(text: String) {
        if (compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()) return
        val lists = compose.onAllNodes(hasScrollToIndexAction())
        for (i in 0 until lists.fetchSemanticsNodes().size) {
            if (runCatching { lists[i].performScrollToNode(hasText(text)) }.isSuccess) return
        }
    }

    private fun click(text: String) {
        runCatching { compose.waitForText(text, timeoutMs = 5_000) }.onFailure { reveal(text) }
        compose.waitForText(text)
        compose.onAllNodesWithText(text).onFirst().performClick()
        compose.waitForIdle()
    }

    private fun back() {
        TestEnv.pressBack()
        compose.waitForIdle()
    }

    /** Home → "Stockage interne" → fixture folder. */
    private fun openFixture() {
        click("Stockage interne")
        click(FIXTURE_NAME)
        compose.waitForText(TEXT_FILE)
    }

    private fun assertAlive() {
        assertEquals(Lifecycle.State.RESUMED, scenario.state)
        assertNull("a crash was recorded", CrashLog.read(TestEnv.context))
    }

    @Test fun homeToBrowserOpenFolderAndBack() {
        click("Stockage interne")
        compose.waitForText(FIXTURE_NAME)
        click(FIXTURE_NAME)
        compose.waitForText(SUB_FOLDER)
        compose.onNodeWithText(PDF_FILE).assertIsDisplayed()

        click(SUB_FOLDER)
        compose.waitForText("dedans.txt")

        back() // folder by folder: back in the fixture folder
        compose.waitForText(TEXT_FILE)
        compose.onNodeWithText(SUB_FOLDER).assertIsDisplayed()

        back() // storage root
        compose.waitForText(FIXTURE_NAME)
        compose.onNodeWithText(FIXTURE_NAME).assertIsDisplayed()
        assertAlive()
    }

    @Test fun textPreviewOpensAndCloses() {
        openFixture()
        click(TEXT_FILE)
        compose.waitForText(TEXT_CONTENT, substring = true)
        back()
        compose.waitForText(PDF_FILE)
        compose.onNodeWithText(TEXT_FILE).assertIsDisplayed()
        assertAlive()
    }

    /** Regression 0.2.2: leaving the PDF preview closed the document twice and crashed. */
    @Test fun pdfPreviewOpensAndClosesTwice() {
        openFixture()
        repeat(2) {
            click(PDF_FILE)
            // Subtitle "… · 1 page" appears once PdfRenderer opened the document.
            compose.waitForText("1 page", substring = true)
            back()
            compose.waitForText(TEXT_FILE)
            // Let the render executor and the disposal run before checking we are still alive.
            Thread.sleep(1_500)
            compose.waitForIdle()
            compose.onNodeWithText(PDF_FILE).assertIsDisplayed()
            assertAlive()
        }
    }
}
