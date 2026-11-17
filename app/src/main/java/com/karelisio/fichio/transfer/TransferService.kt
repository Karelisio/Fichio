package com.karelisio.fichio.transfer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.getSystemService
import com.karelisio.fichio.container
import com.karelisio.fichio.core.fs.StoragePaths
import com.karelisio.fichio.data.db.TransferLogEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.BindException

/**
 * Foreground service (dataSync) running the local Ktor server. Stops by itself after
 * `transferIdleMinutes` without requests and when the Wi-Fi address disappears.
 */
class TransferService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var server: TransferServer? = null
    private var auth: TransferAuth? = null
    private var watchdog: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown(null)
            return START_NOT_STICKY
        }
        if (server != null) return START_NOT_STICKY
        val started = runCatching {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification("Démarrage du transfert…", null),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        }
        if (started.isFailure) {
            TransferController.set(TransferState.Error("Le service de transfert n'a pas pu démarrer"))
            stopSelf()
            return START_NOT_STICKY
        }
        TransferController.set(TransferState.Starting)
        scope.launch { startServer() }
        return START_NOT_STICKY
    }

    private suspend fun startServer() {
        val c = applicationContext.container
        val settings = runCatching { c.settings.current() }.getOrNull()
        val basePort = settings?.transferPort ?: 8080
        val idleMs = (settings?.transferIdleMinutes ?: 10).coerceAtLeast(1) * 60_000L
        val ip = NetUtils.findWifiAddress(this)?.hostAddress
        if (ip == null) {
            fail("Aucun réseau Wi-Fi : connectez-vous à un Wi-Fi ou activez le point d'accès.")
            return
        }
        val a = TransferAuth(onCodeChanged = { TransferController.onCode(it) })
        val guard = PathGuard(
            StoragePaths.primary,
            listOf(StoragePaths.androidData, StoragePaths.androidObb, StoragePaths.fichioDir),
        )
        var srv: TransferServer? = null
        var lastError: Throwable? = null
        for (port in basePort until (basePort + 10).coerceAtMost(65_536)) {
            val candidate = TransferServer(
                context = applicationContext,
                host = ip,
                port = port,
                auth = a,
                guard = guard,
                log = ::log,
                onChanged = { dir -> c.fileSystems.notifyChanged(dir) },
            )
            try {
                withContext(Dispatchers.IO) { candidate.start() }
                srv = candidate
                break
            } catch (e: Throwable) {
                lastError = e
                runCatching { candidate.stop() }
                if (e !is BindException && e.cause !is BindException) break
            }
        }
        if (srv == null) {
            fail("Impossible d'ouvrir le serveur : ${lastError?.message ?: "port occupé"}")
            return
        }
        if (stopping) {
            withContext(Dispatchers.IO) { srv.stop() }
            return
        }
        server = srv
        auth = a
        TransferController.auth = a
        val url = "http://$ip:${srv.port}"
        val code = a.newCode()
        TransferController.resetActivity()
        TransferController.set(TransferState.Active(url, ip, srv.port, code, 0, idleMs))
        getSystemService<NotificationManager>()?.notify(NOTIFICATION_ID, notification("Transfert actif — $url", url))
        registerNetwork(ip)
        watchdog = scope.launch {
            while (isActive) {
                delay(5_000)
                val idle = System.currentTimeMillis() - srv.lastRequest.get()
                when {
                    idle >= idleMs -> shutdown("Arrêt automatique après ${idleMs / 60_000} min d'inactivité")
                    !NetUtils.isStillAssigned(ip) -> shutdown("Wi-Fi perdu : transfert arrêté")
                    else -> TransferController.onSessions(a.sessionCount())
                }
            }
        }
    }

    private fun registerNetwork(ip: String) {
        val cm = getSystemService<ConnectivityManager>() ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                scope.launch {
                    delay(1_500)
                    if (!NetUtils.isStillAssigned(ip)) shutdown("Wi-Fi perdu : transfert arrêté")
                }
            }
        }
        runCatching {
            cm.registerNetworkCallback(
                NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), cb,
            )
            networkCallback = cb
        }
    }

    private fun log(action: String, path: String, size: Long, client: String) {
        val c = applicationContext.container
        c.appScope.launch(Dispatchers.IO) {
            runCatching {
                c.database.transferLog().insert(
                    TransferLogEntity(time = System.currentTimeMillis(), action = action, path = path, size = size, client = client),
                )
            }
        }
    }

    private fun fail(message: String) {
        TransferController.set(TransferState.Error(message))
        stopForegroundAndSelf()
    }

    @Synchronized
    private fun shutdown(reason: String?) {
        if (stopping) return
        stopping = true
        watchdog?.cancel()
        networkCallback?.let { cb -> runCatching { getSystemService<ConnectivityManager>()?.unregisterNetworkCallback(cb) } }
        networkCallback = null
        val s = server
        server = null
        auth?.revokeAll()
        auth = null
        TransferController.auth = null
        TransferController.set(TransferState.Stopped(reason))
        // Engine shutdown blocks: never on the main thread.
        Thread { runCatching { s?.stop() } }.start()
        stopForegroundAndSelf()
    }

    private fun stopForegroundAndSelf() {
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    override fun onDestroy() {
        if (!stopping) shutdown(null)
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(text: String, url: String?): Notification {
        val nm = getSystemService<NotificationManager>()
        if (nm?.getNotificationChannel(CHANNEL) == null) {
            nm?.createNotificationChannel(
                NotificationChannel(CHANNEL, "Transfert local", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Serveur de transfert Wi-Fi actif"
                    setShowBadge(false)
                },
            )
        }
        val stop = PendingIntent.getService(
            this, 1, Intent(this, TransferService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 2, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(if (url != null) "Transfert actif" else "Fichio")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(if (url != null) "$text\nAndroid/data n'est pas partagé." else text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(open)
            .addAction(0, "Arrêter", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        const val ACTION_STOP = "com.karelisio.fichio.transfer.STOP"
        private const val CHANNEL = "transfer"
        private const val NOTIFICATION_ID = 4_810
    }
}
