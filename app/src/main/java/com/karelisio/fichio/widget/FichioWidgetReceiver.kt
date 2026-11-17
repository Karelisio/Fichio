package com.karelisio.fichio.widget

import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/** Home-screen widget receiver; refreshed by updatePeriodMillis (30 min) and [FichioWidget.updateAll]. */
class FichioWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = FichioWidget()
}
