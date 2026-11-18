// SPDX-License-Identifier: Apache-2.0
package io.github.mangi.eta.agent.voice.qwen

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import io.github.mangi.eta.agent.voice.SpeechErrorCode
import io.github.mangi.eta.agent.voice.SpeechFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.UUID

/** IPC client only: this object and the main application never reference/load sherpa JNI. */
object QwenAsrEngine {
    suspend fun recognize(
        context: Context,
        frames: ReceiveChannel<ByteArray>,
        begin: () -> Unit,
        onPartial: (String) -> Unit,
        onEndpoint: () -> Unit,
        endpointSilenceMs: Int,
    ): String = withContext(Dispatchers.IO) {
        if (endpointSilenceMs != 0 && endpointSilenceMs !in 100..30_000)
            throw SpeechFailure(SpeechErrorCode.CONFIGURATION, "自动停录静音时间须为 100～30000 毫秒，0 表示手动停录")
        val app = context.applicationContext
        val id = UUID.randomUUID().toString()
        data class Event(val what: Int, val sequence: Int, val text: String, val code: String)
        val events = Channel<Event>(16)
        val connected = CompletableDeferred<IBinder>()
        val reply = Messenger(Handler(Looper.getMainLooper()) { msg ->
            if (msg.data.getString(QwenAsrProtocol.ID) == id) {
                if (events.trySend(Event(msg.what, msg.arg1, msg.data.getString(QwenAsrProtocol.TEXT).orEmpty(),
                    msg.data.getString(QwenAsrProtocol.CODE).orEmpty())).isFailure)
                    events.close(SpeechFailure(SpeechErrorCode.BACKPRESSURE, "Qwen 本地识别消息过多，请重试"))
            }
            true
        })
        fun died() {
            val failure = SpeechFailure(SpeechErrorCode.SERVER, "Qwen 本地进程已停止（可能内存不足），模型保留且音频未上传")
            connected.completeExceptionally(failure); events.close(failure)
        }
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) { connected.complete(service) }
            override fun onServiceDisconnected(name: ComponentName) = died()
            override fun onBindingDied(name: ComponentName) = died()
            override fun onNullBinding(name: ComponentName) = died()
        }
        val death = IBinder.DeathRecipient { died() }
        var binder: IBinder? = null
        var remote: Messenger? = null
        var linked = false
        var bound = false
        val pipe = ParcelFileDescriptor.createPipe()
        val output = ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])
        var endpoint = false
        fun send(what: Int, sequence: Int = 0, size: Int = 0) {
            checkNotNull(remote).send(Message.obtain(null, what).apply {
                arg1 = sequence; arg2 = size; replyTo = reply
                data = Bundle().apply {
                    putString(QwenAsrProtocol.ID, id)
                    if (what == QwenAsrProtocol.START) {
                        putParcelable(QwenAsrProtocol.PIPE, pipe[0]); putInt(QwenAsrProtocol.SILENCE, endpointSilenceMs)
                    }
                }
            })
        }
        fun error(event: Event): Nothing = throw SpeechFailure(
            SpeechErrorCode.entries.firstOrNull { it.name == event.code } ?: SpeechErrorCode.PROTOCOL,
            event.text.ifBlank { "Qwen 本地识别失败，请重试" },
        )
        suspend fun accept(event: Event) {
            when (event.what) {
                QwenAsrProtocol.ERROR -> error(event)
                QwenAsrProtocol.ENDPOINT -> if (!endpoint && endpointSilenceMs > 0) {
                    endpoint = true; withContext(Dispatchers.Main.immediate) { onEndpoint() }
                }
                else -> throw SpeechFailure(SpeechErrorCode.PROTOCOL, "Qwen 返回了意外的本地消息")
            }
        }
        suspend fun await(what: Int, sequence: Int = 0): String {
            while (true) {
                val event = events.receive()
                if (event.what == what) {
                    if (what == QwenAsrProtocol.ACK && event.sequence != sequence)
                        throw SpeechFailure(SpeechErrorCode.PROTOCOL, "Qwen 音频确认序号不匹配")
                    return event.text
                }
                accept(event)
            }
        }
        suspend fun <T> phase(millis: Long, message: String, block: suspend () -> T): T = try {
            withTimeout(millis) { block() }
        } catch (e: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw SpeechFailure(SpeechErrorCode.TIMEOUT, message)
        }
        try {
            phase(QwenAsrProtocol.PREPARE_MS + 5_000, "Qwen 模型准备超时，请检查可用内存") {
                withContext(Dispatchers.Main.immediate) {
                    bound = app.bindService(Intent(app, QwenAsrService::class.java), connection, Context.BIND_AUTO_CREATE)
                    if (!bound) throw SpeechFailure(SpeechErrorCode.CONFIGURATION, "无法启动 Qwen 本地识别进程")
                }
                val service = connected.await(); binder = service
                service.linkToDeath(death, 0); linked = true; remote = Messenger(service)
                send(QwenAsrProtocol.START)
                pipe[0].close() // Binder owns a duplicated FD in the remote process.
                await(QwenAsrProtocol.READY)
            }
            withContext(Dispatchers.Main.immediate) { begin() }
            phase(QwenAsrProtocol.CAPTURE_MS, "Qwen 录音等待超时，单次录音最长 30 秒") {
                var sequence = 0; var bytes = 0L
                while (!endpoint) {
                    val frame = select<ByteArray?> {
                        frames.onReceiveCatching { result -> result.exceptionOrNull()?.let { throw it }; result.getOrNull() }
                        events.onReceive { event -> accept(event); null }
                    } ?: break
                    if (frame.size % 2 != 0) throw SpeechFailure(SpeechErrorCode.AUDIO, "Qwen 需要完整的 PCM16 采样")
                    bytes += frame.size
                    if (bytes > QwenAsrProtocol.MAX_AUDIO_BYTES) throw SpeechFailure(SpeechErrorCode.AUDIO,
                        "Qwen 单次录音最长 30 秒，请缩短语音后重试（超长音频未被静默截断）")
                    var offset = 0
                    while (offset < frame.size && !endpoint) {
                        val size = minOf(QwenAsrProtocol.FEED_CHUNK, frame.size - offset)
                        // One 3200-byte chunk in flight. Pipe capacity exceeds this bounded write.
                        output.write(frame, offset, size)
                        send(QwenAsrProtocol.PCM, ++sequence, size)
                        await(QwenAsrProtocol.ACK, sequence)
                        offset += size
                    }
                }
            }
            output.close()
            val text = phase(QwenAsrProtocol.DECODE_MS + 5_000, "Qwen 本地解码超过 120 秒，请重试") {
                send(QwenAsrProtocol.FINISH)
                await(QwenAsrProtocol.FINAL)
            }
            if (text.isBlank()) throw SpeechFailure(SpeechErrorCode.NO_SPEECH, "Qwen 未识别到文字，请重试")
            // This is a non-streaming model. One final callback, never synthetic token-by-token partials.
            withContext(Dispatchers.Main.immediate) { onPartial(text) }
            text
        } catch (e: CancellationException) { throw e }
        catch (e: SpeechFailure) { throw e }
        catch (e: Exception) {
            throw SpeechFailure(SpeechErrorCode.PROTOCOL, "Qwen 本地识别连接失败，请重试（音频未上传）")
        } finally {
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                runCatching { if (remote != null) send(QwenAsrProtocol.CANCEL) }
                runCatching { output.close() }; runCatching { pipe[0].close() }
                if (linked) runCatching { binder?.unlinkToDeath(death, 0) }
                if (bound) runCatching { app.unbindService(connection) }
                events.close()
            }
        }
    }
}
