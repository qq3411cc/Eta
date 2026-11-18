package io.github.mangi.eta.agent.voice.local

/** Blocking native ABI. Access only from the dedicated offline speech process. */
object GgufSpeechNative {
    init { System.loadLibrary("eta_transcribe_jni") }
    external fun load(path: String, threads: Int): Long
    external fun recognize(handle: Long, pcm: FloatArray): String
    external fun release(handle: Long)
}
