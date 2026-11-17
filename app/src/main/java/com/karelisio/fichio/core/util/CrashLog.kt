package com.karelisio.fichio.core.util

import android.content.Context
import android.os.Build
import com.karelisio.fichio.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Local crash journal (never sent anywhere): the last fatal or background error is written to
 * the app's private files and shown on next launch so it can be copied / shared by the user.
 */
object CrashLog {
    private fun file(context: Context) = File(context.filesDir, "last_crash.txt")

    fun write(context: Context, thread: String, error: Throwable, fatal: Boolean) {
        val sw = StringWriter()
        error.printStackTrace(PrintWriter(sw))
        val text = buildString {
            appendLine(if (fatal) "Plantage" else "Erreur en arrière-plan")
            appendLine("Fichio ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Thread : $thread · ${Format.date(System.currentTimeMillis())}")
            appendLine()
            append(sw.toString().take(24_000))
        }
        // Keep the first fatal crash rather than a later background error.
        val f = file(context)
        if (!fatal && f.exists() && f.readText().startsWith("Plantage")) return
        f.writeText(text)
    }

    fun read(context: Context): String? = file(context).takeIf { it.exists() }?.readText()?.takeIf { it.isNotBlank() }

    fun clear(context: Context) { file(context).delete() }
}
