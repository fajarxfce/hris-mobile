@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlin.experimental.ExperimentalNativeApi::class,
)

package dev.fajar.starter.fileio

import kotlin.coroutines.resume
import kotlin.native.ref.WeakReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okio.FileSystem
import okio.IOException
import okio.Path.Companion.toPath
import platform.Foundation.NSURL
import platform.UIKit.*
import platform.UniformTypeIdentifiers.UTType
import platform.UniformTypeIdentifiers.UTTypeData
import platform.UniformTypeIdentifiers.UTTypeImage
import platform.darwin.NSObject

/** The host attaches its controller and closes the source when its container is disposed. */
class AppleFileInputSource : FileInputSource {
    private var owner: WeakReference<UIViewController>? = null
    private val inputs = FileInputRegistry<AppleFileInput>()

    fun attach(controller: UIViewController) {
        if (owner?.get() !== controller) {
            inputs.cancelSelections()
            owner = WeakReference(controller)
        }
    }

    override suspend fun select(requestId: String, mediaTypes: Set<String>): FileInputMetadata? =
        withContext(Dispatchers.Main.immediate) {
            validateFileMediaTypes(mediaTypes)
            val controller = owner?.get() ?: throw FileInputUnavailableException()
            if (controller.presentedViewController != null) throw FileInputBusyException()
            val ticket = inputs.reserve(requestId, currentCoroutineContext().job)
            var picker: UIDocumentPickerViewController? = null
            var delegate: AppleFilePickerDelegate? = null
            var input: AppleFileInput? = null
            var retained = false
            try {
                val types =
                    mediaTypes.map { type ->
                        when (type) {
                            "*/*" -> UTTypeData
                            "image/*" -> UTTypeImage
                            else ->
                                UTType.typeWithMIMEType(type)
                                    ?: throw FileInputUnavailableException()
                        }
                    }
                val dialog =
                    UIDocumentPickerViewController(forOpeningContentTypes = types, asCopy = false)
                picker = dialog
                dialog.allowsMultipleSelection = false
                val url =
                    suspendCancellableCoroutine<NSURL?> { continuation ->
                        delegate = AppleFilePickerDelegate(continuation)
                        dialog.delegate = delegate
                        controller.presentViewController(dialog, animated = true, completion = null)
                    } ?: return@withContext null
                val selected = AppleFileInput(url, url.startAccessingSecurityScopedResource())
                input = selected
                val metadata =
                    withContext(Dispatchers.IO) {
                        val path = (url.path ?: throw FileInputUnavailableException()).toPath()
                        val details = FileSystem.SYSTEM.metadata(path)
                        if (!details.isRegularFile) throw FileInputUnavailableException()
                        FileInputMetadata(
                            url.lastPathComponent,
                            url.pathExtension?.let {
                                UTType.typeWithFilenameExtension(it)?.preferredMIMEType
                            },
                            details.size,
                            details.lastModifiedAtMillis,
                        )
                    }
                inputs.publish(requestId, ticket, selected)
                retained = true
                metadata
            } finally {
                // UIKit's delegate is weak; this operation owns and clears the continuation.
                delegate?.clear()
                picker?.delegate = null
                picker?.dismissViewControllerAnimated(false, completion = null)
                if (!retained) {
                    input?.close()
                    inputs.discardSelection(requestId, ticket)
                }
            }
        }

    override fun read(requestId: String): Flow<ByteArray> =
        flow {
                coroutineScope {
                    val operation = currentCoroutineContext().job
                    val input = inputs.beginRead(requestId, operation)
                    // A reader has its own balanced access even if the selection is released
                    // meanwhile.
                    val scoped = input.url.startAccessingSecurityScopedResource()
                    try {
                        val path =
                            (input.url.path ?: throw FileInputUnavailableException()).toPath()
                        FileSystem.SYSTEM.openReadOnly(path).use { handle ->
                            val bytes = ByteArray(FILE_INPUT_BLOCK_BYTES)
                            var offset = 0L
                            var ended = false
                            while (!ended) {
                                var filled = 0
                                while (filled < bytes.size && !ended) {
                                    ensureActive()
                                    when (
                                        val count =
                                            handle.read(offset, bytes, filled, bytes.size - filled)
                                    ) {
                                        -1 -> ended = true
                                        0 -> throw IOException("File input did not advance.")
                                        else -> {
                                            filled += count
                                            offset += count
                                        }
                                    }
                                }
                                ensureActive()
                                if (filled > 0) emit(bytes.copyOf(filled))
                            }
                        }
                    } finally {
                        if (scoped) input.url.stopAccessingSecurityScopedResource()
                        inputs.endRead(requestId, operation)
                    }
                }
            }
            .flowOn(Dispatchers.IO)
            .buffer(0)

    override suspend fun release(requestId: String) {
        inputs.release(requestId)?.close()
    }

    override fun close() {
        owner = null
        inputs.close().forEach { it.close() }
    }
}

private class AppleFileInput(val url: NSURL, private val scoped: Boolean) {
    fun close() {
        if (scoped) url.stopAccessingSecurityScopedResource()
    }
}

private class AppleFilePickerDelegate(private var continuation: CancellableContinuation<NSURL?>?) :
    NSObject(), UIDocumentPickerDelegateProtocol {
    override fun documentPicker(
        controller: UIDocumentPickerViewController,
        didPickDocumentsAtURLs: List<*>,
    ) {
        val pending = continuation
        continuation = null
        if (pending?.isActive == true)
            pending.resume(didPickDocumentsAtURLs.singleOrNull() as? NSURL)
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
        val pending = continuation
        continuation = null
        if (pending?.isActive == true) pending.resume(null)
    }

    fun clear() {
        continuation = null
    }
}
