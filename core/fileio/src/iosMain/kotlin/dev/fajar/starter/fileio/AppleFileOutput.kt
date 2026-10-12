@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.fajar.starter.fileio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.FileHandle
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.Foundation.NSURL

/** Native descriptor ownership only. Closing during a write is deferred until that write exits. */
internal class AppleFileOutput
private constructor(
    val ticket: Any,
    private val size: Long,
    private val url: NSURL,
    private val scoped: Boolean,
    handle: FileHandle,
) {
    private val gate = Mutex()
    private val closing = MutableStateFlow(false)
    private var handle: FileHandle? = handle
    private var written = 0L

    suspend fun write(offset: Long, bytes: ByteArray) {
        try {
            gate.withLock {
                if (closing.value) throw FileReleasedException()
                require(bytes.size in 1..FILE_OUTPUT_BLOCK_BYTES)
                if (offset != written || bytes.size.toLong() > size - written)
                    throw FileChangedException()
                requireNotNull(handle).write(offset, bytes, 0, bytes.size)
                written += bytes.size
            }
        } finally {
            if (closing.value) close()
        }
    }

    suspend fun complete() {
        try {
            gate.withLock {
                if (closing.value) throw FileReleasedException()
                if (written != size) throw FileChangedException()
                requireNotNull(handle).flush()
            }
        } finally {
            if (closing.value) close()
        }
    }

    fun close() {
        closing.value = true
        if (!gate.tryLock()) return
        try {
            val resource = handle ?: return
            handle = null
            try {
                resource.close()
            } finally {
                if (scoped) url.stopAccessingSecurityScopedResource()
            }
        } finally {
            gate.unlock()
        }
    }

    fun closeAfterFailure(cause: Throwable) {
        try {
            close()
        } catch (cleanup: Throwable) {
            cause.addSuppressed(cleanup)
        }
    }

    companion object {
        fun open(ticket: Any, size: Long, url: NSURL): AppleFileOutput {
            val scoped = url.startAccessingSecurityScopedResource()
            try {
                val path = (url.path ?: throw FileUnavailableException()).toPath()
                val handle = FileSystem.SYSTEM.openReadWrite(path, mustExist = true)
                try {
                    if (handle.size() != 0L) throw FileChangedException()
                    return AppleFileOutput(ticket, size, url, scoped, handle)
                } catch (cause: Throwable) {
                    try {
                        handle.close()
                    } catch (cleanup: Throwable) {
                        cause.addSuppressed(cleanup)
                    }
                    throw cause
                }
            } catch (cause: Throwable) {
                if (scoped) url.stopAccessingSecurityScopedResource()
                throw cause
            }
        }
    }
}
