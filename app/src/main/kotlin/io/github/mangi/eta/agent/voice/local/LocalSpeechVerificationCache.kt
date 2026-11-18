// SPDX-License-Identifier: Apache-2.0
package io.github.mangi.eta.agent.voice.local

/** Not persisted: a new service process must hash once. Caller holds the model-store lock. */
internal class LocalSpeechVerificationCache<T : Any> {
    private val verified = HashMap<String, T>()
    fun needsFullVerification(id: String, fingerprint: T): Boolean = verified[id] != fingerprint
    fun forget(id: String) { verified.remove(id) }
    suspend fun verify(id: String, forceFull: Boolean, fingerprint: () -> T, fullVerify: suspend () -> Unit) {
        val before = fingerprint()
        if (forceFull || needsFullVerification(id, before)) {
            forget(id)
            fullVerify()
            record(id, before, fingerprint())
        }
    }
    fun record(id: String, before: T, after: T) {
        require(before == after) { "模型在校验时发生变化" }
        // There are eight catalog entries, only two of which use this cache.
        require(id in setOf("vosk_small_cn_022", "vosk_cn_022"))
        verified[id] = after
    }
}
