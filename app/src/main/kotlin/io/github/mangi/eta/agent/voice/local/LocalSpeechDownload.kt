// SPDX-License-Identifier: Apache-2.0
package io.github.mangi.eta.agent.voice.local

import io.github.mangi.eta.agent.voice.SpeechErrorCode
import io.github.mangi.eta.agent.voice.SpeechFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI

/** User-initiated model fetch only. No runtime discovery, unpinned branches, or recognition uploads. */
internal object LocalSpeechDownload {
    suspend fun fetch(model: LocalSpeechModelDescriptor, stage: File, progress: (LocalSpeechProgress) -> Unit) {
        var completed = 0L
        progress(LocalSpeechProgress(LocalSpeechStage.DOWNLOADING, 0, model.sizeBytes))
        for (file in model.archive?.let { listOf(it) } ?: model.files) {
            currentCoroutineContext().ensureActive()
            var url = URI(file.pinnedUrl)
            var connection: HttpURLConnection? = null
            try {
                for (redirect in 0..8) {
                    currentCoroutineContext().ensureActive()
                    require(allowedUrl(model, url)) { "模型下载重定向不安全" }
                    val c = (url.toURL().openConnection() as HttpURLConnection).apply {
                        instanceFollowRedirects = false; connectTimeout = 15_000; readTimeout = 5_000
                        setRequestProperty("Accept-Encoding", "identity")
                    }
                    connection = c
                    val status = c.responseCode
                    if (status in setOf(301, 302, 303, 307, 308)) {
                        require(redirect < 8) { "模型下载重定向过多" }
                        val location = c.getHeaderField("Location") ?: error("模型下载缺少地址")
                        url = url.resolve(location); c.disconnect(); connection = null
                        continue
                    }
                    if (status != 200) throw SpeechFailure(SpeechErrorCode.NETWORK, "模型下载失败（HTTP $status），请检查网络后重试")
                    val length = c.contentLengthLong
                    require(length == -1L || length == file.sizeBytes) { "下载文件大小与固定版本不匹配" }
                    val base = completed
                    c.inputStream.use { input -> copyExact(input, File(stage, file.name), file.sizeBytes) { n ->
                        progress(LocalSpeechProgress(LocalSpeechStage.DOWNLOADING, base + n, model.sizeBytes))
                    } }
                    completed += file.sizeBytes
                    if (model.engine == LocalSpeechBackend.VOSK) {
                        val archive = File(stage, file.name)
                        try { LocalSpeechVoskArchive.unpack(model, stage, archive, progress) }
                        finally { check(archive.delete()) { "无法清理临时模型压缩包" } }
                    }
                    break
                }
            } catch (e: CancellationException) { throw e }
            catch (e: SpeechFailure) { throw e }
            catch (e: IllegalArgumentException) { throw e }
            catch (_: java.net.SocketTimeoutException) { throw SpeechFailure(SpeechErrorCode.TIMEOUT, "模型下载超时，请检查网络后重试；已有模型保留") }
            catch (_: java.io.IOException) { throw SpeechFailure(SpeechErrorCode.NETWORK, "模型下载中断，请检查网络和存储空间后重试；已有模型保留") }
            finally { connection?.disconnect() }
        }
    }
    internal fun allowedUrl(model: LocalSpeechModelDescriptor, url: URI): Boolean {
        if (model.engine == LocalSpeechBackend.VOSK) {
            // No arbitrary official-host files, query strings, credentials, alternate ports, or CDN redirects.
            return url.toASCIIString() == LocalSpeechRegistry.voskUrl(model.id)
        }
        val host = url.host.orEmpty()
        return url.scheme == "https" && url.userInfo == null && (url.port == -1 || url.port == 443) &&
            (host == "huggingface.co" || host.endsWith(".huggingface.co") || host.endsWith(".hf.co"))
    }
    internal suspend fun copyExact(input: InputStream, output: File, expected: Long, expectedSha256: String? = null, progress: (Long) -> Unit) {
        require(!output.exists()) { "临时文件重复" }
        val coroutine = currentCoroutineContext(); val buffer = ByteArray(64 * 1024); var bytes = 0L
        val digest = expectedSha256?.let { java.security.MessageDigest.getInstance("SHA-256") }
        val stream = try { output.outputStream() } catch (_: java.io.IOException) {
            throw SpeechFailure(SpeechErrorCode.STORAGE, "无法创建模型临时文件，请检查存储空间和权限")
        }
        stream.use { sink ->
            while (true) {
                coroutine.ensureActive()
                val n = input.read(buffer, 0, minOf(buffer.size.toLong(), expected - bytes + 1L).toInt())
                if (n == -1) break
                require(n > 0 && n.toLong() <= expected - bytes) { "文件超出目录大小，已拒绝导入" }
                try { sink.write(buffer, 0, n) } catch (_: java.io.IOException) {
                    throw SpeechFailure(SpeechErrorCode.STORAGE, "模型写入失败，请释放存储空间后重试；已有文件保留")
                }
                digest?.update(buffer, 0, n)
                bytes += n; progress(bytes)
            }
            require(bytes == expected) { "文件不完整或不属于所选模型" }
            if (digest != null) require(digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == expectedSha256) {
                "模型写入流 SHA-256 不匹配"
            }
            try { sink.fd.sync() } catch (_: java.io.IOException) {
                throw SpeechFailure(SpeechErrorCode.STORAGE, "模型文件同步失败，请检查存储空间后重试")
            }
        }
    }
}
