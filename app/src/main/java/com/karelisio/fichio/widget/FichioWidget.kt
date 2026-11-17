package com.karelisio.fichio.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.karelisio.fichio.FichioApp
import com.karelisio.fichio.MainActivity
import com.karelisio.fichio.domain.file.formatBytes

/** Glance widget: free-space summary plus 2–6 clickable favorite folders. */
class FichioWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val favorites = runCatching {
            (context.applicationContext as FichioApp)
                .container.database.favoriteDao().getAll()
                .take(6)
        }.getOrDefault(emptyList())

        val (free, total) = runCatching {
            val stat = StatFs(Environment.getExternalStorageDirectory().absolutePath)
            stat.availableBytes to stat.totalBytes
        }.getOrDefault(0L to 0L)

        provideContent {
            GlanceTheme {
                Column(
                    modifier = GlanceModifier
                        .fillMaxWidth()
                        .cornerRadius(28.dp)
                        .background(GlanceTheme.colors.surface)
                        .padding(16.dp)
                ) {
                    Text(
                        text = "Fichio",
                        style = TextStyle(
                            color = GlanceTheme.colors.onSurface,
                            fontWeight = FontWeight.Bold
                        )
                    )
                    Text(
                        text = "${formatBytes(free)} libre sur ${formatBytes(total)}",
                        style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant)
                    )
                    favorites.forEach { fav ->
                        val intent = Intent(context, MainActivity::class.java).apply {
                            action = Intent.ACTION_VIEW
                            data = Uri.parse("fichio://folder?path=${Uri.encode(fav.path)}")
                        }
                        Row(
                            modifier = GlanceModifier
                                .fillMaxWidth()
                                .padding(top = 8.dp)
                                .clickable(actionStartActivity(intent)),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "★",
                                style = TextStyle(color = GlanceTheme.colors.primary)
                            )
                            Text(
                                text = fav.name,
                                style = TextStyle(
                                    color = GlanceTheme.colors.onSurface,
                                    fontWeight = FontWeight.Medium
                                ),
                                modifier = GlanceModifier.padding(start = 8.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
