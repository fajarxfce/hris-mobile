package dev.fajar.starter.fileio

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.ReadableByteChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runInterruptible

/** The caller owns closing the channel, including cancellation before collection starts. */
internal fun readFileChannel(channel: ReadableByteChannel): Flow<ByteArray> = flow {
    val buffer = ByteBuffer.allocate(FILE_INPUT_BLOCK_BYTES)
    var ended = false
    while (!ended) {
        currentCoroutineContext().ensureActive()
        buffer.clear()
        while (buffer.hasRemaining() && !ended) {
            when (val count = runInterruptible(Dispatchers.IO) { channel.read(buffer) }) {
                -1 -> ended = true
                0 -> throw IOException("File input did not advance.")
                else -> check(count > 0)
            }
        }
        if (buffer.position() > 0) emit(buffer.array().copyOf(buffer.position()))
    }
}
