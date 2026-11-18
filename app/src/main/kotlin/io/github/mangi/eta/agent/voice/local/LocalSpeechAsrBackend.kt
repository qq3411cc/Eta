package io.github.mangi.eta.agent.voice.local

import android.content.Context
import io.github.mangi.eta.agent.voice.LocalAsrBackend
import io.github.mangi.eta.agent.voice.SpeechErrorCode
import io.github.mangi.eta.agent.voice.SpeechFailure
import io.github.mangi.eta.data.model.LocalSpeechConfig
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * 用 Time-IU 的本机离线引擎（ggml/transcribe + Vosk 运行时）实现 [LocalAsrBackend]。
 *
 * [LocalSpeechEngine] 只是 IPC 客户端，native 在 `:local_speech` 独立进程里加载，
 * 所以引擎把进程拖垮时不会连带 App 主进程。
 */
internal object LocalSpeechAsrBackend : LocalAsrBackend {
    @Volatile
    private var app: Context? = null

    override val id: String = "local-speech"

    override val available: Boolean get() = app != null

    override fun attach(context: Context) {
        app = context.applicationContext
    }

    override fun describe(): String = runCatching {
        app?.let { LocalSpeechEngine.modelSummary(it) }
    }.getOrNull() ?: "本机离线识别未就绪"

    override suspend fun recognize(
        wav: ByteArray,
        config: LocalSpeechConfig,
        onProgress: (String) -> Unit,
    ): String {
        val context = app
            ?: throw SpeechFailure(SpeechErrorCode.LOCAL_BACKEND, "本机离线识别未初始化")
        val pcm = pcmPayload(wav)
        if (pcm.isEmpty()) throw SpeechFailure(SpeechErrorCode.AUDIO, "录音数据为空")
        onProgress("正在加载本机语音引擎")
        return coroutineScope {
            val frames = Channel<ByteArray>(Channel.BUFFERED)
            launch(Dispatchers.IO) {
                var offset = 0
                while (offset < pcm.size) {
                    val size = minOf(FRAME_BYTES, pcm.size - offset)
                    frames.send(pcm.copyOfRange(offset, offset + size))
                    offset += size
                }
                frames.close()
            }
            LocalSpeechEngine.recognize(
                context = context,
                frames = frames,
                begin = { onProgress("正在识别") },
                onPartial = {},
                onEndpoint = {},
                endpointSilenceMs = 0,
            )
        }
    }

    override fun release() = Unit

    /** 跳过 RIFF 头，取 data chunk 的 PCM16 主体；解析失败时退回「去掉 44 字节头」。 */
    private fun pcmPayload(wav: ByteArray): ByteArray {
        if (wav.size <= 44 || wav[0] != 'R'.code.toByte() || wav[1] != 'I'.code.toByte()) return wav
        val buffer = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        var cursor = 12
        while (cursor + 8 <= wav.size) {
            val id = String(wav, cursor, 4, Charsets.US_ASCII)
            val size = buffer.getInt(cursor + 4)
            if (size < 0 || cursor + 8 + size > wav.size) break
            if (id == "data") return wav.copyOfRange(cursor + 8, cursor + 8 + size)
            cursor += 8 + size
        }
        return wav.copyOfRange(44, wav.size)
    }

    private const val FRAME_BYTES = 6_400
}
