// SPDX-License-Identifier: Apache-2.0
package io.github.mangi.eta.agent.voice.qwen

import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineQwen3AsrModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig

/** The production factory is also used by the standalone public-WAV JNI benchmark. */
internal object QwenNativeRecognizer {
    fun create(dir: String): OfflineRecognizer = OfflineRecognizer(config = OfflineRecognizerConfig(
        modelConfig = OfflineModelConfig(qwen3Asr = OfflineQwen3AsrModelConfig(
            convFrontend = "$dir/conv_frontend.onnx", encoder = "$dir/encoder.int8.onnx",
            decoder = "$dir/decoder.int8.onnx", tokenizer = "$dir/tokenizer",
            maxTotalLen = 1024, maxNewTokens = 512,
        ), numThreads = 3, provider = "cpu"),
    ))
}
