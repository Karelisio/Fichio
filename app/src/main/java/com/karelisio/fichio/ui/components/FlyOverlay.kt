package com.karelisio.fichio.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.karelisio.fichio.container
import com.karelisio.fichio.core.fs.StoragePaths
import com.karelisio.fichio.core.model.FileEntry
import com.karelisio.fichio.ops.OperationEvent
import com.karelisio.fichio.ui.theme.LocalReduceMotion
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.max

@Immutable
data class Flight(val id: Long, val entry: FileEntry, val from: Rect, val to: Rect, val delayMs: Long, val destination: String)

/**
 * Signature animation #2 — "flying file": when a copy/move starts, the file thumbnail flies on
 * an arc from where it was to the destination folder, which springs ("pop") on arrival.
 *
 * Items register their on-screen bounds with [flySource] / [flyTarget]; the overlay is placed
 * once at the root of the app.
 */
object FlyRegistry {
    private val coords = HashMap<String, LayoutCoordinates>()
    internal val flights = mutableStateListOf<Flight>()
    /** Destination path → arrival tick, observed by folder views to spring on arrival. */
    val arrivals = mutableStateMapOf<String, Int>()
    private var nextId = 0L
    /** Set by drag & drop: the drop already shows the motion, so skip the next flight there. */
    @Volatile var suppressDestination: String? = null

    /** Known entries currently visible (to fly with the right thumbnail). */
    private val entries = HashMap<String, FileEntry>()

    fun register(key: String, c: LayoutCoordinates) { coords[key] = c }
    fun unregister(key: String, c: LayoutCoordinates?) { if (c == null || coords[key] === c) coords.remove(key) }
    fun rememberEntry(e: FileEntry) { entries[e.path] = e }

    /** First registered key with [prefix] whose bounds contain [point] (root coordinates). */
    fun hit(point: Offset, prefix: String, exclude: (String) -> Boolean = { false }): String? =
        coords.entries.firstOrNull { (k, c) ->
            k.startsWith(prefix) && !exclude(k) && c.isAttached && c.boundsInRoot().contains(point)
        }?.key

    fun bounds(key: String): Rect? = coords[key]?.takeIf { it.isAttached }?.boundsInRoot()?.takeIf { it.width > 0 && it.height > 0 }

    fun arrive(destination: String) { arrivals[destination] = (arrivals[destination] ?: 0) + 1 }

    /** Launch flights for up to 3 sources towards [destination]. */
    fun launch(sources: List<String>, destination: String, fallbackSize: Float) {
        if (suppressDestination == destination) { suppressDestination = null; arrive(destination); return }
        val to = bounds("folder:$destination") ?: bounds("header:$destination") ?: run { arrive(destination); return }
        val clip = bounds("clipboard")
        sources.take(3).forEachIndexed { i, src ->
            val from = bounds("item:$src") ?: clip ?: return@forEachIndexed
            val entry = entries[src] ?: FileEntry(src, StoragePaths.name(src), false, 0, 0)
            val sized = if (from.width > fallbackSize * 2) Rect(from.center - Offset(fallbackSize / 2, fallbackSize / 2), androidx.compose.ui.geometry.Size(fallbackSize, fallbackSize)) else from
            flights += Flight(nextId++, entry, sized, to, i * 70L, destination)
        }
        if (flights.none { it.destination == destination }) arrive(destination)
    }

    internal fun finish(f: Flight) {
        flights.remove(f)
        if (flights.none { it.destination == f.destination }) arrive(f.destination)
    }
}

/** Registers this node as the visible origin of [entry] for flights. */
fun Modifier.flySource(entry: FileEntry): Modifier = composed {
    val key = "item:${entry.path}"
    val holder = remember { arrayOfNulls<LayoutCoordinates>(1) }
    DisposableEffect(key) { onDispose { FlyRegistry.unregister(key, holder[0]) } }
    onGloballyPositioned { holder[0] = it; FlyRegistry.register(key, it); FlyRegistry.rememberEntry(entry) }
}

/** Registers a drop/arrival target (a folder row, a folder header, the clipboard bar…). */
fun Modifier.flyTarget(key: String): Modifier = composed {
    val holder = remember { arrayOfNulls<LayoutCoordinates>(1) }
    DisposableEffect(key) { onDispose { FlyRegistry.unregister(key, holder[0]) } }
    onGloballyPositioned { holder[0] = it; FlyRegistry.register(key, it) }
}

/** Overlay rendering the flights. Place last in the root Box. */
@Composable
fun FlyOverlay() {
    val context = LocalContext.current
    val reduce = LocalReduceMotion.current
    val sizePx = with(LocalDensity.current) { 56.dp.toPx() }
    LaunchedEffect(Unit) {
        context.container.operations.events.collect { e ->
            if (e is OperationEvent.Transfer) {
                if (reduce) FlyRegistry.arrive(e.destination) else FlyRegistry.launch(e.sources, e.destination, sizePx)
            }
        }
    }
    Box(Modifier.fillMaxSize()) {
        FlyRegistry.flights.toList().forEach { f -> key(f.id) { FlyingThumb(f) } }
    }
}

@Composable
private fun FlyingThumb(f: Flight) {
    val haptics = rememberHaptics()
    val progress = remember { Animatable(0f) }
    LaunchedEffect(f.id) {
        delay(f.delayMs)
        progress.animateTo(1f, tween(720, easing = FastOutSlowInEasing))
        haptics.tick()
        FlyRegistry.finish(f)
    }
    val density = LocalDensity.current
    val side = with(density) { 56.dp.toPx() }
    Surface(
        shape = MaterialTheme.shapes.medium,
        shadowElevation = 8.dp,
        tonalElevation = 2.dp,
        modifier = Modifier
            .size(56.dp)
            .graphicsLayer {
                val t = progress.value
                val p0 = f.from.center
                val p2 = f.to.center
                // Control point above the midpoint: a natural arc whatever the direction.
                val lift = max(160.dp.toPx(), abs(p2.x - p0.x) * 0.45f)
                val p1 = Offset((p0.x + p2.x) / 2f, minOf(p0.y, p2.y) - lift)
                val u = 1 - t
                val x = u * u * p0.x + 2 * u * t * p1.x + t * t * p2.x
                val y = u * u * p0.y + 2 * u * t * p1.y + t * t * p2.y
                translationX = x - side / 2
                translationY = y - side / 2
                val s = if (t < 0.3f) 1f + 0.2f * (t / 0.3f) else 1.2f - 0.85f * ((t - 0.3f) / 0.7f)
                scaleX = s; scaleY = s
                rotationZ = -14f + 22f * t
                alpha = if (t > 0.88f) (1f - t) / 0.12f else 1f
            },
    ) {
        FileThumbnail(f.entry, size = 56.dp)
    }
}
