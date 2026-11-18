// SPDX-License-Identifier: Apache-2.0
package io.github.mangi.eta.agent.voice.offline

/** One START, READY, (PCM -> PARTIAL? -> ENDPOINT? -> ACK)*, FINISH -> FINAL. */
internal object OfflineAsrProtocol {
    const val START = 1
    const val PCM = 2
    const val FINISH = 3
    const val CANCEL = 4
    const val READY = 10
    const val ACK = 11
    const val PARTIAL = 12
    const val ENDPOINT = 13
    const val FINAL = 14
    const val ERROR = 15
    const val ID = "session"
    const val AUDIO = "pcm16"
    const val SILENCE = "silenceMs"
    const val MODEL_ID = "modelId"
    const val TEXT = "text"
    const val MAX_CHUNK = 32 * 1024
    const val FEED_CHUNK = 3_200 // 100 ms at 16 kHz PCM16
    const val MAX_AUDIO_BYTES = 60 * 16_000 * 2
    const val SESSION_TIMEOUT_MS = 65_000L
}
