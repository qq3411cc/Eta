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
import android.os.Process
import android.os.SystemClock
import io.github.mangi.eta.agent.voice.SpeechErrorCode
import io.github.mangi.eta.agent.voice.SpeechFailure
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.UUID

/** No broadcast/optimistic invalidation: only the owning native worker can ACK deletion.
 * Cancellation before entry is harmless. Once entered, complete the confirmed operation
 * non-cancellably with a bounded wait for the actual result. IPC death/timeouts are explicitly unknown, never success; refresh
 * inspection in the UI's finally block, including cancellation, before offering a retry.
 */
internal object LocalSpeechDeletionClient {
    suspend fun delete(context: Context, modelId: String): LocalSpeechInstallation {
        currentCoroutineContext().ensureActive()
        return withContext(NonCancellable + Dispatchers.Main.immediate) {
            val app = context.applicationContext
            val id = UUID.randomUUID().toString()
            val connected = CompletableDeferred<IBinder>()
            val completed = CompletableDeferred<LocalSpeechInstallation>()
            var sent = false
            fun unknown(): SpeechFailure = SpeechFailure(SpeechErrorCode.SERVER,
                if (sent) "删除进程连接已中断，结果未确认（可能已部分删除）；请刷新模型状态后重试"
                else "无法连接本机离线服务，尚未发送删除请求")
            fun disconnected() {
                connected.completeExceptionally(unknown())
                completed.completeExceptionally(unknown())
            }
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, service: IBinder) { connected.complete(service) }
                override fun onServiceDisconnected(name: ComponentName) = disconnected()
                override fun onBindingDied(name: ComponentName) = disconnected()
                override fun onNullBinding(name: ComponentName) = disconnected()
            }
            val reply = Messenger(Handler(Looper.getMainLooper()) { msg ->
                if (msg.sendingUid == Process.myUid() && msg.data.getString(LocalSpeechProtocol.ID) == id &&
                    msg.data.getString(LocalSpeechProtocol.MODEL) == modelId) {
                    when (msg.what) {
                        LocalSpeechProtocol.DELETED -> completed.complete(LocalSpeechInstallation(modelId, false,
                            msg.data.getString(LocalSpeechProtocol.TEXT).orEmpty()))
                        LocalSpeechProtocol.ERROR -> completed.completeExceptionally(SpeechFailure(
                            runCatching { SpeechErrorCode.valueOf(msg.data.getString(LocalSpeechProtocol.CODE).orEmpty()) }
                                .getOrDefault(SpeechErrorCode.STORAGE),
                            msg.data.getString(LocalSpeechProtocol.TEXT).orEmpty()))
                    }
                }
                true
            })
            val death = IBinder.DeathRecipient { Handler(Looper.getMainLooper()).post { disconnected() } }
            var binder: IBinder? = null
            var linked = false
            var bound = false
            try {
                val deadline = SystemClock.elapsedRealtime() + LocalSpeechProtocol.DELETE_MS
                withTimeout(LocalSpeechProtocol.DELETE_MS) {
                    bound = app.bindService(Intent(app, LocalSpeechService::class.java), connection, Context.BIND_AUTO_CREATE)
                    if (!bound) throw unknown()
                    val service = connected.await(); binder = service
                    service.linkToDeath(death, 0); linked = true
                    sent = true // conservatively unknown if Binder fails during submission
                    Messenger(service).send(Message.obtain(null, LocalSpeechProtocol.DELETE).apply {
                        replyTo = reply
                        data = Bundle().apply {
                            putString(LocalSpeechProtocol.ID, id)
                            putString(LocalSpeechProtocol.MODEL, modelId)
                            putLong(LocalSpeechProtocol.DELETE_DEADLINE, deadline)
                        }
                    })
                    completed.await()
                }
            } catch (_: TimeoutCancellationException) {
                throw SpeechFailure(SpeechErrorCode.TIMEOUT,
                    if (sent) "删除结果未在限时内确认（可能已部分删除）；请刷新模型状态，勿直接重复操作"
                    else "连接删除服务超时，尚未发送删除请求")
            } catch (e: SpeechFailure) { throw e }
            catch (_: Exception) { throw unknown() }
            finally {
                if (linked) runCatching { binder?.unlinkToDeath(death, 0) }
                if (bound) runCatching { app.unbindService(connection) }
            }
        }
    }
}
