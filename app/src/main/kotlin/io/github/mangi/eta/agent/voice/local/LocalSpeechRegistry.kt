// SPDX-License-Identifier: Apache-2.0
package io.github.mangi.eta.agent.voice.local

import android.content.Context
import org.json.JSONObject
import java.net.URI

enum class LocalSpeechBackend { GGUF, SENSEVOICE_ONNX, VOSK }
enum class LocalSpeechStage { DOWNLOADING, IMPORTING, VERIFYING, INSTALLING, COMPLETE }
data class LocalSpeechProgress(val stage: LocalSpeechStage, val bytes: Long, val total: Long)
data class LocalSpeechModelFile(val name: String, val sizeBytes: Long, val sha256: String,
    val pinnedUrl: String, val revision: String, val role: String)
data class LocalSpeechModelDescriptor(val id: String, val displayName: String,
    val engine: LocalSpeechBackend, val architecture: String, val quantization: String,
    val description: String, val licenseName: String, val licenseAsset: String,
    val available: Boolean, val availabilityReason: String?, val files: List<LocalSpeechModelFile>,
    val recommended: Boolean, val archive: LocalSpeechModelFile? = null,
    val licenseSource: String? = null) {
    /** Network/SAF transfer bytes, not RAM or unpacked storage. */
    val sizeBytes: Long get() = archive?.sizeBytes ?: installedSizeBytes
    val installedSizeBytes: Long get() = files.sumOf { it.sizeBytes }
    val installationSpaceBytes: Long get() = installedSizeBytes + (archive?.sizeBytes ?: 0L)
}
data class LocalSpeechInstallation(val modelId: String, val installed: Boolean, val detail: String)

/** Metadata only; no recognizer class or JNI initialization is reachable here. */
internal object LocalSpeechRegistry {
    const val DEFAULT_ID = "sensevoice_small_q8_0"
    private val ids = setOf("qwen3_asr_06b_q4_k_m", "qwen3_asr_06b_q5_k_m",
        "sensevoice_small_f16", DEFAULT_ID, "sensevoice_small_q4_k_m", "sensevoice_small_int8", "vosk_small_cn_022", "vosk_cn_022")
    /** Recommendation changes never migrate an existing valid selection (including INT8). */
    fun selectionOrDefault(savedId: String?): String = savedId?.takeIf { it in ids } ?: DEFAULT_ID

    fun catalog(context: Context): List<LocalSpeechModelDescriptor> {
        val bytes = context.assets.open("local_speech/catalog.json").use { input ->
            val data = input.readBytesBounded(131_072); data.toString(Charsets.UTF_8)
        }
        return parse(bytes)
    }
    internal fun parse(bytes: String): List<LocalSpeechModelDescriptor> {
        require(bytes.length <= 131_072)
        val root = JSONObject(bytes)
        require(root.getInt("schemaVersion") == 1 && root.getString("recommendedModelId") == DEFAULT_ID)
        val models = root.getJSONArray("models")
        require(models.length() == 8)
        val result = (0 until models.length()).map { index ->
            val m = models.getJSONObject(index)
            val id = m.getString("id"); require(id in ids)
            val backend = LocalSpeechBackend.valueOf(m.getString("engine"))
            val files = m.getJSONArray("files").let { a -> (0 until a.length()).map { n ->
                val f = a.getJSONObject(n)
                LocalSpeechModelFile(f.getString("name"), f.getLong("sizeBytes"), f.getString("sha256"),
                    f.getString("pinnedUrl"), f.getString("revision"), f.getString("role")).also { file ->
                    require(if (backend == LocalSpeechBackend.VOSK) safeVoskPath(file.name)
                        else file.name.matches(Regex("[A-Za-z0-9_.-]{1,128}")) && file.name != "." && file.name != "..")
                    require(file.sizeBytes in (if (backend == LocalSpeechBackend.VOSK) 0L..3_000_000_000L else 1L..2_000_000_000L) && file.sha256.matches(Regex("[a-f0-9]{64}")))
                    if (backend == LocalSpeechBackend.VOSK) {
                        require(file.revision == "0.22" && file.pinnedUrl == voskUrl(id))
                    } else {
                    require(file.revision.matches(Regex("[a-f0-9]{40}")))
                    val url = URI(file.pinnedUrl)
                    require(url.scheme == "https" && url.host == "huggingface.co" && url.port == -1 &&
                        url.rawQuery == null && url.fragment == null && url.userInfo == null)
                    val publisher = if (backend == LocalSpeechBackend.GGUF) "handy-computer" else "csukuangfj"
                    require(url.path.startsWith("/$publisher/") && url.path.endsWith("/resolve/${file.revision}/${file.name}"))
                    }
                }
            } }
            require(files.map { it.name }.distinct().size == files.size)
            require(files.all { it.role == if (backend == LocalSpeechBackend.VOSK) "payload" else if (it.name == "tokens.txt") "tokens" else "model" })
            val archive = if (backend == LocalSpeechBackend.VOSK) {
                val a = m.getJSONObject("archive")
                LocalSpeechModelFile(a.getString("name"), a.getLong("sizeBytes"), a.getString("sha256"),
                    a.getString("pinnedUrl"), a.getString("revision"), a.getString("role")).also {
                    require(it.name == "${voskRoot(id)}.zip" && it.pinnedUrl == voskUrl(id))
                    require(it.revision == "0.22" && it.role == "archive")
                    require(it.sizeBytes in 1..2_000_000_000L && it.sha256.matches(Regex("[a-f0-9]{64}")))
                }
            } else { require(!m.has("archive")); null }
            require(m.getLong("sizeBytes") == (archive?.sizeBytes ?: files.sumOf { it.sizeBytes }))
            require(if (backend == LocalSpeechBackend.GGUF) files.size == 1 && files.single().name.endsWith(".gguf")
                else if (backend == LocalSpeechBackend.SENSEVOICE_ONNX) files.map { it.name }.toSet() == setOf("model.int8.onnx", "tokens.txt")
                else files.size in 5..128 && files.map { it.name }.toSet().containsAll(setOf(
                    "vosk/am/final.mdl", "vosk/conf/mfcc.conf", "vosk/conf/model.conf")) &&
                    files.any { it.name == "vosk/graph/HCLG.fst" || it.name == "vosk/graph/HCLr.fst" })
            if (backend == LocalSpeechBackend.VOSK) {
                require(files.sumOf { it.sizeBytes } in 1..LocalSpeechVoskArchive.MAX_UNPACKED_BYTES)
                require(m.getLong("installedSizeBytes") == files.sumOf { it.sizeBytes })
                require(m.getString("licenseName") == "Apache-2.0")
            }
            val architecture = m.getString("architecture")
            require(architecture in setOf("qwen3_asr", "sensevoice", "vosk"))
            require((backend == LocalSpeechBackend.VOSK) == (architecture == "vosk"))
            require((backend == LocalSpeechBackend.VOSK) == id.startsWith("vosk_"))
            require(backend != LocalSpeechBackend.SENSEVOICE_ONNX || architecture == "sensevoice")
            val license = m.getString("licenseAsset")
            require(license in setOf("local_speech/licenses/Qwen-Apache-2.0.txt", "local_speech/licenses/SenseVoice-Model-License-1.1.txt", "local_speech/licenses/Vosk-Apache-2.0.txt"))
            require((backend == LocalSpeechBackend.VOSK) == (license == "local_speech/licenses/Vosk-Apache-2.0.txt"))
            LocalSpeechModelDescriptor(id, m.getString("displayName"), backend, architecture,
                m.getString("quantization"), m.getString("description"), m.getString("licenseName"), license,
                m.getBoolean("available"), m.optString("availabilityReason").takeUnless { it.isBlank() || it == "null" },
                files, id == DEFAULT_ID, archive,
                if (backend == LocalSpeechBackend.VOSK) m.getString("licenseSource").also {
                    require(it == "https://alphacephei.com/vosk/models")
                } else null)
        }
        require(result.map { it.id }.toSet() == ids)
        return result
    }
    internal fun voskRoot(id: String): String = when (id) {
        "vosk_small_cn_022" -> "vosk-model-small-cn-0.22"
        "vosk_cn_022" -> "vosk-model-cn-0.22"
        else -> error("未知 Vosk 模型")
    }
    internal fun voskUrl(id: String): String = "https://alphacephei.com/vosk/models/${voskRoot(id)}.zip"
    internal fun safeVoskPath(name: String): Boolean = name.length <= 240 && name.startsWith("vosk/") &&
        name.split('/').let { it.size in 2..6 && it.all { p -> p != "." && p != ".." && p.matches(Regex("[A-Za-z0-9_.-]{1,128}")) } }
    fun model(context: Context, id: String): LocalSpeechModelDescriptor =
        catalog(context).firstOrNull { it.id == id } ?: error("未知本机模型，请重新选择")
}

internal fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream(minOf(limit, 8192)); val buffer = ByteArray(8192)
    while (true) {
        val n = read(buffer); if (n == -1) break
        require(n > 0 && output.size().toLong() + n <= limit) { "元数据过大或输入无效" }
        output.write(buffer, 0, n)
    }
    return output.toByteArray()
}
