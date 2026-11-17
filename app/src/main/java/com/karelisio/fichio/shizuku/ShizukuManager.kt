package com.karelisio.fichio.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.karelisio.fichio.BuildConfig
import com.karelisio.fichio.core.fs.FileBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

enum class ShizukuState { NOT_INSTALLED, NOT_RUNNING, NO_PERMISSION, CONNECTING, READY }

/**
 * Shevery (Shizuku fork) connection + UserService bound for Android/data and Android/obb.
 *
 * Uses the standard `rikka.shizuku.Shizuku` API only, so it works with Shizuku itself and any
 * fork (Shevery) delivering its binder through our ShizukuProvider. Every call is wrapped in
 * runCatching: the rest of the app must keep working when no manager is installed.
 */
class ShizukuManager(private val context: Context) {
    private val _state = MutableStateFlow(ShizukuState.NOT_RUNNING)
    val state: StateFlow<ShizukuState> = _state.asStateFlow()

    @Volatile private var service: IFileService? = null
    @Volatile private var currentBackend: ShizukuBackend? = null

    /** Non-null once [state] is READY. */
    val backend: FileBackend? get() = if (_state.value == ShizukuState.READY) currentBackend else null

    /** Package of the detected Shevery/Shizuku manager app, if any. */
    @Volatile var managerPackage: String? = null
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val main = Handler(Looper.getMainLooper())
    private var initialised = false
    @Volatile private var detectionDone = false
    private var bound = false
    private var connectTimeout: Job? = null

    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(ComponentName(context.packageName, FichioUserService::class.java.name))
            .daemon(false)
            .processNameSuffix("files")
            .tag("fichio-files")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            connectTimeout?.cancel()
            if (binder == null || !binder.pingBinder()) {
                bound = false
                refresh()
                return
            }
            val s = IFileService.Stub.asInterface(binder)
            runCatching { binder.linkToDeath({ main.post { onServiceLost() } }, 0) }
            service = s
            currentBackend = ShizukuBackend(s) { main.post { onServiceLost() } }
            _state.value = ShizukuState.READY
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            onServiceLost()
        }
    }

    private val binderReceived = Shizuku.OnBinderReceivedListener { refresh() }
    private val binderDead = Shizuku.OnBinderDeadListener {
        bound = false
        dropService()
        refresh()
    }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { code, _ ->
        if (code == PERMISSION_CODE) refresh()
    }

    fun init() {
        if (initialised) return
        initialised = true
        runCatching {
            Shizuku.addBinderReceivedListenerSticky(binderReceived, main)
            Shizuku.addBinderDeadListener(binderDead, main)
            Shizuku.addRequestPermissionResultListener(permissionResult, main)
        }
        refresh()
        detectManager()
    }

    fun requestPermission() {
        runCatching {
            if (!Shizuku.pingBinder()) {
                refresh(); return
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) refresh()
            else Shizuku.requestPermission(PERMISSION_CODE)
        }.onFailure { refresh() }
    }

    /** Re-detects the manager and reconnects from scratch. */
    fun retry() {
        if (!initialised) init()
        runCatching { if (bound) Shizuku.unbindUserService(userServiceArgs, connection, true) }
        bound = false
        dropService()
        refresh()
        detectManager()
    }

    /** Package scan off the main thread, then re-evaluates the state. */
    private fun detectManager() {
        scope.launch(Dispatchers.IO) {
            managerPackage = runCatching { findManagerPackage() }.getOrNull()
            detectionDone = true
            main.post { refresh() }
        }
    }

    /** Opens the Shevery/Shizuku manager app. Returns false when none is installed. */
    fun openManager(context: Context): Boolean {
        val pkg = managerPackage ?: runCatching { findManagerPackage() }.getOrNull()?.also { managerPackage = it } ?: return false
        val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        return runCatching {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }.getOrDefault(false)
    }

    private fun refresh() {
        val next = runCatching {
            when {
                !Shizuku.pingBinder() -> {
                    bound = false
                    dropService()
                    if (detectionDone && managerPackage == null) ShizukuState.NOT_INSTALLED else ShizukuState.NOT_RUNNING
                }
                Shizuku.isPreV11() -> ShizukuState.NOT_RUNNING
                Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> {
                    dropService()
                    ShizukuState.NO_PERMISSION
                }
                service != null && currentBackend != null -> ShizukuState.READY
                else -> {
                    bind()
                    ShizukuState.CONNECTING
                }
            }
        }.getOrElse { ShizukuState.NOT_RUNNING }
        _state.value = next
    }

    private fun bind() {
        if (bound) return
        runCatching {
            Shizuku.bindUserService(userServiceArgs, connection)
            bound = true
        }.onFailure { bound = false }
        connectTimeout?.cancel()
        connectTimeout = scope.launch {
            delay(CONNECT_TIMEOUT_MS)
            // Still not connected: let the user retry (process may have been killed by MagicOS).
            if (_state.value == ShizukuState.CONNECTING) {
                bound = false
                _state.value = ShizukuState.NOT_RUNNING
            }
        }
    }

    private fun onServiceLost() {
        bound = false
        dropService()
        refresh()
    }

    private fun dropService() {
        service = null
        currentBackend = null
    }

    /**
     * Looks for Shizuku itself, then any package defining the Shizuku API permission or
     * exposing a Shizuku/Shevery named component (forks keep the permission name).
     */
    private fun findManagerPackage(): String? {
        val pm = context.packageManager
        KNOWN_PACKAGES.firstOrNull { isInstalled(pm, it) }?.let { return it }
        val packages: List<PackageInfo> = runCatching {
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
        }.getOrDefault(emptyList())
        packages.firstOrNull { p -> p.permissions?.any { it.name in MANAGER_PERMISSIONS } == true }?.let { return it.packageName }
        return packages.firstOrNull { p ->
            p.packageName != context.packageName &&
                (p.packageName.contains("shevery", true) || p.packageName.contains("shizuku", true))
        }?.packageName
    }

    private fun isInstalled(pm: PackageManager, pkg: String) =
        runCatching {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(pkg, 0)
            true
        }.getOrDefault(false)

    companion object {
        const val PERMISSION_CODE = 4_242
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private val KNOWN_PACKAGES = listOf("moe.shizuku.privileged.api")
        private val MANAGER_PERMISSIONS = setOf("moe.shizuku.manager.permission.API_V23", "moe.shizuku.manager.permission.MANAGER")
    }
}
