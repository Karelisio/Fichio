package com.karelisio.fichio.onboarding

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import com.karelisio.fichio.ui.theme.LocalReduceMotion
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Compose [Shape] drawing a [Morph] (normalised 0..1 polygons) at [progress], rotated by [rotation] degrees. */
class MorphShape(
    private val morph: Morph,
    private val progress: Float,
    private val rotation: Float = 0f,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val path = Path()
        val cubics = morph.asCubics(progress)
        if (cubics.isNotEmpty()) {
            path.moveTo(cubics.first().anchor0X, cubics.first().anchor0Y)
            cubics.forEach { c -> path.cubicTo(c.control0X, c.control0Y, c.control1X, c.control1Y, c.anchor1X, c.anchor1Y) }
            path.close()
        }
        val m = Matrix()
        m.translate(size.width / 2f, size.height / 2f)
        m.rotateZ(rotation)
        m.translate(-size.width / 2f, -size.height / 2f)
        m.scale(size.width, size.height)
        path.transform(m)
        return Outline.Generic(path)
    }
}

/**
 * Badge whose silhouette morphs endlessly between [shapes] while slowly turning.
 * Static (first shape) when motion is reduced.
 */
@Composable
fun MorphingBadge(
    shapes: List<RoundedPolygon>,
    color: Color,
    modifier: Modifier = Modifier,
    periodMs: Int = 2200,
    spin: Boolean = true,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val reduce = LocalReduceMotion.current
    var index by remember { mutableIntStateOf(0) }
    val morphs = remember(shapes) { shapes.indices.map { Morph(shapes[it], shapes[(it + 1) % shapes.size]) } }
    val progress = remember { Animatable(0f) }
    LaunchedEffect(reduce, shapes) {
        if (reduce || shapes.size < 2) return@LaunchedEffect
        while (true) {
            delay(periodMs / 2L)
            progress.snapTo(0f)
            progress.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessVeryLow))
            index = (index + 1) % shapes.size
            progress.snapTo(0f)
        }
    }
    val rotation = if (spin && !reduce) {
        val t = rememberInfiniteTransition(label = "spin")
        t.animateFloat(0f, 360f, infiniteRepeatable(tween(18_000, easing = LinearEasing)), label = "rot").value
    } else 0f
    val shape = MorphShape(morphs[index.coerceIn(0, morphs.lastIndex)], progress.value, rotation)
    Box(modifier.clip(shape).background(color), contentAlignment = Alignment.Center, content = content)
}

/** Page 1: morphing shapes orbiting a central folder. */
@Composable
fun WelcomeIllustration(modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val reduce = LocalReduceMotion.current
    val t = rememberInfiniteTransition(label = "orbit")
    val orbit by t.animateFloat(0f, 1f, infiniteRepeatable(tween(9000, easing = LinearEasing)), label = "o")
    val o = if (reduce) 0.1f else orbit
    Box(modifier.size(260.dp), contentAlignment = Alignment.Center) {
        val satellites = listOf(
            Triple(MaterialShapes.Flower, cs.tertiaryContainer, 0f),
            Triple(MaterialShapes.SoftBurst, cs.secondaryContainer, 0.33f),
            Triple(MaterialShapes.Gem, cs.primaryContainer, 0.66f),
        )
        satellites.forEachIndexed { i, (shape, color, phase) ->
            val a = 2 * PI * (o + phase)
            MorphingBadge(
                listOf(shape, MaterialShapes.Cookie6Sided, MaterialShapes.Circle),
                color,
                Modifier.offset((cos(a) * 96).dp, (sin(a) * 96).dp).size((46 + i * 6).dp),
                periodMs = 2600 + i * 500,
            )
        }
        MorphingBadge(
            listOf(MaterialShapes.Cookie9Sided, MaterialShapes.Clover4Leaf, MaterialShapes.Sunny, MaterialShapes.Cookie12Sided),
            cs.primary,
            Modifier.size(140.dp),
        ) {
            Icon(Icons.Rounded.Folder, null, Modifier.size(64.dp), tint = cs.onPrimary)
        }
    }
}

/** Page 2: a folder that opens while files fly out of it. */
@Composable
fun FolderOpenIllustration(modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val reduce = LocalReduceMotion.current
    val t = rememberInfiniteTransition(label = "fly")
    val cycle by t.animateFloat(0f, 1f, infiniteRepeatable(tween(3200, easing = LinearEasing)), label = "c")
    val c = if (reduce) 0.5f else cycle
    // Lid opens during the first third, stays open, closes at the end.
    val open = when {
        c < 0.2f -> c / 0.2f
        c < 0.8f -> 1f
        else -> 1f - (c - 0.8f) / 0.2f
    }
    val icons = listOf<ImageVector>(Icons.Rounded.Image, Icons.Rounded.Movie, Icons.Rounded.Audiotrack, Icons.Rounded.Description)
    val tints = listOf(cs.tertiary, cs.secondary, cs.primary, cs.error)
    Box(modifier.size(260.dp), contentAlignment = Alignment.Center) {
        icons.forEachIndexed { i, icon ->
            val local = ((c - 0.15f - i * 0.12f) / 0.55f).coerceIn(0f, 1f)
            if (local > 0f && local < 1f) {
                val angle = -PI / 2 + (i - 1.5) * 0.55
                val dist = 30 + 100 * local
                Box(
                    Modifier
                        .offset((cos(angle) * dist).dp, (sin(angle) * dist + 20).dp)
                        .graphicsLayer {
                            alpha = (1f - local) * 1.4f
                            rotationZ = (i - 1.5f) * 40f * local
                            val s = 0.6f + 0.6f * local
                            scaleX = s; scaleY = s
                        }
                        .size(44.dp)
                        .clip(MaterialShapes.Cookie4Sided.toShape())
                        .background(tints[i].copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center,
                ) { Icon(icon, null, tint = tints[i]) }
            }
        }
        Box(
            Modifier.offset(y = 30.dp).size(150.dp).clip(MaterialShapes.Cookie9Sided.toShape())
                .background(cs.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (open > 0.5f) Icons.Rounded.FolderOpen else Icons.Rounded.Folder, null,
                Modifier.size(76.dp).graphicsLayer {
                    rotationX = -open * 18f
                    scaleY = 1f - 0.06f * open
                },
                tint = cs.onPrimaryContainer,
            )
        }
    }
}

/** Page 3: a storage ring that fills up with a bouncy overshoot. */
@Composable
fun GaugeIllustration(modifier: Modifier = Modifier, active: Boolean = true) {
    val cs = MaterialTheme.colorScheme
    val reduce = LocalReduceMotion.current
    val fill = remember { Animatable(0f) }
    LaunchedEffect(active, reduce) {
        if (!active) { fill.snapTo(0f); return@LaunchedEffect }
        if (reduce) { fill.snapTo(0.68f); return@LaunchedEffect }
        fill.snapTo(0f)
        delay(200)
        fill.animateTo(0.68f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessVeryLow))
    }
    val track = cs.surfaceContainerHighest
    val segments = listOf(cs.primary to 0.45f, cs.tertiary to 0.3f, cs.secondary to 0.25f)
    Box(modifier.size(240.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 26.dp.toPx()
            val inset = stroke / 2 + 6.dp.toPx()
            val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
            val topLeft = Offset(inset, inset)
            drawArc(track, 0f, 360f, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            var start = -90f
            val total = 360f * fill.value
            segments.forEach { (color, part) ->
                val sweep = total * part
                if (sweep > 0.5f) drawArc(color, start, (sweep - 4f).coerceAtLeast(0.5f), false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                start += sweep
            }
        }
        Text(
            "${(fill.value * 100).toInt().coerceIn(0, 100)} %",
            style = MaterialTheme.typography.displaySmall.copy(fontSize = 44.sp),
            color = cs.onSurface,
        )
    }
}

/** Last page: confetti-like shapes bursting around a big check. */
@Composable
fun ReadyIllustration(modifier: Modifier = Modifier, active: Boolean = true) {
    val cs = MaterialTheme.colorScheme
    val reduce = LocalReduceMotion.current
    val burst = remember { Animatable(0f) }
    LaunchedEffect(active, reduce) {
        if (!active) { burst.snapTo(0f); return@LaunchedEffect }
        if (reduce) { burst.snapTo(1f); return@LaunchedEffect }
        burst.snapTo(0f)
        burst.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessVeryLow))
    }
    val shapes = listOf(MaterialShapes.Flower, MaterialShapes.Gem, MaterialShapes.SoftBurst, MaterialShapes.Clover4Leaf, MaterialShapes.Sunny, MaterialShapes.Heart)
    val colors = listOf(cs.primary, cs.tertiary, cs.secondary, cs.primaryContainer, cs.tertiaryContainer, cs.secondaryContainer)
    Box(modifier.size(260.dp), contentAlignment = Alignment.Center) {
        shapes.forEachIndexed { i, s ->
            val a = 2 * PI * i / shapes.size - PI / 2
            val d = 108 * burst.value
            Box(
                Modifier.offset((cos(a) * d).dp, (sin(a) * d).dp)
                    .graphicsLayer { rotationZ = 180f * burst.value + i * 30; val sc = burst.value.coerceIn(0f, 1.2f); scaleX = sc; scaleY = sc }
                    .size(34.dp).clip(s.toShape()).background(colors[i])
            )
        }
        MorphingBadge(listOf(MaterialShapes.Cookie12Sided, MaterialShapes.Sunny, MaterialShapes.Cookie9Sided), cs.primary, Modifier.size(150.dp)) {
            AnimatedCheck(checked = active, color = cs.onPrimary, modifier = Modifier.size(72.dp), strokeWidth = 9.dp)
        }
    }
}

/** Check mark drawn stroke by stroke when [checked] becomes true. */
@Composable
fun AnimatedCheck(checked: Boolean, color: Color, modifier: Modifier = Modifier, strokeWidth: Dp = 4.dp) {
    val reduce = LocalReduceMotion.current
    val p = remember { Animatable(if (checked) 1f else 0f) }
    LaunchedEffect(checked, reduce) {
        val target = if (checked) 1f else 0f
        if (reduce) p.snapTo(target) else p.animateTo(target, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessLow))
    }
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val path = Path().apply {
            moveTo(w * 0.2f, h * 0.53f)
            lineTo(w * 0.42f, h * 0.74f)
            lineTo(w * 0.8f, h * 0.3f)
        }
        val measure = PathMeasure().apply { setPath(path, false) }
        val seg = Path()
        measure.getSegment(0f, measure.length * p.value.coerceIn(0f, 1f), seg, true)
        drawPath(seg, color, style = Stroke(strokeWidth.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** Generic file glyph used by small decorations. */
val FileGlyph: ImageVector get() = Icons.AutoMirrored.Rounded.InsertDriveFile
