@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.karelisio.fichio.preview

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Precision
import com.karelisio.fichio.container
import com.karelisio.fichio.core.fs.StoragePaths
import com.karelisio.fichio.core.model.FileEntry
import com.karelisio.fichio.core.model.FileType
import com.karelisio.fichio.data.prefs.FichioSettings
import com.karelisio.fichio.ui.components.BackButton
import com.karelisio.fichio.ui.components.rememberHaptics
import com.karelisio.fichio.ui.navigation.Opener
import com.karelisio.fichio.ui.navigation.sharedElementKey
import com.karelisio.fichio.ui.components.Thumb
import com.karelisio.fichio.ui.theme.LocalReduceMotion
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Images of the same folder, sorted by name; falls back to [entry] alone. */
private suspend fun siblingImages(context: android.content.Context, entry: FileEntry, showHidden: Boolean): List<FileEntry> =
    runCatching {
        context.container.fileSystems.list(entry.parentPath)
            .filter { !it.isDirectory && it.type == FileType.IMAGE && (showHidden || !it.isHidden || it.path == entry.path) }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }.getOrNull()?.takeIf { list -> list.any { it.path == entry.path } } ?: listOf(entry)

/** Full-screen image viewer: pager over the folder, zoom, swipe down to close, hideable bars. */
@Composable
internal fun ImageViewer(
    entry: FileEntry,
    onBack: () -> Unit,
    onShowInFolder: ((String) -> Unit)?,
    /** For content:// previews: a single image, no folder actions. */
    single: Boolean = false,
    extraActions: (@Composable () -> Unit)? = null,
) {
    val context = LocalContext.current
    val reduce = LocalReduceMotion.current
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val settings by context.container.settings.settings.collectAsStateWithLifecycle(FichioSettings())
    val polaro = remember { Opener.isPolaroInstalled(context) }

    var images by remember(entry.path) { mutableStateOf(listOf(entry)) }
    LaunchedEffect(entry.path, settings.showHidden) {
        if (!single) images = siblingImages(context, entry, settings.showHidden)
    }
    val pager = rememberPagerState { images.size }
    LaunchedEffect(images) {
        val idx = images.indexOfFirst { it.path == entry.path }
        if (idx >= 0 && pager.currentPage != idx) pager.scrollToPage(idx)
    }
    val zoomCache = remember { HashMap<String, ZoomState>() }
    val zoomStates = remember(images) { images.associate { it.path to zoomCache.getOrPut(it.path) { ZoomState(scope) } } }
    val current = images.getOrNull(pager.currentPage) ?: entry
    LaunchedEffect(pager.settledPage) {
        val settled = images.getOrNull(pager.settledPage)?.path
        zoomStates.forEach { (k, z) -> if (k != settled) z.reset() }
    }

    var bars by remember { mutableStateOf(true) }
    var info by remember { mutableStateOf<FileEntry?>(null) }
    var confirmTrash by remember { mutableStateOf<FileEntry?>(null) }
    ImmersiveMode(immersive = !bars)
    DarkSystemBars()

    val currentZoom = zoomStates[current.path]
    val dismissFraction = currentZoom?.let { z -> if (z.container.height > 0) min(1f, abs(z.dismiss) / (z.container.height * 0.45f)) else 0f } ?: 0f

    val config = LocalConfiguration.current
    val density = LocalDensity.current
    val maxDim = remember(config) {
        with(density) { (max(config.screenWidthDp, config.screenHeightDp).dp.toPx() * 2f).toInt().coerceIn(1024, 4096) }
    }

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = 1f - dismissFraction }.background(Color.Black))
        HorizontalPager(
            state = pager,
            key = { images.getOrNull(it)?.path ?: it },
            beyondViewportPageCount = 1,
            pageSpacing = 16.dp,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val img = images.getOrNull(page) ?: return@HorizontalPager
            val zs = zoomStates[img.path] ?: return@HorizontalPager
            ZoomableBox(
                state = zs,
                reduceMotion = reduce,
                onTap = { bars = !bars; haptics.tick() },
                onDismiss = { haptics.confirm(); onBack() },
            ) {
                ImagePage(
                    img = img,
                    maxDim = maxDim,
                    shared = img.path == entry.path,
                    onSize = { zs.content = it },
                    onOpenWith = { scope.launch { openWith(context, img.path) } },
                )
            }
        }

        // Top bar.
        AnimatedVisibility(
            visible = bars && dismissFraction < 0.05f,
            enter = fadeIn() + slideInVertically { -it / 2 },
            exit = fadeOut() + slideOutVertically { -it / 2 },
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.65f), Color.Transparent)))
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.material3.LocalContentColor provides Color.White,
                ) {
                    BackButton(onBack)
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(current.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.MiddleEllipsis, color = Color.White)
                        if (images.size > 1) {
                            Text(
                                "${pager.currentPage + 1} / ${images.size}",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White.copy(alpha = 0.75f),
                            )
                        }
                    }
                }
            }
        }

        // Bottom floating toolbar.
        AnimatedVisibility(
            visible = bars && dismissFraction < 0.05f,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 16.dp),
        ) {
            val preferPolaro = polaro && settings.openImagesInPolaro && !single
            val actions: @Composable () -> Unit = {
                ToolbarAction(Icons.Rounded.Share, "Partager") { scope.launch { shareFile(context, current.path) } }
                ToolbarAction(Icons.AutoMirrored.Rounded.OpenInNew, "Ouvrir avec") { scope.launch { openWith(context, current.path) } }
                if (polaro && !preferPolaro && !single) ToolbarAction(Icons.Rounded.AutoAwesome, "Ouvrir dans Polaro") { Opener.openInPolaro(context, current.path) }
                ToolbarAction(Icons.Rounded.Info, "Infos") { info = current }
                if (!single && onShowInFolder != null) ToolbarAction(Icons.Rounded.FolderOpen, "Afficher dans le dossier") { onShowInFolder(current.parentPath) }
                if (!single) ToolbarAction(Icons.Rounded.Delete, "Supprimer") { haptics.longPress(); confirmTrash = current }
                extraActions?.invoke()
            }
            if (preferPolaro) {
                HorizontalFloatingToolbar(
                    expanded = true,
                    floatingActionButton = {
                        FloatingToolbarDefaults.VibrantFloatingActionButton(onClick = {
                            haptics.click()
                            if (!Opener.openInPolaro(context, current.path)) scope.launch { openWith(context, current.path) }
                        }) { Icon(Icons.Rounded.AutoAwesome, "Ouvrir dans Polaro") }
                    },
                    colors = FloatingToolbarDefaults.vibrantFloatingToolbarColors(),
                ) { actions() }
            } else {
                HorizontalFloatingToolbar(expanded = true, colors = FloatingToolbarDefaults.vibrantFloatingToolbarColors()) { actions() }
            }
        }
    }

    info?.let { InfoSheet(it, onDismiss = { info = null }) }
    confirmTrash?.let { target ->
        TrashDialog(target.name, onDismiss = { confirmTrash = null }) {
            confirmTrash = null
            if (trashFile(context, target.path)) {
                haptics.confirm()
                val remaining = images.filterNot { it.path == target.path }
                if (remaining.isEmpty()) onBack() else images = remaining
            }
        }
    }
}

@Composable
internal fun ToolbarAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        IconButton(onClick = onClick) { Icon(icon, contentDescription = label) }
    }
}

@Composable
private fun ImagePage(
    img: FileEntry,
    maxDim: Int,
    shared: Boolean,
    onSize: (Size) -> Unit,
    onOpenWith: () -> Unit,
) {
    val context = LocalContext.current
    var loading by remember(img.path) { mutableStateOf(true) }
    var failed by remember(img.path) { mutableStateOf(false) }
    val request = remember(img.path, img.lastModified, maxDim) {
        val data: Any = when {
            img.path.contains("://") -> img.path // content:// preview, resolved by Coil
            StoragePaths.isRestricted(img.path) -> Thumb(img.path, FileType.IMAGE, img.lastModified)
            else -> File(img.path)
        }
        ImageRequest.Builder(context)
            .data(data)
            .size(maxDim, maxDim)
            .precision(Precision.INEXACT)
            .crossfade(true)
            .build()
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AsyncImage(
            model = request,
            contentDescription = img.name,
            contentScale = ContentScale.Fit,
            onSuccess = {
                loading = false
                onSize(Size(it.result.image.width.toFloat(), it.result.image.height.toFloat()))
            },
            onError = { loading = false; failed = true },
            modifier = Modifier.fillMaxSize().then(if (shared) Modifier.sharedElementKey("thumb-${img.path}") else Modifier),
        )
        if (loading) LoadingIndicator(Modifier.size(56.dp), color = Color.White)
        if (failed) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.BrokenImage, null, tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(64.dp))
                Spacer(Modifier.size(12.dp))
                Text("Aperçu indisponible pour ce format", color = Color.White)
                Spacer(Modifier.size(12.dp))
                FilledTonalButton(onClick = onOpenWith) {
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Ouvrir avec…")
                }
            }
        }
    }
}
