// SPDX-License-Identifier: Apache-2.0
package io.github.mangi.eta.agent.voice.local

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

/** Bounded parsers, independent of Android and all native runtimes. Exact SHA is still mandatory. */
internal object LocalSpeechValidation {
    fun sha256(file: File, checkCancelled: () -> Unit = {}, progress: (Long) -> Unit = {}): String {
        val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(64 * 1024)
        var count = 0L
        file.inputStream().buffered().use { input -> while (true) {
            checkCancelled(); val n = input.read(buffer); if (n == -1) break
            digest.update(buffer, 0, n); count += n; progress(count)
        } }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    fun gguf(file: File, expectedArchitecture: String) = file.inputStream().use { gguf(it, expectedArchitecture) }
    fun gguf(input: InputStream, expectedArchitecture: String) {
        val r = Header(input)
        require(r.u32() == 0x46554747L) { "不是 GGUF 文件；原始 safetensors 不支持直接导入，请先取得目录列出的量化 GGUF" }
        require(r.u32() in 2L..3L) { "GGUF 版本不支持" }
        require(r.u64() in 1L..1_000_000L) { "GGUF tensor 数量无效" }
        val kv = r.u64(); require(kv in 1..4096L)
        var architecture: String? = null
        repeat(kv.toInt()) {
            val key = r.string(1024); val type = r.u32().toInt()
            if (key == "general.architecture") {
                require(architecture == null && type == 8)
                architecture = r.string(128)
            } else r.value(type)
        }
        require(architecture == expectedArchitecture) { "模型架构不匹配；仅支持目录中的 Qwen3-ASR / SenseVoice 文件" }
    }
    fun onnx(file: File) {
        file.inputStream().use { input ->
            // ONNX ModelProto starts with its protobuf IR-version varint field, not a ZIP/GGUF/safetensors container.
            require(input.read() == 8 && input.read() in 1..127) { "不是目录指定的 ONNX 模型" }
        }
    }
    fun tokens(file: File) {
        require(file.length() in 1..1_048_576L) { "tokens.txt 大小无效" }
        val bytes = file.readBytes()
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        val text = decoder.decode(ByteBuffer.wrap(bytes)).toString()
        require('\u0000' !in text)
        val ids = HashSet<Int>(); var count = 0
        text.lineSequence().forEach { raw ->
            val line = raw.removeSuffix("\r")
            if (line.isNotEmpty()) {
                require(line.length <= 4096)
                val split = line.lastIndexOf(' '); require(split > 0)
                val id = line.substring(split + 1).toIntOrNull()
                require(id != null && id in 0..1_000_000 && ids.add(id)) { "tokens.txt 词表无效" }
                count++; require(count <= 200_000)
            }
        }
        require(count > 0)
    }
    private class Header(input: InputStream) {
        val data = DataInputStream(BufferedInputStream(input)); var consumed = 0L
        private fun charge(n: Long) { require(n >= 0 && n <= 33_554_432L - consumed) { "GGUF metadata 超出安全限制" }; consumed += n }
        fun u32(): Long { charge(4); return Integer.reverseBytes(data.readInt()).toLong() and 0xffffffffL }
        fun u64(): Long { charge(8); return java.lang.Long.reverseBytes(data.readLong()).also { require(it >= 0) } }
        fun string(max: Int): String {
            val count = u64(); require(count <= max); charge(count)
            val bytes = ByteArray(count.toInt()); data.readFully(bytes)
            return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        }
        private fun skip(n: Long) {
            charge(n); var left = n
            while (left > 0) { val skipped = data.skip(minOf(left, 8192)); require(skipped > 0) { "GGUF metadata 截断" }; left -= skipped }
        }
        fun value(type: Int) {
            when (type) {
                0, 1, 7 -> skip(1)
                2, 3 -> skip(2)
                4, 5, 6 -> skip(4)
                10, 11, 12 -> skip(8)
                8 -> { val n = u64(); require(n <= 1_048_576); skip(n) }
                9 -> {
                    val element = u32().toInt(); val n = u64(); require(n <= 2_000_000 && element != 9)
                    val width = when (element) { 0, 1, 7 -> 1; 2, 3 -> 2; 4, 5, 6 -> 4; 10, 11, 12 -> 8; 8 -> 0; else -> error("GGUF metadata 类型无效") }
                    if (width > 0) skip(n * width) else repeat(n.toInt()) { value(8) }
                }
                else -> error("GGUF metadata 类型无效")
            }
        }
    }
}
