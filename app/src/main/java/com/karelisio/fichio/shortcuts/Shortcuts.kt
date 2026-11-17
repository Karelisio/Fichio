package com.karelisio.fichio.shortcuts

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.IconCompat
import com.karelisio.fichio.MainActivity
import com.karelisio.fichio.container
import com.karelisio.fichio.widget.FichioWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue

/** App shortcuts + pinned folder shortcuts on the launcher. */
object Shortcuts {
    const val EXTRA_PATH = "com.karelisio.fichio.extra.PATH"
    const val EXTRA_DESTINATION = "com.karelisio.fichio.extra.DESTINATION"

    private const val MAX_DYNAMIC = 4

    /** Background/foreground pairs for generated folder icons (slate-blue family + accents). */
    private val palette = listOf(
        0xFF3F5F90.toInt() to 0xFFD6E3FF.toInt(),
        0xFF6F5576.toInt() to 0xFFF9D8FF.toInt(),
        0xFF2E6B5A.toInt() to 0xFFB8F0DA.toInt(),
        0xFF8A5A1E.toInt() to 0xFFFFDDB8.toInt(),
        0xFF555F71.toInt() to 0xFFD9E3F8.toInt(),
        0xFF94435A.toInt() to 0xFFFFD9E1.toInt(),
    )

    /** Intent opening [path] in the browser; unique data so launchers/PendingIntents don't merge them. */
    fun folderIntent(context: Context, path: String): Intent =
        Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.Builder().scheme("fichio").authority("folder").appendQueryParameter("path", path).build()
            putExtra(EXTRA_PATH, path)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    private fun shortcutId(path: String) = "folder:" + path.hashCode().toUInt().toString(16)

    private fun build(context: Context, path: String, label: String, rank: Int = 0): ShortcutInfoCompat {
        val name = label.ifBlank { path.substringAfterLast('/').ifEmpty { "Dossier" } }
        return ShortcutInfoCompat.Builder(context, shortcutId(path))
            .setShortLabel(name.take(24))
            .setLongLabel(name)
            .setIcon(IconCompat.createWithAdaptiveBitmap(folderIcon(context, path, name)))
            .setIntent(folderIntent(context, path))
            .setRank(rank)
            .build()
    }

    /** Asks the launcher to pin a shortcut that opens [path]. */
    fun pinFolder(context: Context, path: String, label: String): Boolean {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) return false
        return runCatching { ShortcutManagerCompat.requestPinShortcut(context, build(context, path, label), null) }
            .getOrDefault(false)
    }

    /** Refreshes dynamic shortcuts (favourites): list of (path, label). */
    fun refreshDynamic(context: Context, favorites: List<Pair<String, String>>) {
        runCatching {
            val max = ShortcutManagerCompat.getMaxShortcutCountPerActivity(context).coerceAtMost(MAX_DYNAMIC).coerceAtLeast(1)
            val list = favorites.take(max).mapIndexed { i, (path, label) -> build(context, path, label, i) }
            ShortcutManagerCompat.setDynamicShortcuts(context, list)
        }
    }

    /**
     * Keeps dynamic shortcuts and the home-screen widget in sync with the favourites table.
     * Call once at app start (e.g. from FichioApp/MainActivity) with an app-lifetime scope.
     */
    fun observeFavorites(context: Context, scope: CoroutineScope): Job {
        val app = context.applicationContext
        return scope.launch(Dispatchers.Default) {
            app.container.favorites.favorites
                .map { list -> list.map { it.path to it.label } }
                .distinctUntilChanged()
                .debounce(400)
                .collect { favs ->
                    refreshDynamic(app, favs)
                    runCatching { FichioWidget.updateAll(app) }
                }
        }
    }

    /** 108dp adaptive bitmap: tinted background, rounded folder silhouette and the folder initial. */
    private fun folderIcon(context: Context, path: String, label: String): Bitmap {
        val density = context.resources.displayMetrics.density
        val px = (108 * density).toInt().coerceAtLeast(108)
        val (bg, fg) = palette[path.hashCode().absoluteValue % palette.size]
        val bmp = createBitmap(px, px)
        val c = Canvas(bmp)
        c.drawColor(bg)
        val s = px / 108f
        // Safe zone is the centre 66dp: draw the folder within ~[30, 78].
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fg }
        val tab = Path().apply {
            addRoundRect(RectF(30 * s, 34 * s, 54 * s, 46 * s), 4 * s, 4 * s, Path.Direction.CW)
        }
        c.drawPath(tab, paint)
        c.drawRoundRect(RectF(30 * s, 39 * s, 78 * s, 74 * s), 7 * s, 7 * s, paint)
        val initial = label.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString()
        if (initial != null) {
            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = bg
                textSize = 22 * s
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            val y = 57 * s - (text.descent() + text.ascent()) / 2
            c.drawText(initial, 54 * s, y, text)
        } else {
            paint.color = Color.argb(60, 0, 0, 0)
            c.drawRoundRect(RectF(30 * s, 46 * s, 78 * s, 48 * s), s, s, paint)
        }
        return bmp
    }
}
