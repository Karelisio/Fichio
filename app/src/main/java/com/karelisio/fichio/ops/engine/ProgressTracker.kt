package com.karelisio.fichio.ops.engine

import com.karelisio.fichio.ops.JobProgress

/**
 * Accumulates byte/item progress, smooths the transfer speed (exponential moving average over
 * ~0.5 s samples) and throttles reports to [EngineListener.onProgress].
 */
class ProgressTracker(
    private val sink: (JobProgress) -> Unit,
    private val clock: () -> Long = System::nanoTime,
    private val minIntervalNs: Long = 100_000_000L,
    private val sampleNs: Long = 500_000_000L,
) {
    var totalBytes = 0L; private set
    var totalItems = 0; private set
    var doneBytes = 0L; private set
    var doneItems = 0; private set
    var currentName = ""; private set
    var speed = 0L; private set

    /** Bytes really transferred (excludes renames/skips) — the only ones that count for speed. */
    private var transferred = 0L
    private var lastEmit = Long.MIN_VALUE
    private var sampleTime = clock()
    private var sampleBytes = 0L

    /** Bytes already counted for the file currently being copied (to settle on finish/failure). */
    private var fileStartBytes = 0L
    private var fileSize = 0L

    fun start(totalBytes: Long, totalItems: Int) {
        this.totalBytes = totalBytes
        this.totalItems = totalItems
        emit(force = true)
    }

    fun setName(name: String) {
        currentName = name
        emit()
    }

    fun beginFile(name: String, size: Long) {
        currentName = name
        fileStartBytes = doneBytes
        fileSize = size
        emit()
    }

    fun addBytes(n: Int) {
        doneBytes += n
        transferred += n
        emit()
    }

    /** Settles the current file at exactly its size (files may grow/shrink while copied). */
    fun endFile() {
        doneBytes = fileStartBytes + fileSize
        doneItems++
        emit()
    }

    /** Counts work that did not need byte copying (rename, skip, failure remainder). */
    fun advance(bytes: Long, items: Int) {
        doneBytes += bytes
        doneItems += items
        emit()
    }

    /** Rolls back a failed file's partial bytes, then counts it as done (with its full size). */
    fun abortFile() {
        doneBytes = fileStartBytes
        advance(fileSize, 1)
    }

    fun itemDone() {
        doneItems++
        emit()
    }

    fun snapshot() = JobProgress(
        doneBytes = doneBytes.coerceAtMost(maxOf(totalBytes, doneBytes)),
        totalBytes = totalBytes,
        doneItems = doneItems,
        totalItems = totalItems,
        currentName = currentName,
        speed = speed,
    )

    fun emit(force: Boolean = false) {
        val now = clock()
        val dt = now - sampleTime
        if (dt >= sampleNs) {
            val inst = ((transferred - sampleBytes) * 1_000_000_000.0 / dt).toLong()
            speed = if (speed == 0L) inst else (0.35 * inst + 0.65 * speed).toLong()
            sampleTime = now
            sampleBytes = transferred
        }
        if (force || lastEmit == Long.MIN_VALUE || now - lastEmit >= minIntervalNs) {
            lastEmit = now
            sink(snapshot())
        }
    }
}
