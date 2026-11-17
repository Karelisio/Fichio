@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.karelisio.fichio.preview

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toPath
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import coil3.compose.AsyncImage
import com.karelisio.fichio.container
import com.karelisio.fichio.core.fs.StoragePaths
import com.karelisio.fichio.core.model.FileEntry
import com.karelisio.fichio.core.model.FileType
import com.karelisio.fichio.core.util.Format
import com.karelisio.fichio.ui.components.BackButton
import com.karelisio.fichio.ui.components.FileIcon
import com.karelisio.fichio.ui.components.Thumb
import com.karelisio.fichio.ui.components.bouncy
import com.karelisio.fichio.ui.components.pressScale
import com.karelisio.fichio.ui.components.rememberHaptics
import com.karelisio.fichio.ui.theme.LocalReduceMotion
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/** Shape morphing between two normalised polygons, optionally rotated around its centre. */
class MorphShape(private val morph: Morph, private val progress: Float, private val rotation: Float = 0f) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val path = morph.toPath(progress)
        if (rotation != 0f) {
            path.translate(Offset(-0.5f, -0.5f))
            path.transform(Matrix().apply { rotateZ(rotation) })
            path.translate(Offset(0.5f, 0.5f))
        }
        path.transform(Matrix().apply { scale(size.width, size.height) })
        return Outline.Generic(path)
    }
}

@Composable
private fun rememberMorph(from: RoundedPolygon, to: RoundedPolygon) = remember(from, to) { Morph(from, to) }

/** Audio files of the same folder, sorted by name. */
private suspend fun siblingAudio(context: android.content.Context, entry: FileEntry): List<String> = runCatching {
    context.container.fileSystems.list(entry.parentPath)
        .filter { !it.isDirectory && it.type == FileType.AUDIO && (!it.isHidden || it.path == entry.path) }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        .map { it.path }
}.getOrNull()?.takeIf { entry.path in it } ?: listOf(entry.path)

/**
 * Expressive audio player. Playback runs in [PlaybackService]; this screen only drives the
 * MediaController. [cached] is set for content:// previews (played from a cache copy).
 */
@Composable
internal fun AudioPlayerScreen(
    entry: FileEntry,
    onBack: () -> Unit,
    onShowInFolder: ((String) -> Unit)?,
    cached: File? = null,
    extraActions: (@Composable () -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val reduce = LocalReduceMotion.current
    val now by PlayerHub.state.collectAsStateWithLifecycle()
    var info by remember { mutableStateOf<FileEntry?>(null) }

    // Start (or keep) playback of this file.
    LaunchedEffect(entry.path) {
        PlayerHub.reconnectIfRunning(context)
        delay(60) // let a reconnecting controller publish its state
        if (PlayerHub.state.value?.id == entry.path) return@LaunchedEffect
        when {
            cached != null -> PlayerHub.playCached(context, entry.path, cached, entry.nameWithoutExtension)
            StoragePaths.isRestricted(entry.path) -> runCatching {
                PlayerHub.playCached(context, entry.path, PreviewCache.localFile(context, entry.path), entry.nameWithoutExtension)
            }
            else -> {
                val list = siblingAudio(context, entry)
                PlayerHub.playFiles(context, list, list.indexOf(entry.path).coerceAtLeast(0))
            }
        }
    }

    val playing = now?.isPlaying == true
    val title = now?.title ?: entry.nameWithoutExtension
    val currentPath = now?.id?.takeIf { it.startsWith("/") } ?: entry.path
    val currentEntry = remember(currentPath) {
        if (currentPath == entry.path) entry else File(currentPath).toEntry()
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        // Soft, blurred artwork backdrop.
        CoverImage(
            now = now, path = currentPath, lastModified = currentEntry.lastModified,
            modifier = Modifier.fillMaxSize().blur(80.dp).graphicsLayer { alpha = 0.35f },
            fallback = {},
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color.Transparent, MaterialTheme.colorScheme.surface.copy(alpha = 0.9f), MaterialTheme.colorScheme.surface)),
            ),
        )
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
                BackButton(onBack)
                Text(
                    if ((now?.count ?: 0) > 1) "Lecture · ${now!!.index + 1} / ${now!!.count}" else "Lecture",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (currentEntry.path.startsWith("/") && cached == null) {
                    IconButton(onClick = { scope.launch { shareFile(context, currentEntry.path) } }) { Icon(Icons.Rounded.Share, "Partager") }
                    if (onShowInFolder != null) IconButton(onClick = { onShowInFolder(currentEntry.parentPath) }) { Icon(Icons.Rounded.FolderOpen, "Afficher dans le dossier") }
                }
                IconButton(onClick = { info = currentEntry }) { Icon(Icons.Rounded.Info, "Infos") }
                extraActions?.invoke()
            }

            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val side = min(maxWidth - 56.dp, maxHeight - 24.dp).coerceAtLeast(120.dp).coerceAtMost(380.dp)
                MorphingCover(now = now, path = currentPath, lastModified = currentEntry.lastModified, playing = playing, reduce = reduce, modifier = Modifier.size(side))
            }

            Column(Modifier.fillMaxWidth().padding(horizontal = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                AnimatedContent(
                    targetState = title to now?.artist,
                    transitionSpec = { (fadeIn() + slideInVertically { it / 3 }) togetherWith (fadeOut() + slideOutVertically { -it / 3 }) },
                    label = "title",
                ) { (t, a) ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        Text(t, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                        Text(
                            listOfNotNull(a, now?.album).joinToString(" · ").ifEmpty { "Artiste inconnu" },
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Normal),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.height(20.dp))
                WavySeekBar(now = now, playing = playing, reduce = reduce)
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    val shuffle = now?.shuffle == true
                    IconToggleButton(checked = shuffle, onCheckedChange = { haptics.tick(); PlayerHub.toggleShuffle() }) {
                        Icon(Icons.Rounded.Shuffle, "Lecture aléatoire", tint = if (shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    FilledTonalIconButton(onClick = { haptics.tick(); PlayerHub.previous() }, modifier = Modifier.size(60.dp)) {
                        Icon(Icons.Rounded.SkipPrevious, "Précédent", Modifier.size(30.dp))
                    }
                    PlayPauseButton(playing = playing, buffering = now?.buffering == true) {
                        haptics.click()
                        if (now == null) {
                            scope.launch {
                                val list = if (cached == null && !StoragePaths.isRestricted(entry.path)) siblingAudio(context, entry) else emptyList()
                                if (list.isNotEmpty()) PlayerHub.playFiles(context, list, list.indexOf(entry.path).coerceAtLeast(0))
                                else PlayerHub.playCached(context, entry.path, cached ?: PreviewCache.localFile(context, entry.path), entry.nameWithoutExtension)
                            }
                        } else PlayerHub.toggle()
                    }
                    FilledTonalIconButton(
                        onClick = { haptics.tick(); PlayerHub.next() },
                        enabled = now?.hasNext == true,
                        modifier = Modifier.size(60.dp),
                    ) { Icon(Icons.Rounded.SkipNext, "Suivant", Modifier.size(30.dp)) }
                    val repeat = now?.repeat ?: Player.REPEAT_MODE_OFF
                    IconToggleButton(checked = repeat != Player.REPEAT_MODE_OFF, onCheckedChange = { haptics.tick(); PlayerHub.cycleRepeat() }) {
                        Icon(
                            if (repeat == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                            "Répéter",
                            tint = if (repeat != Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(28.dp))
            }
        }
    }
    info?.let { InfoSheet(it, onDismiss = { info = null }) }
}

@Composable
private fun CoverImage(
    now: NowPlaying?,
    path: String,
    lastModified: Long,
    modifier: Modifier,
    fallback: @Composable () -> Unit,
) {
    var failed by remember(path) { mutableStateOf(false) }
    val art = now?.artwork?.takeIf { now.id == path }
    val model: Any? = when {
        art != null -> art
        path.startsWith("/") && !StoragePaths.isRestricted(path) -> Thumb(path, FileType.AUDIO, lastModified)
        else -> null
    }
    if (model == null || failed) { fallback(); return }
    AsyncImage(
        model = model,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        onError = { failed = true },
        modifier = modifier,
    )
}

@Composable
private fun MorphingCover(now: NowPlaying?, path: String, lastModified: Long, playing: Boolean, reduce: Boolean, modifier: Modifier) {
    val morph = rememberMorph(MaterialShapes.Square, MaterialShapes.Cookie9Sided)
    val progress by animateFloatAsState(if (playing && !reduce) 1f else 0f, bouncy(), label = "morph")
    val rotation = if (playing && !reduce) {
        val t = rememberInfiniteTransition(label = "spin")
        t.animateFloat(0f, 360f, infiniteRepeatable(tween(24_000, easing = LinearEasing)), label = "rot").value
    } else 0f
    val scale by animateFloatAsState(if (playing || reduce) 1f else 0.92f, bouncy(), label = "coverScale")
    val shape = MorphShape(morph, progress, rotation * progress)
    Box(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale; shadowElevation = 24f; this.shape = shape; clip = true }
            .background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        CoverImage(now, path, lastModified, Modifier.fillMaxSize()) {
            Icon(Icons.Rounded.MusicNote, null, Modifier.fillMaxSize(0.42f), tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}

@Composable
private fun PlayPauseButton(playing: Boolean, buffering: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val width by animateDpAsState(if (playing) 112.dp else 88.dp, bouncy(), label = "w")
    val corner by animateDpAsState(if (playing) 28.dp else 44.dp, bouncy(), label = "c")
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(corner),
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        interactionSource = interaction,
        modifier = Modifier.height(88.dp).width(width).pressScale(interaction, 0.9f),
    ) {
        Box(contentAlignment = Alignment.Center) {
            AnimatedContent(
                targetState = playing,
                transitionSpec = { (scaleIn(bouncy(), initialScale = 0.4f) + fadeIn()) togetherWith (scaleOut(targetScale = 0.4f) + fadeOut()) },
                label = "pp",
            ) { p ->
                Icon(if (p) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (p) "Pause" else "Lecture", Modifier.size(40.dp))
            }
            if (buffering) androidx.compose.material3.LoadingIndicator(Modifier.size(84.dp), color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.4f))
        }
    }
}

@Composable
private fun WavySeekBar(now: NowPlaying?, playing: Boolean, reduce: Boolean) {
    val duration = now?.durationMs ?: 0L
    var position by remember { mutableLongStateOf(0L) }
    val slider = remember { SliderState(0f) }
    LaunchedEffect(now?.id, playing, duration) {
        while (true) {
            position = PlayerHub.position()
            if (!slider.isDragging && duration > 0) slider.value = (position.toFloat() / duration).coerceIn(0f, 1f)
            delay(if (playing) 200 else 600)
        }
    }
    val amplitude by animateFloatAsState(if (playing && !reduce) 1f else 0f, label = "amp")
    Slider(
        state = slider,
        onValueChange = { slider.value = it },
        onValueChangeFinished = { if (duration > 0) PlayerHub.seekTo((slider.value * duration).toLong()) },
        enabled = duration > 0,
        modifier = Modifier.fillMaxWidth(),
        track = { _ ->
            LinearWavyProgressIndicator(
                progress = { slider.value },
                modifier = Modifier.fillMaxWidth().height(14.dp),
                amplitude = { amplitude },
                wavelength = 36.dp,
            )
        },
    )
    Row(Modifier.fillMaxWidth()) {
        val shown = if (slider.isDragging) (slider.value * duration).toLong() else position
        Text(Format.duration(shown), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        Text(if (duration > 0) Format.duration(duration) else "--:--", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Floating mini player, visible while something is loaded in [PlaybackService]. */
@Composable
internal fun MiniPlayer(onOpen: (String) -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val reduce = LocalReduceMotion.current
    val haptics = rememberHaptics()
    LaunchedEffect(Unit) { PlayerHub.reconnectIfRunning(context) }
    val state by PlayerHub.state.collectAsStateWithLifecycle()
    var last by remember { mutableStateOf<NowPlaying?>(null) }
    if (state != null) last = state
    AnimatedVisibility(
        visible = state != null,
        enter = if (reduce) fadeIn() else slideInVertically(bouncy()) { it } + scaleIn(bouncy(), initialScale = 0.85f) + fadeIn(),
        exit = if (reduce) fadeOut() else slideOutVertically { it } + scaleOut(targetScale = 0.9f) + fadeOut(),
        modifier = modifier,
    ) {
        val np = last ?: return@AnimatedVisibility
        val interaction = remember { MutableInteractionSource() }
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            shadowElevation = 6.dp,
            tonalElevation = 3.dp,
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .pressScale(interaction, 0.97f)
                .clip(RoundedCornerShape(28.dp))
                .clickable(interactionSource = interaction, indication = androidx.compose.material3.ripple()) { haptics.click(); onOpen(np.id) },
        ) {
            Column {
                Row(Modifier.padding(start = 8.dp, end = 4.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    val morph = rememberMorph(MaterialShapes.Circle, MaterialShapes.SoftBurst)
                    val p by animateFloatAsState(if (np.isPlaying && !reduce) 1f else 0f, bouncy(), label = "miniMorph")
                    Box(
                        Modifier.size(44.dp).graphicsLayer { shape = MorphShape(morph, p); clip = true }
                            .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        CoverImage(np, np.id, 0L, Modifier.fillMaxSize()) {
                            FileIcon(FileType.AUDIO, size = 44.dp)
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(np.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            np.artist ?: if (np.count > 1) "${np.index + 1} / ${np.count}" else "Fichio",
                            style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = { haptics.tick(); PlayerHub.toggle() }) {
                        AnimatedContent(np.isPlaying, transitionSpec = { scaleIn(bouncy()) togetherWith scaleOut() }, label = "miniPP") { pl ->
                            Icon(if (pl) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (pl) "Pause" else "Lecture")
                        }
                    }
                    IconButton(onClick = { haptics.confirm(); PlayerHub.stop() }) { Icon(Icons.Rounded.Close, "Arrêter") }
                }
                MiniProgress(np)
            }
        }
    }
}

@Composable
private fun MiniProgress(np: NowPlaying) {
    var fraction by remember { mutableStateOf(0f) }
    LaunchedEffect(np.id, np.isPlaying, np.durationMs) {
        while (true) {
            fraction = if (np.durationMs > 0) (PlayerHub.position().toFloat() / np.durationMs).coerceIn(0f, 1f) else 0f
            delay(500)
        }
    }
    LinearWavyProgressIndicator(
        progress = { fraction },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 8.dp).height(8.dp),
        amplitude = { if (np.isPlaying) 1f else 0f },
        wavelength = 20.dp,
    )
}
