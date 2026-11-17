package com.karelisio.fichio.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.os.StatFs
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.createBitmap
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll as glanceUpdateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.material3.ColorProviders
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.karelisio.fichio.MainActivity
import com.karelisio.fichio.R
import com.karelisio.fichio.container
import com.karelisio.fichio.core.fs.StoragePaths
import com.karelisio.fichio.core.util.Format
import com.karelisio.fichio.shortcuts.Shortcuts
import com.karelisio.fichio.ui.theme.SlateDark
import com.karelisio.fichio.ui.theme.SlateLight
import kotlinx.coroutines.launch

/** Snapshot rendered by the widget. */
private data class WidgetData(
    val free: Long,
    val total: Long,
    val favorites: List<Pair<String, String>>,
) {
    val usedFraction: Float get() = if (total <= 0) 0f else ((total - free).toFloat() / total).coerceIn(0f, 1f)
}

/** Glance widget: free-space ring + 2–6 clickable favourite folders depending on its size. */
class FichioWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Responsive(setOf(SMALL, WIDE, MEDIUM, LARGE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = load(context)
        provideContent {
            GlanceTheme(colors = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) GlanceTheme.colors else fallbackColors) {
                Content(data)
            }
        }
    }

    private suspend fun load(context: Context): WidgetData {
        val favorites = runCatching {
            context.container.database.favorites().all().map { it.path to it.label.ifBlank { StoragePaths.name(it.path) } }
        }.getOrDefault(emptyList())
        val (free, total) = runCatching {
            val stat = StatFs(StoragePaths.primary)
            stat.availableBytes to stat.totalBytes
        }.getOrDefault(0L to 0L)
        return WidgetData(free, total, favorites)
    }

    companion object {
        val SMALL = DpSize(110.dp, 110.dp)
        val WIDE = DpSize(250.dp, 110.dp)
        val MEDIUM = DpSize(180.dp, 180.dp)
        val LARGE = DpSize(300.dp, 250.dp)

        private val fallbackColors = ColorProviders(light = SlateLight, dark = SlateDark)

        /** Re-renders every placed instance (call after favourites/operations change). */
        fun updateAll(context: Context) {
            val app = context.applicationContext
            app.container.appScope.launch { runCatching { FichioWidget().glanceUpdateAll(app) } }
        }

        /** Suspending variant. */
        suspend fun refresh(context: Context) {
            runCatching { FichioWidget().glanceUpdateAll(context.applicationContext) }
        }
    }
}

@Composable
private fun Content(data: WidgetData) {
    val size = LocalSize.current
    val context = LocalContext.current
    val favCount = when {
        size.width >= LARGE_W && size.height >= LARGE_H -> 6
        size.height >= MEDIUM_H -> 4
        size.width >= WIDE_W -> 2
        else -> 0
    }
    val favs = data.favorites.take(favCount)
    val homeIntent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    // API 31+: system corner radius; API 30: rounded drawable (cornerRadius is a no-op there).
    val rounded = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        GlanceModifier.fillMaxSize().appWidgetBackground().background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(android.R.dimen.system_app_widget_background_radius)
    } else {
        GlanceModifier.fillMaxSize().appWidgetBackground().background(ImageProvider(R.drawable.widget_background))
    }

    Box(rounded.padding(12.dp)) {
        when {
            favCount == 0 -> Column(
                GlanceModifier.fillMaxSize().clickable(actionStartActivity(homeIntent)),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Gauge(data, ringDp = (minOf(size.width.value, size.height.value) - 24).coerceIn(56f, 120f).toInt(), compact = true)
            }
            size.height < MEDIUM_H -> Row(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                Box(GlanceModifier.clickable(actionStartActivity(homeIntent))) { GaugeRow(data, ringDp = 72) }
                Spacer(GlanceModifier.width(10.dp))
                Column(GlanceModifier.defaultWeight()) { FavoriteList(favs, columns = 1) }
            }
            else -> Column(GlanceModifier.fillMaxSize()) {
                Box(GlanceModifier.fillMaxWidth().clickable(actionStartActivity(homeIntent))) { GaugeRow(data, ringDp = if (favCount >= 6) 76 else 64) }
                Spacer(GlanceModifier.height(10.dp))
                FavoriteList(favs, columns = if (favCount >= 6 && size.width >= LARGE_W) 2 else 1)
            }
        }
    }
}

private val WIDE_W = 240.dp
private val MEDIUM_H = 170.dp
private val LARGE_W = 290.dp
private val LARGE_H = 240.dp

/** Ring + big "X Go libres" next to it. */
@Composable
private fun GaugeRow(data: WidgetData, ringDp: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Ring(data, ringDp)
        Spacer(GlanceModifier.width(12.dp))
        Column {
            val (num, unit) = Format.sizeParts(data.free)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(num, style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 30.sp, fontWeight = FontWeight.Bold))
                Text(" $unit", style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Medium))
            }
            Text("libres sur ${Format.size(data.total)}", style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp))
        }
    }
}

/** Small layout: ring with the free amount inside. */
@Composable
private fun Gauge(data: WidgetData, ringDp: Int, compact: Boolean) {
    Box(contentAlignment = Alignment.Center) {
        Ring(data, ringDp)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val (num, unit) = Format.sizeParts(data.free)
            Text(num, style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = if (compact) 20.sp else 26.sp, fontWeight = FontWeight.Bold))
            Text("$unit libres", style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp))
        }
    }
}

@Composable
private fun Ring(data: WidgetData, ringDp: Int) {
    val context = LocalContext.current
    val primary = GlanceTheme.colors.primary.getColor(context).toArgb()
    val track = GlanceTheme.colors.secondaryContainer.getColor(context).toArgb()
    val bitmap = ringBitmap(context, ringDp, data.usedFraction, primary, track)
    Image(
        provider = ImageProvider(bitmap),
        contentDescription = "${(data.usedFraction * 100).toInt()} % utilisés",
        modifier = GlanceModifier.size(ringDp.dp),
    )
}

@Composable
private fun FavoriteList(favs: List<Pair<String, String>>, columns: Int) {
    val context = LocalContext.current
    if (favs.isEmpty()) {
        Text(
            "Ajoutez des favoris dans Fichio pour les retrouver ici",
            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp),
        )
        return
    }
    val rows = favs.chunked(columns)
    Column(GlanceModifier.fillMaxWidth()) {
        rows.forEachIndexed { r, row ->
            if (r > 0) Spacer(GlanceModifier.height(6.dp))
            Row(GlanceModifier.fillMaxWidth()) {
                row.forEachIndexed { i, (path, label) ->
                    if (i > 0) Spacer(GlanceModifier.width(6.dp))
                    Row(
                        GlanceModifier.defaultWeight().height(40.dp)
                            .cornerRadius(20.dp)
                            .background(GlanceTheme.colors.secondaryContainer)
                            .clickable(actionStartActivity(Shortcuts.folderIntent(context, path)))
                            .padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Image(
                            provider = ImageProvider(R.drawable.ic_widget_folder),
                            contentDescription = null,
                            modifier = GlanceModifier.size(20.dp),
                            colorFilter = androidx.glance.ColorFilter.tint(GlanceTheme.colors.onSecondaryContainer),
                        )
                        Spacer(GlanceModifier.width(8.dp))
                        Text(
                            label, maxLines = 1,
                            style = TextStyle(color = GlanceTheme.colors.onSecondaryContainer, fontSize = 13.sp, fontWeight = FontWeight.Medium),
                        )
                    }
                }
                // Keep the grid aligned when the last row is incomplete.
                repeat(columns - row.size) {
                    Spacer(GlanceModifier.width(6.dp))
                    Spacer(GlanceModifier.defaultWeight())
                }
            }
        }
    }
}

/** Draws the usage ring with Android's Canvas (Glance cannot draw arcs itself). */
private fun ringBitmap(context: Context, sizeDp: Int, fraction: Float, color: Int, track: Int): Bitmap {
    val d = context.resources.displayMetrics.density
    val px = (sizeDp * d).toInt().coerceIn(48, 480)
    val bmp = createBitmap(px, px)
    val c = Canvas(bmp)
    val stroke = px * 0.13f
    val inset = stroke / 2f + 1f
    val rect = RectF(inset, inset, px - inset, px - inset)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        strokeCap = Paint.Cap.ROUND
    }
    paint.color = track
    c.drawArc(rect, 0f, 360f, false, paint)
    val sweep = 360f * fraction
    if (sweep > 0.5f) {
        paint.color = if (fraction > 0.9f) ColorUtils.blendARGB(color, 0xFFBA1A1A.toInt(), 0.6f) else color
        c.drawArc(rect, -90f, sweep.coerceAtMost(359.5f), false, paint)
    }
    return bmp
}
