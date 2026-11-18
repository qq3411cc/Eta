// SPDX-License-Identifier: Apache-2.0
package io.github.mangi.eta.agent.voice.offline

import android.content.Context
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/** Explicit user-triggered transfer only. Not referenced by recognition or service preparation. */
internal object OfflineAsrModelManager {
    private const val EXTRA_ZIP_BYTES = 2L * 1024 * 1024
    private val client by lazy {
        OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .connectTimeout(30, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.MINUTES).build()
    }
    // Normal HF LFS/Xet redirects only. Never honor a caller URL or HTTP downgrade.
    private fun allowed(url: okhttp3.HttpUrl): Boolean {
        val host = url.host
        return url.scheme == "https" && url.port == 443 && url.username.isEmpty() && url.password.isEmpty() &&
            (host == "huggingface.co" || host == "cdn-lfs.huggingface.co" ||
                host.endsWith(".hf.co") || host == "hf.co" || host.endsWith(".xethub.hf.co"))
    }
    private fun source(context: Context, id: String): Pair<String, String> {
        val json = context.assets.open("offline_asr/sources.json").use { JSONObject(it.bufferedReader().readText()) }
        val array = json.getJSONArray("models")
        val source = (0 until array.length()).map { array.getJSONObject(it) }.single { it.getString("id") == id }
        val repo = source.getString("repository")
        val revision = source.getString("revision")
        require(repo.matches(Regex("csukuangfj/sherpa-ncnn-streaming-zipformer-(small-)?bilingual-zh-en-2023-02-(13|16)")))
        require(revision.matches(Regex("[0-9a-f]{40}")))
        return repo to revision
    }
    suspend fun download(context: Context, id: String,
        onProgress: (OfflineAsrModelStore.Progress) -> Unit): OfflineAsrModelStore.ModelStatus =
        install(context, id, onProgress) { stage, manifest, progress, check ->
            val (repo, revision) = source(context, id)
            var done = 0L
            for (entry in manifest.entries) {
                fetch("https://huggingface.co/$repo/resolve/$revision/${entry.name}", entry.size) { input ->
                    copy(input, File(stage, entry.name), entry.size, check) { n -> progress(done + n) }
                }
                done += entry.size
            }
        }

    suspend fun importModel(context: Context, id: String, open: () -> InputStream,
        onProgress: (OfflineAsrModelStore.Progress) -> Unit): OfflineAsrModelStore.ModelStatus =
        install(context, id, onProgress) { stage, manifest, progress, check ->
            val raw = open()
            cancellableClose(raw) {
                ZipInputStream(raw.buffered()).use { zip ->
                    val expected = manifest.entries.associateBy { it.name }
                    val seen = HashSet<String>()
                    val paths = HashSet<String>()
                    val metadata = setOf("README.md", "LICENSE", "NOTICE.md", "manifest.json")
                    var parent: String? = null
                    var bytes = 0L
                    var modelBytes = 0L
                    var count = 0
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        check()
                        val entry = zip.nextEntry ?: break
                        require(++count <= 64) { "Too many ZIP entries" }
                        val name = entry.name
                        require(name.length <= 512 && !name.startsWith('/') && '\\' !in name && ':' !in name && '\u0000' !in name) { "Unsafe ZIP path" }
                        val components = name.removeSuffix("/").split('/')
                        require(components.all { it.isNotEmpty() && it != "." && it != ".." }) { "ZIP traversal rejected" }
                        require(paths.add(name)) { "Duplicate ZIP path" }
                        if (entry.isDirectory) {
                            require(components.size <= 2 && entry.size <= 0) { "Invalid ZIP directory" }
                            require(zip.read() == -1) { "ZIP directory contains payload" }
                            zip.closeEntry()
                            continue
                        }
                        require(components.size <= 3) { "At most two wrapper directories supported" }
                        val basename = components.last()
                        require(seen.add(basename)) { "Duplicate ZIP filename" }
                        val spec = expected[basename]
                        require(spec != null || basename in metadata) { "Unsupported model ZIP entry: $basename; only catalog NCNN packs supported" }
                        val folder = components.dropLast(1).joinToString("/")
                        if (spec != null) {
                            if (parent == null) parent = folder
                            require(parent == folder) { "Model files must share one directory" }
                        }
                        val limit = spec?.size ?: EXTRA_ZIP_BYTES
                        require(entry.size < 0 || entry.size <= limit) { "ZIP entry size exceeds manifest" }
                        var written = 0L
                        val output = if (spec != null) FileOutputStream(File(stage, basename)) else null
                        try {
                            while (true) {
                                check()
                                val n = zip.read(buffer)
                                if (n < 0) break
                                written += n
                                bytes += n
                                require(written <= limit && bytes <= manifest.entries.sumOf { it.size } + EXTRA_ZIP_BYTES) { "ZIP expanded-size limit exceeded" }
                                output?.write(buffer, 0, n)
                                if (spec != null) { modelBytes += n; progress(modelBytes) }
                            }
                            if (spec != null) require(written == spec.size) { "Incomplete model ZIP file: $basename" }
                            output?.fd?.sync()
                        } finally { output?.close() }
                        zip.closeEntry()
                    }
                    require(seen.containsAll(expected.keys)) { "ZIP missing required model files" }
                }
            }
        }

    private suspend fun install(context: Context, id: String,
        onProgress: (OfflineAsrModelStore.Progress) -> Unit,
        transfer: suspend (File, OfflineAsrModelStore.ModelManifest, (Long) -> Unit, () -> Unit) -> Unit,
    ): OfflineAsrModelStore.ModelStatus = withContext(Dispatchers.IO) {
        val descriptor = OfflineAsrModelStore.descriptor(context, id)
        val manifest = OfflineAsrModelStore.manifest(context, descriptor)
        val root = OfflineAsrModelStore.root(context)
        val coroutine = currentCoroutineContext()
        val check = { coroutine.ensureActive() }
        // Acquire cross-process lock without making locked() suspend, then retain throughout transfer.
        val lockPath = File(root, ".deployment.lock")
        check(lockPath.canonicalPath == lockPath.absolutePath) { "Unsafe deployment lock" }
        java.io.RandomAccessFile(lockPath, "rw").use { lockFile ->
            var lock: java.nio.channels.FileLock? = null
            while (lock == null) {
                check()
                lock = try { lockFile.channel.tryLock() } catch (_: java.nio.channels.OverlappingFileLockException) { null }
                if (lock == null) kotlinx.coroutines.delay(25)
            }
            lock.use {
                val target = OfflineAsrModelStore.target(root, id, manifest)
                if (target.exists()) {
                    OfflineAsrModelStore.verifyTarget(target, manifest, check)
                    return@withContext OfflineAsrModelStore.ModelStatus(descriptor, OfflineAsrModelStore.State.INSTALLED, "Already installed; preserved")
                }
                // One payload write, no archive copy or second full payload. Headroom is checked once.
                check(root.usableSpace >= descriptor.totalBytes + EXTRA_ZIP_BYTES + 1024 * 1024) { "Insufficient storage for model plus 3 MiB safety reserve" }
                val stage = File(root, ".stage-${UUID.randomUUID()}")
                check(stage.mkdir()) { "Cannot create model staging directory" }
                try {
                    var lastBytes = -1L
                    var lastTime = 0L
                    val progress: (Long) -> Unit = { bytes ->
                        check()
                        val now = System.nanoTime()
                        if (bytes == 0L || bytes == descriptor.totalBytes || now - lastTime >= 100_000_000L) {
                            require(bytes in 0..descriptor.totalBytes && bytes >= lastBytes)
                            onProgress(OfflineAsrModelStore.Progress(id, bytes, descriptor.totalBytes))
                            lastBytes = bytes; lastTime = now
                        }
                    }
                    progress(0)
                    transfer(stage, manifest, progress, check)
                    OfflineAsrModelStore.validate(stage, manifest, check)
                    FileOutputStream(File(stage, ".complete")).use {
                        it.write(OfflineAsrModelStore.digest(manifest.raw).toByteArray()); it.fd.sync()
                    }
                    check()
                    check(!target.exists() && stage.renameTo(target)) { "Atomic model installation failed; existing models preserved" }
                    OfflineAsrModelStore.ModelStatus(descriptor, OfflineAsrModelStore.State.INSTALLED, "Installed; SHA256, size and format verified")
                } catch (error: Exception) {
                    // A cancelled blocking HTTP/SAF read commonly throws IOException. Preserve
                    // structured cancellation instead of leaking that failure to the UI parent.
                    check()
                    throw error
                } finally {
                    // Only our UUID staging path; never prune installed/user directories or other stages.
                    if (stage.exists()) stage.deleteRecursively()
                }
            }
        }
    }

    private fun copy(input: InputStream, file: File, limit: Long, check: () -> Unit, progress: (Long) -> Unit) {
        FileOutputStream(file).use { output ->
            val buffer = ByteArray(32 * 1024)
            var total = 0L
            while (true) {
                check()
                val n = input.read(buffer)
                if (n < 0) break
                total += n
                require(total <= limit) { "Download exceeds manifest size" }
                output.write(buffer, 0, n)
                progress(total)
            }
            require(total == limit) { "Download does not match manifest size" }
            output.fd.sync()
        }
    }

    private suspend fun fetch(url: String, size: Long, use: (InputStream) -> Unit) = coroutineScope {
        var request = Request.Builder().url(url).header("Accept-Encoding", "identity").build()
        repeat(9) { redirects ->
            currentCoroutineContext().ensureActive()
            require(allowed(request.url)) { "Non-Hugging-Face HTTPS URL rejected" }
            val call = client.newCall(request)
            val cancellation = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { call.cancel() }
            }
            try {
                call.execute().use { response ->
                    if (response.code in setOf(301, 302, 303, 307, 308)) {
                        require(redirects < 8) { "Too many model redirects" }
                        val next = response.header("Location")?.let { request.url.resolve(it) } ?: error("Missing redirect location")
                        require(allowed(next)) { "Unsafe model redirect rejected" }
                        request = Request.Builder().url(next).header("Accept-Encoding", "identity").build()
                    } else {
                        check(response.isSuccessful && response.code == 200) { "Model server HTTP ${response.code}" }
                        val body = checkNotNull(response.body) { "Missing model response body" }
                        require(body.contentLength() < 0 || body.contentLength() == size) { "Model HTTP length differs from manifest" }
                        body.byteStream().use(use)
                        return@coroutineScope
                    }
                }
            } finally { cancellation.cancel() }
        }
        error("Model redirect budget exhausted")
    }

    private suspend fun <T> cancellableClose(input: InputStream, block: () -> T): T = coroutineScope {
        val cancellation = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { runCatching { input.close() } }
        }
        try { input.use { block() } } finally { cancellation.cancel() }
    }
}
