// SPDX-License-Identifier: Apache-2.0
package io.github.mangi.eta.agent.voice.local

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/** Only the two catalog-pinned original ZIPs. Never resolve an archive-controlled output path. */
internal object LocalSpeechVoskArchive {
    const val MAX_ENTRIES = 256
    const val MAX_UNPACKED_BYTES = 4_000_000_000L

    suspend fun importArchive(model: LocalSpeechModelDescriptor, stage: File, source: InputStream,
        progress: (LocalSpeechProgress) -> Unit) {
        val archive = requireNotNull(model.archive)
        val file = File(stage, archive.name)
        require(!file.exists()) { "临时文件重复；已有文件保留" }
        try {
            LocalSpeechDownload.copyExact(source, file, archive.sizeBytes) { bytes ->
                progress(LocalSpeechProgress(LocalSpeechStage.IMPORTING, bytes, archive.sizeBytes))
            }
            unpack(model, stage, file, progress)
        } finally { if (file.exists()) check(file.delete()) { "无法清理临时模型压缩包" } }
    }

    suspend fun unpack(model: LocalSpeechModelDescriptor, stage: File, archiveFile: File,
        progress: (LocalSpeechProgress) -> Unit) {
        val archive = requireNotNull(model.archive)
        require(model.engine == LocalSpeechBackend.VOSK && model.files.size in 1..128)
        require(model.installedSizeBytes in 1..MAX_UNPACKED_BYTES)
        require(archiveFile.length() == archive.sizeBytes) { "Vosk 官方 ZIP 大小不匹配" }
        val coroutine = currentCoroutineContext()
        require(LocalSpeechValidation.sha256(archiveFile, { coroutine.ensureActive() }) { bytes ->
            progress(LocalSpeechProgress(LocalSpeechStage.VERIFYING, bytes, archive.sizeBytes))
        } == archive.sha256) { "Vosk ZIP SHA-256 不匹配；仅支持所选模型的官方原始 ZIP" }
        archiveFile.inputStream().buffered().use { extractVerified(model, stage, it, progress) }
    }

    /** Internal for bounded malicious-archive tests; production always verifies ZIP SHA before this. */
    internal suspend fun extractVerified(model: LocalSpeechModelDescriptor, stage: File, source: InputStream,
        progress: (LocalSpeechProgress) -> Unit) {
        val root = LocalSpeechRegistry.voskRoot(model.id)
        val expected = model.files.associateBy { entry ->
            require(LocalSpeechRegistry.safeVoskPath(entry.name)) { "Vosk 目录路径不安全" }
            require(entry.sizeBytes in 0..MAX_UNPACKED_BYTES)
            "$root/${entry.name.removePrefix("vosk/")}"
        }
        require(expected.size == model.files.size && expected.size in 1..128)
        require(model.installedSizeBytes in 1..MAX_UNPACKED_BYTES)
        val directories = expected.keys.flatMap { name ->
            name.split('/').dropLast(1).runningReduce { a, b -> "$a/$b" }
        }.toSet()
        val seen = HashSet<String>(); val files = HashSet<String>()
        var total = 0L; var count = 0
        require(stage.isDirectory && stage.canonicalPath == stage.absolutePath)
        ZipInputStream(source).use { zip ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val entry = zip.nextEntry ?: break
                require(++count <= MAX_ENTRIES) { "Vosk ZIP 条目过多" }
                val path = entry.name.removeSuffix("/")
                require(path.length in 1..240 && path.split('/').all {
                    it != "." && it != ".." && it.matches(Regex("[A-Za-z0-9_.-]{1,128}"))
                }) { "Vosk ZIP 路径不安全" }
                require(seen.add(path)) { "Vosk ZIP 条目重复" }
                if (entry.isDirectory) {
                    require(path in directories && zip.read() == -1) { "Vosk ZIP 目录不受支持或包含数据" }
                } else {
                    val file = expected[path] ?: throw IllegalArgumentException("Vosk ZIP 含非目录指定文件")
                    require(files.add(path) && (entry.size == -1L || entry.size == file.sizeBytes)) { "Vosk ZIP 文件大小不匹配" }
                    require(file.sizeBytes <= MAX_UNPACKED_BYTES - total) { "Vosk ZIP 解压大小超限" }
                    val out = File(stage, file.name)
                    require(out.canonicalPath == out.absolutePath && out.absolutePath.startsWith(stage.absolutePath + "/vosk/"))
                    check(out.parentFile!!.isDirectory || out.parentFile!!.mkdirs()) { "无法创建 Vosk 模型目录" }
                    val base = total
                    LocalSpeechDownload.copyExact(zip, out, file.sizeBytes, expectedSha256 = file.sha256) { bytes ->
                        progress(LocalSpeechProgress(LocalSpeechStage.IMPORTING, base + bytes, model.installedSizeBytes))
                    }
                    total += file.sizeBytes
                }
                zip.closeEntry()
            }
        }
        require(files == expected.keys && total == model.installedSizeBytes) { "Vosk ZIP 缺少模型文件" }
    }
}
