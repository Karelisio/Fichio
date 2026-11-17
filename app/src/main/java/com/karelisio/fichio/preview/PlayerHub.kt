package com.karelisio.fichio.preview

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.karelisio.fichio.core.fs.StoragePaths
import com.karelisio.fichio.core.model.FileTypes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** What the mini player and the audio screen display. */
@Immutable
data class NowPlaying(
    /** Original file path (or content Uri string) of the current item. */
    val id: String,
    val title: String,
    val artist: String?,
    val album: String?,
    val isPlaying: Boolean,
    val buffering: Boolean,
    val durationMs: Long,
    val index: Int,
    val count: Int,
    val repeat: Int,
    val shuffle: Boolean,
    val artwork: ByteArray?,
) {
    val hasNext: Boolean get() = index < count - 1 || repeat == Player.REPEAT_MODE_ALL
    val hasPrevious: Boolean get() = count > 0
    val isLocalFile: Boolean get() = id.startsWith("/")

    override fun equals(other: Any?): Boolean = other is NowPlaying && id == other.id && title == other.title &&
        artist == other.artist && album == other.album && isPlaying == other.isPlaying && buffering == other.buffering &&
        durationMs == other.durationMs && index == other.index && count == other.count && repeat == other.repeat &&
        shuffle == other.shuffle && (artwork === other.artwork || artwork.contentEquals(other.artwork))

    override fun hashCode(): Int = id.hashCode() * 31 + isPlaying.hashCode()
}

/**
 * Process-wide MediaController connected to [PlaybackService]. Every call must happen on the main
 * thread (Compose callbacks do). Commands issued before the connection completes are queued.
 */
object PlayerHub {
    private var controller: MediaController? = null
    private var future: ListenableFuture<MediaController>? = null
    private val pending = ArrayList<(MediaController) -> Unit>()
    private lateinit var appContext: Context

    private val _state = MutableStateFlow<NowPlaying?>(null)
    val state: StateFlow<NowPlaying?> = _state.asStateFlow()

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = publish()
    }

    fun connect(context: Context) {
        appContext = context.applicationContext
        if (controller != null || future != null) return
        val token = SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
        val f = MediaController.Builder(appContext, token).buildAsync()
        future = f
        f.addListener({
            val c = runCatching { f.get() }.getOrNull()
            if (c == null) { future = null; pending.clear(); return@addListener }
            controller = c
            c.addListener(listener)
            pending.toList().also { pending.clear() }.forEach { it(c) }
            publish()
        }, ContextCompat.getMainExecutor(appContext))
    }

    /** Re-attaches to a service that kept playing while the UI was gone. */
    fun reconnectIfRunning(context: Context) {
        if (PlaybackService.running) connect(context)
    }

    private fun withController(context: Context?, block: (MediaController) -> Unit) {
        val c = controller
        if (c != null) { block(c); return }
        pending += block
        connect(context ?: appContext)
    }

    /** Plays [paths] (local files) starting at [startIndex]. */
    fun playFiles(context: Context, paths: List<String>, startIndex: Int) {
        val items = paths.map { mediaItemFor(it) }
        withController(context) { c ->
            c.setMediaItems(items, startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0)), 0L)
            c.prepare()
            c.play()
        }
    }

    /** Plays a single cached copy while keeping [id] (original path / Uri) as identity. */
    fun playCached(context: Context, id: String, file: File, title: String) {
        val item = MediaItem.Builder()
            .setMediaId(id)
            .setUri(Uri.fromFile(file))
            .setMimeType(FileTypes.mimeOfName(file.name).takeIf { it.startsWith("audio/") })
            .setMediaMetadata(MediaMetadata.Builder().setDisplayTitle(title).build())
            .build()
        withController(context) { c ->
            c.setMediaItem(item)
            c.prepare()
            c.play()
        }
    }

    private fun mediaItemFor(path: String): MediaItem = MediaItem.Builder()
        .setMediaId(path)
        .setUri(Uri.fromFile(File(path)))
        .setMediaMetadata(MediaMetadata.Builder().setDisplayTitle(StoragePaths.name(path).substringBeforeLast('.')).build())
        .build()

    fun toggle() = controller?.let { if (it.isPlaying) it.pause() else { if (it.playbackState == Player.STATE_ENDED) it.seekTo(0); it.play() } }
    fun play() = controller?.play()
    fun pause() = controller?.pause()

    fun next() = controller?.let { if (it.hasNextMediaItem()) it.seekToNextMediaItem() }

    /** Restarts the track after 3 s, otherwise goes to the previous one. */
    fun previous() = controller?.let {
        if (it.currentPosition > 3_000 || !it.hasPreviousMediaItem()) it.seekTo(0) else it.seekToPreviousMediaItem()
    }

    fun seekTo(ms: Long) = controller?.seekTo(ms)

    fun cycleRepeat() = controller?.let {
        it.repeatMode = when (it.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun toggleShuffle() = controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled }

    fun position(): Long = controller?.currentPosition ?: 0L
    fun bufferedPosition(): Long = controller?.bufferedPosition ?: 0L

    /** Stops playback, empties the queue and releases the connection (the service then stops). */
    fun stop() {
        controller?.let {
            it.stop()
            it.clearMediaItems()
            it.removeListener(listener)
            it.release()
        }
        controller = null
        future = null
        pending.clear()
        _state.value = null
    }

    private fun publish() {
        val c = controller ?: run { _state.value = null; return }
        val item = c.currentMediaItem
        if (item == null || c.mediaItemCount == 0) { _state.value = null; return }
        val md = c.mediaMetadata
        val id = item.mediaId
        val fallback = item.mediaMetadata.displayTitle?.toString() ?: id.substringAfterLast('/').substringBeforeLast('.')
        _state.value = NowPlaying(
            id = id,
            title = md.title?.toString()?.takeIf { it.isNotBlank() } ?: fallback,
            artist = (md.artist ?: md.albumArtist)?.toString()?.takeIf { it.isNotBlank() },
            album = md.albumTitle?.toString()?.takeIf { it.isNotBlank() },
            isPlaying = c.isPlaying,
            buffering = c.playbackState == Player.STATE_BUFFERING,
            durationMs = c.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L,
            index = c.currentMediaItemIndex,
            count = c.mediaItemCount,
            repeat = c.repeatMode,
            shuffle = c.shuffleModeEnabled,
            artwork = md.artworkData,
        )
    }
}
