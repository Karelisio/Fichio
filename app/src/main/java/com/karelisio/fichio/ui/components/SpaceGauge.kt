package com.karelisio.fichio.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.karelisio.fichio.domain.file.CategoryUsage
import com.karelisio.fichio.domain.file.StorageStats

/**
 * The "living gauge": an animated ring showing used vs free space, with the
 * used portion split into per-category segments that reorganize with a spring.
 */
@Composable
fun SpaceGauge(
    stats: StorageStats?,
    categories: List<CategoryUsage>,
    modifier: Modifier = Modifier,
    diameter: Dp = 220.dp
) {
    val targetFraction = stats?.usedFraction ?: 0f
    val animatedFraction by animateFloatAsState(
        targetValue = targetFraction,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "gauge"
    )

    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val usedColor = MaterialTheme.colorScheme.primary

    Box(modifier = modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = 22.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            val topLeft = Offset(inset, inset)

            // Free track (full circle).
            drawArc(
                color = trackColor,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )

            // Used portion split by category.
            val usedSweep = 360f * animatedFraction
            if (usedSweep > 0f) {
                val totalBytes = categories.sumOf { it.bytes }.toFloat()
                if (totalBytes > 0f) {
                    var start = -90f
                    for (cat in categories) {
                        val sweep = usedSweep * (cat.bytes / totalBytes)
                        if (sweep > 0.5f) {
                            drawArc(
                                color = cat.category.tint(),
                                startAngle = start,
                                sweepAngle = sweep - 2f, // small gap between segments
                                useCenter = false,
                                topLeft = topLeft,
                                size = arcSize,
                                style = Stroke(width = stroke, cap = StrokeCap.Round)
                            )
                            start += sweep
                        }
                    }
                } else {
                    drawArc(
                        color = usedColor,
                        startAngle = -90f,
                        sweepAngle = usedSweep,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = stroke, cap = StrokeCap.Round)
                    )
                }
            }
        }
    }
}

