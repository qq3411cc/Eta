// SPDX-License-Identifier: Apache-2.0
package io.github.mangi.eta.agent.voice.qwen

import android.content.Context
import android.system.Os
import io.github.mangi.eta.agent.voice.SpeechErrorCode
import io.github.mangi.eta.agent.voice.SpeechFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/** Exactly one pinned Qwen3-ASR ONNX bundle, never a generic model loader. */
@OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)
object QwenModelStore {
    enum class State { MISSING, INSTALLED, INVALID }
    data class Status(val installed: Boolean, val detail: String, val sizeBytes: Long, val state: State)
    data class Progress(val bytesCompleted: Long, val totalBytes: Long, val stage: String)
    private data class Entry(val path: String, val size: Long, val sha256: String)
    private data class Manifest(val files: List<Entry>, val url: String, val size: Long, val hash: String) {
        val unpacked = files.sumOf { it.size }
    }
    private val mutex = Mutex()
    private const val MARGIN = 256L * 1024 * 1024
    private const val MAX_ARCHIVE = 1_200_000_000L
    internal const val FORMAT_ERROR = "原始 Safetensors 需对应 runtime 转换，当前只支持 sherpa Qwen ONNX 包（固定六文件）"
    internal fun directory(context: Context) = File(context.noBackupFilesDir, "qwen-asr/model")
    private fun root(context: Context) = directory(context).parentFile!!.apply { mkdirs() }
    private fun manifest(context: Context): Manifest {
        val json = context.assets.open("qwen_asr/manifest.json").bufferedReader().use { JSONObject(it.readText()) }
        val a = json.getJSONObject("archive")
        val files = json.getJSONArray("files")
        return Manifest((0 until files.length()).map {
            val f = files.getJSONObject(it); Entry(f.getString("path"), f.getLong("size"), f.getString("sha256"))
        }, a.getString("url"), a.getLong("size"), a.getString("sha256"))
    }
    private suspend fun <T> locked(context: Context, block: suspend () -> T): T = mutex.withLock {
        RandomAccessFile(File(root(context), ".lock"), "rw").use { file ->
            var lock: java.nio.channels.FileLock? = null
            while (lock == null) {
                currentCoroutineContext().ensureActive()
                lock = try { file.channel.tryLock() } catch (_: java.nio.channels.OverlappingFileLockException) { null }
                if (lock == null) delay(50)
            }
            try {
                // Recover the preserved directory if a previous process died between the two renames.
                val target = directory(context)
                if (!target.exists()) {
                    val previous = root(context).listFiles().orEmpty().filter { it.isDirectory && it.name.startsWith(".previous-") }
                        .maxByOrNull { it.lastModified() }
                    if (previous != null) check(previous.renameTo(target)) { "旧模型恢复失败，原文件仍已保留" }
                }
                block()
            } finally { lock.release() }
        }
    }
    /** Metadata identity is not an integrity proof; only reuse a previously verified loaded instance. */
    internal data class FileIdentity(val path: String, val size: Long, val mtime: Long,
        val device: Long, val inode: Long, val ctime: Long)
    internal data class ModelIdentity(val manifest: String, val files: List<FileIdentity>)
    internal fun quickFingerprint(context: Context): ModelIdentity {
        val manifestText = context.assets.open("qwen_asr/manifest.json").bufferedReader().use { it.readText() }
        val m = manifest(context)
        val dir = directory(context)
        fun identity(file: File, expected: String? = null): FileIdentity {
            val canonical = file.canonicalPath
            check(expected == null || canonical == expected) { "Qwen model path changed" }
            val stat = Os.stat(canonical)
            return FileIdentity(canonical, stat.st_size, file.lastModified(), stat.st_dev, stat.st_ino, stat.st_ctime)
        }
        return ModelIdentity(manifestText, listOf(identity(dir)) + m.files.map { identity(File(dir, it.path), File(dir.canonicalFile, it.path).absolutePath) })
    }
    private suspend fun inspectLocked(context: Context): Status {
        val dir = directory(context)
        if (!dir.exists()) return Status(false, "未安装 Qwen3-ASR-0.6B ONNX 模型", 0, State.MISSING)
        val m = manifest(context)
        val valid = verify(dir, m)
        return Status(valid, if (valid) "Qwen3-ASR-0.6B int8 · 六文件 SHA-256 校验通过 · 最长 30 秒" else
            "模型不完整或校验失败；原文件保留，请重新导入或下载", m.files.sumOf { File(dir, it.path).length() },
            if (valid) State.INSTALLED else State.INVALID)
    }
    /** Full six-file SHA256 on IO, not a size-only readiness claim. */
    suspend fun inspect(context: Context): Status = withContext(Dispatchers.IO) {
        locked(context) { inspectLocked(context) }
    }
    /** Keep install's cross-process lock through verification AND native load (same worker, no JNI on IO). */
    internal suspend fun <T : Any> loadVerified(context: Context, release: (T) -> Unit,
        onVerified: (Long) -> Unit = {}, load: () -> T): Pair<ModelIdentity, T> = locked(context) {
        val before = quickFingerprint(context)
        val verifyStart = System.nanoTime()
        val status = inspectLocked(context)
        onVerified((System.nanoTime() - verifyStart) / 1_000_000)
        check(status.installed) { status.detail }
        check(before == quickFingerprint(context)) { "Qwen model changed during verification" }
        val value = load()
        try {
            check(before == quickFingerprint(context)) { "Qwen model changed during loading" }
            before to value
        } catch (error: Throwable) { release(value); throw error }
    }
    suspend fun download(context: Context, onProgress: (Progress) -> Unit = {}) = withContext(Dispatchers.IO) {
        locked(context) {
            val m = manifest(context)
            install(context, m, onProgress) { archive ->
                checkSpace(context, m.size + m.unpacked + MARGIN)
                val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
                    .connectTimeout(30, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
                val job = currentCoroutineContext()[Job]!!
                var url = m.url
                var done = false
                try {
                    for (redirect in 0..8) {
                        val host = java.net.URI(url).host?.lowercase().orEmpty()
                        require(java.net.URI(url).scheme == "https" && allowedHost(host)) { "模型下载重定向到不受信任地址" }
                        val call = client.newCall(Request.Builder().url(url).build())
                        val handle = job.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { call.cancel() }
                        try {
                            val response = try { call.execute() } catch (e: java.io.IOException) {
                                currentCoroutineContext().ensureActive(); throw e
                            }
                            response.use { response ->
                                if (response.code in listOf(301, 302, 303, 307, 308)) {
                                    val location = response.header("Location") ?: error("模型下载重定向缺少地址")
                                    url = java.net.URI(url).resolve(location).toString()
                                } else {
                                    check(response.isSuccessful) { "模型下载失败：HTTP ${response.code}" }
                                    val body = response.body
                                    if (body.contentLength() >= 0) check(body.contentLength() == m.size) { "模型下载长度不符" }
                                    body.byteStream().use { input -> copyArchive(input, archive, m.size, onProgress, "下载") }
                                    done = true
                                }
                            }
                        } finally { handle.dispose() }
                        if (done) break
                    }
                    check(done) { "模型下载重定向次数过多" }
                    check(archive.length() == m.size && sha256(archive) == m.hash) { "官方模型压缩包 SHA-256 校验失败" }
                } finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
            }
        }
    }
    suspend fun importModel(context: Context, open: () -> InputStream, onProgress: (Progress) -> Unit = {}) =
        withContext(Dispatchers.IO) {
            locked(context) {
                val m = manifest(context)
                install(context, m, onProgress) { archive ->
                    checkSpace(context, MAX_ARCHIVE + m.unpacked + MARGIN)
                    val input = open()
                    val handle = currentCoroutineContext()[Job]!!.invokeOnCompletion(onCancelling = true, invokeImmediately = true) {
                        runCatching { input.close() }
                    }
                    try { input.use { copyArchive(it, archive, MAX_ARCHIVE, onProgress, "导入（压缩包总量未知）", false) } }
                    finally { handle.dispose() }
                }
            }
        }
    private fun allowedHost(host: String) = host == "github.com" || host == "release-assets.githubusercontent.com" ||
        host == "objects.githubusercontent.com" || host == "github-releases.githubusercontent.com"
    private fun checkSpace(context: Context, bytes: Long) {
        if (root(context).usableSpace < bytes) throw SpeechFailure(SpeechErrorCode.STORAGE, "模型安装空间不足，请至少预留 2.5 GB 可用空间")
    }
    private suspend fun install(context: Context, m: Manifest, progress: (Progress) -> Unit, acquire: suspend (File) -> Unit) {
        val base = root(context)
        val stage = File(base, ".stage-${UUID.randomUUID()}").apply { check(mkdir()) }
        val archive = File(stage, "bundle")
        val model = File(stage, "model").apply { check(mkdir()) }
        try {
            // A failed/cancelled replacement never deletes the currently installed (even invalid) directory.
            acquire(archive)
            extract(archive, model, m, progress)
            check(verify(model, m, progress)) { "模型文件 SHA-256 校验失败；请使用指定的 Qwen ONNX 模型包" }
            currentCoroutineContext().ensureActive()
            val target = directory(context)
            val previous = File(base, ".previous-${UUID.randomUUID()}")
            if (target.exists()) check(target.renameTo(previous)) { "无法保留旧模型，安装已停止" }
            if (!model.renameTo(target)) {
                if (previous.exists()) check(previous.renameTo(target)) { "模型回滚失败，旧模型仍保存在 ${previous.name}" }
                error("模型原子安装失败")
            }
            // No suspension/cancellation between backup and atomic same-filesystem rename.
            previous.deleteRecursively()
            progress(Progress(m.unpacked, m.unpacked, "安装完成"))
        } finally { stage.deleteRecursively() }
    }
    private suspend fun copyArchive(input: InputStream, target: File, cap: Long, progress: (Progress) -> Unit, stage: String, known: Boolean = true) {
        target.outputStream().use { out ->
            val buffer = ByteArray(128 * 1024); var total = 0L; var reported = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = try { input.read(buffer) } catch (e: java.io.IOException) {
                    currentCoroutineContext().ensureActive(); throw e
                }; if (n < 0) break
                total += n; require(total <= cap) { "模型压缩包过大或不是受支持的 Qwen 模型包" }
                out.write(buffer, 0, n)
                if (total - reported >= 1024 * 1024) { progress(Progress(total, if (known) cap else 0, stage)); reported = total }
            }
            out.fd.sync()
            progress(Progress(total, if (known) cap else 0, stage))
        }
    }
    private suspend fun extract(archive: File, dest: File, m: Manifest, progress: (Progress) -> Unit) {
        archive.inputStream().buffered().use { raw ->
            raw.mark(4); val signature = ByteArray(3); val n = raw.read(signature); raw.reset()
            val seen = mutableSetOf<String>(); val seenPaths = mutableSetOf<String>(); var bytes = 0L; var entries = 0; var ignored = 0L
            suspend fun consume(name: String, size: Long, directory: Boolean, ordinary: Boolean, input: InputStream) {
                require(++entries <= 128) { "模型包文件条目过多" }
                require(!name.startsWith('/') && '\\' !in name && ':' !in name && name.split('/').none { it == ".." || it == "." }) { "模型包包含不安全路径" }
                require(ordinary || directory) { "模型包不允许链接或特殊文件" }
                val clean = name.trimEnd('/')
                require(seenPaths.add(clean)) { "模型包包含重复路径：$clean" }
                require(clean.isNotBlank() && clean.split('/').all { it.isNotEmpty() }) { "模型包路径无效" }
                if (directory) { require(clean.split('/').size <= 4) { "模型包目录层级过深" }; return }
                require(!clean.endsWith(".safetensors", true)) { FORMAT_ERROR }
                val entry = m.files.firstOrNull { clean == it.path || clean.endsWith("/${it.path}") &&
                    clean.removeSuffix("/${it.path}").split('/').size <= 2 }
                if (entry == null) {
                    // Official release includes README and public WAV examples: bounded, never installed.
                    val allowed = clean.substringAfterLast('/') in setOf("README.md", "LICENSE", "NOTICE") ||
                        clean.substringAfterLast('/') == "transcript.txt" && clean.split('/').contains("test_wavs") ||
                        clean.substringAfterLast('/').endsWith(".wav", true) && clean.split('/').contains("test_wavs")
                    require(allowed) { FORMAT_ERROR }
                    val b = ByteArray(32 * 1024)
                    while (true) { currentCoroutineContext().ensureActive(); val count = input.read(b); if (count < 0) break
                        ignored += count; require(ignored <= 32L * 1024 * 1024) { "模型附加文件过大" } }
                    return
                }
                require(seen.add(entry.path)) { "模型包包含重复文件：${entry.path}" }
                require(size < 0 || size == entry.size) { "模型文件长度不匹配：${entry.path}" }
                val file = File(dest, entry.path); file.parentFile!!.mkdirs()
                file.outputStream().use { out ->
                    val b = ByteArray(128 * 1024); var count = 0L; var reported = bytes
                    while (true) { currentCoroutineContext().ensureActive(); val read = input.read(b); if (read < 0) break
                        count += read; require(count <= entry.size) { "模型文件解压超限：${entry.path}" }
                        out.write(b, 0, read); bytes += read
                        if (bytes - reported >= 1024 * 1024) { progress(Progress(bytes, m.unpacked, "解压")); reported = bytes }
                    }
                    require(count == entry.size) { "模型文件不完整：${entry.path}" }; out.fd.sync()
                }
            }
            if (n == 3 && signature[0] == 'P'.code.toByte() && signature[1] == 'K'.code.toByte()) {
                ZipInputStream(raw).use { zip ->
                    while (true) { val e = zip.nextEntry ?: break; consume(e.name, e.size, e.isDirectory, true, zip); zip.closeEntry() }
                }
            } else if (n == 3 && signature.contentEquals(byteArrayOf(66, 90, 104))) {
                TarArchiveInputStream(BZip2CompressorInputStream(raw)).use { tar ->
                    while (true) { val e = tar.nextEntry ?: break
                        require(tar.canReadEntryData(e)) { "不支持的 TAR 条目" }
                        consume(e.name, e.size, e.isDirectory, e.isFile && !e.isSymbolicLink && !e.isLink && !e.isSparse, tar)
                    }
                }
            } else error(FORMAT_ERROR)
            progress(Progress(bytes, m.unpacked, "解压"))
            require(seen.size == m.files.size) { "模型包缺少指定六文件；$FORMAT_ERROR" }
        }
    }
    private suspend fun sha256(file: File, tick: (Long) -> Unit = {}): String {
        val digest = MessageDigest.getInstance("SHA-256"); val b = ByteArray(128 * 1024); var done = 0L
        file.inputStream().use { input -> while (true) { currentCoroutineContext().ensureActive(); val n = input.read(b); if (n < 0) break
            digest.update(b, 0, n); done += n; tick(done) } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private suspend fun verify(dir: File, m: Manifest, progress: (Progress) -> Unit = {}): Boolean {
        var done = 0L; var reported = -1024L * 1024
        for (e in m.files) {
            currentCoroutineContext().ensureActive()
            val file = File(dir, e.path)
            if (!file.isFile || file.length() != e.size || file.canonicalPath != File(dir.canonicalFile, e.path).absolutePath) return false
            if (sha256(file) { n -> if (done + n - reported >= 1024 * 1024) {
                progress(Progress(done + n, m.unpacked, "校验 SHA-256")); reported = done + n
            } } != e.sha256) return false
            done += e.size
        }
        progress(Progress(done, m.unpacked, "校验 SHA-256"))
        return true
    }
}
