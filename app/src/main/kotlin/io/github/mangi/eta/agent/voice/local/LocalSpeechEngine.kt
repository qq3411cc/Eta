// SPDX-License-Identifier: Apache-2.0
package io.github.mangi.eta.agent.voice.local

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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.UUID

/** IPC client only: this object and the main application never reference/load sherpa JNI. */
object LocalSpeechEngine {
    fun availableModels(context: Context) = LocalSpeechModelStore.availableModels(context)
    fun selectedModelId(context: Context): String = LocalSpeechModelStore.selectedId(context)
    fun setSelectedModelId(context: Context, id: String) = LocalSpeechModelStore.selectModel(context, id)
    fun modelSummary(context: Context): String = availableModels(context).firstOrNull { it.id == selectedModelId(context) }?.displayName ?: "请选择本机模型"

    suspend fun deleteModel(context: Context, id: String): LocalSpeechInstallation = LocalSpeechModelStore.deleteModel(context, id)

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
        val modelId = LocalSpeechModelStore.selectedId(app) // Snapshot only in main process.
        val id = UUID.randomUUID().toString()
        val events = Channel<LocalSpeechEvent>(16)
        val connected = CompletableDeferred<IBinder>()
        val reply = Messenger(Handler(Looper.getMainLooper()) { msg ->
            if (msg.data.getString(LocalSpeechProtocol.ID) == id) {
                if (events.trySend(LocalSpeechEvent(msg.what, msg.arg1, msg.data.getString(LocalSpeechProtocol.TEXT).orEmpty(),
                    msg.data.getString(LocalSpeechProtocol.CODE).orEmpty())).isFailure)
                    events.close(SpeechFailure(SpeechErrorCode.BACKPRESSURE, "本机离线本地识别消息过多，请重试"))
            }
            true
        })
        fun died() {
            val failure = SpeechFailure(SpeechErrorCode.SERVER, "本机离线本地进程已停止（可能内存不足），模型保留且音频未上传")
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
        fun send(what: Int, sequence: Int = 0, size: Int = 0) {
            checkNotNull(remote).send(Message.obtain(null, what).apply {
                arg1 = sequence; arg2 = size; replyTo = reply
                data = Bundle().apply {
                    putString(LocalSpeechProtocol.ID, id)
                    if (what == LocalSpeechProtocol.START) {
                        putString(LocalSpeechProtocol.MODEL, modelId)
                        putParcelable(LocalSpeechProtocol.PIPE, pipe[0]); putInt(LocalSpeechProtocol.SILENCE, endpointSilenceMs)
                    }
                }
            })
        }
        val session = LocalSpeechClientSession(events, endpointSilenceMs,
            onPartial = { withContext(Dispatchers.Main.immediate) { onPartial(it) } },
            onEndpoint = { withContext(Dispatchers.Main.immediate) { onEndpoint() } },
        )
        suspend fun <T> phase(millis: Long, message: String, block: suspend () -> T): T = try {
            withTimeout(millis) { block() }
        } catch (e: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw SpeechFailure(SpeechErrorCode.TIMEOUT, message)
        }
        try {
            phase(LocalSpeechProtocol.PREPARE_MS + 5_000, "本机离线模型准备超时，请检查可用内存") {
                withContext(Dispatchers.Main.immediate) {
                    bound = app.bindService(Intent(app, LocalSpeechService::class.java), connection, Context.BIND_AUTO_CREATE)
                    if (!bound) throw SpeechFailure(SpeechErrorCode.CONFIGURATION, "无法启动本机离线本地识别进程")
                }
                val service = connected.await(); binder = service
                service.linkToDeath(death, 0); linked = true; remote = Messenger(service)
                send(LocalSpeechProtocol.START)
                pipe[0].close() // Binder owns a duplicated FD in the remote process.
                session.await(LocalSpeechProtocol.READY)
            }
            withContext(Dispatchers.Main.immediate) { begin() }
            phase(LocalSpeechProtocol.CAPTURE_MS, "本机离线录音等待超时，单次录音最长 30 秒") {
                session.capture(frames) { frame, offset, size, sequence ->
                    // One bounded chunk in flight; the session waits for its matching ACK.
                    output.write(frame, offset, size)
                    send(LocalSpeechProtocol.PCM, sequence, size)
                }
            }
            output.close()
            val text = phase(LocalSpeechProtocol.DECODE_MS + 5_000, "本机离线本地解码超过 120 秒，请重试") {
                session.finish { send(LocalSpeechProtocol.FINISH) }
            }
            if (text.isBlank()) throw SpeechFailure(SpeechErrorCode.NO_SPEECH, "本机离线未识别到文字，请重试")
            // FINAL is returned once, never duplicated as a partial callback.
            text
        } catch (e: CancellationException) { throw e }
        catch (e: SpeechFailure) { throw e }
        catch (e: Exception) {
            throw SpeechFailure(SpeechErrorCode.PROTOCOL, "本机离线本地识别连接失败，请重试（音频未上传）")
        } finally {
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                runCatching { if (remote != null) send(LocalSpeechProtocol.CANCEL) }
                runCatching { output.close() }; runCatching { pipe[0].close() }
                if (linked) runCatching { binder?.unlinkToDeath(death, 0) }
                if (bound) runCatching { app.unbindService(connection) }
                events.close()
            }
        }
    }
}
