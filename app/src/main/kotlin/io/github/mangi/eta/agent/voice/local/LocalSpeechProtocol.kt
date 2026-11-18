// SPDX-License-Identifier: Apache-2.0
package io.github.mangi.eta.agent.voice.local

internal object LocalSpeechProtocol {
    const val START = 1
    const val PCM = 2
    const val FINISH = 3
    const val CANCEL = 4
    const val DELETE = 5
    const val DELETED = 16
    const val DELETE_MS = 90_000L
    const val DELETE_DEADLINE = "deleteDeadlineElapsedMs"
    const val READY = 10
    const val ACK = 11
    const val PARTIAL = 12
    const val ENDPOINT = 13
    const val FINAL = 14
    const val ERROR = 15
    const val ID = "generation"
    const val MODEL = "modelId"
    const val MAX_TEXT_BYTES = 32_000
    const val PIPE = "pcmPipe"
    const val SILENCE = "silenceMs"
    const val TEXT = "text"
    const val CODE = "code"
    const val FEED_CHUNK = 3_200
    const val MAX_AUDIO_BYTES = 30 * 16_000 * 2
    const val PREPARE_MS = 90_000L
    const val CAPTURE_MS = 60_000L
    const val DECODE_MS = 120_000L
}

