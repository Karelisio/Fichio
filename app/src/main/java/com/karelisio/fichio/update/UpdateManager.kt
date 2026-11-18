package com.karelisio.fichio.update

import com.karelisio.fichio.core.util.Strings
import com.karelisio.fichio.R
import android.content.Context
import com.karelisio.fichio.BuildConfig
import com.karelisio.fichio.container
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Observable state of the in-app updater. */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val checkedAt: Long) : UpdateState
    data class Available(val update: AvailableUpdate) : UpdateState
    data class Downloading(val update: AvailableUpdate, val read: Long, val total: Long, val bytesPerSecond: Long) : UpdateState {
        val fraction: Float get() = if (total <= 0) 0f else (read.toFloat() / total).coerceIn(0f, 1f)
    }
    data class Installing(val update: AvailableUpdate) : UpdateState
    data class Error(val message: String, val update: AvailableUpdate? = null) : UpdateState
}

/** Network access to the public GitHub API (the only remote call the app makes). */
object UpdateChecker {
    private const val TIMEOUT_MS = 8_000
    const val MIN_INTERVAL_MS = 6 * 60 * 60 * 1000L

    val latestUrl: String get() = "https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest"
    val repoUrl: String get() = "https://github.com/${BuildConfig.UPDATE_REPO}"

    /** Fetches releases/latest; returns the update when strictly newer than [currentVersion]. */
    suspend fun fetchLatest(currentVersion: String = BuildConfig.VERSION_NAME): AvailableUpdate? = withContext(Dispatchers.IO) {
        val conn = (URL(latestUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("User-Agent", "Fichio-Updater")
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        }
        try {
            when (val code = conn.responseCode) {
                200 -> {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    ReleaseParser.toUpdate(ReleaseParser.parse(body))
                        ?.takeIf { ReleaseParser.isNewer(it.version, currentVersion) }
                }
                404 -> null // no release published yet
                403, 429 -> throw IOException(Strings.get(R.string.update_err_rate_limit))
                else -> throw IOException(Strings.get(R.string.update_err_unexpected, code))
            }
        } finally {
            conn.disconnect()
        }
    }
}

/** Single entry point used by [UpdateHost] and the settings screen. */
object UpdateManager {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** True when the sheet should be shown (auto check found something, or user asked). */
    private val _sheetVisible = MutableStateFlow(false)
    val sheetVisible: StateFlow<Boolean> = _sheetVisible.asStateFlow()

    private var job: Job? = null

    val currentVersion: String get() = BuildConfig.VERSION_NAME

    /**
     * Checks GitHub. Without [force], honours the auto-check setting, the 6 h throttle and the
     * skipped version. With [force] (settings "Vérifier maintenant"), always checks and shows the sheet.
     */
    fun checkNow(context: Context, force: Boolean = true) {
        val app = context.applicationContext
        val c = app.container
        if (job?.isActive == true) return
        if (_state.value is UpdateState.Downloading || _state.value is UpdateState.Installing) return
        job = c.appScope.launch {
            val s = c.settings.current()
            val now = System.currentTimeMillis()
            if (!force && (!s.autoCheckUpdates || now - s.lastUpdateCheck < UpdateChecker.MIN_INTERVAL_MS)) return@launch
            _state.value = UpdateState.Checking
            try {
                val update = UpdateChecker.fetchLatest()
                c.settings.setLastUpdateCheck(now)
                if (update == null) {
                    _state.value = UpdateState.UpToDate(now)
                } else {
                    _state.value = UpdateState.Available(update)
                    if (force || update.version != s.skippedVersion) _sheetVisible.value = true
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = if (force) UpdateState.Error(e.message ?: Strings.get(R.string.update_err_check)) else UpdateState.Idle
            }
        }
    }

    fun showSheet() { _sheetVisible.value = true }

    fun dismiss() {
        _sheetVisible.value = false
        val st = _state.value
        if (st is UpdateState.Error && st.update != null) _state.value = UpdateState.Available(st.update)
    }

    fun skip(context: Context, version: String) {
        val c = context.applicationContext.container
        c.appScope.launch { c.settings.setSkippedVersion(version) }
        _sheetVisible.value = false
    }

    /** Downloads the APK into cacheDir/updates and hands it to [SelfInstaller]. */
    fun downloadAndInstall(context: Context, update: AvailableUpdate) {
        val app = context.applicationContext
        if (!SelfInstaller.canInstall(app)) {
            SelfInstaller.openInstallSettings(app)
            return
        }
        if (job?.isActive == true) return
        job = app.container.appScope.launch {
            try {
                val file = download(app, update)
                _state.value = UpdateState.Installing(update)
                SelfInstaller.install(app, file) { error ->
                    _state.value = if (error == null) UpdateState.Available(update) else UpdateState.Error(error, update)
                }
            } catch (e: CancellationException) {
                _state.value = UpdateState.Available(update)
                throw e
            } catch (e: Exception) {
                _state.value = UpdateState.Error(e.message ?: Strings.get(R.string.update_err_download), update)
            }
        }
    }

    fun cancelDownload() {
        job?.cancel()
    }

    private suspend fun download(context: Context, update: AvailableUpdate): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        // Keep only the file being downloaded.
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, "Fichio-${update.version}.apk")
        val part = File(dir, target.name + ".part")
        var url = URL(update.apkUrl)
        var conn: HttpURLConnection
        var redirects = 0
        // GitHub assets redirect to another host: follow manually so cross-host hops are handled.
        while (true) {
            conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 20_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "Fichio-Updater")
                setRequestProperty("Accept", "application/octet-stream")
            }
            val code = conn.responseCode
            if (code in 300..399 && redirects < 5) {
                val loc = conn.getHeaderField("Location") ?: break
                conn.disconnect()
                url = URL(url, loc)
                redirects++
                continue
            }
            if (code != 200) {
                conn.disconnect()
                throw IOException(Strings.get(R.string.update_err_download_refused, code))
            }
            break
        }
        try {
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: update.apkSize
            var read = 0L
            val started = System.nanoTime()
            var lastEmit = 0L
            conn.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        read += n
                        val now = System.nanoTime()
                        if (now - lastEmit > 120_000_000L) {
                            lastEmit = now
                            val secs = ((now - started) / 1e9).coerceAtLeast(0.001)
                            _state.value = UpdateState.Downloading(update, read, total, (read / secs).toLong())
                        }
                    }
                }
            }
            _state.value = UpdateState.Downloading(update, read, total.coerceAtLeast(read), 0)
        } finally {
            conn.disconnect()
        }
        if (!part.renameTo(target)) throw IOException(Strings.get(R.string.update_err_write))
        target
    }
}
