package dev.fajar.starter.fileio

import java.io.Closeable
import java.nio.channels.FileChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

/**
 * An owned native output. The handle registry serializes operations; release closes its channel.
 */
internal class JvmFileOutput(
    val ticket: Any,
    private val size: Long,
    private val channel: FileChannel,
    private val owner: Closeable = channel,
    private val flush: () -> Unit = { channel.force(true) },
) : Closeable {
    private var written = 0L

    suspend fun write(offset: Long, bytes: ByteArray) {
        if (offset != written || bytes.size.toLong() > size - written) throw FileChangedException()
        writeFileChannel(channel, bytes)
        written += bytes.size
    }

    suspend fun complete() {
        if (written != size) throw FileChangedException()
        runInterruptible(Dispatchers.IO) { flush() }
    }

    override fun close() = owner.close()

    fun closeAfterFailure(cause: Throwable) {
        try {
            close()
        } catch (cleanup: Throwable) {
            cause.addSuppressed(cleanup)
        }
    }
}

/** Failure closing one descriptor must not leave another owned output open. */
internal fun closeFileOutputs(outputs: List<JvmFileOutput>) {
    var failure: Throwable? = null
    for (output in outputs) {
        try {
            output.close()
        } catch (cause: Throwable) {
            if (failure == null) failure = cause else failure.addSuppressed(cause)
        }
    }
    failure?.let { throw it }
}
