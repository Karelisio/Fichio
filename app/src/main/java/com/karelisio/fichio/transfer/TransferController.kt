package com.karelisio.fichio.transfer

import com.karelisio.fichio.core.util.Strings
import com.karelisio.fichio.R
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Immutable
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

@Immutable
sealed interface TransferState {
    /** Not running; [reason] explains an automatic stop (idle, Wi-Fi lost…). */
    data class Stopped(val reason: String? = null) : TransferState
    data object Starting : TransferState
    data class Active(
        val url: String,
        val ip: String,
        val port: Int,
        /** One-time code currently accepted, null once used (a new one can be drawn). */
        val code: String?,
        val sessions: Int,
        val idleTimeoutMs: Long,
    ) : TransferState {
        /** URL encoded in the QR code (logs in directly). */
        val qrUrl: String get() = if (code != null) "$url/?code=$code" else url
    }
    data class Error(val message: String) : TransferState
}

/** Process-wide state of the local transfer server, driven by [TransferService]. */
object TransferController {
    private val _state = MutableStateFlow<TransferState>(TransferState.Stopped())
    val state: StateFlow<TransferState> = _state.asStateFlow()

    private val _lastActivity = MutableStateFlow(0L)
    /** Time of the last accepted request (refreshed at most once per second). */
    val lastActivity: StateFlow<Long> = _lastActivity.asStateFlow()

    @Volatile internal var auth: TransferAuth? = null

    fun start(context: Context) {
        if (_state.value is TransferState.Active || _state.value == TransferState.Starting) return
        _state.value = TransferState.Starting
        runCatching {
            ContextCompat.startForegroundService(context, Intent(context, TransferService::class.java))
        }.onFailure { _state.value = TransferState.Error(it.message ?: Strings.get(R.string.transfer_err_start)) }
    }

    fun stop(context: Context) {
        runCatching {
            context.startService(Intent(context, TransferService::class.java).setAction(TransferService.ACTION_STOP))
        }.onFailure { _state.value = TransferState.Stopped() }
    }

    /** Draws a new one-time code (e.g. to connect a second computer). */
    fun regenerateCode() {
        auth?.newCode()
    }

    internal fun set(state: TransferState) {
        _state.value = state
    }

    internal fun onCode(code: String?) {
        _state.update { if (it is TransferState.Active) it.copy(code = code) else it }
    }

    internal fun onSessions(count: Int) {
        _state.update { if (it is TransferState.Active && it.sessions != count) it.copy(sessions = count) else it }
    }

    internal fun touch(now: Long = System.currentTimeMillis()) {
        if (now - _lastActivity.value >= 1_000) _lastActivity.value = now
    }

    internal fun resetActivity(now: Long = System.currentTimeMillis()) {
        _lastActivity.value = now
    }
}
