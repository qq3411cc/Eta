// SPDX-License-Identifier: Apache-2.0
package io.github.mangi.eta.agent.voice.offline

import android.content.Context
import org.json.JSONObject
import java.io.DataInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException
import java.security.MessageDigest
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Catalog metadata is bundled; payloads are installed only by explicit download/import. */
object OfflineAsrModelStore {
    const val SMALL_ID = "default_small"
    const val STANDARD_ID = "standard_bilingual"
    const val DEFAULT_ID = STANDARD_ID
    private const val PREFS = "offline_asr_model_selection"
    private const val SELECTED = "selected_model_id"

    data class ModelDescriptor(
        val id: String,
        val label: String,
        val totalBytes: Long,
        val sampleRate: Int,
        val featureDim: Int,
        val recommended: Boolean,
        val description: String,
    )
    private val names = setOf("encoder_jit_trace-pnnx.ncnn.param", "encoder_jit_trace-pnnx.ncnn.bin",
        "decoder_jit_trace-pnnx.ncnn.param", "decoder_jit_trace-pnnx.ncnn.bin",
        "joiner_jit_trace-pnnx.ncnn.param", "joiner_jit_trace-pnnx.ncnn.bin", "tokens.txt")
    internal data class Entry(val name: String, val size: Long, val hash: String, val magic: String)
    internal data class ModelManifest(val raw: ByteArray, val entries: List<Entry>, val tokenLines: Int?)
    internal fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).hex()
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun safeId(id: String) = id.matches(Regex("[a-z][a-z0-9_]{0,63}"))
    private fun catalog(context: Context): List<ModelDescriptor> {
        val json = context.assets.open("offline_asr/catalog.json").use { JSONObject(it.bufferedReader().readText()) }
        val array = json.getJSONArray("models")
        val models = (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            ModelDescriptor(item.getString("id"), item.getString("label"), item.getLong("totalBytes"),
                item.getInt("sampleRate"), item.getInt("featureDim"), item.getBoolean("recommended"), item.getString("description"))
        }
        require(models.isNotEmpty() && models.map { it.id }.toSet().size == models.size)
        require(models.all { safeId(it.id) && it.totalBytes > 0 && it.sampleRate == 16000 && it.featureDim == 80 })
        return models
    }
    /** Catalog, not installation status: no payloads ship in APK assets. */
    fun availableModels(context: Context): List<ModelDescriptor> = catalog(context)
    enum class State { MISSING, INSTALLED, INVALID }
    data class ModelStatus(val descriptor: ModelDescriptor, val state: State, val detail: String) {
        val installed: Boolean get() = state == State.INSTALLED
    }
    data class Progress(val modelId: String, val bytesCompleted: Long, val totalBytes: Long)
    suspend fun selectModel(context: Context, id: String) = withContext(Dispatchers.IO) {
        setSelectedModelId(context, id)
    }
    suspend fun inspect(context: Context, id: String): ModelStatus = withContext(Dispatchers.IO) {
        val descriptor = descriptor(context, id)
        val job = currentCoroutineContext()
        try {
            prepare(context, id) { job.ensureActive() }
            ModelStatus(descriptor, State.INSTALLED, "Installed; SHA256, size and format verified")
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: MissingModelException) {
            ModelStatus(descriptor, State.MISSING, "CONFIG: 请在离线语音设置下载或导入此模型；未安装模型时语音不可用")
        } catch (_: Exception) {
            ModelStatus(descriptor, State.INVALID, "CONFIG: 模型校验失败，原文件保留；请检查离线模型设置")
        }
    }
    suspend fun installedModels(context: Context): List<ModelDescriptor> =
        availableModels(context).filter { inspect(context, it.id).installed }
    suspend fun download(context: Context, id: String, onProgress: (Progress) -> Unit = {}): ModelStatus =
        OfflineAsrModelManager.download(context, id, onProgress)
    suspend fun importModel(context: Context, id: String, open: () -> InputStream,
        onProgress: (Progress) -> Unit = {}): ModelStatus =
        OfflineAsrModelManager.importModel(context, id, open, onProgress)
    internal class MissingModelException : IllegalStateException(
        "CONFIG: 未安装所选离线语音模型；请在离线语音设置显式下载或导入 ZIP，识别期间不会联网下载")
    fun selectedModelId(context: Context): String {
        val available = availableModels(context)
        check(available.isNotEmpty()) { "Offline ASR catalog is empty" }
        val stored = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(SELECTED, null)
        return available.firstOrNull { it.id == stored }?.id
            ?: available.firstOrNull { it.id == DEFAULT_ID }?.id ?: available.first().id
    }
    fun setSelectedModelId(context: Context, id: String) {
        require(availableModels(context).any { it.id == id }) { "Unknown offline ASR model: $id" }
        // SharedPreferences is intentionally read only by client/UI; service receives an explicit START model ID.
        // Synchronous disk confirmation: callers must invoke this setter on Dispatchers.IO.
        // commit() can update in-memory state even when persistence fails; do not claim rollback.
        check(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(SELECTED, id).commit()) { "Model selection persistence failed" }
    }
    internal fun descriptor(context: Context, id: String): ModelDescriptor {
        require(safeId(id)) { "Invalid model ID" }
        return catalog(context).firstOrNull { it.id == id } ?: error("Unknown offline ASR model: $id")
    }
    internal fun manifest(context: Context, descriptor: ModelDescriptor): ModelManifest {
        val raw = try {
            context.assets.open("offline_asr/${descriptor.id}/manifest.json").use { it.readBytes() }
        } catch (error: java.io.FileNotFoundException) {
            // Byte-identical legacy small manifest keeps its old deployment directory and marker compatible.
            if (descriptor.id != SMALL_ID) throw error
            context.assets.open("offline_asr/manifest.json").use { it.readBytes() }
        }
        val json = JSONObject(raw.toString(Charsets.UTF_8))
        require(json.getString("id") == descriptor.id && json.getInt("sampleRate") == descriptor.sampleRate &&
            json.getInt("featureDim") == descriptor.featureDim)
        val array = json.getJSONArray("files")
        val result = (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            Entry(item.getString("name"), item.getLong("size"), item.getString("sha256"), item.getString("magic"))
        }
        require(result.size == names.size && result.map { it.name }.toSet() == names)
        require(result.sumOf { it.size } == descriptor.totalBytes)
        if (json.has("totalBytes")) require(json.getLong("totalBytes") == descriptor.totalBytes)
        require(result.all { it.size >= 16 && it.hash.matches(Regex("[a-f0-9]{64}")) && it.magic.matches(Regex("[a-f0-9]{32}")) })
        val tokenLines = if (json.has("tokenLines")) json.getInt("tokenLines").also { require(it >= 3) } else null
        return ModelManifest(raw, result, tokenLines)
    }
    /** Compatibility helper for local callers; service MUST use the explicit-ID overload. */
    internal fun prepare(context: Context, checkCancelled: () -> Unit): File =
        prepare(context, selectedModelId(context), checkCancelled)

    internal fun root(context: Context): File {
        val root = File(context.filesDir, "offline_asr/models")
        check(root.mkdirs() || root.isDirectory)
        check(root.canonicalPath == File(context.filesDir.canonicalFile, "offline_asr/models").absolutePath) { "Unsafe model root" }
        // Android may expose filesDir through a legitimate /data/data ancestor alias.
        // Canonicalize the verified root before checking leaf paths for symlinks.
        return root.canonicalFile
    }
    internal fun target(root: File, id: String, model: ModelManifest): File {
        val file = File(root, "$id-${digest(model.raw).take(16)}")
        check(file.canonicalFile.parentFile == root.canonicalFile && file.canonicalFile.name == file.name) { "Unsafe model target" }
        return file
    }
    internal fun <T> locked(root: File, checkCancelled: () -> Unit, action: () -> T): T {
        val lockPath = File(root, ".deployment.lock")
        check(lockPath.canonicalPath == lockPath.absolutePath) { "Unsafe deployment lock" }
        RandomAccessFile(lockPath, "rw").use { lockFile ->
            var lock: java.nio.channels.FileLock? = null
            while (lock == null) {
                checkCancelled()
                lock = try { lockFile.channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                if (lock == null) Thread.sleep(25)
            }
            lock.use { checkCancelled(); return action() }
        }
    }
    internal fun verifyTarget(target: File, model: ModelManifest, checkCancelled: () -> Unit) {
        val marker = File(target, ".complete")
        check(marker.canonicalPath == marker.absolutePath && marker.isFile && marker.length() == 64L &&
            marker.readText() == digest(model.raw)) {
            "CONFIG: 离线模型标记无效；原文件保留，请检查离线模型设置"
        }
        validate(target, model, checkCancelled)
    }
    internal fun prepare(context: Context, modelId: String, checkCancelled: () -> Unit): File {
        val model = manifest(context, descriptor(context, modelId))
        val root = root(context)
        return locked(root, checkCancelled) {
            val target = target(root, modelId, model)
            if (!target.exists()) throw MissingModelException()
            verifyTarget(target, model, checkCancelled)
            target
        }
    }
    internal fun validate(directory: File, model: ModelManifest, checkCancelled: () -> Unit) {
        model.entries.forEach { entry ->
            checkCancelled()
            val file = File(directory, entry.name)
            check(file.canonicalPath == file.absolutePath && file.canonicalFile.parentFile == directory.canonicalFile && file.isFile && file.length() == entry.size) {
                "Offline model file invalid: ${entry.name}; model preserved"
            }
            val sha = MessageDigest.getInstance("SHA-256")
            DataInputStream(file.inputStream()).use { input ->
                val header = ByteArray(16)
                input.readFully(header)
                check(header.hex() == entry.magic) { "Offline model magic mismatch: ${entry.name}" }
                sha.update(header)
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    checkCancelled()
                    val n = input.read(buffer)
                    if (n < 0) break
                    sha.update(buffer, 0, n)
                }
            }
            check(sha.digest().hex() == entry.hash) { "Offline model checksum mismatch: ${entry.name}; model preserved" }
            if (entry.name.endsWith(".param")) {
                check(file.bufferedReader().use { it.readLine() } == "7767517") { "Invalid NCNN parameter magic" }
            }
            if (entry.name == "tokens.txt") {
                val lines = file.readLines(Charsets.UTF_8)
                check(lines.size >= 3 && lines.first() == "<blk> 0") { "Invalid token vocabulary" }
                if (model.tokenLines != null) check(lines.size == model.tokenLines) { "Token count differs from model manifest" }
                lines.forEachIndexed { index, line ->
                    check(line.substringAfterLast(' ').toIntOrNull() == index) { "Invalid token ID sequence" }
                }
            }
        }
    }
    /** Metadata only: no native loading or full model hashing on UI thread. */
    fun modelSummary(context: Context): String {
        val id = selectedModelId(context)
        val descriptor = descriptor(context, id)
        val hash = digest(manifest(context, descriptor).raw)
        val target = File(context.filesDir, "offline_asr/models/$id-${hash.take(16)}")
        val marker = File(target, ".complete")
        val state = when {
            !target.exists() -> "CONFIG: 未安装，请在设置下载或导入模型；语音不可用"
            runCatching { marker.readText() == hash }.getOrDefault(false) -> "deployed; files validated on next use"
            else -> "deployment marker invalid; existing files preserved"
        }
        return "${descriptor.label} [$id] · ${descriptor.totalBytes} bytes · 16 kHz · CPU greedy · $state"
    }
}
