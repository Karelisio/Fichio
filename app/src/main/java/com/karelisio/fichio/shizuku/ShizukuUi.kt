package com.karelisio.fichio.shizuku

import com.karelisio.fichio.core.util.Strings
import androidx.compose.ui.res.stringResource
import com.karelisio.fichio.R
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toPath
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.karelisio.fichio.container
import com.karelisio.fichio.ui.components.bouncy
import com.karelisio.fichio.ui.components.rememberHaptics
import com.karelisio.fichio.ui.theme.LocalReduceMotion

/**
 * Explains why Android/data / Android/obb is not reachable (Shevery missing, stopped, not
 * authorised, connecting) with the matching actions; turns into an animated check once READY.
 */
@Composable
fun ShizukuStatusPanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val manager = context.container.shizuku
    val state by manager.state.collectAsStateWithLifecycle()
    val haptics = rememberHaptics()

    // Coming back from the Shevery app: re-check without tearing down a live connection.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val s = manager.state.value
        if (s != ShizukuState.READY && s != ShizukuState.CONNECTING) manager.retry()
    }
    var previous by remember { mutableStateOf(state) }
    LaunchedEffect(state) {
        if (state == ShizukuState.READY && previous != ShizukuState.READY) haptics.confirm()
        previous = state
    }

    val container by animateColorAsState(
        when (state) {
            ShizukuState.READY -> MaterialTheme.colorScheme.primaryContainer
            ShizukuState.NO_PERMISSION -> MaterialTheme.colorScheme.secondaryContainer
            ShizukuState.CONNECTING -> MaterialTheme.colorScheme.surfaceContainerHighest
            else -> MaterialTheme.colorScheme.tertiaryContainer
        },
        label = "badge",
    )
    val content = when (state) {
        ShizukuState.READY -> MaterialTheme.colorScheme.onPrimaryContainer
        ShizukuState.NO_PERMISSION -> MaterialTheme.colorScheme.onSecondaryContainer
        ShizukuState.CONNECTING -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onTertiaryContainer
    }

    Surface(
        modifier = modifier.fillMaxWidth().padding(16.dp),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            MorphingBadge(polygonFor(state), container, Modifier.size(124.dp)) {
                AnimatedContent(
                    targetState = state,
                    transitionSpec = { (scaleIn(bouncy()) + fadeIn()) togetherWith fadeOut() },
                    label = "badgeIcon",
                ) { s ->
                    when (s) {
                        ShizukuState.CONNECTING -> LoadingIndicator(Modifier.size(64.dp), color = content)
                        ShizukuState.READY -> AnimatedCheck(content, Modifier.size(64.dp))
                        else -> Icon(iconFor(s), null, Modifier.size(52.dp), tint = content)
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            AnimatedContent(
                targetState = state,
                transitionSpec = {
                    (slideInVertically(bouncy()) { it / 3 } + fadeIn()) togetherWith fadeOut()
                },
                label = "texts",
            ) { s ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        titleFor(s),
                        style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        messageFor(s),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            if (state != ShizukuState.READY) {
                Spacer(Modifier.height(20.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    when (state) {
                        ShizukuState.NO_PERMISSION -> PrimaryAction(stringResource(R.string.common_allow), Icons.Rounded.Key) {
                            haptics.click(); manager.requestPermission()
                        }
                        ShizukuState.NOT_RUNNING -> PrimaryAction(stringResource(R.string.shizuku_open), Icons.Rounded.OpenInNew) {
                            haptics.click()
                            if (!manager.openManager(context)) haptics.reject()
                        }
                        else -> Unit
                    }
                    OutlinedButton(
                        onClick = { haptics.tick(); manager.retry() },
                        shapes = ButtonDefaults.shapes(),
                    ) {
                        Icon(Icons.Rounded.Refresh, null, Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                        Text(stringResource(R.string.common_retry))
                    }
                }
            }
        }
    }
}

@Composable
private fun PrimaryAction(label: String, icon: ImageVector, onClick: () -> Unit) {
    Button(onClick = onClick, shapes = ButtonDefaults.shapes()) {
        Icon(icon, null, Modifier.size(ButtonDefaults.IconSize))
        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
        Text(label)
    }
}

private fun polygonFor(s: ShizukuState): RoundedPolygon = when (s) {
    ShizukuState.NOT_INSTALLED -> MaterialShapes.Cookie4Sided
    ShizukuState.NOT_RUNNING -> MaterialShapes.Cookie9Sided
    ShizukuState.NO_PERMISSION -> MaterialShapes.Clover4Leaf
    ShizukuState.CONNECTING -> MaterialShapes.SoftBurst
    ShizukuState.READY -> MaterialShapes.Sunny
}

private fun iconFor(s: ShizukuState): ImageVector = when (s) {
    ShizukuState.NOT_INSTALLED -> Icons.Rounded.Download
    ShizukuState.NOT_RUNNING -> Icons.Rounded.PowerSettingsNew
    else -> Icons.Rounded.Lock
}

private fun titleFor(s: ShizukuState) = when (s) {
    ShizukuState.NOT_INSTALLED -> Strings.get(R.string.shizuku_not_installed)
    ShizukuState.NOT_RUNNING -> Strings.get(R.string.shizuku_not_started)
    ShizukuState.NO_PERMISSION -> Strings.get(R.string.shizuku_permission_needed)
    ShizukuState.CONNECTING -> Strings.get(R.string.shizuku_connecting)
    ShizukuState.READY -> Strings.get(R.string.shizuku_access_granted)
}

private fun messageFor(s: ShizukuState) = when (s) {
    ShizukuState.NOT_INSTALLED ->
        Strings.get(R.string.shizuku_msg_not_installed)
    ShizukuState.NOT_RUNNING ->
        Strings.get(R.string.shizuku_msg_not_running)
    ShizukuState.NO_PERMISSION ->
        Strings.get(R.string.shizuku_msg_no_permission)
    ShizukuState.CONNECTING -> Strings.get(R.string.shizuku_msg_connecting)
    ShizukuState.READY -> Strings.get(R.string.shizuku_msg_ready)
}

/** Badge whose silhouette morphs (spring) between expressive shapes and slowly turns. */
@Composable
internal fun MorphingBadge(
    target: RoundedPolygon,
    color: Color,
    modifier: Modifier = Modifier,
    rotate: Boolean = true,
    content: @Composable () -> Unit,
) {
    val reduce = LocalReduceMotion.current
    var from by remember { mutableStateOf(target) }
    var to by remember { mutableStateOf(target) }
    val progress = remember { Animatable(1f) }
    LaunchedEffect(target) {
        if (target === to) return@LaunchedEffect
        from = to; to = target
        if (reduce) {
            progress.snapTo(1f)
        } else {
            progress.snapTo(0f)
            progress.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessLow))
        }
    }
    val morph = remember(from, to) { Morph(from, to) }
    val spin = if (rotate && !reduce) {
        val t = rememberInfiniteTransition(label = "spin")
        t.animateFloat(0f, 360f, infiniteRepeatable(tween(24_000, easing = LinearEasing), RepeatMode.Restart), label = "r").value
    } else 0f
    Box(modifier, contentAlignment = Alignment.Center) {
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer { rotationZ = spin }
                .clip(MorphShape(morph, progress.value))
                .background(color),
        )
        content()
    }
}

/** Outline of a [Morph] at [progress], scaled from the unit square to the layout size. */
internal class MorphShape(private val morph: Morph, private val progress: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val path = morph.toPath(progress.coerceIn(0f, 1f), Path())
        path.transform(Matrix().apply { scale(size.width, size.height) })
        return Outline.Generic(path)
    }
}

/** Check mark drawn stroke by stroke. */
@Composable
internal fun AnimatedCheck(color: Color, modifier: Modifier = Modifier) {
    val reduce = LocalReduceMotion.current
    val p = remember { Animatable(if (reduce) 1f else 0f) }
    LaunchedEffect(Unit) { p.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessLow)) }
    Canvas(modifier) {
        val w = size.width
        val full = Path().apply {
            moveTo(w * 0.22f, w * 0.53f)
            lineTo(w * 0.42f, w * 0.72f)
            lineTo(w * 0.80f, w * 0.32f)
        }
        val measure = PathMeasure().apply { setPath(full, false) }
        val seg = Path()
        measure.getSegment(0f, measure.length * p.value.coerceIn(0f, 1f), seg, true)
        drawPath(seg, color, style = Stroke(width = w * 0.11f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
