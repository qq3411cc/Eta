// SPDX-License-Identifier: Apache-2.0
package io.github.mangi.eta.agent.voice.local

import io.github.mangi.eta.agent.voice.SpeechErrorCode
import io.github.mangi.eta.agent.voice.SpeechFailure

/** Worker-owned session resources and finalization policy shared by the service and tests. */
internal class LocalSpeechUtterance {
    var stream: LocalSpeechStreamingSession? = null
    fun finish(audio: LocalSpeechPcmAccumulator, offline: () -> String): String {
        // Historical batch backends retain their RMS gate; streaming already decoded every frame.
        if (stream == null && (!audio.hasVoice || audio.size == 0))
            throw SpeechFailure(SpeechErrorCode.NO_SPEECH, "未检测到清晰语音，请重试")
        return (stream?.finish() ?: offline()).trim().also {
            if (it.isEmpty()) throw SpeechFailure(SpeechErrorCode.NO_SPEECH, "未识别到文字，请重试")
            check(it.toByteArray(Charsets.UTF_8).size <= LocalSpeechProtocol.MAX_TEXT_BYTES) { "识别文本过长，请缩短语音" }
        }
    }
    fun closeBeforeCache(onCloseFailure: () -> Unit, finishCache: () -> Unit) {
        val decoder = stream; stream = null
        try { decoder?.close() }
        catch (_: Exception) { onCloseFailure() }
        catch (_: LinkageError) { onCloseFailure() }
        catch (_: OutOfMemoryError) { onCloseFailure() }
        finally { finishCache() }
    }
}
