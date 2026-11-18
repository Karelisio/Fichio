package com.karelisio.fichio.ui.browser

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.karelisio.fichio.core.model.FileEntry
import com.karelisio.fichio.core.model.ViewMode
import com.karelisio.fichio.ui.components.rememberHaptics
import com.karelisio.fichio.ui.theme.LocalReduceMotion
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

/** Physical swipe directions (independent of the layout direction). */
enum class SwipeDirection { RIGHT, LEFT }

/** Thresholds of the row swipe. Pure, unit-tested. */
object SwipeMath {
    /** Fraction of the row width past which releasing triggers the action. */
    const val THRESHOLD = 0.32f
    /** A fast fling triggers from this fraction. */
    const val MIN_FLING_FRACTION = 0.12f
    /** Beyond this fraction the row follows the finger with resistance. */
    const val RESISTANCE_FROM = 0.6f
    private const val RESISTANCE = 0.25f

    /** New offset after a drag [delta], rubber-banded past [RESISTANCE_FROM] and clamped to the width. */
    fun drag(current: Float, delta: Float, width: Float): Float {
        if (width <= 0f) return 0f
        val limit = width * RESISTANCE_FROM
        val outward = current != 0f && sign(delta) == sign(current)
        val factor = if (outward && abs(current) >= limit) RESISTANCE else 1f
        return (current + delta * factor).coerceIn(-width, width)
    }

    /** Direction whose action would run if released now (null = below threshold). */
    fun armed(offset: Float, width: Float): SwipeDirection? = when {
        width <= 0f -> null
        offset >= width * THRESHOLD -> SwipeDirection.RIGHT
        offset <= -width * THRESHOLD -> SwipeDirection.LEFT
        else -> null
    }

    /** Outcome of a release with [velocity] (px/s); [flingVelocity] is the fling threshold in px/s. */
    fun resolve(offset: Float, velocity: Float, width: Float, flingVelocity: Float): SwipeDirection? {
        val dir = if (offset > 0f) SwipeDirection.RIGHT else if (offset < 0f) SwipeDirection.LEFT else return null
        val backwards = velocity != 0f && sign(velocity) != sign(offset) && abs(velocity) >= flingVelocity
        if (armed(offset, width) != null) return if (backwards) null else dir
        val flung = abs(offset) >= width * MIN_FLING_FRACTION && sign(velocity) == sign(offset) && abs(velocity) >= flingVelocity
        return if (flung) dir else null
    }

    /** 0..1 progress towards the threshold, for the background reveal. */
    fun progress(offset: Float, width: Float): Float =
        if (width <= 0f) 0f else (abs(offset) / (width * THRESHOLD)).coerceIn(0f, 1f)
}

/** Pinch thresholds and the order of the view modes. Pure, unit-tested. */
object PinchMath {
    const val STEP = 1.28f
    private val ORDER = listOf(ViewMode.LIST, ViewMode.GRID, ViewMode.LARGE)

    /** +1 spread enough (bigger items), -1 pinched enough (denser), 0 otherwise. */
    fun step(zoom: Float): Int = when {
        zoom >= STEP -> 1
        zoom <= 1f / STEP -> -1
        else -> 0
    }

    /** View after spreading ([zoomIn]) or pinching; null at either end. */
    fun next(mode: ViewMode, zoomIn: Boolean): ViewMode? = ORDER.getOrNull(ORDER.indexOf(mode) + if (zoomIn) 1 else -1)

    /** Live scale feedback of the content while pinching. */
    fun feedback(zoom: Float): Float = 1f + (zoom - 1f).coerceIn(-0.3f, 0.3f) * 0.25f
}

/** Swipe actions of the list rows: left = trash, right = share (file) / favourite (folder). */
class SwipeActions(
    val onDelete: (FileEntry) -> Unit,
    val onSecondary: (FileEntry) -> Unit,
)

/**
 * A list row that can be swiped horizontally. Vertical scrolling is untouched (horizontal
 * [draggable] only claims horizontal slop) and the swipe is disabled by the caller in selection
 * mode / while dragging between panes, so long-press drags keep working.
 */
@Composable
fun SwipeableRow(
    entry: FileEntry,
    enabled: Boolean,
    favorite: Boolean,
    actions: SwipeActions,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val reduce = LocalReduceMotion.current
    val haptics = rememberHaptics()
    val flingVelocity = with(LocalDensity.current) { 900.dp.toPx() }
    var offset by remember { mutableFloatStateOf(0f) }
    var width by remember { mutableIntStateOf(0) }
    var armed by remember { mutableStateOf<SwipeDirection?>(null) }

    LaunchedEffect(enabled) {
        if (!enabled && offset != 0f) animate(offset, 0f, animationSpec = spring()) { v, _ -> offset = v }
    }

    val state = rememberDraggableState { delta ->
        val w = width.toFloat()
        offset = SwipeMath.drag(offset, delta, w)
        val now = SwipeMath.armed(offset, w)
        if (now != armed) {
            armed = now
            if (now != null) haptics.segment() else haptics.tick()
        }
    }

    Box(
        modifier
            .onSizeChanged { width = it.width }
            .draggable(
                state = state,
                orientation = Orientation.Horizontal,
                enabled = enabled,
                onDragStopped = { velocity ->
                    val w = width.toFloat()
                    val dir = SwipeMath.resolve(offset, velocity, w, flingVelocity)
                    armed = null
                    val back = if (reduce) tween<Float>(120) else spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessMediumLow)
                    when (dir) {
                        SwipeDirection.LEFT -> {
                            haptics.confirm()
                            animate(offset, -w, animationSpec = tween(if (reduce) 80 else 180)) { v, _ -> offset = v }
                            actions.onDelete(entry)
                            // The row normally leaves the listing; if it stays (dialog, failure), bring it back.
                            delay(700)
                            animate(offset, 0f, animationSpec = back) { v, _ -> offset = v }
                        }
                        SwipeDirection.RIGHT -> {
                            haptics.confirm()
                            actions.onSecondary(entry)
                            animate(offset, 0f, animationSpec = back) { v, _ -> offset = v }
                        }
                        null -> animate(offset, 0f, animationSpec = back) { v, _ -> offset = v }
                    }
                },
            ),
    ) {
        if (offset != 0f) SwipeBackground(offset, width.toFloat(), entry, favorite, armed, reduce)
        Box(Modifier.offset { IntOffset(offset.roundToInt(), 0) }) { content() }
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.SwipeBackground(
    offset: Float,
    width: Float,
    entry: FileEntry,
    favorite: Boolean,
    armed: SwipeDirection?,
    reduce: Boolean,
) {
    val cs = MaterialTheme.colorScheme
    val left = offset < 0f
    val isArmed = armed != null
    val (idle, strong, onStrong) = when {
        left -> Triple(cs.errorContainer, cs.error, cs.onError)
        entry.isDirectory -> Triple(cs.tertiaryContainer, cs.tertiary, cs.onTertiary)
        else -> Triple(cs.primaryContainer, cs.primary, cs.onPrimary)
    }
    val onIdle = when {
        left -> cs.onErrorContainer
        entry.isDirectory -> cs.onTertiaryContainer
        else -> cs.onPrimaryContainer
    }
    val bg by animateColorAsState(if (isArmed) strong else idle, label = "swipeBg")
    val fg by animateColorAsState(if (isArmed) onStrong else onIdle, label = "swipeFg")
    val progress = SwipeMath.progress(offset, width)
    val scale by animateFloatAsState(
        targetValue = if (isArmed) 1.3f else 0.7f + 0.3f * progress,
        animationSpec = if (reduce) tween(80) else spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMedium),
        label = "swipeIcon",
    )
    val icon = when {
        left -> Icons.Rounded.Delete
        entry.isDirectory -> if (favorite) Icons.Rounded.StarOutline else Icons.Rounded.Star
        else -> Icons.Rounded.Share
    }
    val label = when {
        left -> "Mettre à la corbeille"
        entry.isDirectory -> if (favorite) "Retirer des favoris" else "Ajouter aux favoris"
        else -> "Partager"
    }
    Box(
        Modifier
            .matchParentSize()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(20.dp))
            .graphicsLayer { alpha = (0.35f + progress).coerceAtMost(1f) }
            .background(bg),
    ) {
        Icon(
            icon, label, tint = fg,
            modifier = Modifier
                .align(if (left) Alignment.CenterEnd else Alignment.CenterStart)
                .padding(horizontal = 24.dp)
                .size(26.dp)
                .graphicsLayer { scaleX = scale; scaleY = scale },
        )
    }
}

/**
 * Two-finger pinch on a container, observed in the Initial pass: single-finger gestures
 * (scroll, swipe, long-press drag) are never consumed; while two fingers are down their moves
 * are consumed so the list does not scroll under the pinch. One step per gesture.
 */
fun Modifier.pinchToChangeView(
    enabled: Boolean,
    onScale: (Float) -> Unit,
    onStep: (zoomIn: Boolean) -> Unit,
): Modifier = if (!enabled) this else pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var zoom = 1f
        var fired = false
        var pinching = false
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val pressed = event.changes.count { it.pressed }
            if (pressed == 0) break
            if (pressed >= 2) {
                pinching = true
                zoom *= event.calculateZoom()
                event.changes.forEach { if (it.positionChanged()) it.consume() }
                if (!fired) {
                    val step = PinchMath.step(zoom)
                    if (step != 0) { fired = true; onScale(1f); onStep(step > 0) } else onScale(PinchMath.feedback(zoom))
                }
            }
        }
        if (pinching) onScale(1f)
    }
}
