package dev.fajar.starter.fileio

import java.awt.FileDialog
import java.awt.Frame
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import javax.swing.SwingUtilities
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class DesktopFileInputSource : FileInputSource {
    private val inputs = FileInputRegistry<Path>()

    override suspend fun select(requestId: String, mediaTypes: Set<String>): FileInputMetadata? =
        withContext(Dispatchers.Main.immediate) {
            validateFileMediaTypes(mediaTypes)
            val ticket = inputs.reserve(requestId, currentCoroutineContext().job)
            var retained = false
            try {
                val selected =
                    suspendCancellableCoroutine<Path?> { continuation ->
                        val dialog = FileDialog(null as Frame?, "Select file", FileDialog.LOAD)
                        dialog.isMultipleMode = false
                        continuation.invokeOnCancellation {
                            SwingUtilities.invokeLater { dialog.dispose() }
                        }
                        SwingUtilities.invokeLater {
                            try {
                                if (continuation.isActive) dialog.isVisible = true
                                val file = dialog.files.singleOrNull()?.toPath()
                                if (continuation.isActive) continuation.resume(file)
                            } catch (error: Exception) {
                                if (continuation.isActive) continuation.resumeWithException(error)
                            } finally {
                                dialog.dispose()
                            }
                        }
                    } ?: return@withContext null
                val metadata =
                    runInterruptible(Dispatchers.IO) {
                        val attributes =
                            Files.readAttributes(selected, BasicFileAttributes::class.java)
                        if (!attributes.isRegularFile) throw FileInputUnavailableException()
                        FileInputMetadata(
                            selected.fileName.toString(),
                            Files.probeContentType(selected),
                            attributes.size(),
                            attributes.lastModifiedTime().toMillis(),
                        )
                    }
                inputs.publish(requestId, ticket, selected)
                retained = true
                metadata
            } finally {
                if (!retained) inputs.discardSelection(requestId, ticket)
            }
        }

    override fun read(requestId: String): Flow<ByteArray> =
        flow {
                coroutineScope {
                    val operation = currentCoroutineContext().job
                    val selected = inputs.beginRead(requestId, operation)
                    try {
                        // Opening and reading the channel share the producer's IO scope and
                        // finally.
                        FileChannel.open(selected, StandardOpenOption.READ).use { channel ->
                            emitAll(readFileChannel(channel))
                        }
                    } finally {
                        inputs.endRead(requestId, operation)
                    }
                }
            }
            .flowOn(Dispatchers.IO)
            .buffer(0)

    override suspend fun release(requestId: String) {
        inputs.release(requestId)
    }

    override fun close() {
        inputs.close()
    }
}
