// SPDX-License-Identifier: Apache-2.0
package io.github.mangi.eta.agent.voice.qwen

import android.app.Application
import android.app.Service
import android.content.Intent
import android.content.ComponentCallbacks2
import android.os.SystemClock
import android.util.Log
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.os.Process
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import io.github.mangi.eta.agent.voice.SpeechErrorCode
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** JNI, native construction, decode and release are reachable only in :qwen_asr. */
class QwenAsrService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "EtaQwenNative") }
    private val cache = WarmCachePolicy<QwenModelStore.ModelIdentity, OfflineRecognizer>({ it.release() })
    private var idleTimer: Runnable? = null
    private var retentionGeneration = 0L // main-thread START/stopSelf race guard
    @Volatile private var destroyed = false
    @Volatile private var active: Session? = null
    private class Session(val id: String, var reply: Messenger?, val input: ParcelFileDescriptor.AutoCloseInputStream, silence: Int) {
        val cancelled = AtomicBoolean()
        val closed = AtomicBoolean()
        val pending = AtomicBoolean()
        val finishing = AtomicBoolean()
        var audio: QwenPcmAccumulator? = QwenPcmAccumulator(silence)
        val native = AtomicBoolean()
        var recognizer: OfflineRecognizer? = null // only accessed on worker
        var sequence = 0
        var endpoint = false
        lateinit var death: IBinder.DeathRecipient
        var timer: Runnable? = null
    }
    override fun onCreate() {
        super.onCreate()
        check(Application.getProcessName() == "$packageName:qwen_asr") { "Qwen JNI requires its own process" }
    }
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { msg ->
        if (msg.sendingUid != Process.myUid()) return@Handler true
        val id = msg.data.getString(QwenAsrProtocol.ID) ?: return@Handler true
        when (msg.what) {
            QwenAsrProtocol.START -> start(msg, id)
            QwenAsrProtocol.CANCEL -> active?.takeIf { it.id == id }?.let { close(it) }
            else -> active?.takeIf { it.id == id && !it.cancelled.get() }?.let { s ->
                when (msg.what) {
                    QwenAsrProtocol.PCM -> {
                        val count = msg.arg2
                        if (s.finishing.get() || s.endpoint || count !in 2..QwenAsrProtocol.FEED_CHUNK || count % 2 != 0 ||
                            msg.arg1 != s.sequence + 1 || !s.pending.compareAndSet(false, true)) {
                            fail(s, SpeechErrorCode.PROTOCOL, "Qwen 音频传输顺序错误，请重试")
                        } else {
                            s.sequence = msg.arg1; val sequence = msg.arg1
                            worker.execute {
                                try {
                                    if (!s.cancelled.get()) {
                                        val audio = ByteArray(count); var offset = 0
                                        while (offset < count) {
                                            val n = s.input.read(audio, offset, count - offset)
                                            check(n > 0) { "Qwen 音频管道提前关闭" }; offset += n
                                        }
                                        val endpoint = checkNotNull(s.audio).append(audio)
                                        if (endpoint) { s.endpoint = true; reply(s, QwenAsrProtocol.ENDPOINT) }
                                        s.pending.set(false)
                                        reply(s, QwenAsrProtocol.ACK, sequence = sequence)
                                    }
                                } catch (e: Exception) { fail(s, SpeechErrorCode.AUDIO, e.message ?: "Qwen 音频读取失败") }
                            }
                        }
                    }
                    QwenAsrProtocol.FINISH -> {
                        if (s.pending.get() || !s.finishing.compareAndSet(false, true)) {
                            fail(s, SpeechErrorCode.PROTOCOL, "Qwen 音频尚未接收完成")
                        } else {
                            arm(s, QwenAsrProtocol.DECODE_MS, "Qwen 本地解码超时，模型文件已保留")
                            worker.execute { decode(s) } // strictly after the last acknowledged PCM read
                        }
                    }
                }
            }
        }
        true
    })
    private fun start(message: Message, id: String) {
        val pfd = message.data.getParcelable(QwenAsrProtocol.PIPE, ParcelFileDescriptor::class.java) ?: return
        val remote = message.replyTo
        if (remote == null) { pfd.close(); return }
        if (active != null || destroyed) {
            pfd.close()
            runCatching { remote.send(Message.obtain(null, QwenAsrProtocol.ERROR).apply { data = Bundle().apply {
                putString(QwenAsrProtocol.ID, id); putString(QwenAsrProtocol.CODE, SpeechErrorCode.BACKPRESSURE.name)
                putString(QwenAsrProtocol.TEXT, "Qwen 本地识别正在使用中，请稍后重试")
            } }) }
            return
        }
        val silence = message.data.getInt(QwenAsrProtocol.SILENCE)
        if (silence != 0 && silence !in 100..30_000) { pfd.close(); return }
        val s = Session(id, remote, ParcelFileDescriptor.AutoCloseInputStream(pfd), silence)
        active = s
        retentionGeneration++
        idleTimer?.let { main.removeCallbacks(it) }; idleTimer = null
        // Only reached after an app-UID START caused by user speech; no FGS, alarm or wake lock.
        // Background start restrictions may reject this: bound-only recognition remains valid.
        runCatching { startService(Intent(this, QwenAsrService::class.java)) }
            .onFailure { Log.i("EtaQwen", "provider=qwen model-cache=bound-only reason=background-start-restricted") }
        s.death = IBinder.DeathRecipient { main.post { close(s) } }
        try { remote.binder.linkToDeath(s.death, 0) } catch (_: Exception) { close(s); return }
        arm(s, QwenAsrProtocol.PREPARE_MS, "Qwen 模型校验或加载超过 90 秒，请检查设备内存后重试")
        worker.execute {
            try {
                if (s.cancelled.get()) return@execute
                val prepareStart = SystemClock.elapsedRealtime()
                val quick = runCatching { QwenModelStore.quickFingerprint(this) }.getOrNull()
                if (s.cancelled.get()) return@execute
                var hashMs = 0L; var loadMs = 0L
                val (recognizer, hit) = cache.borrow(s, quick) {
                    runBlocking {
                        QwenModelStore.loadVerified(this@QwenAsrService, { it: OfflineRecognizer -> it.release() },
                            onVerified = { hashMs = it }) {
                            s.native.set(true)
                            try {
                                check(!s.cancelled.get()) { "Qwen session cancelled" }
                                val loadStart = SystemClock.elapsedRealtime()
                                QwenNativeRecognizer.create(QwenModelStore.directory(this@QwenAsrService).absolutePath)
                                    .also { loadMs = SystemClock.elapsedRealtime() - loadStart }
                            } finally { s.native.set(false) }
                        }
                    }
                }
                s.recognizer = recognizer // borrowed, never session-owned
                Log.i("EtaQwen", "provider=qwen model-cache=${if (hit) "hit" else "miss"} " +
                    "hash-ms=$hashMs cold-load-ms=$loadMs ready-ms=${SystemClock.elapsedRealtime() - prepareStart}")
                main.post {
                    if (!s.cancelled.get()) {
                        arm(s, QwenAsrProtocol.CAPTURE_MS, "Qwen 等待录音超时，单次录音最长 30 秒")
                        reply(s, QwenAsrProtocol.READY)
                    }
                }
            } catch (e: Exception) { fail(s, SpeechErrorCode.CONFIGURATION, "Qwen 模型加载失败：${e.message.orEmpty().take(180)}") }
            catch (e: LinkageError) { fail(s, SpeechErrorCode.CONFIGURATION, "Qwen 原生库不兼容，请安装 arm64 完整版") }
            catch (e: OutOfMemoryError) { fail(s, SpeechErrorCode.STORAGE, "Qwen 内存不足，请关闭其他应用后重试") }
        }
    }
    private fun decode(s: Session) {
        if (s.cancelled.get()) return
        s.native.set(true)
        try {
            if (s.cancelled.get()) return
            val audio = checkNotNull(s.audio)
            check(audio.hasVoice && audio.size > 0) { "未检测到清晰语音，请重试" }
            val recognizer = checkNotNull(s.recognizer)
            val parts = mutableListOf<String>()
            val count = audio.size / 2
            // Qwen is non-streaming. Bounded ten-second segments prevent prompt/audio context truncation.
            // All captured PCM is processed, including the last short segment; only one final is delivered.
            for (start in 0 until count step 160_000) {
                if (s.cancelled.get()) return
                val stream = recognizer.createStream()
                try {
                    stream.acceptWaveform(audio.samples(start, minOf(160_000, count - start)), 16_000)
                    recognizer.decode(stream)
                    val result = recognizer.getResult(stream)
                    check(result.tokens.size < 480) { "Qwen 文本达到解码上限，请缩短语音后重试" }
                    if (result.text.isNotBlank()) parts += result.text.trim()
                } finally { stream.release() }
            }
            val text = parts.joinToString(" ").trim()
            check(text.isNotEmpty()) { "Qwen 未识别到文字，请重试" }
            // Stream is already released. Complete PCM/FD/reply/lease cleanup before FINAL.
            main.post { close(s, text) }
        } catch (e: Exception) { fail(s, SpeechErrorCode.NO_SPEECH, e.message ?: "Qwen 本地识别失败") }
        catch (e: LinkageError) { cache.trim(); fail(s, SpeechErrorCode.CONFIGURATION, "Qwen 原生解码失败，请重新安装完整版") }
        catch (e: OutOfMemoryError) { cache.trim(); fail(s, SpeechErrorCode.STORAGE, "Qwen 解码内存不足，音频未上传") }
        finally { s.native.set(false) }
    }
    private fun reply(s: Session, what: Int, text: String = "", sequence: Int = 0, code: SpeechErrorCode? = null) {
        if (s.cancelled.get()) return
        try { s.reply?.send(Message.obtain(null, what).apply {
            arg1 = sequence; data = Bundle().apply {
                putString(QwenAsrProtocol.ID, s.id); putString(QwenAsrProtocol.TEXT, text)
                code?.let { putString(QwenAsrProtocol.CODE, it.name) }
            }
        }) } catch (_: Exception) { main.post { close(s) } }
    }
    private fun fail(s: Session, code: SpeechErrorCode, message: String) {
        Log.w("EtaQwen", "provider=qwen session-error=${code.name}")
        reply(s, QwenAsrProtocol.ERROR, message, code = code)
        main.post { close(s) }
    }
    private fun arm(s: Session, millis: Long, message: String) {
        s.timer?.let { main.removeCallbacks(it) }
        s.timer = Runnable { if (active === s && !s.cancelled.get()) fail(s, SpeechErrorCode.TIMEOUT, message) }
            .also { main.postDelayed(it, millis) }
    }
    private fun close(s: Session, finalText: String? = null) {
        if (!s.cancelled.compareAndSet(false, true)) return
        s.timer?.let { main.removeCallbacks(it) }; s.timer = null
        runCatching { s.input.close() }
        val remote = s.reply
        runCatching { remote?.binder?.unlinkToDeath(s.death, 0) }
        s.reply = null
        // Cannot interrupt JNI. Non-native verification/queueing cancellation does not kill the cache.
        val watchdog = Runnable { if (!s.closed.get() && s.native.get()) Process.killProcess(Process.myPid()) }
        main.postDelayed(watchdog, 2_000)
        worker.execute {
            try {
                s.recognizer = null; s.audio = null
                val now = SystemClock.elapsedRealtime()
                if (!cache.finish(s, now)) cache.finishUnborrowed(now)
            } finally {
                s.closed.set(true)
                main.post {
                    main.removeCallbacks(watchdog)
                    if (active === s) active = null // never admit B before A's worker finishes
                    if (!destroyed) scheduleIdle()
                    if (finalText != null && !destroyed) runCatching {
                        remote?.send(Message.obtain(null, QwenAsrProtocol.FINAL).apply { data = Bundle().apply {
                            putString(QwenAsrProtocol.ID, s.id); putString(QwenAsrProtocol.TEXT, finalText)
                        } })
                    }
                }
            }
        }
    }
    private fun scheduleIdle() {
        val retentionToken = retentionGeneration
        // One delayed callback, not polling. The worker's generation rejects stale expiry work.
        worker.execute {
            val token = cache.generation
            main.post {
                if (active == null && !destroyed) {
                    idleTimer?.let { main.removeCallbacks(it) }
                    idleTimer = Runnable {
                        worker.execute {
                            if (cache.expire(token, SystemClock.elapsedRealtime())) main.post {
                                if (active == null && !destroyed && retentionGeneration == retentionToken) stopSelf()
                            }
                        }
                    }.also { main.postDelayed(it, cache.idleMillis) }
                }
            }
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY
    override fun onBind(intent: Intent): IBinder = messenger.binder
    override fun onUnbind(intent: Intent): Boolean { active?.let { close(it) }; return false }
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            if (!destroyed) worker.execute { cache.trim() }
        }
    }
    override fun onLowMemory() { super.onLowMemory(); if (!destroyed) worker.execute { cache.trim() } }
    override fun onDestroy() {
        destroyed = true
        idleTimer?.let { main.removeCallbacks(it) }; idleTimer = null
        active?.let { close(it) }
        val watchdog = Runnable { Process.killProcess(Process.myPid()) }
        main.postDelayed(watchdog, 2_000)
        worker.execute {
            try { cache.evict() }
            finally { main.post { main.removeCallbacks(watchdog) } }
        }
        worker.shutdown() // queued session completion precedes cache release; never concurrent JNI
        super.onDestroy()
    }
}
