package com.karelisio.fichio.apk

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.Immutable
import androidx.core.content.IntentCompat
import androidx.core.net.toUri
import com.karelisio.fichio.container
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Install progress, observed by the APK screen (and the in-app updater). */
sealed interface InstallState {
    data object Idle : InstallState
    /** Writing the APK(s) into the session. */
    @Immutable data class Writing(val packageName: String?, val progress: Float) : InstallState
    /** The system confirmation dialog is showing. */
    data class WaitingUser(val packageName: String?) : InstallState
    data class Success(val packageName: String?) : InstallState
    data class Failure(val packageName: String?, val message: String) : InstallState
}

/** PackageInstaller session wrapper: base + splits in one session, status through [InstallReceiver]. */
object ApkInstaller {
    const val ACTION_INSTALL_STATUS = "com.karelisio.fichio.apk.INSTALL_STATUS"
    private const val EXTRA_TARGET = "com.karelisio.fichio.apk.TARGET"

    private val _state = MutableStateFlow<InstallState>(InstallState.Idle)
    val state: StateFlow<InstallState> = _state.asStateFlow()

    fun reset() {
        _state.value = InstallState.Idle
    }

    /** Whether the user allowed Fichio to install apps ("sources inconnues"). */
    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    fun unknownSourcesIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun openUnknownSourcesSettings(context: Context) {
        try {
            context.startActivity(unknownSourcesIntent(context))
        } catch (_: ActivityNotFoundException) {
            context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /**
     * Installs a single APK. Fire-and-forget entry point (used by the in-app updater): progress
     * and result go to [state]. Opens the "unknown sources" settings when needed.
     */
    fun install(context: Context, apkFile: File) {
        val app = context.applicationContext
        if (!canInstall(app)) {
            _state.value = InstallState.Failure(null, "Autorisez Fichio à installer des applis, puis réessayez.")
            openUnknownSourcesSettings(app)
            return
        }
        app.container.appScope.launch {
            runCatching { installSources(app, listOf(ApkSource.OfFile(apkFile)), null) }
        }
    }

    /**
     * Writes [sources] (base first, then splits) into one FULL_INSTALL session and commits it.
     * Suspends until the commit is sent; the outcome arrives later through [state].
     */
    suspend fun installSources(
        context: Context,
        sources: List<ApkSource>,
        packageName: String?,
        onProgress: (done: Long, total: Long, name: String) -> Unit = { _, _, _ -> },
        checkpoint: suspend () -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        require(sources.isNotEmpty())
        val app = context.applicationContext
        val installer = app.packageManager.packageInstaller
        val total = sources.sumOf { it.length.coerceAtLeast(0) }
        _state.value = InstallState.Writing(packageName, 0f)

        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            if (packageName != null) setAppPackageName(packageName)
            if (total > 0) setSize(total)
            setInstallReason(PackageManager.INSTALL_REASON_USER)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            if (Build.VERSION.SDK_INT >= 33) setPackageSource(PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE)
        }
        val sessionId = try {
            installer.createSession(params)
        } catch (e: Exception) {
            fail(packageName, "Impossible de démarrer l'installation : ${e.message ?: e.javaClass.simpleName}")
            throw e
        }
        val session = installer.openSession(sessionId)
        try {
            var done = 0L
            val buf = ByteArray(128 * 1024)
            val usedNames = HashSet<String>()
            sources.forEachIndexed { index, src ->
                var name = src.name.ifBlank { "split_$index.apk" }
                if (!usedNames.add(name)) name = "${index}_$name".also { usedNames.add(it) }
                session.openWrite(name, 0, src.length.takeIf { it > 0 } ?: -1).use { out ->
                    src.open().use { input ->
                        var since = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            since += n
                            if (since >= 512 * 1024) {
                                since = 0
                                checkpoint()
                                onProgress(done, total, name)
                                if (total > 0) session.setStagingProgress(done.toFloat() / total)
                                _state.value = InstallState.Writing(packageName, if (total > 0) done.toFloat() / total else 0f)
                            }
                        }
                    }
                    session.fsync(out)
                }
            }
            onProgress(total, total, "")
            _state.value = InstallState.Writing(packageName, 1f)
            val intent = Intent(app, InstallReceiver::class.java)
                .setAction(ACTION_INSTALL_STATUS)
                .setPackage(app.packageName)
                .putExtra(EXTRA_TARGET, packageName)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            val pending = PendingIntent.getBroadcast(app, sessionId, intent, flags)
            session.commit(pending.intentSender)
            session.close()
        } catch (t: Throwable) {
            runCatching { session.abandon() }
            if (t is CancellationException) _state.value = InstallState.Failure(packageName, "Installation annulée.")
            else fail(packageName, "Échec de l'écriture : ${t.message ?: t.javaClass.simpleName}")
            throw t
        }
    }

    private fun fail(pkg: String?, message: String) {
        _state.value = InstallState.Failure(pkg, message)
    }

    /** Called by [InstallReceiver] for every session status. */
    internal fun onStatus(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val pkg = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME) ?: intent.getStringExtra(EXTRA_TARGET)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                if (confirm == null) {
                    fail(pkg, "Confirmation impossible : réessayez.")
                    return
                }
                _state.value = InstallState.WaitingUser(pkg)
                try {
                    context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: Exception) {
                    fail(pkg, "Impossible d'afficher la confirmation d'installation.")
                }
            }
            PackageInstaller.STATUS_SUCCESS -> _state.value = InstallState.Success(pkg)
            else -> fail(pkg, ApkFormat.installFailureMessage(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)))
        }
    }

    /** Launch intent of an installed package, if it has one. */
    fun launchIntent(context: Context, packageName: String): Intent? =
        context.packageManager.getLaunchIntentForPackage(packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
