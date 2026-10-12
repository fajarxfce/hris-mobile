@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlin.experimental.ExperimentalNativeApi::class,
    kotlin.uuid.ExperimentalUuidApi::class,
)

package dev.fajar.starter.fileio

import kotlin.native.ref.WeakReference
import kotlin.uuid.Uuid
import kotlinx.coroutines.*
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.UIKit.*

class AppleFileOutputSource : FileOutputSource {
    private var owner: WeakReference<UIViewController>? = null
    private val outputs = FileHandleRegistry<AppleFileOutput>()

    fun attach(controller: UIViewController) {
        if (owner?.get() !== controller) {
            outputs.cancelAcquisitions()
            owner = WeakReference(controller)
        }
    }

    override suspend fun select(
        requestId: String,
        fileName: String,
        mediaType: String,
        size: Long,
    ): Boolean =
        withContext(Dispatchers.Main.immediate) {
            validateFileOutput(fileName, mediaType, size)
            val controller = owner?.get() ?: throw FileUnavailableException()
            if (controller.presentedViewController != null) throw FileBusyException()
            val ticket = outputs.reserve(requestId, currentCoroutineContext().job)
            var scratch: Path? = null
            var picker: UIDocumentPickerViewController? = null
            var delegate: AppleFilePickerDelegate? = null
            var retained = false
            try {
                // UIKit exports an empty, app-owned placeholder to select/create the destination.
                // Document bytes are written only after the caller's subsequent admission check.
                val placeholder =
                    withContext(Dispatchers.IO) {
                        val directory =
                            NSTemporaryDirectory().toPath() / "file-output-${Uuid.random()}"
                        FileSystem.SYSTEM.createDirectory(directory, mustCreate = true)
                        scratch = directory
                        (directory / fileName).also {
                            FileSystem.SYSTEM.openReadWrite(it, mustCreate = true).close()
                        }
                    }
                val dialog =
                    UIDocumentPickerViewController(
                        forExportingURLs = listOf(NSURL.fileURLWithPath(placeholder.toString())),
                        asCopy = true,
                    )
                picker = dialog
                dialog.allowsMultipleSelection = false
                val url =
                    suspendCancellableCoroutine<NSURL?> { continuation ->
                        delegate = AppleFilePickerDelegate(continuation)
                        dialog.delegate = delegate
                        controller.presentViewController(dialog, animated = true, completion = null)
                    } ?: return@withContext false
                withContext(Dispatchers.IO) {
                    val output = AppleFileOutput.open(ticket, size, url)
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
                delegate?.clear()
                picker?.delegate = null
                picker?.dismissViewControllerAnimated(false, completion = null)
                withContext(NonCancellable + Dispatchers.IO) {
                    try {
                        if (!retained) outputs.discard(requestId, ticket)?.close()
                    } finally {
                        scratch?.let { FileSystem.SYSTEM.deleteRecursively(it) }
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
        owner = null
        var failure: Throwable? = null
        for (output in outputs.close()) {
            try {
                output.close()
            } catch (cause: Throwable) {
                if (failure == null) failure = cause else failure.addSuppressed(cause)
            }
        }
        failure?.let { throw it }
    }
}
