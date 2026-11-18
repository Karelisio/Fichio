package com.karelisio.fichio.ops

import kotlinx.coroutines.CompletableDeferred
import kotlin.coroutines.cancellation.CancellationException

/**
 * FIFO semaphore whose number of permits can change while coroutines wait on it (the
 * « Opérations simultanées » setting). Raising the limit admits waiters immediately; lowering it
 * lets running holders finish and admits nobody until the count is back under the new limit.
 * Cancelling a waiter removes it from the queue (or gives back a permit granted concurrently).
 */
class DynamicSemaphore(limit: Int) {
    private val lock = Any()
    private var limit = limit.coerceAtLeast(1)
    private var running = 0
    private val waiters = ArrayDeque<CompletableDeferred<Unit>>()

    val permits: Int get() = synchronized(lock) { limit }
    val inUse: Int get() = synchronized(lock) { running }

    fun setLimit(value: Int) {
        val admitted = synchronized(lock) {
            limit = value.coerceAtLeast(1)
            admitLocked()
        }
        admitted.forEach { it.complete(Unit) }
    }

    suspend fun acquire() {
        val ticket = synchronized(lock) {
            if (running < limit && waiters.isEmpty()) {
                running++
                return
            }
            CompletableDeferred<Unit>().also { waiters.addLast(it) }
        }
        try {
            ticket.await()
        } catch (c: CancellationException) {
            val granted = synchronized(lock) { !waiters.remove(ticket) }
            // The permit was handed over just as we were cancelled: pass it on.
            if (granted) release()
            throw c
        }
    }

    fun release() {
        val admitted = synchronized(lock) {
            running = (running - 1).coerceAtLeast(0)
            admitLocked()
        }
        admitted.forEach { it.complete(Unit) }
    }

    suspend inline fun <T> withPermit(block: () -> T): T {
        acquire()
        try {
            return block()
        } finally {
            release()
        }
    }

    private fun admitLocked(): List<CompletableDeferred<Unit>> {
        if (waiters.isEmpty() || running >= limit) return emptyList()
        val out = ArrayList<CompletableDeferred<Unit>>()
        while (running < limit && waiters.isNotEmpty()) {
            running++
            out += waiters.removeFirst()
        }
        return out
    }
}
