// SPDX-License-Identifier: Apache-2.0
package io.github.mangi.eta.agent.voice.local

import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File

/** A warm Model, not a warm utterance. All access/close stays on the service's serial worker. */
internal class VoskSpeechRecognizer(
    private val openDecoder: () -> VoskDecoder,
    private val closeModel: () -> Unit,
) : LocalSpeechRecognizer {
    private var released = false
    override fun openStream(): LocalSpeechStreamingSession {
        check(!released) { "Vosk model is closed" }
        return VoskSpeechSession(openDecoder())
    }
    override fun recognize(pcm: FloatArray): String = openStream().use { stream ->
        // Existing offline backends use normalized floats. Vosk's float API expects PCM scale;
        // use PCM16LE explicitly so this fallback has the same scale as live capture.
        val bytes = ByteArray(pcm.size * 2)
        pcm.forEachIndexed { index, value ->
            val sample = (value * 32768f).toInt().coerceIn(-32768, 32767)
            bytes[index * 2] = sample.toByte()
            bytes[index * 2 + 1] = (sample shr 8).toByte()
        }
        for (offset in bytes.indices step LocalSpeechProtocol.FEED_CHUNK)
            stream.accept(bytes.copyOfRange(offset, minOf(offset + LocalSpeechProtocol.FEED_CHUNK, bytes.size)))
        stream.finish()
    }
    override fun release() {
        if (!released) { released = true; closeModel() }
    }
    companion object {
        fun load(dir: File): VoskSpeechRecognizer {
            val model = Model(File(dir, "vosk").absolutePath)
            return VoskSpeechRecognizer(openDecoder = {
                val recognizer = Recognizer(model, 16_000f)
                object : VoskDecoder {
                    override fun accept(pcm: ByteArray) = recognizer.acceptWaveForm(pcm, pcm.size)
                    override fun result() = recognizer.result
                    override fun partial() = recognizer.partialResult
                    override fun finalResult() = recognizer.finalResult
                    override fun close() = recognizer.close()
                }
            }, closeModel = model::close)
        }
    }
}

/** Small native boundary makes real result assembly/ownership testable without loading ARM JNI. */
internal interface VoskDecoder : AutoCloseable {
    fun accept(pcm: ByteArray): Boolean
    fun result(): String
    fun partial(): String
    fun finalResult(): String
}

internal class VoskSpeechSession(private val decoder: VoskDecoder) : LocalSpeechStreamingSession {
    private val committed = mutableListOf<String>()
    private var lastPartial = ""
    private var finished = false
    private var closed = false
    private fun text(json: String, key: String): String {
        require(json.toByteArray(Charsets.UTF_8).size <= LocalSpeechProtocol.MAX_TEXT_BYTES) { "Vosk result is too large" }
        val value = JSONObject(json).get(key)
        require(value is String) { "Vosk result has no $key text" }
        return value.trim()
    }
    private fun combined(tail: String = ""): String = (committed + tail).filter { it.isNotEmpty() }.joinToString(" ").also {
        check(it.toByteArray(Charsets.UTF_8).size <= LocalSpeechProtocol.MAX_TEXT_BYTES) { "识别文本过长，请缩短语音" }
    }
    override fun accept(pcm: ByteArray): String? {
        check(!closed && !finished) { "Vosk utterance is closed" }
        require(pcm.isNotEmpty() && pcm.size % 2 == 0 && pcm.size <= LocalSpeechProtocol.FEED_CHUNK)
        val partial = if (decoder.accept(pcm)) {
            // Vosk's internal endpoint commits a segment only. Capture VAD/manual stop still
            // owns the end of the user utterance; never emit FINAL or stop recording here.
            text(decoder.result(), "text").takeIf { it.isNotEmpty() }?.let(committed::add)
            combined()
        } else combined(text(decoder.partial(), "partial"))
        return if (partial != lastPartial) partial.also { lastPartial = it } else null
    }
    override fun finish(): String {
        check(!closed && !finished) { "Vosk utterance is closed" }
        finished = true
        return combined(text(decoder.finalResult(), "text"))
    }
    override fun close() {
        if (!closed) { closed = true; decoder.close() }
    }
}
