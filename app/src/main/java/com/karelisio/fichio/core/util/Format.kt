package com.karelisio.fichio.core.util

import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow

object Format {
    private val units = arrayOf("o", "Ko", "Mo", "Go", "To")

    /** Human readable size in French units (o, Ko, Mo, Go, To). */
    fun size(bytes: Long): String {
        if (bytes < 1024) return "$bytes o"
        val exp = (ln(bytes.toDouble()) / ln(1024.0)).toInt().coerceIn(1, units.size - 1)
        val value = bytes / 1024.0.pow(exp)
        val pattern = if (value >= 100) "%.0f %s" else if (value >= 10) "%.1f %s" else "%.2f %s"
        return String.format(Locale.FRANCE, pattern, value, units[exp])
    }

    /** Splits a size into a number and its unit, for expressive big-number displays. */
    fun sizeParts(bytes: Long): Pair<String, String> {
        val s = size(bytes)
        return s.substringBeforeLast(' ') to s.substringAfterLast(' ')
    }

    fun date(millis: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.FRANCE).format(Date(millis))

    fun shortDate(millis: Long): String =
        DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.FRANCE).format(Date(millis))

    /** "à l'instant", "il y a 5 min", "hier", or a short date. */
    fun relative(millis: Long, now: Long = System.currentTimeMillis()): String {
        val diff = abs(now - millis)
        val min = 60_000L
        val hour = 60 * min
        val day = 24 * hour
        return when {
            diff < min -> "à l'instant"
            diff < hour -> "il y a ${diff / min} min"
            diff < day -> "il y a ${diff / hour} h"
            diff < 2 * day -> "hier"
            diff < 7 * day -> "il y a ${diff / day} j"
            else -> shortDate(millis)
        }
    }

    fun count(n: Int, singular: String, plural: String = singular + "s"): String =
        "$n ${if (n <= 1) singular else plural}"

    fun items(n: Int) = count(n, "élément")

    fun duration(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format(Locale.FRANCE, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.FRANCE, "%d:%02d", m, s)
    }
}
