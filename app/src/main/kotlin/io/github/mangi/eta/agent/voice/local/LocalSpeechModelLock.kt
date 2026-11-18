// SPDX-License-Identifier: Apache-2.0
package io.github.mangi.eta.agent.voice.local

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException

internal class LocalSpeechModelBusyException : IOException("Model operation already in progress")

/** Shared by install, inspect, load and delete. File locks serialize actual app processes;
 * the mutex also covers JVM-overlapping-lock semantics and creation of the lock directory.
 * Deletion must refuse busy work rather than execute much later after an IPC timeout.
 */
internal class LocalSpeechModelLock {
    private val mutex = Mutex()
    suspend fun <T> withLock(file: () -> File, wait: Boolean = true, action: suspend () -> T): T {
        if (wait) mutex.lock() else if (!mutex.tryLock()) throw LocalSpeechModelBusyException()
        try {
            RandomAccessFile(file(), "rw").use { handle ->
                var lock: FileLock? = null
                try {
                    while (lock == null) {
                        currentCoroutineContext().ensureActive()
                        lock = try { handle.channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                        if (lock == null) {
                            if (!wait) throw LocalSpeechModelBusyException()
                            delay(50)
                        }
                    }
                    return action()
                } finally { lock?.release() }
            }
        } finally { mutex.unlock() }
    }
}
