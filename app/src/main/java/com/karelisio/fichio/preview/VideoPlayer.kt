package com.karelisio.fichio.preview

import android.content.pm.ActivityInfo
import android.net.Uri
import android.view.ViewGroup
import androidx.annotation.OptIn
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.karelisio.fichio.core.fs.StoragePaths
import com.karelisio.fichio.core.model.FileEntry
import com.karelisio.fichio.ui.components.BackButton
import kotlinx.coroutines.launch
import java.io.File

/** In-memory resume positions (path → ms), kept for the app session. */
internal object VideoPositions {
    private val map = object : LinkedHashMap<String, Long>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > 64
    }

    @Synchronized fun get(id: String): Long = map[id] ?: 0L
    @Synchronized fun put(id: String, pos: Long, duration: Long) {
        // Finished (or almost) videos restart from the beginning next time.
        if (duration > 0 && pos > duration - 3_000) map.remove(id) else if (pos > 2_000) map[id] = pos
    }
}

/**
 * Full-screen immersive video player (Media3 ExoPlayer + PlayerView controls). Rotates to
 * landscape for landscape videos, resumes at the last position. [uri] overrides the file
 * (content:// previews).
 */
@OptIn(UnstableApi::class)
@androidx.compose.runtime.Composable
internal fun VideoPlayerScreen(
    entry: FileEntry,
    onBack: () -> Unit,
    uri: Uri? = null,
    extraActions: (@Composable () -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val activity = remember(context) { context.findActivity() }
    var source by remember(entry.path, uri) { mutableStateOf(uri) }
    var error by remember { mutableStateOf<String?>(null) }
    var controlsVisible by remember { mutableStateOf(true) }
    var info by remember { mutableStateOf(false) }
    val id = uri?.toString() ?: entry.path

    LaunchedEffect(entry.path, uri) {
        if (uri == null) {
            source = runCatching {
                if (StoragePaths.isRestricted(entry.path)) Uri.fromFile(PreviewCache.localFile(context, entry.path))
                else Uri.fromFile(File(entry.path))
            }.getOrElse { error = "Lecture impossible : ${it.message}"; null }
        }
    }

    val player = remember {
        ExoPlayer.Builder(context)
            .setAudioAttributes(androidx.media3.common.AudioAttributes.DEFAULT, true)
            .build()
            .apply { repeatMode = Player.REPEAT_MODE_OFF }
    }
    ImmersiveMode(immersive = !controlsVisible)
    DarkSystemBars()

    // Orientation follows the video's aspect ratio; restored when leaving.
    DisposableEffect(activity) {
        val original = activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        onDispose { activity?.requestedOrientation = original }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width == 0 || videoSize.height == 0) return
                val ratio = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                activity?.requestedOrientation =
                    if (ratio > 1.05f) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }

            override fun onPlayerError(e: PlaybackException) {
                error = "Ce format vidéo n'est pas pris en charge (${e.errorCodeName})"
            }
        }
        player.addListener(listener)
        onDispose {
            VideoPositions.put(id, player.currentPosition, player.duration)
            player.removeListener(listener)
            player.release()
        }
    }
    LaunchedEffect(source) {
        val s = source ?: return@LaunchedEffect
        player.setMediaItem(MediaItem.fromUri(s), VideoPositions.get(id))
        player.prepare()
        player.playWhenReady = true
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    this.player = player
                    setShowNextButton(false)
                    setShowPreviousButton(false)
                    setShowSubtitleButton(true)
                    controllerShowTimeoutMs = 3_000
                    keepScreenOn = true
                    setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v ->
                        controlsVisible = v == android.view.View.VISIBLE
                    })
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (source == null && error == null) {
            ExpressiveLoading(Modifier.align(Alignment.Center))
        }
        error?.let { msg ->
            Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.ErrorOutline, null, tint = Color.White, modifier = Modifier.size(56.dp))
                Spacer(Modifier.size(12.dp))
                Text(msg, color = Color.White, style = MaterialTheme.typography.bodyLarge)
                if (uri == null) {
                    Spacer(Modifier.size(16.dp))
                    FilledTonalButton(onClick = { scope.launch { openWith(context, entry.path) } }) {
                        Icon(Icons.AutoMirrored.Rounded.OpenInNew, null)
                        Spacer(Modifier.size(8.dp))
                        Text("Ouvrir avec…")
                    }
                }
            }
        }
        AnimatedVisibility(
            visible = controlsVisible || error != null,
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            CompositionLocalProvider(LocalContentColor provides Color.White) {
                Row(
                    Modifier.fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)))
                        .statusBarsPadding().padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BackButton(onBack)
                    Text(entry.name, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis, modifier = Modifier.weight(1f))
                    if (uri == null) {
                        IconButton(onClick = { scope.launch { shareFile(context, entry.path) } }) { Icon(Icons.Rounded.Share, "Partager") }
                        IconButton(onClick = { scope.launch { openWith(context, entry.path) } }) { Icon(Icons.AutoMirrored.Rounded.OpenInNew, "Ouvrir avec") }
                    }
                    IconButton(onClick = { info = true }) { Icon(Icons.Rounded.Info, "Infos") }
                    extraActions?.invoke()
                }
            }
        }
    }
    if (info) InfoSheet(entry, onDismiss = { info = false })
}

@kotlin.OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ExpressiveLoading(modifier: Modifier = Modifier, color: Color = Color.White) {
    LoadingIndicator(modifier.size(64.dp), color = color)
}
