package com.karelisio.fichio

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.karelisio.fichio.TestEnv.waitForText
import com.karelisio.fichio.core.util.CrashLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Cold start of MainActivity on a real device: the release workflow depends on it. */
@RunWith(AndroidJUnit4::class)
@LargeTest
class ColdStartTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun firstLaunchShowsOnboarding() {
        TestEnv.prepare(onboardingDone = false)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.waitForText("Bienvenue dans Fichio")
            compose.onNodeWithText("Bienvenue dans Fichio").assertIsDisplayed()
            compose.onNodeWithText("Passer").assertIsDisplayed()
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
        }
        assertNull("a crash was recorded", CrashLog.read(TestEnv.context))
    }

    @Test fun launchAfterOnboardingShowsHome() {
        TestEnv.prepare(onboardingDone = true)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.waitForText("Rechercher dans vos fichiers")
            compose.onNodeWithText("Rechercher dans vos fichiers").assertIsDisplayed()
            compose.waitForText("Stockage interne")
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
        }
        assertNull("a crash was recorded", CrashLog.read(TestEnv.context))
    }
}
