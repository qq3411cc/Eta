// SPDX-License-Identifier: Apache-2.0
package io.github.mangi.eta.agent.voice.local

import io.github.mangi.eta.agent.voice.SpeechErrorCode
import io.github.mangi.eta.agent.voice.SpeechFailure
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.selects.select

internal data class LocalSpeechEvent(val what: Int, val sequence: Int = 0, val text: String = "", val code: String = "")

/** The production IPC client's event/PCM pump. Transport and UI dispatch are injected, not policy. */
internal class LocalSpeechClientSession(
    private val events: ReceiveChannel<LocalSpeechEvent>,
    private val silenceMs: Int,
    private val onPartial: suspend (String) -> Unit,
    private val onEndpoint: suspend () -> Unit,
) {
    private var endpoint = false
    private suspend fun accept(event: LocalSpeechEvent) {
        when (event.what) {
            LocalSpeechProtocol.ERROR -> throw SpeechFailure(
                SpeechErrorCode.entries.firstOrNull { it.name == event.code } ?: SpeechErrorCode.PROTOCOL,
                event.text.ifBlank { "本机离线本地识别失败，请重试" },
            )
            LocalSpeechProtocol.PARTIAL -> {
                if (event.text.toByteArray(Charsets.UTF_8).size > LocalSpeechProtocol.MAX_TEXT_BYTES)
                    throw SpeechFailure(SpeechErrorCode.PROTOCOL, "本机离线识别文本过长")
                onPartial(event.text)
            }
            LocalSpeechProtocol.ENDPOINT -> if (!endpoint && silenceMs > 0) {
                endpoint = true; onEndpoint()
            }
            else -> throw SpeechFailure(SpeechErrorCode.PROTOCOL, "本机离线返回了意外的本地消息")
        }
    }
    suspend fun await(what: Int, sequence: Int = 0): String {
        while (true) {
            val event = events.receive()
            if (event.what == what) {
                if (what == LocalSpeechProtocol.ACK && event.sequence != sequence)
                    throw SpeechFailure(SpeechErrorCode.PROTOCOL, "本机离线音频确认序号不匹配")
                return event.text
            }
            accept(event)
        }
    }
    suspend fun capture(frames: ReceiveChannel<ByteArray>, sendPcm: suspend (ByteArray, Int, Int, Int) -> Unit) {
        var sequence = 0; var bytes = 0L
        while (!endpoint) {
            val received = select<Pair<Boolean, ByteArray?>> {
                frames.onReceiveCatching { result ->
                    result.exceptionOrNull()?.let { throw it }; true to result.getOrNull()
                }
                events.onReceive { event -> accept(event); false to null }
            }
            // A partial is an event, not PCM EOF. Only channel closure ends capture.
            if (!received.first) continue
            val frame = received.second ?: break
            if (frame.size % 2 != 0) throw SpeechFailure(SpeechErrorCode.AUDIO, "本机离线需要完整的 PCM16 采样")
            bytes += frame.size
            if (bytes > LocalSpeechProtocol.MAX_AUDIO_BYTES) throw SpeechFailure(SpeechErrorCode.AUDIO,
                "本机离线单次录音最长 30 秒，请缩短语音后重试（超长音频未被静默截断）")
            var offset = 0
            while (offset < frame.size && !endpoint) {
                val size = minOf(LocalSpeechProtocol.FEED_CHUNK, frame.size - offset)
                sendPcm(frame, offset, size, ++sequence)
                await(LocalSpeechProtocol.ACK, sequence)
                offset += size
            }
        }
    }
    suspend fun finish(sendFinish: () -> Unit): String {
        sendFinish()
        return await(LocalSpeechProtocol.FINAL).also {
            if (it.isBlank()) throw SpeechFailure(SpeechErrorCode.NO_SPEECH, "本机离线未识别到文字，请重试")
        }
    }
}
