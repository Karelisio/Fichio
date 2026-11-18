package com.karelisio.fichio.core.util

import com.karelisio.fichio.R
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow

/** Human readable sizes, dates and counts, in the language of the application resources. */
object Format {
    private val unitIds = intArrayOf(
        R.string.format_unit_b, R.string.format_unit_kb, R.string.format_unit_mb,
        R.string.format_unit_gb, R.string.format_unit_tb,
    )
    /** Used only when no Android resources exist (plain JVM unit tests). */
    private val fallbackUnits = arrayOf("o", "Ko", "Mo", "Go", "To")

    private fun unit(i: Int): String =
        if (Strings.resources == null) fallbackUnits[i] else Strings.get(unitIds[i])

    private val locale: Locale get() = Strings.locale

    /** Human readable size with localised units (o/Ko/Mo… in French, B/KB/MB… in English). */
    fun size(bytes: Long): String {
        if (bytes < 1024) return "$bytes ${unit(0)}"
        val exp = (ln(bytes.toDouble()) / ln(1024.0)).toInt().coerceIn(1, unitIds.size - 1)
        val value = bytes / 1024.0.pow(exp)
        val pattern = if (value >= 100) "%.0f %s" else if (value >= 10) "%.1f %s" else "%.2f %s"
        return String.format(locale, pattern, value, unit(exp))
    }

    /** Splits a size into a number and its unit, for expressive big-number displays. */
    fun sizeParts(bytes: Long): Pair<String, String> {
        val s = size(bytes)
        return s.substringBeforeLast(' ') to s.substringAfterLast(' ')
    }

    fun date(millis: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale).format(Date(millis))

    fun shortDate(millis: Long): String =
        DateFormat.getDateInstance(DateFormat.MEDIUM, locale).format(Date(millis))

    /** "à l'instant", "il y a 5 min", "hier", or a short date. */
    fun relative(millis: Long, now: Long = System.currentTimeMillis()): String {
        val diff = abs(now - millis)
        val min = 60_000L
        val hour = 60 * min
        val day = 24 * hour
        return when {
            diff < min -> Strings.get(R.string.format_just_now)
            diff < hour -> Strings.get(R.string.format_minutes_ago, (diff / min).toInt())
            diff < day -> Strings.get(R.string.format_hours_ago, (diff / hour).toInt())
            diff < 2 * day -> Strings.get(R.string.format_yesterday)
            diff < 7 * day -> Strings.get(R.string.format_days_ago, (diff / day).toInt())
            else -> shortDate(millis)
        }
    }

    /** "3 éléments" / "3 items". */
    fun items(n: Int): String = Strings.plural(R.plurals.format_items, n)

    /** A decimal number formatted for the current language (decimal comma in French). */
    fun decimal(value: Double, digits: Int = 1): String = String.format(locale, "%.${digits}f", value)

    fun duration(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.ROOT, "%d:%02d", m, s)
    }
}
