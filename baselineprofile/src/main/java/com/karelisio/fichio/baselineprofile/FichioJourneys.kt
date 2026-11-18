package com.karelisio.fichio.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until

/** Release-like build profiled by the plugin (nonMinifiedRelease / benchmarkRelease: no suffix). */
const val TARGET_PACKAGE = "com.karelisio.fichio"

/** Folder with enough entries to exercise the lazy list, fast scroller and child counts. */
private const val FIXTURE = "/sdcard/0-fichio-profil"
private const val TIMEOUT = 10_000L

/** All-files access + notifications granted from the shell, and a big folder to scroll. */
fun prepareDevice(device: UiDevice) {
    device.executeShellCommand("appops set $TARGET_PACKAGE MANAGE_EXTERNAL_STORAGE allow")
    device.executeShellCommand("pm grant $TARGET_PACKAGE android.permission.POST_NOTIFICATIONS")
    // The journeys look for the French texts: force the per-app language (Android 13+).
    device.executeShellCommand("cmd locale set-app-locales $TARGET_PACKAGE --locales fr")
    if (device.executeShellCommand("ls $FIXTURE/f1999.txt").isBlank()) {
        device.executeShellCommand("mkdir -p $FIXTURE/Dossier")
        // UiAutomation does not run a shell: no loops/pipes, so batch the names per command.
        (0 until 2000).chunked(200).forEach { chunk ->
            device.executeShellCommand("touch " + chunk.joinToString(" ") { "$FIXTURE/f$it.txt" })
        }
    }
}

/** First launch only: walks the onboarding ("Passer", "Suivant"…, "Commencer"). */
fun MacrobenchmarkScope.completeOnboardingIfShown() {
    if (!device.wait(Until.hasObject(By.text("Passer")), 3_000)) return
    device.findObject(By.text("Passer"))?.click()
    repeat(12) {
        device.waitForIdle()
        device.findObject(By.text("Commencer"))?.let { it.click(); return }
        device.wait(Until.findObject(By.text("Suivant")), TIMEOUT)?.click()
    }
}

/** Home: wait for the gauge screen and scroll through its sections. */
fun MacrobenchmarkScope.homeJourney() {
    device.wait(Until.hasObject(By.text("Rechercher dans vos fichiers")), TIMEOUT)
    device.findObject(By.scrollable(true))?.let { list ->
        list.setGestureMargin(device.displayWidth / 8)
        list.fling(Direction.DOWN)
        device.waitForIdle()
        list.fling(Direction.UP)
        device.waitForIdle()
    }
}

/** Browser: internal storage, then the 2 000-entry fixture folder, flung both ways. */
fun MacrobenchmarkScope.browserJourney() {
    device.wait(Until.findObject(By.text("Stockage interne")), TIMEOUT)?.click()
    device.wait(Until.findObject(By.text("0-fichio-profil")), TIMEOUT)?.click()
    device.wait(Until.hasObject(By.text("Dossier")), TIMEOUT)
    device.findObject(By.scrollable(true))?.let { list ->
        list.setGestureMargin(device.displayWidth / 8)
        repeat(3) { list.fling(Direction.DOWN); device.waitForIdle() }
        repeat(4) { list.fling(Direction.UP); device.waitForIdle() }
    }
    // Open a sub-folder (drawer animation) and come back folder by folder.
    device.findObject(By.text("Dossier"))?.let { it.click(); device.waitForIdle() }
    device.pressBack()
    device.waitForIdle()
    device.pressBack()
    device.waitForIdle()
}
