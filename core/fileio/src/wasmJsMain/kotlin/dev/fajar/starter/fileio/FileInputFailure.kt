@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.fajar.starter.fileio

import kotlin.js.JsException

internal actual fun fileInputExceptionOrNull(cause: Throwable): Exception? =
    when (cause) {
        is Exception -> cause
        is JsException -> BrowserFileInputException(cause)
        else -> if (cause::class == Throwable::class) BrowserFileInputException(cause) else null
    }

private class BrowserFileInputException(cause: Throwable) :
    Exception("Browser file input failed.", cause)
