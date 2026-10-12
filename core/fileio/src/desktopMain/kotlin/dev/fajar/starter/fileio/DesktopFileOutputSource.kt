package dev.fajar.starter.fileio

import java.awt.FileDialog
import java.awt.Frame
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import javax.swing.SwingUtilities
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*

/** A selected new file is opened once, retaining its descriptor through bounded writes. */
class DesktopFileOutputSource : FileOutputSource {
    private val outputs = FileHandleRegistry<JvmFileOutput>()

    override suspend fun select(
        requestId: String,
        fileName: String,
        mediaType: String,
        size: Long,
    ): Boolean =
        withContext(Dispatchers.Main.immediate) {
            validateFileOutput(fileName, mediaType, size)
            val ticket = outputs.reserve(requestId, currentCoroutineContext().job)
            var retained = false
            try {
                val path =
                    suspendCancellableCoroutine<Path?> { continuation ->
                        val dialog = FileDialog(null as Frame?, "Save file", FileDialog.SAVE)
                        dialog.file = fileName
                        continuation.invokeOnCancellation {
                            SwingUtilities.invokeLater { dialog.dispose() }
                        }
                        SwingUtilities.invokeLater {
                            try {
                                if (continuation.isActive) dialog.isVisible = true
                                val chosen = dialog.files.singleOrNull()?.toPath()
                                if (continuation.isActive) continuation.resume(chosen)
                            } catch (cause: Exception) {
                                if (continuation.isActive) continuation.resumeWithException(cause)
                            } finally {
                                dialog.dispose()
                            }
                        }
                    } ?: return@withContext false
                // CREATE_NEW also rejects a symlink or a file created after the dialog returned.
                // Replacing existing files is deliberately not part of this raw output capability.
                withContext(Dispatchers.IO) {
                    val output =
                        JvmFileOutput(
                            ticket,
                            size,
                            FileChannel.open(
                                path,
                                StandardOpenOption.CREATE_NEW,
                                StandardOpenOption.WRITE,
                            ),
                        )
                    try {
                        outputs.publish(requestId, ticket, output)
                    } catch (cause: Throwable) {
                        output.closeAfterFailure(cause)
                        throw cause
                    }
                }
                retained = true
                true
            } finally {
                if (!retained) {
                    withContext(NonCancellable + Dispatchers.IO) {
                        outputs.discard(requestId, ticket)?.close()
                    }
                }
            }
        }

    override suspend fun write(requestId: String, offset: Long, bytes: ByteArray) =
        withContext(Dispatchers.IO) {
            val operation = currentCoroutineContext().job
            val output = outputs.beginOperation(requestId, operation)
            try {
                output.write(offset, bytes)
            } catch (cause: Throwable) {
                outputs.discard(requestId, output.ticket)
                output.closeAfterFailure(cause)
                throw cause
            } finally {
                outputs.endOperation(requestId, operation)
            }
        }

    override suspend fun complete(requestId: String) =
        withContext(Dispatchers.IO) {
            val operation = currentCoroutineContext().job
            val output = outputs.beginOperation(requestId, operation)
            try {
                output.complete()
                output.close()
            } catch (cause: Throwable) {
                output.closeAfterFailure(cause)
                throw cause
            } finally {
                outputs.discard(requestId, output.ticket)
            }
        }

    override suspend fun release(requestId: String) =
        withContext(Dispatchers.IO) {
            outputs.release(requestId)?.close()
            Unit
        }

    override fun close() {
        closeFileOutputs(outputs.close())
    }
}
