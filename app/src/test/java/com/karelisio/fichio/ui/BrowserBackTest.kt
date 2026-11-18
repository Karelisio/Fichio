package com.karelisio.fichio.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.karelisio.fichio.ui.browser.BrowserScreen
import com.karelisio.fichio.ui.theme.FichioTheme
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** System back inside the browser goes up one folder (predictive-back handler, non-gesture path). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BrowserBackTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val tmp = TemporaryFolder()

    private fun waitText(t: String) = compose.waitUntil(5_000) { compose.onAllNodesWithText(t).fetchSemanticsNodes().isNotEmpty() }

    @Test fun backGoesUpOneFolder() {
        val root = tmp.newFolder("Racine")
        root.resolve("Sous").mkdirs()
        root.resolve("Sous/dedans.txt").writeText("x")
        root.resolve("dehors.txt").writeText("y")
        compose.setContent { FichioTheme(dynamicColor = false) { BrowserScreen(rememberNavController(), root.absolutePath, false) } }
        waitText("dehors.txt")
        compose.onNodeWithText("Sous").performClick()
        waitText("dedans.txt")
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        waitText("dehors.txt")
    }

    /** Leaving and re-entering the browser composition (e.g. back from a preview) keeps the folder. */
    @Test fun reenteringKeepsCurrentFolder() {
        val root = tmp.newFolder("Racine2")
        root.resolve("Sous").mkdirs()
        root.resolve("Sous/dedans.txt").writeText("x")
        root.resolve("dehors.txt").writeText("y")
        var shown by androidx.compose.runtime.mutableStateOf(true)
        compose.setContent {
            FichioTheme(dynamicColor = false) {
                val nav = rememberNavController()
                if (shown) BrowserScreen(nav, root.absolutePath, false) else androidx.compose.material3.Text("aperçu")
            }
        }
        waitText("dehors.txt")
        compose.onNodeWithText("Sous").performClick()
        waitText("dedans.txt")
        shown = false
        waitText("aperçu")
        shown = true
        waitText("dedans.txt")
    }
}
