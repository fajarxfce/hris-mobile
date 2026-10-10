@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.fajar.starter.storage

import kotlin.js.JsException

internal actual fun storageExceptionOrNull(cause: Throwable): Exception? =
    when (cause) {
        is Exception -> cause
        is JsException -> BrowserStorageException(cause)
        // Some Wasm SDK Promise adapters (including WebCrypto) erase JS errors into
        // a bare Throwable. Keep Kotlin Error subclasses outside the Result boundary.
        else -> if (cause::class == Throwable::class) BrowserStorageException(cause) else null
    }

internal class BrowserStorageException(cause: Throwable) :
    Exception("Browser storage operation failed.", cause)
