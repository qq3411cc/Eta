// SPDX-License-Identifier: Apache-2.0
package io.github.mangi.eta.agent.voice.local

import org.json.JSONArray
import org.json.JSONObject

/** A marker is evidence only when every catalog-bound field matches; payload SHA is checked separately. */
internal object LocalSpeechManifest {
    private fun file(f: LocalSpeechModelFile) = JSONObject().put("name", f.name)
        .put("sizeBytes", f.sizeBytes).put("sha256", f.sha256).put("pinnedUrl", f.pinnedUrl)
        .put("revision", f.revision).put("role", f.role)
    fun encode(model: LocalSpeechModelDescriptor): JSONObject = JSONObject().put("schemaVersion", 1)
        .put("modelId", model.id).put("engine", model.engine.name).put("architecture", model.architecture)
        .put("quantization", model.quantization).put("licenseName", model.licenseName).put("licenseAsset", model.licenseAsset)
        .put("files", JSONArray().apply { model.files.forEach { f -> put(JSONObject().put("name", f.name)
            .put("sizeBytes", f.sizeBytes).put("sha256", f.sha256).put("pinnedUrl", f.pinnedUrl).put("revision", f.revision).put("role", f.role)) } })
        .also { json -> model.archive?.let { json.put("archive", file(it)).put("licenseSource", model.licenseSource) } }
    fun validate(text: String, model: LocalSpeechModelDescriptor) {
        require(text.length <= 65_536)
        val actual = JSONObject(text); val expected = encode(model)
        // Compare every catalog-bound field and reject extra keys, rather than trusting a downloaded marker.
        require(actual.length() == expected.length()) { "安装元数据不匹配" }
        expected.keys().forEach { key ->
            if (key == "archive") {
                val a = actual.getJSONObject(key); val e = expected.getJSONObject(key)
                require(a.length() == e.length())
                e.keys().forEach { field -> require(if (field == "sizeBytes") a.getLong(field) == e.getLong(field) else a.get(field) == e.get(field)) }
            } else if (key != "files") require(actual.get(key) == expected.get(key)) { "安装元数据不匹配" }
        }
        val a = actual.getJSONArray("files"); val e = expected.getJSONArray("files"); require(a.length() == e.length())
        repeat(e.length()) { i ->
            val x = a.getJSONObject(i); val y = e.getJSONObject(i); require(x.length() == y.length())
            y.keys().forEach { key -> require(if (key == "sizeBytes") x.getLong(key) == y.getLong(key) else x.get(key) == y.get(key)) { "安装文件元数据不匹配" } }
        }
    }
}
