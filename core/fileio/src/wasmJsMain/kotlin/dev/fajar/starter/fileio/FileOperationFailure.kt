@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.fajar.starter.fileio

import kotlin.js.JsException

internal actual fun fileOperationExceptionOrNull(cause: Throwable): Exception? =
    when (cause) {
        is Exception -> cause
        is JsException ->
            when (browserFileErrorName(cause.thrownValue)) {
                "NotAllowedError",
                "SecurityError" -> FilePermissionException(cause)
                "NotFoundError" -> FileUnavailableException(cause)
                "InvalidStateError",
                "AbortError" -> FileReleasedException(cause)
                "InvalidModificationError" -> FileChangedException(cause)
                "QuotaExceededError",
                "NotReadableError" -> FileIoException(cause)
                else -> BrowserFileOperationException(cause)
            }
        else -> if (cause::class == Throwable::class) BrowserFileOperationException(cause) else null
    }

private class BrowserFileOperationException(cause: Throwable) :
    Exception("Browser file operation failed.", cause)

@JsFun("(value) => typeof value?.name === 'string' ? value.name : ''")
private external fun browserFileErrorName(value: JsAny?): String
