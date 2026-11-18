@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.karelisio.fichio.preview

import androidx.compose.ui.res.stringResource
import com.karelisio.fichio.R
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.karelisio.fichio.core.model.FileEntry
import com.karelisio.fichio.ui.components.bouncy
import com.karelisio.fichio.ui.components.rememberHaptics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Thread-confined wrapper around [PdfRenderer] (which is not thread-safe): one page open at a
 * time, rendered on a dedicated thread, results kept in a byte-bounded LRU cache.
 */
internal class PdfDocument(file: File) : AutoCloseable {
    private val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(fd)
    private val lock = Mutex()
    private val executor = Executors.newSingleThreadExecutor()
    private val dispatcher = executor.asCoroutineDispatcher()
    @Volatile private var closed = false

    val pageCount: Int = renderer.pageCount
    /** Width / height of each page, read lazily. */
    private val ratios = FloatArray(pageCount) { 0f }

    private val cache = object : LruCache<Long, Bitmap>((Runtime.getRuntime().maxMemory() / 6).toInt().coerceAtLeast(16 shl 20)) {
        override fun sizeOf(key: Long, value: Bitmap) = value.allocationByteCount
    }

    suspend fun ratio(index: Int): Float {
        if (ratios[index] > 0f) return ratios[index]
        return withContext(dispatcher) {
            lock.withLock {
                if (closed) return@withLock 0.707f
                renderer.openPage(index).use { p -> (p.width.toFloat() / p.height).also { ratios[index] = it } }
            }
        }
    }

    fun cached(index: Int, width: Int): Bitmap? = cache.get(key(index, width))

    suspend fun render(index: Int, width: Int): Bitmap? = withContext(dispatcher) {
        cache.get(key(index, width))?.let { return@withContext it }
        lock.withLock {
            if (closed) return@withLock null
            renderer.openPage(index).use { page ->
                val w = width.coerceIn(64, 4096)
                val h = (w.toFloat() * page.height / page.width).roundToInt().coerceAtLeast(1)
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(android.graphics.Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                ratios[index] = page.width.toFloat() / page.height
                cache.put(key(index, width), bmp)
                bmp
            }
        }
    }

    private fun key(index: Int, width: Int) = (index.toLong() shl 32) or width.toLong()

    /** Idempotent: a second call (or a call after shutdown) is a no-op instead of a crash. */
    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        val release = Runnable {
            runCatching { renderer.close() }
            runCatching { fd.close() }
            cache.evictAll()
        }
        // Close on the render thread, after any in-flight render.
        try {
            executor.execute(release)
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            release.run()
        }
        executor.shutdown()
    }
}

/** PDF pages in a LazyColumn, global pinch zoom, "page X / N" indicator and go-to-page. */
@Composable
internal fun PdfViewer(file: File, entry: FileEntry, modifier: Modifier = Modifier, onPageCount: (Int) -> Unit = {}) {
    val doc by produceState<Result<PdfDocument>?>(null, file) {
        value = withContext(Dispatchers.IO) { runCatching { PdfDocument(file) } }
    }
    // Capture the value: reading the delegated state inside onDispose would close the *new*
    // document as soon as it finishes loading.
    val opened = doc
    DisposableEffect(opened) { onDispose { opened?.getOrNull()?.close() } }
    when (val d = doc) {
        null -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator() }
        else -> d.fold(
            onSuccess = { LaunchedEffect(it) { onPageCount(it.pageCount) }; PdfPages(it, modifier) },
            onFailure = {
                Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        if (it is SecurityException) stringResource(R.string.pdf_protected) else stringResource(R.string.pdf_open_failed, entry.name),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            },
        )
    }
}

@Composable
private fun PdfPages(doc: PdfDocument, modifier: Modifier) {
    val list = rememberLazyListState()
    val hScroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    var zoom by remember { mutableFloatStateOf(1f) }
    var jump by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val currentPage by remember { derivedStateOf { list.firstVisibleItemIndex + 1 } }
    var showIndicator by remember { mutableStateOf(true) }
    LaunchedEffect(list.isScrollInProgress) {
        if (list.isScrollInProgress) showIndicator = true else { delay(2_200); showIndicator = false }
    }

    BoxWithConstraints(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLow)) {
        val baseWidth = maxWidth
        val basePx = with(density) { baseWidth.toPx() }
        // Render at screen width, or twice that once zoomed in.
        val renderWidth = (basePx * (if (zoom > 1.4f) 2f else 1f)).roundToInt()
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.changes.count { it.pressed } >= 2) {
                                val z = event.calculateZoom()
                                val c = event.calculateCentroid(useCurrent = true)
                                val old = zoom
                                val new = (old * z).coerceIn(1f, 4f)
                                if (new != old) {
                                    val ratio = new / old
                                    zoom = new
                                    // Keep the content under the fingers in place.
                                    val cx = if (c == Offset.Unspecified) 0f else c.x
                                    val cy = if (c == Offset.Unspecified) 0f else c.y
                                    val dx = (hScroll.value + cx) * (ratio - 1f)
                                    val dy = (list.firstVisibleItemScrollOffset + cy) * (ratio - 1f)
                                    scope.launch { hScroll.scrollBy(dx); list.scrollBy(dy) }
                                }
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                },
        ) {
            LazyColumn(
                state = list,
                modifier = Modifier.fillMaxSize().horizontalScroll(hScroll, enabled = zoom > 1.01f),
                contentPadding = PaddingValues(top = 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(doc.pageCount, key = { it }) { index ->
                    PdfPage(doc, index, renderWidth, Modifier.width(baseWidth * zoom).padding(horizontal = 8.dp))
                }
            }
        }

        AnimatedVisibility(
            visible = showIndicator || jump,
            enter = scaleIn(bouncy()) + fadeIn(),
            exit = scaleOut() + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 20.dp),
        ) {
            Surface(
                onClick = { haptics.tick(); jump = true },
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                shadowElevation = 4.dp,
            ) {
                Text(
                    stringResource(R.string.pdf_page_of, currentPage, doc.pageCount),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                )
            }
        }
    }

    if (jump) {
        var text by remember { mutableStateOf(currentPage.toString()) }
        val target = text.toIntOrNull()?.takeIf { it in 1..doc.pageCount }
        AlertDialog(
            onDismissRequest = { jump = false },
            title = { Text(stringResource(R.string.pdf_go_to_page)) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { v -> text = v.filter { it.isDigit() }.take(6) },
                    singleLine = true,
                    label = { Text("1 – ${doc.pageCount}") },
                    isError = target == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            },
            confirmButton = {
                TextButton(enabled = target != null, onClick = {
                    jump = false
                    target?.let { scope.launch { list.animateScrollToItem(it - 1) } }
                }) { Text(stringResource(R.string.pdf_go)) }
            },
            dismissButton = { TextButton(onClick = { jump = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

@Composable
private fun PdfPage(doc: PdfDocument, index: Int, renderWidth: Int, modifier: Modifier) {
    var ratio by remember(index) { mutableFloatStateOf(0.707f) }
    val bitmap by produceState(doc.cached(index, renderWidth), index, renderWidth) {
        ratio = doc.ratio(index).takeIf { it > 0f } ?: ratio
        val b = doc.render(index, renderWidth)
        if (b != null) value = b
    }
    Box(
        modifier
            .aspectRatio(ratio)
            .shadow(2.dp, MaterialTheme.shapes.extraSmall)
            .clip(MaterialTheme.shapes.extraSmall)
            .background(Color.White),
        contentAlignment = Alignment.Center,
    ) {
        val b = bitmap
        if (b != null && !b.isRecycled) {
            Image(b.asImageBitmap(), contentDescription = stringResource(R.string.pdf_page, index + 1), contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxSize())
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                LoadingIndicator(color = Color.Gray)
                Text("${index + 1}", color = Color.Gray, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
