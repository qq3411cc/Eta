// SPDX-License-Identifier: Apache-2.0
package io.github.mangi.eta.agent.voice.local

import io.github.mangi.eta.agent.voice.qwen.WarmCachePolicy

/** Main-thread admission, matching Service.START and close-completion ordering. */
internal class LocalSpeechDeletionGate {
    var inProgress = false; private set
    fun begin(active: Boolean): Boolean {
        if (active || inProgress) return false
        inProgress = true
        return true
    }
    fun finish() { inProgress = false }
}

/** Runs on the very same serial worker as load/decode/lease completion.
 * A real cache disposal must finish before filesystem mutation or the success ACK.
 * A failing native release aborts deletion (there is deliberately no finally-delete).
 */
internal suspend fun <T> deleteIdleLocalSpeechModel(
    cache: WarmCachePolicy<*, *>, deleteFiles: suspend () -> T,
): T {
    check(cache.idle) { "本机模型仍在使用，请先停止录音或识别后再删除" }
    cache.evict()
    return deleteFiles()
}

/** Must run inside the store's acquired model lock, before verification-cache or file mutation. */
internal inline fun <T> localSpeechDeletionAfterLock(checkDeadline: () -> Unit, mutate: () -> T): T {
    checkDeadline() // outside mutation error wrapping: retain the precise TIMEOUT/no-files-changed result
    return mutate()
}

/** Same monotonic clock in both app processes; stale queued requests cannot remove a re-import. */
internal fun localSpeechDeletionIsLive(deadline: Long, now: Long): Boolean =
    now >= 0 && deadline > now && deadline - now <= LocalSpeechProtocol.DELETE_MS
