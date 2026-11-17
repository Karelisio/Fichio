package com.karelisio.fichio.shizuku

import android.content.Context
import com.karelisio.fichio.core.fs.FileBackend
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class ShizukuState { NOT_INSTALLED, NOT_RUNNING, NO_PERMISSION, CONNECTING, READY }

/**
 * Shevery (Shizuku fork) connection + UserService bound for Android/data and Android/obb.
 * CONTRACT STUB — implemented by the Shevery module.
 */
class ShizukuManager(private val context: Context) {
    private val _state = MutableStateFlow(ShizukuState.NOT_RUNNING)
    val state: StateFlow<ShizukuState> = _state
    /** Non-null once [state] is READY. */
    val backend: FileBackend? get() = null
    fun init() {}
    fun requestPermission() {}
    fun retry() {}
}
