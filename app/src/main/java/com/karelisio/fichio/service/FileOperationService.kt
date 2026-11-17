package com.karelisio.fichio.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import com.karelisio.fichio.MainActivity
import com.karelisio.fichio.R
import com.karelisio.fichio.domain.operations.ConflictAction
import com.karelisio.fichio.domain.operations.FileTransferEngine
import com.karelisio.fichio.domain.operations.OperationKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.io.File
import java.util.ArrayDeque

/**
 * Foreground service executing a queue of copy/move/delete operations with
 * byte-accurate progress, pause/resume and cancellation. The notification uses
 * Android 16's ProgressStyle when available and falls back to a classic
 * determinate progress bar otherwise.
 */
class FileOperationService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val engine = FileTransferEngine()
    private val queue = ArrayDeque<JobSpec>()

    private var running: Job? = null
    private var current: JobSpec? = null
    private var lastPercent = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> engine.setPaused(true)
            ACTION_RESUME -> engine.setPaused(false)
            ACTION_CANCEL -> running?.cancel()
            else -> {
                val spec = JobSpec.from(intent) ?: return START_NOT_STICKY
                queue.addLast(spec)
                startForeground(NOTIFICATION_ID, buildNotification(0, "En attente…", false))
                pump()
            }
        }
        return START_NOT_STICKY
    }

    private fun pump() {
        if (running?.isActive == true) return
        val next = queue.pollFirst() ?: run {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        current = next
        running = scope.launch {
            notifyProgress(0, next.label, false)
            val job = scope.launch {
                engine.progress.collect { p ->
                    val percent = (p.fraction * 100).toInt()
                    if (percent != lastPercent) {
                        lastPercent = percent
                        notifyProgress(percent, p.currentName.ifEmpty { next.label }, engine.paused.value)
                    }
                }
            }
            val outcome = when (next.kind) {
                OperationKind.COPY -> engine.copy(
                    next.sources.map(::File), File(next.destination),
                    defaultConflict = next.conflict
                )
                OperationKind.MOVE -> engine.move(
                    next.sources.map(::File), File(next.destination),
                    defaultConflict = next.conflict
                )
                OperationKind.DELETE -> engine.delete(next.sources.map(::File))
            }
            job.cancel()
            notifyFinished(next, outcome)
            running = null
            lastPercent = -1
            engine.setPaused(false)
            pump()
        }
    }

    private fun notifyProgress(percent: Int, text: String, paused: Boolean) {
        val n = buildNotification(percent, text, paused)
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, n)
    }

    private fun notifyFinished(spec: JobSpec, outcome: com.karelisio.fichio.domain.operations.OperationOutcome) {
        val title = when (spec.kind) {
            OperationKind.COPY -> "Copie terminée"
            OperationKind.MOVE -> "Déplacement terminé"
            OperationKind.DELETE -> "Suppression terminée"
        }
        val text = if (outcome.cancelled) "Opération annulée" else
            "${outcome.completedFiles} fichier(s), ${outcome.skippedFiles} ignoré(s)"
        val n = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shortcut_storage)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(activityIntent())
            .setAutoCancel(true)
            .build()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID + 1, n)
    }

    private fun buildNotification(percent: Int, text: String, paused: Boolean): Notification {
        createChannel(this)
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shortcut_storage)
            .setContentTitle("Fichio")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setContentIntent(activityIntent())
            .addAction(action(if (paused) ACTION_RESUME else ACTION_PAUSE, "Pause", REQ_PAUSE))
            .addAction(action(ACTION_CANCEL, "Annuler", REQ_CANCEL))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            builder.setStyle(Notification.ProgressStyle().setProgress(percent))
        } else {
            builder.setProgress(100, percent, false)
        }
        return builder.build()
    }

    private fun action(action: String, label: String, requestCode: Int): Notification.Action {
        val intent = Intent(this, FileOperationService::class.java).setAction(action)
        val pi = PendingIntent.getService(
            this, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                label, pi
            ).build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, label, pi).build()
        }
    }

    private fun activityIntent(): PendingIntent =
        PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private data class JobSpec(
        val kind: OperationKind,
        val sources: List<String>,
        val destination: String,
        val conflict: ConflictAction
    ) {
        val label: String get() = when (kind) {
            OperationKind.COPY -> "Copie en cours…"
            OperationKind.MOVE -> "Déplacement en cours…"
            OperationKind.DELETE -> "Suppression en cours…"
        }

        companion object {
            fun from(intent: Intent?): JobSpec? {
                if (intent == null) return null
                val kind = when (intent.getStringExtra(EXTRA_OP)) {
                    "copy" -> OperationKind.COPY
                    "move" -> OperationKind.MOVE
                    "delete" -> OperationKind.DELETE
                    else -> return null
                }
                val sources = intent.getStringArrayListExtra(EXTRA_SOURCES) ?: return null
                val dest = intent.getStringExtra(EXTRA_DEST) ?: ""
                val conflict = runCatching {
                    ConflictAction.valueOf(intent.getStringExtra(EXTRA_CONFLICT) ?: "KEEP_BOTH")
                }.getOrDefault(ConflictAction.KEEP_BOTH)
                return JobSpec(kind, sources, dest, conflict)
            }
        }
    }

    companion object {
        private const val CHANNEL_ID = "fichio_operations"
        private const val NOTIFICATION_ID = 1001
        private const val REQ_PAUSE = 1
        private const val REQ_CANCEL = 2

        private const val EXTRA_OP = "op"
        private const val EXTRA_SOURCES = "sources"
        private const val EXTRA_DEST = "dest"
        private const val EXTRA_CONFLICT = "conflict"

        private const val ACTION_PAUSE = "com.karelisio.fichio.action.PAUSE"
        private const val ACTION_RESUME = "com.karelisio.fichio.action.RESUME"
        private const val ACTION_CANCEL = "com.karelisio.fichio.action.CANCEL"

        fun createChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID, "Opérations de fichiers",
                    NotificationManager.IMPORTANCE_LOW
                )
                (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                    .createNotificationChannel(channel)
            }
        }

        fun enqueue(
            context: Context,
            kind: OperationKind,
            sources: List<String>,
            destination: String,
            conflict: ConflictAction = ConflictAction.KEEP_BOTH
        ) {
            val intent = Intent(context, FileOperationService::class.java).apply {
                putExtra(EXTRA_OP, when (kind) {
                    OperationKind.COPY -> "copy"
                    OperationKind.MOVE -> "move"
                    OperationKind.DELETE -> "delete"
                })
                putStringArrayListExtra(EXTRA_SOURCES, ArrayList(sources))
                putExtra(EXTRA_DEST, destination)
                putExtra(EXTRA_CONFLICT, conflict.name)
            }
            context.startForegroundService(intent)
        }

        fun pause(context: Context) =
            context.startForegroundService(
                Intent(context, FileOperationService::class.java).setAction(ACTION_PAUSE)
            )

        fun resume(context: Context) =
            context.startForegroundService(
                Intent(context, FileOperationService::class.java).setAction(ACTION_RESUME)
            )

        fun cancel(context: Context) =
            context.startForegroundService(
                Intent(context, FileOperationService::class.java).setAction(ACTION_CANCEL)
            )
    }
}
