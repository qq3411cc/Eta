// SPDX-License-Identifier: Apache-2.0
package io.github.mangi.eta.agent.voice.local

import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import java.io.File

/** Loaded exclusively on LocalSpeechService's serial worker. Never reference from the IPC client. */
internal interface LocalSpeechRecognizer {
    fun recognize(pcm: FloatArray): String
    fun release()
    fun openStream(): LocalSpeechStreamingSession? = null
    companion object {
        fun create(dir: File, model: LocalSpeechModelDescriptor): LocalSpeechRecognizer = when (model.engine) {
            LocalSpeechBackend.GGUF -> {
                val handle = GgufSpeechNative.load(File(dir, model.files.single().name).absolutePath, 3)
                check(handle != 0L) { "GGUF 原生模型加载失败" }
                object : LocalSpeechRecognizer {
                    override fun recognize(pcm: FloatArray): String = GgufSpeechNative.recognize(handle, pcm)
                    override fun release() = GgufSpeechNative.release(handle)
                }
            }
            LocalSpeechBackend.VOSK -> VoskSpeechRecognizer.load(dir)
            LocalSpeechBackend.SENSEVOICE_ONNX -> {
                val recognizer = OfflineRecognizer(config = OfflineRecognizerConfig(modelConfig = OfflineModelConfig(
                    senseVoice = OfflineSenseVoiceModelConfig(model = File(dir, "model.int8.onnx").absolutePath,
                        language = "auto", useInverseTextNormalization = true),
                    tokens = File(dir, "tokens.txt").absolutePath, numThreads = 3, provider = "cpu", debug = false,
                )))
                object : LocalSpeechRecognizer {
                    override fun recognize(pcm: FloatArray): String {
                        val stream = recognizer.createStream()
                        try {
                            stream.acceptWaveform(pcm, 16_000)
                            recognizer.decode(stream)
                            return recognizer.getResult(stream).text
                        } finally { stream.release() }
                    }
                    override fun release() = recognizer.release()
                }
            }
        }
    }
}

/** Session-owned streaming state, closed before returning the cached model lease. */
internal interface LocalSpeechStreamingSession : AutoCloseable {
    fun accept(pcm: ByteArray): String?
    fun finish(): String
}
