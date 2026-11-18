package com.karelisio.fichio.update

import com.karelisio.fichio.core.util.Strings
import com.karelisio.fichio.R
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import java.io.File

/** Installs a downloaded APK of Fichio itself through a PackageInstaller session. */
object SelfInstaller {
    private const val ACTION_STATUS = "com.karelisio.fichio.update.INSTALL_STATUS"

    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Opens "Installer des applis inconnues" for Fichio. */
    fun openInstallSettings(context: Context) {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    /**
     * Streams [apk] into a FULL_INSTALL session and commits it. [onResult] receives null on
     * success/hand-off to the system dialog, or an error message.
     */
    fun install(context: Context, apk: File, onResult: (String?) -> Unit) {
        val app = context.applicationContext
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                setPackageSource(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE)
            }
        }
        val sessionId = try {
            installer.createSession(params)
        } catch (e: Exception) {
            onResult(e.message ?: Strings.get(R.string.update_err_session)); return
        }

        // Dynamic receiver for the session status; unregistered once a final status arrives.
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1) != sessionId) return
                when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                        if (confirm != null) {
                            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            runCatching { ctx.startActivity(confirm) }
                                .onFailure { onResult(Strings.get(R.string.update_err_confirm)) }
                        }
                    }
                    PackageInstaller.STATUS_SUCCESS -> {
                        runCatching { ctx.unregisterReceiver(this) }
                        apk.delete()
                        onResult(null)
                    }
                    else -> {
                        runCatching { ctx.unregisterReceiver(this) }
                        val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                        onResult(
                            if (status == PackageInstaller.STATUS_FAILURE_ABORTED) Strings.get(R.string.update_install_cancelled)
                            else Strings.get(R.string.update_install_failed) + (msg?.let { " : $it" } ?: "")
                        )
                    }
                }
            }
        }
        ContextCompat.registerReceiver(app, receiver, IntentFilter(ACTION_STATUS), ContextCompat.RECEIVER_NOT_EXPORTED)

        try {
            installer.openSession(sessionId).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("base.apk", 0, apk.length()).use { out ->
                        input.copyTo(out, 256 * 1024)
                        session.fsync(out)
                    }
                }
                val statusIntent = Intent(ACTION_STATUS).setPackage(app.packageName)
                val pi = PendingIntent.getBroadcast(
                    app, sessionId, statusIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                session.commit(pi.intentSender)
            }
        } catch (e: Exception) {
            runCatching { app.unregisterReceiver(receiver) }
            runCatching { installer.abandonSession(sessionId) }
            onResult(e.message ?: Strings.get(R.string.update_err_install))
        }
    }
}
