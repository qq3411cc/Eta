// SPDX-License-Identifier: Apache-2.0
package io.github.mangi.eta.agent.voice.local

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/** Stream-based SAF import; archive-controlled paths are never resolved on the filesystem. */
internal object LocalSpeechImport {
    suspend fun extract(model: LocalSpeechModelDescriptor, stage: File, opener: () -> InputStream,
        progress: (LocalSpeechProgress) -> Unit) {
        progress(LocalSpeechProgress(LocalSpeechStage.IMPORTING, 0, model.sizeBytes))
        if (model.engine == LocalSpeechBackend.VOSK) {
            opener().use { LocalSpeechVoskArchive.importArchive(model, stage, it, progress) }
            return
        }
        opener().use { source ->
            val input = BufferedInputStream(source)
            input.mark(8); val magic = ByteArray(4); var count = 0
            while (count < 4) { val n = input.read(magic, count, 4 - count); require(n > 0) { "导入文件为空或已截断" }; count += n }
            input.reset()
            when {
                magic.contentEquals(byteArrayOf(71, 71, 85, 70)) -> {
                    require(model.engine == LocalSpeechBackend.GGUF) { "所选 INT8 ONNX 需要包含 model.int8.onnx 和 tokens.txt 的 ZIP" }
                    val file = model.files.single()
                    LocalSpeechDownload.copyExact(input, File(stage, file.name), file.sizeBytes) { bytes ->
                        progress(LocalSpeechProgress(LocalSpeechStage.IMPORTING, bytes, model.sizeBytes))
                    }
                }
                magic.contentEquals(byteArrayOf(80, 75, 3, 4)) -> zip(model, stage, input, progress)
                else -> throw IllegalArgumentException("仅支持目录指定的单个 GGUF 或模型 ZIP；原始 safetensors / 任意 ONNX 不支持直接导入")
            }
        }
    }
    private suspend fun zip(model: LocalSpeechModelDescriptor, stage: File, source: InputStream,
        progress: (LocalSpeechProgress) -> Unit) {
        val expected = model.files.associateBy { it.name }; val seen = HashSet<String>(); val paths = HashSet<String>()
        var entries = 0; var total = 0L; var metadataBytes = 0; var wrapper: String? = null
        val metadataNames = setOf("LICENSE", "LICENSE.txt", "LICENSE.md", "README.md", "README.txt", "manifest.json", "catalog.json", "source.json")
        ZipInputStream(source).use { zip ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val entry = zip.nextEntry ?: break
                require(++entries <= 32) { "ZIP 条目过多" }
                val path = entry.name
                require(path.length in 1..512 && '\\' !in path && '\u0000' !in path && ':' !in path && !path.startsWith('/')) { "ZIP 路径不安全" }
                val parts = path.removeSuffix("/").split('/')
                require(parts.size <= (if (entry.isDirectory) 2 else 3) && parts.all { it.isNotEmpty() && it != "." && it != ".." && it.length <= 160 }) { "ZIP 只允许根目录或最多两层包装目录" }
                require(paths.add(path)) { "ZIP 条目重复" }
                if (entry.isDirectory) {
                    // Never close/drain an unbounded directory payload: reject immediately.
                    require(zip.read() == -1) { "ZIP 目录不能包含数据" }
                } else {
                    val name = parts.last(); val file = expected[name]
                    if (file != null) {
                        require(seen.add(name)) { "ZIP 中存在重复模型文件" }
                        val prefix = parts.dropLast(1).joinToString("/")
                        if (wrapper == null) wrapper = prefix else require(wrapper == prefix) { "模型文件必须处于同一目录" }
                        require(entry.size == -1L || entry.size == file.sizeBytes) { "ZIP 模型大小不匹配" }
                        val base = total
                        LocalSpeechDownload.copyExact(zip, File(stage, name), file.sizeBytes) { bytes ->
                            progress(LocalSpeechProgress(LocalSpeechStage.IMPORTING, base + bytes, model.sizeBytes))
                        }
                        total += file.sizeBytes
                    } else {
                        require(name in metadataNames) { "ZIP 含不支持文件；请提供所选模型文件和小型说明/许可元数据" }
                        val data = zip.readBytesBounded(65_536)
                        metadataBytes += data.size; require(metadataBytes <= 262_144) { "ZIP 附加元数据过大" }
                        val provenance = File(stage, "import-metadata").apply { check(isDirectory || mkdir()) }
                        val out = File(provenance, "${entries}-$name")
                        out.outputStream().use { it.write(data); it.fd.sync() }
                    }
                }
                zip.closeEntry()
            }
        }
        require(seen == expected.keys) { "ZIP 缺少模型文件；INT8 必须同时包含 model.int8.onnx 与 tokens.txt" }
    }
}
