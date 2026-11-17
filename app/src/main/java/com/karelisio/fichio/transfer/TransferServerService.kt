package com.karelisio.fichio.transfer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.karelisio.fichio.MainActivity
import com.karelisio.fichio.R

/**
 * Foreground service hosting the on-demand local HTTP transfer server (Ktor CIO).
 * The actual server implementation is added in the transfer step; this skeleton
 * keeps the manifest reference valid and the service lifecycle correct.
 */
class TransferServerService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        return START_NOT_STICKY
    }

    private fun buildNotification(): Notification {
        createChannel(this)
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shortcut_storage)
            .setContentTitle("Fichio")
            .setContentText("Serveur de transfert local actif")
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "fichio_transfer"
        private const val NOTIFICATION_ID = 1002

        fun createChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID, "Transfert local", NotificationManager.IMPORTANCE_LOW
                )
                (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                    .createNotificationChannel(channel)
            }
        }

        fun start(context: Context) {
            context.startForegroundService(Intent(context, TransferServerService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TransferServerService::class.java))
        }
    }
}
