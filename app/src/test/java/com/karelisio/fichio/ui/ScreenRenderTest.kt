package com.karelisio.fichio.ui

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.karelisio.fichio.core.model.FileCategory
import com.karelisio.fichio.ui.browser.BrowserScreen
import com.karelisio.fichio.ui.home.GaugeSegment
import com.karelisio.fichio.ui.home.HomeScreen
import com.karelisio.fichio.ui.home.LivingGauge
import com.karelisio.fichio.ui.theme.FichioTheme
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Smoke rendering tests of the main screens (Robolectric, native graphics). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "fr")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenRenderTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    @Test fun homeRendersSections() {
        compose.setContent { FichioTheme(dynamicColor = false) { HomeScreen(rememberNavController()) } }
        compose.onNodeWithText("Fichio").assertIsDisplayed()
        compose.onNodeWithText("Rechercher dans vos fichiers").assertIsDisplayed()
        compose.onAllNodes(hasScrollAction())[0].performScrollToNode(hasText("Catégories"))
        compose.onNodeWithText("Catégories").assertIsDisplayed()
    }

    @Test fun livingGaugeShowsUsedSpace() {
        compose.setContent {
            FichioTheme(dynamicColor = false) {
                LivingGauge(
                    total = 128L shl 30, used = 64L shl 30,
                    segments = listOf(
                        GaugeSegment("IMAGES", 20L shl 30, FileCategory.IMAGES),
                        GaugeSegment("VIDEOS", 30L shl 30, FileCategory.VIDEOS),
                        GaugeSegment("other", 14L shl 30, null),
                    ),
                    modifier = Modifier.size(300.dp),
                )
            }
        }
        compose.onNodeWithText("utilisés sur", substring = true).assertIsDisplayed()
        compose.onNodeWithText("libres", substring = true).assertIsDisplayed()
    }

    @Test fun browserListsFolderAndSelects() {
        val dir = tmp.newFolder("Racine")
        dir.resolve("Vacances").mkdirs()
        dir.resolve("notes.txt").writeText("bonjour")
        dir.resolve("photo.jpg").writeBytes(ByteArray(10))
        compose.setContent { FichioTheme(dynamicColor = false) { BrowserScreen(rememberNavController(), dir.absolutePath, false) } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("notes.txt").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Vacances").assertIsDisplayed()
        compose.onNodeWithText("photo.jpg").assertIsDisplayed()
        compose.onNodeWithText("notes.txt").performTouchInput { longClick() }
        compose.waitUntil(3_000) { compose.onAllNodesWithText("sélectionné", substring = true).fetchSemanticsNodes().isNotEmpty() }
    }
}
