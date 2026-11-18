package com.karelisio.fichio.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.karelisio.fichio.analysis.AnalysisScreen
import com.karelisio.fichio.core.model.FileCategory
import com.karelisio.fichio.onboarding.OnboardingScreen
import com.karelisio.fichio.preview.PreviewScreen
import com.karelisio.fichio.search.SearchScreen
import com.karelisio.fichio.settings.PermissionsScreen
import com.karelisio.fichio.settings.SettingsScreen
import com.karelisio.fichio.transfer.TransferScreen
import com.karelisio.fichio.trash.TrashScreen
import com.karelisio.fichio.ui.category.CategoryScreen
import com.karelisio.fichio.ui.theme.FichioTheme
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Smoke rendering tests of the secondary screens, in French (default language). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "fr")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MoreScreensRenderTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    private fun render(content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent { FichioTheme(dynamicColor = false) { content() } }
    }

    private fun waitForText(text: String, substring: Boolean = false) {
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun settingsShowsGroups() {
        render { SettingsScreen(onBack = {}, onOpenPermissions = {}) }
        compose.onNodeWithText("Réglages").assertIsDisplayed()
        compose.onNodeWithText("Apparence").assertIsDisplayed()
        compose.onNodeWithText("Couleurs dynamiques").assertIsDisplayed()
        // Group titles are announced as headings.
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading).and(hasTextMatcher("Apparence"))).assertExists()
    }

    @Test fun permissionsListsFileAccess() {
        render { PermissionsScreen(onBack = {}) }
        compose.onNodeWithText("Autorisations").assertIsDisplayed()
        compose.onNodeWithText("Accès à tous les fichiers").assertExists()
    }

    @Test fun onboardingFirstPage() {
        render { OnboardingScreen(onFinished = {}) }
        compose.onNodeWithText("Bienvenue dans Fichio").assertIsDisplayed()
        compose.onNodeWithText("Passer").assertIsDisplayed()
        compose.onNodeWithText("Suivant").assertIsDisplayed()
    }

    @Test fun emptyTrash() {
        render { TrashScreen(onBack = {}) }
        waitForText("La corbeille est vide")
        compose.onNodeWithText("La corbeille est vide").assertIsDisplayed()
        compose.onNodeWithText("Corbeille").assertIsDisplayed()
    }

    @Test fun searchFieldAndChips() {
        render { SearchScreen(scopePath = null, onOpen = {}, onBack = {}) }
        compose.onNodeWithText("Rechercher des fichiers").assertIsDisplayed()
        compose.onNodeWithText("Type").assertIsDisplayed()
        compose.onNodeWithText("Taille").assertIsDisplayed()
        compose.onNodeWithText("Date").assertIsDisplayed()
    }

    @Test fun analysisInitialState() {
        render { AnalysisScreen(onBack = {}, onOpenFolder = {}) }
        compose.onNodeWithText("Analyse du stockage").assertIsDisplayed()
    }

    @Test fun transferStopped() {
        render { TransferScreen(onBack = {}) }
        compose.onNodeWithText("Transfert local").assertIsDisplayed()
        compose.waitUntil(5_000) {
            listOf("Démarrer le transfert", "Pas de Wi-Fi").any { compose.onAllNodesWithText(it).fetchSemanticsNodes().isNotEmpty() }
        }
    }

    @Test fun textPreviewShowsContent() {
        val file = tmp.newFile("notes.txt").apply { writeText("Bonjour Fichio\nDeuxième ligne") }
        render { PreviewScreen(file.absolutePath, onBack = {}, onEdit = {}, onShowInFolder = {}) }
        waitForText("Bonjour Fichio", substring = true)
        compose.onAllNodesWithText("notes.txt")[0].assertExists()
        compose.onNodeWithText("Bonjour Fichio", substring = true).assertExists()
    }

    @Test fun categoryScreenEmpty() {
        render { CategoryScreen(rememberNavController(), FileCategory.DOCUMENTS) }
        compose.onAllNodesWithText("Documents")[0].assertIsDisplayed()
        waitForText("Aucun fichier")
        compose.onNodeWithText("Aucun fichier").assertExists()
    }

    private fun hasTextMatcher(text: String) = androidx.compose.ui.test.hasText(text)
}

/** The same screens in English (per-app language set to English). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "en")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EnglishRenderTest {
    @get:Rule val compose = createComposeRule()

    @Test fun settingsInEnglish() {
        compose.setContent { FichioTheme(dynamicColor = false) { SettingsScreen(onBack = {}, onOpenPermissions = {}) } }
        compose.onNodeWithText("Settings").assertIsDisplayed()
        compose.onNodeWithText("Appearance").assertIsDisplayed()
        compose.onNodeWithText("Dynamic colours").assertIsDisplayed()
        compose.onAllNodesWithText("Réglages").assertCountEquals(0)
    }

    @Test fun homeInEnglish() {
        compose.setContent { FichioTheme(dynamicColor = false) { com.karelisio.fichio.ui.home.HomeScreen(rememberNavController()) } }
        compose.onNodeWithText("Search your files").assertIsDisplayed()
    }

    @Test fun sizesUseEnglishUnits() {
        org.junit.Assert.assertEquals("1.50 MB", com.karelisio.fichio.core.util.Format.size(1_572_864))
        org.junit.Assert.assertEquals("3 items", com.karelisio.fichio.core.util.Format.items(3))
    }
}

private fun androidx.compose.ui.test.SemanticsNodeInteractionCollection.assertCountEquals(n: Int) {
    org.junit.Assert.assertEquals(n, fetchSemanticsNodes().size)
}
