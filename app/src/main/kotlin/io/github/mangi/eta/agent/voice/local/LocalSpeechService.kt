// SPDX-License-Identifier: Apache-2.0
package io.github.mangi.eta.agent.voice.local

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
import io.github.mangi.eta.agent.voice.qwen.WarmCachePolicy
import io.github.mangi.eta.agent.voice.SpeechFailure
import io.github.mangi.eta.agent.voice.SpeechErrorCode
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Job
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** JNI, native construction, decode and release are reachable only in :local_speech. */
class LocalSpeechService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "EtaLocalSpeechNative") }
    private val releasing = AtomicBoolean()
    private val cache = WarmCachePolicy<LocalSpeechModelStore.ModelIdentity, LocalSpeechRecognizer>({ releaseNative(it) })
    private fun releaseNative(recognizer: LocalSpeechRecognizer) {
        releasing.set(true)
        val watchdog = Runnable { if (releasing.get()) Process.killProcess(Process.myPid()) }
        main.postDelayed(watchdog, 2_000)
        try { recognizer.release() } finally { releasing.set(false); main.removeCallbacks(watchdog) }
    }
    private val deletion = LocalSpeechDeletionGate() // main-thread admission; worker handles actual release/removal
    private var idleTimer: Runnable? = null
    private var retentionGeneration = 0L // main-thread START/stopSelf race guard
    @Volatile private var destroyed = false
    @Volatile private var active: Session? = null
    private class Session(val id: String, var reply: Messenger?, val input: ParcelFileDescriptor.AutoCloseInputStream, val modelId: String, silence: Int) {
        val cancelled = AtomicBoolean()
        val preparation = Job()
        var pendingRecognizer: LocalSpeechRecognizer? = null // worker-owned until cache takes ownership
        val closed = AtomicBoolean()
        val pending = AtomicBoolean()
        val finishing = AtomicBoolean()
        var audio: LocalSpeechPcmAccumulator? = LocalSpeechPcmAccumulator(silence)
        val native = AtomicBoolean()
        val utterance = LocalSpeechUtterance() // session-owned, worker-only
        var recognizer: LocalSpeechRecognizer? = null // only accessed on worker
        var sequence = 0
        @Volatile var endpoint = false
        lateinit var death: IBinder.DeathRecipient
        var timer: Runnable? = null
    }
    override fun onCreate() {
        super.onCreate()
        check(Application.getProcessName() == "$packageName:local_speech") { "本机离线JNI requires its own process" }
    }
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { msg ->
        if (msg.sendingUid != Process.myUid()) return@Handler true
        val id = msg.data.getString(LocalSpeechProtocol.ID) ?: return@Handler true
        when (msg.what) {
            LocalSpeechProtocol.DELETE -> deleteModel(msg, id)
            LocalSpeechProtocol.START -> start(msg, id)
            LocalSpeechProtocol.CANCEL -> active?.takeIf { it.id == id }?.let { close(it) }
            else -> active?.takeIf { it.id == id && !it.cancelled.get() }?.let { s ->
                when (msg.what) {
                    LocalSpeechProtocol.PCM -> {
                        val count = msg.arg2
                        if (s.finishing.get() || s.endpoint || count !in 2..LocalSpeechProtocol.FEED_CHUNK || count % 2 != 0 ||
                            msg.arg1 != s.sequence + 1 || !s.pending.compareAndSet(false, true)) {
                            fail(s, SpeechErrorCode.PROTOCOL, "本机离线音频传输顺序错误，请重试")
                        } else {
                            s.sequence = msg.arg1; val sequence = msg.arg1
                            worker.execute {
                                try {
                                    if (!s.cancelled.get()) {
                                        val audio = ByteArray(count); var offset = 0
                                        while (offset < count) {
                                            val n = s.input.read(audio, offset, count - offset)
                                            check(n > 0) { "本机离线音频管道提前关闭" }; offset += n
                                        }
                                        val endpoint = checkNotNull(s.audio).append(audio)
                                        s.native.set(true)
                                        try {
                                            s.utterance.stream?.accept(audio)?.let { reply(s, LocalSpeechProtocol.PARTIAL, it) }
                                        } finally { s.native.set(false) }
                                        if (endpoint) { s.endpoint = true; reply(s, LocalSpeechProtocol.ENDPOINT) }
                                        s.pending.set(false)
                                        reply(s, LocalSpeechProtocol.ACK, sequence = sequence)
                                    }
                                } catch (e: Exception) {
                                    cache.trim(); fail(s, SpeechErrorCode.AUDIO, "本机离线音频读取或流式解码失败，请重试；音频未上传")
                                } catch (e: LinkageError) {
                                    cache.trim(); fail(s, SpeechErrorCode.CONFIGURATION, "本机离线原生解码失败，请重新安装完整版")
                                } catch (e: OutOfMemoryError) {
                                    cache.trim(); fail(s, SpeechErrorCode.STORAGE, "本机离线解码内存不足，音频未上传")
                                }
                            }
                        }
                    }
                    LocalSpeechProtocol.FINISH -> {
                        if (s.pending.get() || !s.finishing.compareAndSet(false, true)) {
                            fail(s, SpeechErrorCode.PROTOCOL, "本机离线音频尚未接收完成")
                        } else {
                            arm(s, LocalSpeechProtocol.DECODE_MS, "本机离线本地解码超时，模型文件已保留")
                            worker.execute { decode(s) } // strictly after the last acknowledged PCM read
                        }
                    }
                }
            }
        }
        true
    })
    private fun deleteModel(message: Message, requestId: String) {
        val remote = message.replyTo ?: return
        val modelId = message.data.getString(LocalSpeechProtocol.MODEL).orEmpty()
        fun respond(what: Int, text: String, code: SpeechErrorCode? = null) {
            runCatching { remote.send(Message.obtain(null, what).apply { data = Bundle().apply {
                putString(LocalSpeechProtocol.ID, requestId)
                putString(LocalSpeechProtocol.MODEL, modelId)
                putString(LocalSpeechProtocol.TEXT, text)
                code?.let { putString(LocalSpeechProtocol.CODE, it.name) }
            } }) }
        }
        if (runCatching { LocalSpeechRegistry.model(this, modelId) }.isFailure) {
            respond(LocalSpeechProtocol.ERROR, "请选择目录中的本机模型", SpeechErrorCode.CONFIGURATION)
            return
        }
        val deadline = message.data.getLong(LocalSpeechProtocol.DELETE_DEADLINE)
        val now = SystemClock.elapsedRealtime()
        if (!localSpeechDeletionIsLive(deadline, now)) {
            respond(LocalSpeechProtocol.ERROR, "删除请求已过期，未删除模型文件，请刷新状态后重试", SpeechErrorCode.TIMEOUT)
            return
        }
        // Both handlers run on main: a queued START cannot slip between admission and eviction.
        // Reject even a closing/cancelled session until worker has completed stream/cache cleanup.
        if (destroyed || !deletion.begin(active != null)) {
            respond(LocalSpeechProtocol.ERROR, "本机离线正在录音、加载、识别或删除，请先停止并等待完成后再删除", SpeechErrorCode.BACKPRESSURE)
            return
        }
        retentionGeneration++
        idleTimer?.let { main.removeCallbacks(it) }; idleTimer = null
        worker.execute {
            try {
                fun checkDeadline() {
                    if (!localSpeechDeletionIsLive(deadline, SystemClock.elapsedRealtime())) throw SpeechFailure(SpeechErrorCode.TIMEOUT,
                        "删除请求已过期，未删除模型文件，请刷新状态后重试")
                }
                checkDeadline() // never execute a stale request after the client has timed out
                val result = runBlocking {
                    deleteIdleLocalSpeechModel(cache) {
                        checkDeadline() // native release must not extend permission to start removal
                        LocalSpeechModelStore.deleteInstalledModel(this@LocalSpeechService, modelId, ::checkDeadline)
                    }
                }
                // This is the only success path: release and descriptor-relative removal/readback finished.
                respond(LocalSpeechProtocol.DELETED, result.detail)
            } catch (e: SpeechFailure) {
                respond(LocalSpeechProtocol.ERROR, e.userMessage, e.code)
            } catch (_: Throwable) {
                respond(LocalSpeechProtocol.ERROR, "模型删除未确认，可能已部分删除；请刷新状态后重试", SpeechErrorCode.STORAGE)
            } finally {
                main.post {
                    deletion.finish()
                    if (!destroyed) { scheduleIdle(); stopSelf() }
                }
            }
        }
    }
    private fun start(message: Message, id: String) {
        val pfd = message.data.getParcelable(LocalSpeechProtocol.PIPE, ParcelFileDescriptor::class.java) ?: return
        val remote = message.replyTo
        if (remote == null) { pfd.close(); return }
        if (active != null || destroyed || deletion.inProgress) {
            pfd.close()
            runCatching { remote.send(Message.obtain(null, LocalSpeechProtocol.ERROR).apply { data = Bundle().apply {
                putString(LocalSpeechProtocol.ID, id); putString(LocalSpeechProtocol.CODE, SpeechErrorCode.BACKPRESSURE.name)
                putString(LocalSpeechProtocol.TEXT, "本机离线本地识别正在使用中，请稍后重试")
            } }) }
            return
        }
        val silence = message.data.getInt(LocalSpeechProtocol.SILENCE)
        if (silence != 0 && silence !in 100..30_000) { pfd.close(); return }
        val modelId = message.data.getString(LocalSpeechProtocol.MODEL).orEmpty()
        if (runCatching { LocalSpeechRegistry.model(this, modelId) }.isFailure) {
            pfd.close()
            runCatching { remote.send(Message.obtain(null, LocalSpeechProtocol.ERROR).apply { data = Bundle().apply {
                putString(LocalSpeechProtocol.ID, id); putString(LocalSpeechProtocol.CODE, SpeechErrorCode.CONFIGURATION.name)
                putString(LocalSpeechProtocol.TEXT, "请选择目录中的本机模型")
            } }) }
            return
        }
        val s = Session(id, remote, ParcelFileDescriptor.AutoCloseInputStream(pfd), modelId, silence)
        active = s
        retentionGeneration++
        idleTimer?.let { main.removeCallbacks(it) }; idleTimer = null
        // Only reached after an app-UID START caused by user speech; no FGS, alarm or wake lock.
        // Background start restrictions may reject this: bound-only recognition remains valid.
        runCatching { startService(Intent(this, LocalSpeechService::class.java)) }
            .onFailure { Log.i("EtaLocalSpeech", "provider=local model-cache=bound-only reason=background-start-restricted") }
        s.death = IBinder.DeathRecipient { main.post { close(s) } }
        try { remote.binder.linkToDeath(s.death, 0) } catch (_: Exception) { close(s); return }
        arm(s, LocalSpeechProtocol.PREPARE_MS, "本机离线模型校验或加载超过 90 秒，请检查设备内存后重试")
        worker.execute {
            try {
                if (s.cancelled.get()) return@execute
                val prepareStart = SystemClock.elapsedRealtime()
                val quick = runCatching { LocalSpeechModelStore.quickFingerprint(this, s.modelId) }.getOrNull()
                if (s.cancelled.get()) return@execute
                var hashMs = 0L; var loadMs = 0L
                val (recognizer, hit) = cache.borrow(s, quick) {
                    runBlocking(s.preparation) {
                        LocalSpeechModelStore.loadVerified(this@LocalSpeechService, s.modelId, { resource: LocalSpeechRecognizer ->
                            if (s.pendingRecognizer === resource) s.pendingRecognizer = null
                            releaseNative(resource)
                        },
                            onVerified = { hashMs = it }) { dir, model ->
                            s.native.set(true)
                            try {
                                check(!s.cancelled.get()) { "本机离线session cancelled" }
                                val loadStart = SystemClock.elapsedRealtime()
                                LocalSpeechRecognizer.create(dir, model)
                                    .also { s.pendingRecognizer = it; loadMs = SystemClock.elapsedRealtime() - loadStart }
                            } finally { s.native.set(false) }
                        }
                    }
                }
                s.pendingRecognizer = null // cache now owns it, including across cancellation
                s.recognizer = recognizer // borrowed, never session-owned
                s.native.set(true)
                try {
                    if (!s.cancelled.get()) s.utterance.stream = recognizer.openStream()
                } finally { s.native.set(false) }
                Log.i("EtaLocalSpeech", "provider=local model-cache=${if (hit) "hit" else "miss"} " +
                    "hash-ms=$hashMs cold-load-ms=$loadMs ready-ms=${SystemClock.elapsedRealtime() - prepareStart}")
                main.post {
                    if (!s.cancelled.get()) {
                        arm(s, LocalSpeechProtocol.CAPTURE_MS, "本机离线等待录音超时，单次录音最长 30 秒")
                        reply(s, LocalSpeechProtocol.READY)
                    }
                }
            } catch (e: SpeechFailure) { fail(s, e.code, e.userMessage) }
            catch (e: Exception) { fail(s, SpeechErrorCode.CONFIGURATION, "本机模型加载失败，请检查完整性与可用内存；不会切换云端") }
            catch (e: LinkageError) { fail(s, SpeechErrorCode.CONFIGURATION, "本机离线原生库不兼容，请安装 arm64 完整版") }
            catch (e: OutOfMemoryError) { fail(s, SpeechErrorCode.STORAGE, "本机离线内存不足，请关闭其他应用后重试") }
            finally {
                // runBlocking cancellation can discard its result before borrow assigns the cache.
                val unowned = s.pendingRecognizer; s.pendingRecognizer = null
                if (unowned != null) releaseNative(unowned)
            }
        }
    }
    private fun decode(s: Session) {
        if (s.cancelled.get()) return
        s.native.set(true)
        try {
            if (s.cancelled.get()) return
            val audio = checkNotNull(s.audio)
            val recognizer = checkNotNull(s.recognizer)
            val text = s.utterance.finish(audio) { recognizeOffline(s, audio, recognizer) }
            // Session decoder is released by close before FINAL; the model remains in the warm cache.
            main.post { close(s, text) }
        } catch (e: SpeechFailure) { fail(s, e.code, e.userMessage) }
        catch (e: Exception) { cache.trim(); fail(s, SpeechErrorCode.PROTOCOL, "本机模型解码失败，请缩短语音或检查模型后重试；音频未上传") }
        catch (e: LinkageError) { cache.trim(); fail(s, SpeechErrorCode.CONFIGURATION, "本机离线原生解码失败，请重新安装完整版") }
        catch (e: OutOfMemoryError) { cache.trim(); fail(s, SpeechErrorCode.STORAGE, "本机离线解码内存不足，音频未上传") }
        finally { s.native.set(false) }
    }
    private fun recognizeOffline(s: Session, audio: LocalSpeechPcmAccumulator, recognizer: LocalSpeechRecognizer): String {
        val parts = mutableListOf<String>()
        val count = audio.size / 2
        val model = LocalSpeechRegistry.model(this, s.modelId)
        // Qwen's native context is bounded to ten seconds. Hard boundaries can cut words.
        // SenseVoice processes the entire bounded utterance. Nothing is silently truncated.
        val segmentSamples = if (model.architecture == "qwen3_asr") 160_000 else 480_000
        for (start in 0 until count step segmentSamples) {
            if (s.cancelled.get()) return ""
            val text = recognizer.recognize(audio.samples(start, minOf(segmentSamples, count - start))).trim()
            check(text.toByteArray(Charsets.UTF_8).size <= LocalSpeechProtocol.MAX_TEXT_BYTES) { "识别文本过长，请缩短语音" }
            if (text.isNotEmpty()) parts += text
        }
        return parts.joinToString(" ").trim()
    }
    private fun reply(s: Session, what: Int, text: String = "", sequence: Int = 0, code: SpeechErrorCode? = null) {
        if (s.cancelled.get()) return
        try { s.reply?.send(Message.obtain(null, what).apply {
            arg1 = sequence; data = Bundle().apply {
                putString(LocalSpeechProtocol.ID, s.id); putString(LocalSpeechProtocol.TEXT, text)
                code?.let { putString(LocalSpeechProtocol.CODE, it.name) }
            }
        }) } catch (_: Exception) { main.post { close(s) } }
    }
    private fun fail(s: Session, code: SpeechErrorCode, message: String) {
        Log.w("EtaLocalSpeech", "provider=local session-error=${code.name}")
        reply(s, LocalSpeechProtocol.ERROR, message, code = code)
        main.post { close(s) }
    }
    private fun arm(s: Session, millis: Long, message: String) {
        s.timer?.let { main.removeCallbacks(it) }
        s.timer = Runnable { if (active === s && !s.cancelled.get()) fail(s, SpeechErrorCode.TIMEOUT, message) }
            .also { main.postDelayed(it, millis) }
    }
    private fun close(s: Session, finalText: String? = null) {
        if (!s.cancelled.compareAndSet(false, true)) return
        s.preparation.cancel() // stop lock waits and SHA work, not just native decode
        s.timer?.let { main.removeCallbacks(it) }; s.timer = null
        runCatching { s.input.close() }
        val remote = s.reply
        runCatching { remote?.binder?.unlinkToDeath(s.death, 0) }
        s.reply = null
        // Cannot interrupt JNI. Non-native verification/queueing cancellation does not kill the cache.
        val watchdog = Runnable { if (!s.closed.get() && (s.native.get() || releasing.get())) Process.killProcess(Process.myPid()) }
        main.postDelayed(watchdog, 2_000)
        worker.execute {
            try {
                // Always free the utterance before cache.finish may release its model.
                s.native.set(true)
                try {
                    s.utterance.closeBeforeCache(onCloseFailure = { cache.trim() }) {
                        s.recognizer = null; s.audio = null
                        val now = SystemClock.elapsedRealtime()
                        if (!cache.finish(s, now)) cache.finishUnborrowed(now)
                    }
                } finally { s.native.set(false) }
            } finally {
                s.closed.set(true)
                main.post {
                    main.removeCallbacks(watchdog)
                    if (active === s) active = null // never admit B before A's worker finishes
                    if (!destroyed) scheduleIdle()
                    if (finalText != null && !destroyed) runCatching {
                        remote?.send(Message.obtain(null, LocalSpeechProtocol.FINAL).apply { data = Bundle().apply {
                            putString(LocalSpeechProtocol.ID, s.id); putString(LocalSpeechProtocol.TEXT, finalText)
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
