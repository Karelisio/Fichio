package com.karelisio.fichio.ops

import com.karelisio.fichio.core.util.Strings
import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.karelisio.fichio.MainActivity
import com.karelisio.fichio.R
import com.karelisio.fichio.container
import com.karelisio.fichio.core.util.Format
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Foreground service (dataSync) that keeps the process alive while jobs run and shows a
 * ProgressStyle notification, promoted as a Live Update (status chip / dynamic island apps)
 * with Pause/Resume and Cancel actions. Stops itself when the queue is idle.
 */
class FileOperationService : LifecycleService() {

    companion object {
        const val CHANNEL_ID = "file_operations"
        private const val NOTIFICATION_ID = 4201
        private const val SUMMARY_ID = 4202
        private const val ACTION_PAUSE = "com.karelisio.fichio.ops.PAUSE"
        private const val ACTION_RESUME = "com.karelisio.fichio.ops.RESUME"
        private const val ACTION_CANCEL = "com.karelisio.fichio.ops.CANCEL"
        private const val EXTRA_JOB = "job"
        private const val MAX_SEGMENTS = 6
        /** EXTRA_JOB value addressing every running job (notification of simultaneous jobs). */
        private const val ALL = -2L

        /** Starts (or pokes) the service. Safe to call often; never throws. */
        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, FileOperationService::class.java))
            }
        }

        fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, Strings.get(R.string.channel_operations), NotificationManager.IMPORTANCE_LOW).apply {
                    description = Strings.get(R.string.ops_channel_desc)
                    setShowBadge(false)
                },
            )
        }
    }

    private var collecting = false
    private var foreground = false
    private var lastStartId = 0
    /** Ids of jobs seen during this run of the service (for the end summary). */
    private val seen = LinkedHashSet<Long>()

    private val ops get() = applicationContext.container.operations

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        lastStartId = startId
        ensureChannel(this)
        // Must be promoted within a few seconds of startForegroundService, whatever happens next.
        goForeground(build(ops.jobs.value.filterNot { it.state.isFinished }))

        val id = intent?.getLongExtra(EXTRA_JOB, -1L) ?: -1L
        val unfinished = { ops.jobs.value.filterNot { it.state.isFinished } }
        val running = { if (id == ALL) unfinished().filterNot { it.state == JobState.QUEUED }.map { it.id } else listOf(id) }
        when (intent?.action) {
            ACTION_PAUSE -> running().forEach(ops::pause)
            ACTION_RESUME -> running().forEach(ops::resume)
            // « Tout annuler » also drops the queued jobs.
            ACTION_CANCEL -> (if (id == ALL) unfinished().map { it.id } else listOf(id)).forEach(ops::cancel)
        }

        if (!collecting) {
            collecting = true
            lifecycleScope.launch {
                ops.jobs.collect { jobs ->
                    render(jobs)
                    // Notification updates are rate-limited by the system; stay well below.
                    delay(450)
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15+ dataSync time limit: jobs keep running in-process, we just drop the FGS.
        stopForegroundCompat()
        stopSelf()
    }

    private fun render(jobs: List<Job>) {
        val active = jobs.filterNot { it.state.isFinished }
        active.forEach { seen += it.id }
        if (active.isEmpty()) {
            finishRun(jobs)
            return
        }
        notifySafe(NOTIFICATION_ID, build(active))
    }

    private fun finishRun(jobs: List<Job>) {
        val finished = jobs.filter { it.id in seen && it.state.isFinished }
        seen.clear()
        stopForegroundCompat()
        postSummary(finished)
        // Only stops if no newer start request arrived meanwhile (it would expect startForeground).
        stopSelfResult(lastStartId)
    }

    private fun goForeground(notification: Notification) {
        runCatching {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
            )
            foreground = true
        }.onFailure {
            // e.g. ForegroundServiceStartNotAllowedException: jobs still run, without notification.
            stopSelf()
        }
    }

    private fun stopForegroundCompat() {
        if (foreground) runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        foreground = false
    }

    private fun canNotify(): Boolean =
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(this).areNotificationsEnabled()

    @Suppress("MissingPermission")
    private fun notifySafe(id: Int, notification: Notification) {
        if (!canNotify()) return
        try {
            NotificationManagerCompat.from(this).notify(id, notification)
        } catch (_: SecurityException) {
        }
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun actionIntent(action: String, jobId: Long): PendingIntent = PendingIntent.getService(
        this, (action.hashCode() * 31 + jobId.toInt()),
        Intent(this, FileOperationService::class.java).setAction(action).putExtra(EXTRA_JOB, jobId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun build(active: List<Job>): Notification {
        val job = active.firstOrNull { it.state == JobState.RUNNING || it.state == JobState.PAUSED || it.state == JobState.WAITING_CONFLICT }
            ?: active.firstOrNull()
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notif_ops)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openAppIntent())
            .setRequestPromotedOngoing(true)

        if (job == null) {
            return b.setContentTitle(Strings.get(R.string.channel_operations)).setContentText(Strings.get(R.string.ops_preparing))
                .setStyle(NotificationCompat.ProgressStyle().setProgressIndeterminate(true))
                .build()
        }

        // Jobs actually moving (several when « Opérations simultanées » > 1).
        val live = active.filter { it.state == JobState.RUNNING || it.state == JobState.PAUSED || it.state == JobState.WAITING_CONFLICT }
        val multi = live.size > 1
        val p = if (multi) aggregate(live) else job.progress
        val pct = (p.fraction * 100).roundToInt().coerceIn(0, 100)
        val queued = active.size - live.size.coerceAtLeast(1)
        val title = when {
            multi -> Strings.plural(R.plurals.ops_notif_running, live.size, live.size) + if (queued > 0) " (+$queued)" else ""
            active.size > 1 -> "${job.title} (+${active.size - 1})"
            else -> job.title
        }
        val allPaused = live.isNotEmpty() && live.all { it.state == JobState.PAUSED }
        val text = when {
            live.any { it.state == JobState.WAITING_CONFLICT } -> Strings.get(R.string.ops_notif_conflict)
            job.state == JobState.PAUSED && (!multi || allPaused) -> Strings.get(R.string.ops_notif_paused, pct)
            job.state == JobState.QUEUED -> Strings.get(R.string.ops_state_queued)
            else -> buildString {
                if (p.totalBytes > 0) append(Strings.get(R.string.ops_progress_bytes, Format.size(p.doneBytes), Format.size(p.totalBytes)))
                else if (p.totalItems > 0) append(Strings.get(R.string.ops_progress_items, p.doneItems, p.totalItems))
                if (p.speed > 0) append(" · ${Format.size(p.speed)}/s")
                remaining(p)?.let { append(" · $it") }
            }.ifEmpty { Strings.get(R.string.ops_preparing) }
        }
        val sub = if (multi) live.joinToString(" · ") { it.progress.currentName.ifEmpty { it.title } }
        else p.currentName.takeIf { it.isNotEmpty() && job.state == JobState.RUNNING }

        // Several jobs (queued or simultaneous): one segment per job, each filling with its own
        // progress, so parallel jobs advance side by side on the same bar.
        val style = NotificationCompat.ProgressStyle().setStyledByProgress(true)
        val runJobs = ops.jobs.value.filter { it.id in seen || !it.state.isFinished }.takeLast(MAX_SEGMENTS)
        if (runJobs.size > 1) {
            val colors = intArrayOf(
                ContextCompat.getColor(this, android.R.color.system_accent1_500),
                ContextCompat.getColor(this, android.R.color.system_accent3_500),
            ).takeIf { Build.VERSION.SDK_INT >= Build.VERSION_CODES.S }
            var progress = 0
            runJobs.forEachIndexed { i, j ->
                style.addProgressSegment(
                    NotificationCompat.ProgressStyle.Segment(100).apply { colors?.let { setColor(it[i % 2]) } },
                )
                progress += when {
                    j.state.isFinished -> 100
                    else -> (j.progress.fraction * 100).roundToInt()
                }
            }
            style.setProgress(progress)
        } else {
            style.setProgress(pct)
        }
        style.setProgressIndeterminate(
            !multi && (job.state == JobState.QUEUED || (job.progress.indeterminate && job.state == JobState.RUNNING)),
        )

        b.setContentTitle(title)
            .setContentText(text)
            .setSubText(sub)
            .setShortCriticalText(if (multi) "${live.size} · $pct %" else "$pct %")
            .setStyle(style)

        // With several running jobs, the actions apply to all of them (EXTRA_JOB = ALL).
        val target = if (multi) ALL else job.id
        if (allPaused || (!multi && job.state == JobState.PAUSED)) {
            b.addAction(0, if (multi) Strings.get(R.string.ops_resume_all) else Strings.get(R.string.ops_resume), actionIntent(ACTION_RESUME, target))
        } else if (multi || job.state == JobState.RUNNING) {
            b.addAction(0, if (multi) Strings.get(R.string.ops_pause_all) else Strings.get(R.string.ops_pause), actionIntent(ACTION_PAUSE, target))
        }
        b.addAction(0, if (multi) Strings.get(R.string.ops_cancel_all) else Strings.get(R.string.common_cancel), actionIntent(ACTION_CANCEL, target))
        return b.build()
    }

    /** Sum of the progress of jobs running side by side (bytes, items, speed). */
    private fun aggregate(jobs: List<Job>): JobProgress {
        val ps = jobs.map { it.progress }
        return JobProgress(
            doneBytes = ps.sumOf { it.doneBytes },
            totalBytes = ps.sumOf { it.totalBytes },
            doneItems = ps.sumOf { it.doneItems },
            totalItems = ps.sumOf { it.totalItems },
            currentName = "",
            speed = jobs.filter { it.state == JobState.RUNNING }.sumOf { it.progress.speed },
        )
    }

    private fun remaining(p: JobProgress): String? {
        if (p.speed <= 0 || p.totalBytes <= 0) return null
        val sec = (p.totalBytes - p.doneBytes).coerceAtLeast(0) / p.speed
        return when {
            sec < 5 -> null
            sec < 60 -> Strings.get(R.string.ops_left_seconds, sec)
            sec < 3600 -> Strings.get(R.string.ops_left_minutes, sec / 60)
            else -> Strings.get(R.string.ops_left_hours, sec / 3600, (sec % 3600) / 60)
        }
    }

    /** Brief end-of-run notification, only when the app is not visible (the UI shows a snackbar). */
    private fun postSummary(finished: List<Job>) {
        if (finished.isEmpty()) return
        val visible = runCatching {
            ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        }.getOrDefault(false)
        val failures = finished.filter { it.state == JobState.FAILED }
        if (visible && failures.isEmpty()) return
        val done = finished.count { it.state == JobState.DONE }
        val title = when {
            failures.isNotEmpty() && finished.size == 1 -> Strings.get(R.string.ops_notif_failed_one, failures[0].title)
            failures.isNotEmpty() -> Strings.plural(R.plurals.ops_notif_failed_many, failures.size, failures.size)
            finished.size == 1 -> Strings.get(R.string.ops_notif_done_one, finished[0].title)
            else -> Strings.plural(R.plurals.ops_notif_done_many, done, done)
        }
        val text = failures.firstOrNull()?.error ?: finished.lastOrNull()?.let { j ->
            if (j.progress.totalBytes > 0) Format.size(j.progress.totalBytes) else Format.items(j.progress.totalItems.coerceAtLeast(j.sources.size))
        }
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notif_ops)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setSilent(true)
            .setContentIntent(openAppIntent())
            .setTimeoutAfter(if (failures.isEmpty()) 6_000 else 60_000)
            .build()
        notifySafe(SUMMARY_ID, n)
    }
}
