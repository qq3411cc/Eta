// SPDX-License-Identifier: Apache-2.0
package io.github.mangi.eta.agent.voice.qwen

/** Serial-worker-only ownership. Identity/clock/factory/disposal are injectable, no Android/JNI policy. */
internal class WarmCachePolicy<I : Any, R : Any>(
    private val dispose: (R) -> Unit,
    val idleMillis: Long = 120_000L,
) {
    private var workerThread: Thread? = null
    private fun checkWorker() {
        val current = Thread.currentThread()
        if (workerThread == null) workerThread = current
        check(workerThread === current) { "Qwen cache must stay on its serial worker" }
    }
    private var resource: R? = null
    private var identity: I? = null
    private var lease: Any? = null
    private var evictAfterLease = false
    var generation: Long = 0; private set
    var deadline: Long = Long.MAX_VALUE; private set
    val idle: Boolean get() = lease == null
    val loaded: Boolean get() = resource != null
    fun borrow(owner: Any, quickIdentity: I?, load: () -> Pair<I, R>): Pair<R, Boolean> {
        checkWorker()
        check(lease == null) { "Qwen recognizer is busy" }
        generation++; deadline = Long.MAX_VALUE
        if (resource != null && (quickIdentity == null || identity != quickIdentity)) evict()
        val hit = resource != null
        lease = owner // reserve even if verification/load throws; only this owner may finish
        if (!hit) {
            val (verifiedIdentity, newResource) = load()
            identity = verifiedIdentity; resource = newResource
        }
        return checkNotNull(resource) to hit
    }
    /** Returns false for a stale completion: it must not clear another session's lease. */
    fun finish(owner: Any, now: Long): Boolean {
        checkWorker()
        if (lease !== owner) return false
        lease = null; generation++
        if (evictAfterLease) { evictAfterLease = false; evict() }
        deadline = now + idleMillis
        return true
    }
    /** A START cancelled before borrow still needs a bounded started-service lifetime. */
    fun finishUnborrowed(now: Long) {
        checkWorker()
        check(lease == null) { "Cannot idle another session" }
        generation++; deadline = now + idleMillis
    }
    fun trim() {
        checkWorker()
        if (lease == null) evict() else evictAfterLease = true
    }
    fun expire(token: Long, now: Long): Boolean {
        checkWorker()
        if (lease != null || token != generation || now < deadline) return false
        evict(); return true
    }
    fun evict() {
        checkWorker()
        check(lease == null) { "Cannot release an active Qwen recognizer" }
        val old = resource; resource = null; identity = null
        old?.let(dispose)
    }
}

