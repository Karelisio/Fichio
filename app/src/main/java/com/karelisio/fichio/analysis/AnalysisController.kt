package com.karelisio.fichio.analysis

import androidx.compose.runtime.Immutable
import com.karelisio.fichio.AppContainer
import com.karelisio.fichio.core.fs.StoragePaths
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/** Scan status shown by the analysis screen. */
@Immutable
sealed interface ScanStatus {
    data object Idle : ScanStatus
    data class Running(
        val files: Int = 0,
        val bytes: Long = 0,
        val phase: StorageAnalyzer.Phase = StorageAnalyzer.Phase.SCANNING,
        val hashed: Int = 0,
        val hashTotal: Int = 0,
    ) : ScanStatus
    data class Failed(val message: String) : ScanStatus
}

/**
 * Process-wide owner of the last analysis: the result survives leaving the screen (no rescan
 * at each visit) and a running scan keeps going in the app scope until cancelled.
 */
object AnalysisController {
    private val _result = MutableStateFlow<AnalysisResult?>(null)
    val result: StateFlow<AnalysisResult?> = _result.asStateFlow()

    private val _status = MutableStateFlow<ScanStatus>(ScanStatus.Idle)
    val status: StateFlow<ScanStatus> = _status.asStateFlow()

    private var job: Job? = null
    @Volatile private var generation = 0

    val isRunning: Boolean get() = job?.isActive == true

    /** Starts a scan unless one is running ([force] = rescan even with a cached result). */
    fun start(container: AppContainer, force: Boolean = false) {
        if (isRunning) return
        if (!force && _result.value != null) return
        val gen = ++generation
        fun finish(status: ScanStatus) {
            if (gen == generation) _status.value = status
        }
        _status.value = ScanStatus.Running()
        job = container.appScope.launch {
            val progress = StorageAnalyzer.Progress()
            val ticker = launch {
                while (true) {
                    // Only refresh a Running status: never resurrect it after the scan ended.
                    _status.update {
                        if (it is ScanStatus.Running) ScanStatus.Running(
                            progress.files.get(), progress.bytes.get(), progress.phase, progress.hashed, progress.hashTotal,
                        ) else it
                    }
                    delay(120)
                }
            }
            try {
                val root = StoragePaths.primary
                val analyzer = StorageAnalyzer(
                    root = root,
                    excluded = setOf(StoragePaths.androidData, StoragePaths.androidObb, StoragePaths.fichioDir),
                )
                val r = analyzer.scan(progress)
                ticker.cancel()
                _result.value = r
                finish(ScanStatus.Idle)
            } catch (e: CancellationException) {
                ticker.cancel()
                finish(ScanStatus.Idle)
                throw e
            } catch (e: Throwable) {
                ticker.cancel()
                finish(ScanStatus.Failed(e.message ?: "Analyse impossible"))
            }
        }
    }

    fun cancel() {
        generation++
        job?.cancel()
        job = null
        _status.value = ScanStatus.Idle
    }

    /** Drops deleted items from the cached result. */
    fun applyRemoval(paths: Collection<String>) {
        _result.update { it?.removing(paths) }
    }
}
