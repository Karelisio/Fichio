package com.karelisio.fichio.analysis

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import com.karelisio.fichio.core.util.Format
import com.karelisio.fichio.ui.components.AnimatedCounter
import com.karelisio.fichio.ui.components.rememberHaptics
import com.karelisio.fichio.ui.components.snappy
import com.karelisio.fichio.ui.theme.LocalReduceMotion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.IdentityHashMap
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min

private const val RINGS = 3

/** Zoom stack + spring-animated viewport of the sunburst. */
@Stable
class SunburstState internal constructor(
    val model: SunburstModel,
    private val scope: CoroutineScope,
    private val reduceMotion: Boolean,
    restorePaths: List<String>,
) {
    /** Frames from the root down to the zoomed folder. */
    var stack by mutableStateOf(listOf(model.root))
        private set
    /** Absolute depth of each frame of [stack]. */
    private var depths = listOf(0)
    var arcs by mutableStateOf(model.layout(model.root))
        private set
    /** Node whose name/size the centre shows (switches as soon as a zoom starts). */
    var label by mutableStateOf(model.root)
        private set
    var animating by mutableStateOf(false)
        private set
    private var from by mutableStateOf(SunburstViewport())
    private var to by mutableStateOf(SunburstViewport())
    internal val t = Animatable(1f)
    internal val appear = Animatable(if (reduceMotion) 1f else 0f)
    private var arcBaseDepth by mutableStateOf(0)

    val frame: DirNode get() = stack.last()
    val baseDepth: Int get() = arcBaseDepth

    init {
        // Re-open the same folders after a rescan / deletion when they still exist.
        var node = model.root
        val s = mutableListOf(node)
        val d = mutableListOf(0)
        for (p in restorePaths.drop(1)) {
            val next = model.layout(node).firstOrNull { it.node.path == p && it.node.canZoom } ?: break
            node = next.node
            s += node; d += d.last() + next.depth
        }
        stack = s; depths = d; label = node
        arcBaseDepth = d.last()
        arcs = model.layout(node)
    }

    fun viewport(): SunburstViewport = from.lerp(to, t.value.toDouble())

    private val zoomSpring = spring<Float>(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow)

    suspend fun playAppear() {
        if (reduceMotion) return
        appear.snapTo(0f)
        appear.animateTo(1f, spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessVeryLow))
    }

    fun zoomInto(node: DirNode): Boolean {
        if (animating || !node.canZoom) return false
        val arc = arcs.firstOrNull { it.node === node } ?: return false
        animating = true
        label = node
        scope.launch {
            try {
                if (!reduceMotion) {
                    arcs = model.layout(frame, deep = node)
                    from = SunburstViewport()
                    to = SunburstViewport(arc.x0, arc.x1, arc.depth.toDouble())
                    t.snapTo(0f)
                    t.animateTo(1f, zoomSpring)
                }
                stack = stack + node
                depths = depths + (depths.last() + arc.depth)
                arcBaseDepth = depths.last()
                arcs = model.layout(node)
                from = SunburstViewport(); to = SunburstViewport()
                t.snapTo(1f)
            } finally {
                animating = false
            }
        }
        return true
    }

    fun zoomOut(): Boolean {
        if (animating || stack.size < 2) return false
        val current = frame
        val parent = stack[stack.size - 2]
        animating = true
        label = parent
        scope.launch {
            try {
                stack = stack.dropLast(1)
                depths = depths.dropLast(1)
                arcBaseDepth = depths.last()
                val parentArcs = model.layout(parent, deep = current)
                val arc = parentArcs.firstOrNull { it.node === current }
                if (arc != null && !reduceMotion) {
                    arcs = parentArcs
                    from = SunburstViewport(arc.x0, arc.x1, arc.depth.toDouble())
                    to = SunburstViewport()
                    t.snapTo(0f)
                    t.animateTo(1f, zoomSpring)
                }
                arcs = model.layout(parent)
                from = SunburstViewport(); to = SunburstViewport()
                t.snapTo(1f)
            } finally {
                animating = false
            }
        }
        return true
    }

    /** Jumps back to the frame at [index] of [stack] (breadcrumb). */
    fun jumpTo(index: Int) {
        if (animating || index >= stack.size - 1 || index < 0) return
        if (index == stack.size - 2) { zoomOut(); return }
        stack = stack.take(index + 1)
        depths = depths.take(index + 1)
        arcBaseDepth = depths.last()
        label = frame
        arcs = model.layout(frame)
        scope.launch { playAppear() }
    }
}

@Composable
fun rememberSunburstState(model: SunburstModel, restorePaths: List<String> = emptyList()): SunburstState {
    val scope = rememberCoroutineScope()
    val reduce = LocalReduceMotion.current
    return remember(model) { SunburstState(model, scope, reduce, restorePaths) }
}

/** Colour of a slice: hue per top-level family, lightness per absolute depth, harmonised with the scheme. */
fun sunburstColor(scheme: ColorScheme, family: Int, sibling: Int, absDepth: Int, kind: NodeKind): Color {
    val base = FloatArray(3).also { ColorUtils.colorToHSL(scheme.primary.toArgb(), it) }
    val dark = scheme.surface.luminance() < 0.5f
    val hue = ((base[0] + family * 137.508f + if (absDepth > 1) sibling * 11f else 0f) % 360f + 360f) % 360f
    var sat = base[1].coerceIn(0.38f, 0.66f)
    val step = ((absDepth - 1).coerceAtLeast(0) % 3)
    val light = if (dark) 0.72f - step * 0.08f else 0.48f + step * 0.09f
    when (kind) {
        NodeKind.FILES -> sat *= 0.35f
        NodeKind.OTHERS -> sat *= 0.12f
        NodeKind.DIR -> Unit
    }
    return Color(ColorUtils.HSLToColor(floatArrayOf(hue, sat, light)))
}

/**
 * Animated, zoomable sunburst. Tap a slice to zoom into it, tap the centre to go up,
 * long-press a slice (or the centre) to open the folder.
 */
@Composable
fun SunburstChart(
    state: SunburstState,
    onOpenFolder: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val haptics = rememberHaptics()
    LaunchedEffect(state) { state.playAppear() }
    val colorCache = remember(state.arcs, scheme, state.baseDepth) { IdentityHashMap<SunburstArc, Color>() }
    val centreColor = scheme.surfaceContainerHigh

    BoxWithConstraints(
        modifier.fillMaxWidth().widthIn(max = 440.dp).aspectRatio(1f),
        contentAlignment = Alignment.Center,
    ) {
        val sidePx = with(androidx.compose.ui.platform.LocalDensity.current) { min(maxWidth.toPx(), maxHeight.toPx()) }
        val outer = sidePx / 2f * 0.98f
        val inner = outer * 0.31f
        val ring = (outer - inner) / RINGS
        val gapPx = with(androidx.compose.ui.platform.LocalDensity.current) { 2.dp.toPx() }

        fun hit(pos: Offset, size: Size): Pair<SunburstArc?, Boolean> {
            val c = Offset(size.width / 2f, size.height / 2f)
            val r = hypot(pos.x - c.x, pos.y - c.y)
            if (r < inner) return null to true
            if (r > outer) return null to false
            val level = floor((r - inner) / ring).toInt() + 1
            var deg = Math.toDegrees(atan2((pos.y - c.y).toDouble(), (pos.x - c.x).toDouble())) + 90.0
            if (deg < 0) deg += 360.0
            val f = deg / 360.0
            val vp = state.viewport()
            val arc = state.arcs.firstOrNull {
                it.depth == level && f >= vp.angleFraction(it.x0) && f < vp.angleFraction(it.x1)
            }
            return arc to false
        }

        Canvas(
            Modifier.fillMaxSize().pointerInput(state, sidePx) {
                detectTapGestures(
                    onTap = { pos ->
                        if (state.animating) return@detectTapGestures
                        val (arc, centre) = hit(pos, Size(size.width.toFloat(), size.height.toFloat()))
                        when {
                            centre -> if (state.zoomOut()) haptics.click() else haptics.tick()
                            arc != null -> if (state.zoomInto(arc.node)) haptics.click() else haptics.reject()
                        }
                    },
                    onLongPress = { pos ->
                        val (arc, centre) = hit(pos, Size(size.width.toFloat(), size.height.toFloat()))
                        val target = when {
                            centre -> state.frame.dirPath
                            arc != null -> arc.node.dirPath
                            else -> null
                        }
                        if (target != null) {
                            haptics.longPress(); onOpenFolder(target)
                        }
                    },
                )
            },
        ) {
            val vp = state.viewport()
            val appear = state.appear.value.coerceIn(0f, 1.2f)
            val c = Offset(size.width / 2f, size.height / 2f)
            for (arc in state.arcs) {
                val rel = arc.depth - vp.y
                if (rel <= 0.02 || rel > RINGS + 1) continue
                val a0 = vp.angleFraction(arc.x0)
                val a1 = vp.angleFraction(arc.x1)
                if (a1 - a0 < 0.0006) continue
                val alpha = when {
                    rel < 1 -> rel
                    rel > RINGS -> RINGS + 1 - rel
                    else -> 1.0
                }.toFloat().coerceIn(0f, 1f)
                // Rings grow outwards one after the other, angles fan open.
                val grow = (appear * 1.7f - (rel.toFloat() - 1f) * 0.35f).coerceIn(0f, 1.15f)
                if (grow <= 0f || alpha <= 0f) continue
                val rIn = if (rel < 1) inner else inner + (rel.toFloat() - 1f) * ring
                val rOut = if (rel < 1) inner + rel.toFloat() * ring else rIn + ring * grow
                val thickness = rOut - rIn - gapPx
                if (thickness <= 0.5f) continue
                val mid = (rIn + rOut - gapPx) / 2f
                val fan = appear.coerceIn(0f, 1f)
                val sweepDeg = ((a1 - a0) * 360.0 * fan).toFloat()
                val gapDeg = (gapPx / mid * 180f / PI.toFloat())
                val drawSweep = if (sweepDeg > gapDeg * 2f) sweepDeg - gapDeg else sweepDeg * 0.6f
                if (drawSweep < 0.15f) continue
                val start = -90f + (a0 * 360.0 * fan).toFloat() + (sweepDeg - drawSweep) / 2f
                val color = colorCache.getOrPut(arc) {
                    sunburstColor(scheme, arc.family, arc.sibling, state.baseDepth + arc.depth, arc.node.kind)
                }
                drawArc(
                    color = color,
                    startAngle = start,
                    sweepAngle = drawSweep,
                    useCenter = false,
                    topLeft = Offset(c.x - mid, c.y - mid),
                    size = Size(mid * 2, mid * 2),
                    alpha = alpha,
                    style = Stroke(width = thickness),
                )
            }
            drawCircle(centreColor, radius = (inner - gapPx) * appear.coerceIn(0.6f, 1f), center = c)
        }

        // Centre label.
        val diameter = with(androidx.compose.ui.platform.LocalDensity.current) { (inner * 2f * 0.86f).toDp() }
        Box(Modifier.size(diameter).clip(CircleShape), contentAlignment = Alignment.Center) {
            AnimatedContent(
                targetState = state.label,
                transitionSpec = { (scaleIn(snappy(), initialScale = 0.8f) + fadeIn()) togetherWith (scaleOut(targetScale = 1.1f) + fadeOut()) },
                label = "centre",
            ) { node ->
                CentreLabel(node, canGoUp = state.stack.size > 1 || node !== state.model.root)
            }
        }
    }
}

@Composable
private fun CentreLabel(node: DirNode, canGoUp: Boolean) {
    val (num, unit) = Format.sizeParts(node.size)
    Column(
        Modifier.fillMaxSize().padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        AnimatedVisibility(canGoUp) {
            Icon(
                Icons.Rounded.ArrowUpward, "Remonter",
                Modifier.size(18.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape).padding(2.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        Text(
            node.name,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Row(verticalAlignment = Alignment.Bottom) {
            AnimatedCounter(
                num,
                style = MaterialTheme.typography.headlineMedium.copy(color = MaterialTheme.colorScheme.onSurface),
            )
            Text(
                " $unit",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        Text(
            Format.count(node.fileCount, "fichier"),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Legend colour of a direct child of the current frame. */
fun SunburstState.legendColor(scheme: ColorScheme, child: DirNode, index: Int): Color {
    val family = if (frame === model.root) index else model.familyOf(frame)
    return sunburstColor(scheme, family, index, baseDepth + 1, child.kind)
}
