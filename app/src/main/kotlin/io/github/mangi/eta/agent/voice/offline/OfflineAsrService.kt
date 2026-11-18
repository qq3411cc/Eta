// SPDX-License-Identifier: Apache-2.0
// Streaming/decode lifecycle informed by ZorvAI 036e4cdc; IPC/cancellation redesigned for Eta.
package io.github.mangi.eta.agent.voice.offline

import android.app.Application
import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.SystemClock
import com.k2fsa.sherpa.ncnn.DecoderConfig
import com.k2fsa.sherpa.ncnn.ModelConfig
import com.k2fsa.sherpa.ncnn.RecognizerConfig
import com.k2fsa.sherpa.ncnn.SherpaNcnn
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** All native operations (including release) are confined to [worker], never Binder/main threads. */
class OfflineAsrService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "EtaOfflineAsr") }
    @Volatile private var active: Session? = null
    private class Session(val id: String, val reply: Messenger, val silence: Int, val modelId: String) {
        val cancelled = AtomicBoolean(false)
        val closed = AtomicBoolean(false)
        val releasing = AtomicBoolean(false)
        val pending = AtomicBoolean(false)
        val deadline = SystemClock.elapsedRealtime() + OfflineAsrProtocol.SESSION_TIMEOUT_MS
        lateinit var death: IBinder.DeathRecipient
        lateinit var timeout: Runnable
        // Worker-only fields:
        var engine: SherpaNcnn? = null
        var ready = false
        var sequence = 0
        var bytes = 0L
        var lastText = ""
        var endpointSent = false
        var stage = "installed model validation"
    }
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { message ->
        if (message.sendingUid == Process.myUid()) receive(message)
        true
    })
    override fun onCreate() {
        super.onCreate()
        check(Application.getProcessName() == "$packageName:offline_asr") { "Native ASR requires dedicated process" }
    }
    override fun onBind(intent: Intent): IBinder = messenger.binder

    private fun receive(message: Message) {
        val id = message.data.getString(OfflineAsrProtocol.ID) ?: return
        if (id.length > 64) return
        if (message.what == OfflineAsrProtocol.START) {
            val reply = message.replyTo ?: return
            if (active != null) {
                sendDirect(reply, id, OfflineAsrProtocol.ERROR, "Offline ASR is busy")
                return
            }
            val silence = message.data.getInt(OfflineAsrProtocol.SILENCE)
            if (silence != 0 && silence !in 100..30_000) {
                sendDirect(reply, id, OfflineAsrProtocol.ERROR, "Invalid endpoint silence")
                return
            }
            val modelId = message.data.getString(OfflineAsrProtocol.MODEL_ID)
            if (modelId == null || !modelId.matches(Regex("[a-z][a-z0-9_]{0,63}"))) {
                sendDirect(reply, id, OfflineAsrProtocol.ERROR, "Missing or invalid offline model ID")
                return
            }
            val session = Session(id, reply, silence, modelId)
            session.death = IBinder.DeathRecipient { main.post { cancel(session) } }
            session.timeout = Runnable {
                if (!session.closed.get()) {
                    send(session, OfflineAsrProtocol.ERROR, "Offline ASR exceeded 65 seconds; audio was not uploaded")
                    cancel(session)
                }
            }
            active = session
            try {
                reply.binder.linkToDeath(session.death, 0)
                main.postDelayed(session.timeout, OfflineAsrProtocol.SESSION_TIMEOUT_MS)
                worker.execute { guarded(session) { load(session) } }
            } catch (_: Exception) { cancel(session) }
            return
        }
        val session = active?.takeIf { it.id == id && it.reply.binder == message.replyTo?.binder } ?: return
        if (message.what == OfflineAsrProtocol.CANCEL) { cancel(session); return }
        if (session.cancelled.get()) return
        if (message.what != OfflineAsrProtocol.PCM && message.what != OfflineAsrProtocol.FINISH) return
        // Reject queued/malicious PCM instead of accumulating unbounded worker tasks.
        if (!session.pending.compareAndSet(false, true)) {
            fail(session, "Offline ASR backpressure violation")
            return
        }
        val audio = if (message.what == OfflineAsrProtocol.PCM) message.data.getByteArray(OfflineAsrProtocol.AUDIO) else null
        if (message.what == OfflineAsrProtocol.PCM &&
            (audio == null || audio.isEmpty() || audio.size > OfflineAsrProtocol.MAX_CHUNK || audio.size % 2 != 0)) {
            fail(session, "Invalid bounded PCM16 chunk")
            return
        }
        val sequence = message.arg1
        val finish = message.what == OfflineAsrProtocol.FINISH
        worker.execute {
            guarded(session) {
                check(session.ready) { "Offline ASR is not ready" }
                if (finish) finish(session) else consume(session, checkNotNull(audio), sequence)
            }
        }
    }
    private fun checkSession(session: Session) {
        check(!session.cancelled.get() && active === session) { "Offline ASR cancelled" }
        check(SystemClock.elapsedRealtime() < session.deadline) { "Offline ASR deadline exceeded" }
    }
    private fun guarded(session: Session, task: () -> Unit) {
        try { checkSession(session); task() }
        catch (error: Throwable) {
            // Avoid exposing native paths or recognition content in logs/errors.
            if (!session.cancelled.get()) send(session, OfflineAsrProtocol.ERROR,
                if (error is OfflineAsrModelStore.MissingModelException) error.message.orEmpty()
                else "CONFIG: 离线语音失败 (${session.stage})；请检查离线模型设置，原模型保留，无云端回退")
            session.cancelled.set(true)
            close(session)
        }
    }
    private fun load(session: Session) {
        val directory = OfflineAsrModelStore.prepare(this, session.modelId) { checkSession(session) }
        checkSession(session)
        fun path(name: String) = File(directory, name).absolutePath
        val config = RecognizerConfig(
            modelConfig = ModelConfig(
                encoderParam = path("encoder_jit_trace-pnnx.ncnn.param"), encoderBin = path("encoder_jit_trace-pnnx.ncnn.bin"),
                decoderParam = path("decoder_jit_trace-pnnx.ncnn.param"), decoderBin = path("decoder_jit_trace-pnnx.ncnn.bin"),
                joinerParam = path("joiner_jit_trace-pnnx.ncnn.param"), joinerBin = path("joiner_jit_trace-pnnx.ncnn.bin"),
                tokens = path("tokens.txt"), numThreads = 2, useGPU = false),
            decoderConfig = DecoderConfig(method = "greedy_search", numActivePaths = 4),
            enableEndpoint = session.silence > 0,
            rule1MinTrailingSilence = maxOf(2.4f, session.silence / 1000f),
            rule2MinTrailingSilence = session.silence / 1000f,
            rule3MinUtteranceLength = 65f,
        )
        session.stage = "native library/model loading"
        check(SherpaNcnn.nativeLoaded) { SherpaNcnn.nativeLoadError }
        session.engine = SherpaNcnn(config = config)
        check(checkNotNull(session.engine).isValid) { "Native engine rejected model" }
        checkSession(session)
        session.stage = "streaming inference"
        session.ready = true
        send(session, OfflineAsrProtocol.READY)
    }
    private fun drain(session: Session) {
        val engine = checkNotNull(session.engine)
        var steps = 0
        while (engine.isReady()) {
            checkSession(session)
            check(++steps <= 10_000) { "Offline decoder step budget exceeded" }
            engine.decode()
        }
        checkSession(session)
    }
    private fun text(session: Session): String {
        val raw = checkNotNull(session.engine).getText()
        check(raw.length <= 16_384) { "Offline hypothesis exceeded IPC budget" }
        return raw.trim().replace(Regex("(?<=[\\p{IsHan}]) +(?=[\\p{IsHan}])"), "")
    }
    private fun consume(session: Session, audio: ByteArray, sequence: Int) {
        check(sequence == session.sequence + 1) { "Offline PCM sequence mismatch" }
        session.bytes += audio.size
        check(session.bytes <= OfflineAsrProtocol.MAX_AUDIO_BYTES) { "Offline audio exceeds 60 seconds" }
        val samples = FloatArray(audio.size / 2) { index ->
            val low = audio[index * 2].toInt() and 255
            val high = audio[index * 2 + 1].toInt()
            ((high shl 8) or low).toShort().toFloat() / 32768f
        }
        checkNotNull(session.engine).acceptWaveform(samples, 16000f)
        drain(session)
        val current = text(session)
        if (current != session.lastText) {
            session.lastText = current
            send(session, OfflineAsrProtocol.PARTIAL, current)
        }
        if (session.silence > 0 && !session.endpointSent && checkNotNull(session.engine).isEndpoint()) {
            session.endpointSent = true
            send(session, OfflineAsrProtocol.ENDPOINT)
        }
        session.sequence = sequence
        session.pending.set(false)
        send(session, OfflineAsrProtocol.ACK, sequence = sequence)
    }
    private fun finish(session: Session) {
        val engine = checkNotNull(session.engine)
        // Feature-tail padding is synthesized silence, not persisted or replayed user audio.
        engine.acceptWaveform(FloatArray(4800), 16000f)
        engine.inputFinished()
        drain(session)
        val finalText = text(session)
        checkSession(session)
        // Release before FINAL so a subsequent invocation cannot race an occupied native model.
        close(session)
        sendDirect(session.reply, session.id, OfflineAsrProtocol.FINAL, finalText)
    }
    private fun sendDirect(reply: Messenger, id: String, what: Int, text: String = "", sequence: Int = 0) {
        reply.send(Message.obtain(null, what).apply {
            arg1 = sequence
            data = Bundle().apply { putString(OfflineAsrProtocol.ID, id); putString(OfflineAsrProtocol.TEXT, text) }
        })
    }
    private fun send(session: Session, what: Int, text: String = "", sequence: Int = 0) {
        if (session.cancelled.get()) return
        try { sendDirect(session.reply, session.id, what, text, sequence) }
        catch (_: Exception) { main.post { cancel(session) } }
    }
    private fun fail(session: Session, reason: String) {
        send(session, OfflineAsrProtocol.ERROR, reason)
        cancel(session)
    }
    private fun cancel(session: Session) {
        if (session.closed.get() || !session.cancelled.compareAndSet(false, true)) return
        // Native calls cannot be safely interrupted in-place. Never release on another thread.
        main.postDelayed({
            if (!session.closed.get() && Application.getProcessName() == "$packageName:offline_asr") {
                Process.killProcess(Process.myPid())
            }
        }, 2000L)
        worker.execute { close(session) }
    }
    /** Must only be called by worker. closed becomes true AFTER native delete returns. */
    private fun close(session: Session) {
        if (session.closed.get() || !session.releasing.compareAndSet(false, true)) return
        main.postDelayed({
            if (!session.closed.get() && Application.getProcessName() == "$packageName:offline_asr") {
                Process.killProcess(Process.myPid())
            }
        }, 2000L)
        session.engine?.release()
        session.engine = null
        session.closed.set(true)
        main.removeCallbacks(session.timeout)
        runCatching { session.reply.binder.unlinkToDeath(session.death, 0) }
        if (active === session) active = null
    }
    override fun onDestroy() {
        active?.let { cancel(it) }
        worker.shutdown() // queued serial release still runs
        super.onDestroy()
    }
}
