package com.karelisio.fichio.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Generates the baseline profile of Fichio: cold start (startup profile, also used for the dex
 * layout), the home screen and the file browser with a long list.
 *
 * `./gradlew :app:generateReleaseBaselineProfile` → app/src/release/generated/baselineProfiles/.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class BaselineProfileGenerator {
    @get:Rule val rule = BaselineProfileRule()

    @Test fun startupHomeAndBrowser() = rule.collect(
        packageName = TARGET_PACKAGE,
        includeInStartupProfile = true,
        maxIterations = 8,
        stableIterations = 3,
    ) {
        prepareDevice(device)
        pressHome()
        startActivityAndWait()
        completeOnboardingIfShown()
        homeJourney()
        browserJourney()
    }
}
