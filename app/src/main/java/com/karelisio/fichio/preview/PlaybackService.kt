package com.karelisio.fichio.preview

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.karelisio.fichio.MainActivity

/**
 * Media3 session service: background audio with the system media notification. The UI talks to
 * it through a MediaController (see [PlayerHub]).
 */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    @UnstableApi
    override fun onCreate() {
        super.onCreate()
        running = true
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                // Nothing left to play: let the service go away.
                if (state == Player.STATE_IDLE && player.mediaItemCount == 0) stopSelf()
            }
        })
        val open = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            this, 0, open.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player).setSessionActivity(pending).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        running = false
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    companion object {
        /** True while the service exists in this process (used to reconnect the mini player). */
        @Volatile
        var running: Boolean = false
            private set
    }
}
