package com.karelisio.fichio

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.view.KeyEvent
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.platform.app.InstrumentationRegistry
import com.karelisio.fichio.core.util.CrashLog
import kotlinx.coroutines.runBlocking
import java.io.File

/** Shared set-up of the instrumented tests: permissions, settings, on-disk fixtures. */
object TestEnv {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    val context: Context get() = instrumentation.targetContext
    private val pkg get() = context.packageName

    /** Runs [command] as the shell user and returns its stdout. */
    fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .bufferedReader().use { it.readText() }

    /** "All files access" (appops, no settings screen) and notifications, like after onboarding. */
    fun grantPermissions() {
        shell("appops set $pkg MANAGE_EXTERNAL_STORAGE allow")
        shell("pm grant $pkg android.permission.POST_NOTIFICATIONS")
        // The tests look for the French texts: force the per-app language (Android 13+), whatever the emulator's.
        shell("cmd locale set-app-locales $pkg --locales fr")
    }

    /** Deterministic settings: no update check (network sheet), no stale crash report. */
    fun prepare(onboardingDone: Boolean) {
        grantPermissions()
        CrashLog.clear(context)
        runBlocking {
            val s = context.container.settings
            s.setOnboardingDone(onboardingDone)
            s.setAutoCheckUpdates(false)
            s.setConfirmDelete(true)
            s.setShowHidden(false)
        }
    }

    /** Sorted first in the storage root (digits before letters in the natural order). */
    const val FIXTURE_NAME = "0-fichio-tests"
    const val SUB_FOLDER = "Dossier test"
    const val TEXT_FILE = "note.txt"
    const val TEXT_CONTENT = "Bonjour depuis le test instrumenté"
    const val PDF_FILE = "document.pdf"

    /** /sdcard/0-fichio-tests/{Dossier test/dedans.txt, note.txt, document.pdf} */
    fun createFixture(): File {
        @Suppress("DEPRECATION")
        val root = File(Environment.getExternalStorageDirectory(), FIXTURE_NAME)
        root.deleteRecursively()
        File(root, SUB_FOLDER).mkdirs()
        File(root, "$SUB_FOLDER/dedans.txt").writeText("fichier dans le sous-dossier")
        File(root, TEXT_FILE).writeText("$TEXT_CONTENT\nligne 2\n")
        writePdf(File(root, PDF_FILE))
        check(File(root, PDF_FILE).length() > 0) { "fixture not written: all-files access missing?" }
        return root
    }

    fun deleteFixture() {
        @Suppress("DEPRECATION")
        File(Environment.getExternalStorageDirectory(), FIXTURE_NAME).deleteRecursively()
    }

    private fun writePdf(file: File) {
        val doc = PdfDocument()
        try {
            val page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
            page.canvas.drawColor(Color.WHITE)
            page.canvas.drawText("Fichio – page de test", 72f, 96f, Paint().apply { textSize = 24f })
            doc.finishPage(page)
            file.outputStream().use { doc.writeTo(it) }
        } finally {
            doc.close()
        }
    }

    /**
     * System back through the resumed activity's OnBackPressedDispatcher — the path a completed
     * back gesture takes (OnBackInvokedCallback → dispatcher). An injected KEYCODE_BACK is not
     * reliably routed to the app on the CI emulator with enableOnBackInvokedCallback.
     */
    fun pressBack() = instrumentation.runOnMainSync {
        val activity = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
            .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
            .firstOrNull() as? androidx.activity.ComponentActivity
        checkNotNull(activity) { "no resumed activity" }.onBackPressedDispatcher.onBackPressed()
    }

    fun ComposeTestRule.waitForText(text: String, substring: Boolean = false, timeoutMs: Long = 15_000) =
        waitUntil(timeoutMs) { onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty() }
}
