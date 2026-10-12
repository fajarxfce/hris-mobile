package dev.fajar.starter.fileio

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.WritableByteChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible

/** The source owns closing its channel. A zero-progress writer is a failure, never a busy loop. */
internal suspend fun writeFileChannel(channel: WritableByteChannel, bytes: ByteArray) {
    require(bytes.size in 1..FILE_OUTPUT_BLOCK_BYTES)
    val buffer = ByteBuffer.wrap(bytes)
    while (buffer.hasRemaining()) {
        currentCoroutineContext().ensureActive()
        val count = runInterruptible(Dispatchers.IO) { channel.write(buffer) }
        if (count <= 0) throw IOException("File output did not advance.")
    }
    currentCoroutineContext().ensureActive()
}
