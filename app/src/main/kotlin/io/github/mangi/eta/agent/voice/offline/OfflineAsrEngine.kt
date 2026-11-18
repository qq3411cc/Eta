// SPDX-License-Identifier: Apache-2.0
package io.github.mangi.eta.agent.voice.offline

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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.selects.select
import java.util.UUID

/** IPC-only client. Importing this object never loads or constructs a native recognizer. */
object OfflineAsrEngine {
    fun modelSummary(context: Context): String = OfflineAsrModelStore.modelSummary(context)
    fun availableModels(context: Context): List<OfflineAsrModelStore.ModelDescriptor> = OfflineAsrModelStore.availableModels(context)
    fun selectedModelId(context: Context): String = OfflineAsrModelStore.selectedModelId(context)
    fun setSelectedModelId(context: Context, id: String) = OfflineAsrModelStore.setSelectedModelId(context, id)

    suspend fun recognize(
        context: Context,
        frames: ReceiveChannel<ByteArray>,
        begin: () -> Unit,
        onPartial: (String) -> Unit,
        onEndpoint: () -> Unit,
        endpointSilenceMs: Int,
    ): String = withContext(Dispatchers.IO) {
        require(endpointSilenceMs == 0 || endpointSilenceMs in 100..30_000) { "Invalid endpoint silence" }
        val app = context.applicationContext
        // Snapshot in the client process; never read cross-process SharedPreferences in service.
        val modelId = selectedModelId(app)
        val id = UUID.randomUUID().toString()
        data class Event(val what: Int, val sequence: Int, val text: String)
        val inbox = Channel<Event>(16)
        val connected = CompletableDeferred<IBinder>()
        val reply = Messenger(Handler(Looper.getMainLooper()) { message ->
            if (message.data.getString(OfflineAsrProtocol.ID) == id) {
                val event = Event(message.what, message.arg1, message.data.getString(OfflineAsrProtocol.TEXT).orEmpty())
                if (inbox.trySend(event).isFailure) inbox.close(IllegalStateException("Offline ASR reply overflow"))
            }
            true
        })
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) { connected.complete(binder) }
            override fun onServiceDisconnected(name: ComponentName) {
                val error = IllegalStateException("Offline ASR service died; audio was not uploaded")
                connected.completeExceptionally(error)
                inbox.close(error)
            }
            override fun onBindingDied(name: ComponentName) = onServiceDisconnected(name)
            override fun onNullBinding(name: ComponentName) {
                val error = IllegalStateException("Offline ASR binding rejected")
                connected.completeExceptionally(error)
                inbox.close(error)
            }
        }
        val death = IBinder.DeathRecipient { inbox.close(IllegalStateException("Offline native process died; model preserved")) }
        var remote: Messenger? = null
        var binder: IBinder? = null
        var linked = false
        var bound = false
        var endpointNotified = false
        fun send(what: Int, sequence: Int = 0, audio: ByteArray? = null) {
            val message = Message.obtain(null, what).apply {
                arg1 = sequence
                replyTo = reply
                data = Bundle().apply {
                    putString(OfflineAsrProtocol.ID, id)
                    if (what == OfflineAsrProtocol.START) {
                        putInt(OfflineAsrProtocol.SILENCE, endpointSilenceMs)
                        putString(OfflineAsrProtocol.MODEL_ID, modelId)
                    }
                    if (audio != null) putByteArray(OfflineAsrProtocol.AUDIO, audio)
                }
            }
            checkNotNull(remote).send(message)
        }
        suspend fun await(what: Int, sequence: Int = 0): String {
            while (true) {
                val event = inbox.receive()
                when (event.what) {
                    OfflineAsrProtocol.PARTIAL -> withContext(Dispatchers.Main.immediate) { onPartial(event.text) }
                    OfflineAsrProtocol.ENDPOINT -> if (endpointSilenceMs > 0 && !endpointNotified) {
                        endpointNotified = true
                        withContext(Dispatchers.Main.immediate) { onEndpoint() }
                    }
                    OfflineAsrProtocol.ERROR -> throw IllegalStateException(event.text)
                    what -> {
                        check(what != OfflineAsrProtocol.ACK || event.sequence == sequence) { "Offline ASR ACK mismatch" }
                        return event.text
                    }
                    else -> error("Unexpected offline ASR reply ${event.what}")
                }
            }
        }
        try {
            withTimeout(OfflineAsrProtocol.SESSION_TIMEOUT_MS) {
                withContext(Dispatchers.Main.immediate) {
                    bound = app.bindService(Intent(app, OfflineAsrService::class.java), connection, Context.BIND_AUTO_CREATE)
                    check(bound) { "Offline ASR service unavailable" }
                }
                val serviceBinder = connected.await()
                binder = serviceBinder
                serviceBinder.linkToDeath(death, 0)
                linked = true
                remote = Messenger(serviceBinder)
                send(OfflineAsrProtocol.START)
                await(OfflineAsrProtocol.READY)
                withContext(Dispatchers.Main.immediate) { begin() }
                var sequence = 0
                var bytes = 0L
                while (true) {
                    // Also observe process death/errors when capture is idle or still open.
                    val frame = select<ByteArray?> {
                        frames.onReceiveCatching { result ->
                            result.exceptionOrNull()?.let { throw it }
                            result.getOrNull()
                        }
                        inbox.onReceive { event ->
                            if (event.what == OfflineAsrProtocol.ERROR) throw IllegalStateException(event.text)
                            error("Unexpected idle offline ASR reply ${event.what}")
                        }
                    } ?: break
                    require(frame.size % 2 == 0) { "PCM16 frame has an incomplete sample" }
                    bytes += frame.size
                    require(bytes <= OfflineAsrProtocol.MAX_AUDIO_BYTES) { "Offline audio exceeds 60 seconds" }
                    var offset = 0
                    while (offset < frame.size) {
                        val end = minOf(frame.size, offset + OfflineAsrProtocol.FEED_CHUNK)
                        send(OfflineAsrProtocol.PCM, ++sequence, frame.copyOfRange(offset, end))
                        // ACK is emitted only after native consumption: strictly one chunk in flight.
                        await(OfflineAsrProtocol.ACK, sequence)
                        offset = end
                    }
                }
                send(OfflineAsrProtocol.FINISH)
                await(OfflineAsrProtocol.FINAL)
            }
        } finally {
            // NonCancellable only covers nonblocking cleanup, never waits for a hung native call.
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                runCatching { if (remote != null) send(OfflineAsrProtocol.CANCEL) }
                if (linked) runCatching { binder?.unlinkToDeath(death, 0) }
                if (bound) runCatching { app.unbindService(connection) }
                inbox.close()
            }
        }
    }
}
