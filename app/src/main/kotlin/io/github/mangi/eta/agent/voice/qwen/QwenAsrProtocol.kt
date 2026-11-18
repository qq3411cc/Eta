// SPDX-License-Identifier: Apache-2.0
package io.github.mangi.eta.agent.voice.qwen

internal object QwenAsrProtocol {
    const val START = 1
    const val PCM = 2
    const val FINISH = 3
    const val CANCEL = 4
    const val READY = 10
    const val ACK = 11
    const val ENDPOINT = 13
    const val FINAL = 14
    const val ERROR = 15
    const val ID = "generation"
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

/** PCM16LE / mono / 16 kHz. Bounded storage and sample-clock energy endpointing. */
internal class QwenPcmAccumulator(private val silenceMs: Int) {
    private val audio = ByteArray(QwenAsrProtocol.MAX_AUDIO_BYTES)
    var size: Int = 0; private set
    var hasVoice: Boolean = false; private set
    private var silentSamples = 0
    fun append(pcm: ByteArray): Boolean {
        require(pcm.isNotEmpty() && pcm.size % 2 == 0 && pcm.size <= QwenAsrProtocol.FEED_CHUNK) { "PCM16 数据无效" }
        require(size + pcm.size <= audio.size) { "Qwen 单次录音最长 30 秒，请缩短语音后重试（不会静默丢弃超长语音）" }
        pcm.copyInto(audio, size); size += pcm.size
        // Each transport chunk is at most 100 ms; classify 10 ms windows so silence is consecutive.
        for (start in pcm.indices step 320) {
            val end = minOf(start + 320, pcm.size)
            var energy = 0.0
            for (i in start until end step 2) {
                val sample = ((pcm[i].toInt() and 255) or (pcm[i + 1].toInt() shl 8)).toShort().toInt()
                energy += sample.toDouble() * sample
            }
            val samples = (end - start) / 2
            if (energy / samples >= 262.0 * 262.0) { hasVoice = true; silentSamples = 0 }
            else if (hasVoice) silentSamples += samples
        }
        return silenceMs > 0 && hasVoice && silentSamples.toLong() * 1000 >= silenceMs.toLong() * 16_000
    }
    fun samples(start: Int, count: Int): FloatArray {
        require(start >= 0 && count >= 0 && (start + count) * 2 <= size)
        return FloatArray(count) { n ->
            val i = (start + n) * 2
            ((audio[i].toInt() and 255) or (audio[i + 1].toInt() shl 8)).toShort().toInt() / 32768f
        }
    }
}
