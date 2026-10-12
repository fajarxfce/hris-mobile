@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.fajar.starter.fileio

import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import platform.Foundation.NSURL
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.darwin.NSObject

internal class AppleFilePickerDelegate(private var continuation: CancellableContinuation<NSURL?>?) :
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
