package com.karelisio.fichio.preview

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.toSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Zoom/pan/dismiss state of one image. The transform origin is the centre of the container:
 * screen = centre + (content - centre) * scale + offset.
 */
@Stable
class ZoomState(private val scope: CoroutineScope) {
    var scale by mutableFloatStateOf(1f)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set
    /** Vertical drag used by swipe-to-dismiss while not zoomed. */
    var dismiss by mutableFloatStateOf(0f)
        internal set

    var container by mutableStateOf(Size.Zero)
    /** Intrinsic size of the image, to fit it and bound the pan. */
    var content by mutableStateOf(Size.Unspecified)

    private var anim: Job? = null

    val isZoomed: Boolean get() = scale > 1.01f

    private fun fitted(): Size {
        val c = content
        if (container == Size.Zero || c == Size.Unspecified || c.width <= 0f || c.height <= 0f) return container
        val r = min(container.width / c.width, container.height / c.height)
        return Size(c.width * r, c.height * r)
    }

    fun clamp(off: Offset, s: Float): Offset {
        val f = fitted()
        val maxX = max(0f, (f.width * s - container.width) / 2f)
        val maxY = max(0f, (f.height * s - container.height) / 2f)
        return Offset(off.x.coerceIn(-maxX, maxX), off.y.coerceIn(-maxY, maxY))
    }

    fun snapTo(s: Float, off: Offset) {
        anim?.cancel()
        scale = s
        offset = clamp(off, s)
    }

    fun dragDismiss(dy: Float) { anim?.cancel(); dismiss += dy }

    /** Springs scale/offset/dismiss to the given targets. */
    fun animateTo(toScale: Float, toOffset: Offset, toDismiss: Float = 0f, animate: Boolean = true, onEnd: () -> Unit = {}) {
        anim?.cancel()
        if (!animate) { scale = toScale; offset = toOffset; dismiss = toDismiss; onEnd(); return }
        val s0 = scale; val o0 = offset; val d0 = dismiss
        anim = scope.launch {
            animate(0f, 1f, animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)) { t, _ ->
                scale = s0 + (toScale - s0) * t
                offset = o0 + (toOffset - o0) * t
                dismiss = d0 + (toDismiss - d0) * t
            }
            onEnd()
        }
    }

    /** Double tap: zoom in around [tap], or back to 1x. */
    fun toggleZoom(tap: Offset, animate: Boolean) {
        val toScale = if (isZoomed) 1f else DOUBLE_TAP_SCALE
        val centre = Offset(container.width / 2f, container.height / 2f)
        val toOffset = if (toScale == 1f) Offset.Zero else clamp((tap - centre) * (1f - toScale), toScale)
        animateTo(toScale, toOffset, animate = animate)
    }

    fun reset() {
        anim?.cancel(); scale = 1f; offset = Offset.Zero; dismiss = 0f
    }

    companion object {
        const val MAX_SCALE = 8f
        const val DOUBLE_TAP_SCALE = 2.6f
    }
}

@Composable
fun rememberZoomState(): ZoomState {
    val scope = rememberCoroutineScope()
    return remember { ZoomState(scope) }
}

/**
 * Pinch zoom + double-tap zoom at the touched point + bounded pan + swipe-down-to-dismiss.
 * Horizontal drags are left unconsumed when not zoomed (or at the pan edge) so that a parent
 * HorizontalPager can still change page.
 */
@Composable
fun ZoomableBox(
    state: ZoomState,
    modifier: Modifier = Modifier,
    reduceMotion: Boolean = false,
    onTap: () -> Unit = {},
    onDismiss: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val slop = LocalViewConfiguration.current.touchSlop
    Box(
        modifier
            .fillMaxSize()
            .onSizeChanged { state.container = it.toSize() }
            .pointerInput(state) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { state.toggleZoom(it, animate = !reduceMotion) },
                )
            }
            .pointerInput(state, onDismiss) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var dismissing = false
                    var drag = Offset.Zero
                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        val zoom = event.calculateZoom()
                        val pan = event.calculatePan()
                        val centroid = event.calculateCentroid(useCurrent = true)
                        if (pressed >= 2 || (state.isZoomed && !dismissing)) {
                            val oldScale = state.scale
                            val newScale = (oldScale * zoom).coerceIn(0.85f, ZoomState.MAX_SCALE)
                            val centre = Offset(state.container.width / 2f, state.container.height / 2f)
                            val z = newScale / oldScale
                            val oldOff = state.offset
                            val raw = if (centroid == Offset.Unspecified) oldOff + pan
                            else (centroid - centre) - (centroid - centre - oldOff) * z + pan
                            val clamped = state.clamp(raw, newScale)
                            // At the horizontal edge with a one-finger horizontal drag: let the pager take it.
                            val atEdge = pressed == 1 && abs(pan.x) > abs(pan.y) && abs(clamped.x - oldOff.x) < 0.5f && abs(pan.x) > 0.5f
                            if (!atEdge) {
                                state.snapTo(newScale, clamped)
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        } else if (pressed == 1 && onDismiss != null) {
                            drag += pan
                            if (!dismissing && abs(drag.y) > slop && abs(drag.y) > abs(drag.x) * 1.4f) dismissing = true
                            if (dismissing) {
                                state.dragDismiss(pan.y)
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        }
                    } while (event.changes.any { it.pressed })

                    // Gesture end: settle.
                    if (state.scale < 1f) state.animateTo(1f, Offset.Zero, state.dismiss, animate = !reduceMotion)
                    if (dismissing) {
                        val threshold = state.container.height * 0.16f
                        if (abs(state.dismiss) > threshold) onDismiss?.invoke()
                        else state.animateTo(state.scale, state.offset, 0f, animate = !reduceMotion)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val s = state.scale
                    val d = state.dismiss
                    val shrink = if (d != 0f && size.height > 0) 1f - min(0.25f, abs(d) / size.height) else 1f
                    scaleX = s * shrink
                    scaleY = s * shrink
                    translationX = state.offset.x
                    translationY = state.offset.y + d
                },
            contentAlignment = Alignment.Center,
        ) { content() }
    }
}
