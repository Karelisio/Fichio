package com.karelisio.fichio.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.toPath
import com.karelisio.fichio.core.model.FileCategory
import com.karelisio.fichio.core.util.Format
import com.karelisio.fichio.ui.components.AnimatedCounter
import com.karelisio.fichio.ui.theme.GaugeNumberStyle
import com.karelisio.fichio.ui.theme.LocalReduceMotion
import com.karelisio.fichio.ui.theme.polygon
import com.karelisio.fichio.ui.theme.toneFor
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

@Immutable
data class GaugeSegment(val id: String, val bytes: Long, val category: FileCategory?)

/** Animated state of one ring segment: angles + shape morph progress. */
private class SegmentAnim(start: Float) {
    val start = Animatable(start)
    val sweep = Animatable(0f)
    val morph = Animatable(1f)
    var from: RoundedPolygon = MaterialShapes.Circle
    var to: RoundedPolygon = MaterialShapes.Circle
    var morphObj: Morph? = null

    fun retarget(next: RoundedPolygon) {
        from = to; to = next
        morphObj = runCatching { Morph(from.normalizedSafe(), to.normalizedSafe()) }.getOrNull()
    }
}

/**
 * Signature animation #3 — the living gauge. A ring whose per-type segments are laid out by
 * size; when space changes they slide around the ring and resize on springs, and each one
 * carries a "bead" in its category's MaterialShape that morphs (circle → shape) as it settles.
 * The centre shows the used space with expressive big numerals.
 */
@Composable
fun LivingGauge(
    total: Long,
    used: Long,
    segments: List<GaugeSegment>,
    modifier: Modifier = Modifier,
) {
    val reduce = LocalReduceMotion.current
    val scheme = MaterialTheme.colorScheme
    val anims = remember { HashMap<String, SegmentAnim>() }
    var version by remember { mutableStateOf(0) }

    // Order by size: the biggest first, starting at 12 o'clock. Others/free at the end.
    val ordered = remember(segments) { segments.filter { it.bytes > 0 }.sortedByDescending { if (it.category == null) -1 else it.bytes } }

    LaunchedEffect(ordered, total) {
        if (total <= 0) return@LaunchedEffect
        val gap = 3.2f
        var angle = 0f
        coroutineScope {
            ordered.forEachIndexed { i, seg ->
                val raw = seg.bytes.toFloat() / total * 360f
                val sweep = (raw - gap).coerceAtLeast(if (raw > 0.6f) 0.6f else 0f)
                val anim = anims.getOrPut(seg.id) { SegmentAnim(angle) }
                val targetStart = angle + gap / 2
                anim.retarget(seg.category?.polygon() ?: MaterialShapes.Circle)
                launch {
                    if (!reduce) delay(i * 45L)
                    val s = spring<Float>(dampingRatio = 0.62f, stiffness = Spring.StiffnessLow)
                    if (reduce) { anim.start.snapTo(targetStart); anim.sweep.snapTo(sweep); anim.morph.snapTo(1f) }
                    else {
                        launch { anim.start.animateTo(targetStart, s) }
                        launch { anim.sweep.animateTo(sweep, s) }
                        launch { anim.morph.snapTo(0f); anim.morph.animateTo(1f, spring(0.5f, Spring.StiffnessVeryLow)) }
                    }
                }
                angle += raw
            }
            // Segments that disappeared shrink away.
            anims.keys.filter { k -> ordered.none { it.id == k } }.forEach { k ->
                launch { anims[k]?.sweep?.animateTo(0f, spring(0.8f, Spring.StiffnessMediumLow)) }
            }
        }
        version++
    }

    val (num, unit) = Format.sizeParts(used)
    Box(modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxWidth().aspectRatio(1f)) {
            @Suppress("UNUSED_EXPRESSION") version
            val stroke = size.minDimension * 0.085f
            val radius = size.minDimension / 2 - stroke
            val topLeft = Offset(center.x - radius, center.y - radius)
            val arcSize = androidx.compose.ui.geometry.Size(radius * 2, radius * 2)
            // Track (free space).
            drawCircle(scheme.surfaceContainerHighest, radius, center, style = Stroke(stroke))
            anims.forEach { (id, a) ->
                val seg = segments.firstOrNull { it.id == id }
                val color = seg?.category?.let { scheme.toneFor(it).content } ?: scheme.outline
                if (a.sweep.value <= 0.05f) return@forEach
                drawArc(
                    color = color,
                    startAngle = a.start.value - 90f,
                    sweepAngle = a.sweep.value,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
            // Beads: category shapes sitting on large-enough segments.
            anims.forEach { (id, a) ->
                val seg = segments.firstOrNull { it.id == id } ?: return@forEach
                val cat = seg.category ?: return@forEach
                if (a.sweep.value < 22f) return@forEach
                val mid = Math.toRadians((a.start.value + a.sweep.value / 2 - 90f).toDouble())
                val c = Offset(center.x + radius * cos(mid).toFloat(), center.y + radius * sin(mid).toFloat())
                val bead = stroke * 1.55f
                drawBead(a, c, bead, scheme.toneFor(cat).container, a.morph.value * 40f)
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.Bottom) {
                AnimatedCounter(num, style = GaugeNumberStyle.copy(color = scheme.onSurface))
                Text(" $unit", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold), color = scheme.onSurface)
            }
            Text(
                "utilisés sur ${Format.size(total)}",
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                color = scheme.onSurfaceVariant,
            )
            Text(
                "${Format.size((total - used).coerceAtLeast(0))} libres",
                style = MaterialTheme.typography.labelLarge,
                color = scheme.primary,
            )
        }
    }
}

private val scratch = android.graphics.Path()

private fun DrawScope.drawBead(a: SegmentAnim, c: Offset, size: Float, color: Color, rotation: Float) {
    val morph = a.morphObj
    scratch.rewind()
    if (morph != null) morph.toPath(a.morph.value.coerceIn(0f, 1f), scratch) else a.to.normalizedSafe().toPath(scratch)
    val path: Path = scratch.asComposePath()
    val m = Matrix().apply { scale(size, size) }
    path.transform(m)
    translate(c.x - size / 2, c.y - size / 2) {
        rotate(rotation, Offset(size / 2, size / 2)) { drawPath(path, color) }
    }
}

private val normalizedCache = HashMap<RoundedPolygon, RoundedPolygon>()
private fun RoundedPolygon.normalizedSafe(): RoundedPolygon = normalizedCache.getOrPut(this) { normalized() }
