package com.karelisio.fichio.core.util

import android.content.Context
import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import java.util.Locale

/**
 * Localised text for code that has no Compose scope nor a handy Context (engine, backends,
 * exceptions, view models). Reads the application resources on every call so that a change of
 * the per-app language (Android 13+) is followed without restarting the process.
 *
 * Outside Android (plain JVM unit tests) there are no resources: the text then falls back to
 * a neutral `#id` marker so that nothing crashes.
 */
object Strings {
    @Volatile private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext ?: context
    }

    val resources: Resources? get() = appContext?.resources

    /** Locale of the application resources (falls back to the JVM default). */
    val locale: Locale
        get() = resources?.configuration?.locales?.takeIf { !it.isEmpty }?.get(0) ?: Locale.getDefault()

    fun get(@StringRes id: Int, vararg args: Any?): String {
        val r = resources ?: return fallback(id, args)
        return if (args.isEmpty()) r.getString(id) else r.getString(id, *args)
    }

    fun plural(@PluralsRes id: Int, count: Int, vararg args: Any?): String {
        val r = resources ?: return fallback(id, arrayOf(count, *args))
        return if (args.isEmpty()) r.getQuantityString(id, count, count) else r.getQuantityString(id, count, *args)
    }

    private fun fallback(id: Int, args: Array<out Any?>): String =
        if (args.isEmpty()) "#$id" else "#$id " + args.joinToString(" ")
}
