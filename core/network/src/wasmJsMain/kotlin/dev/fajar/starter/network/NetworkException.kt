package dev.fajar.starter.network

import io.ktor.client.engine.js.JsError
import kotlinx.io.IOException

internal actual fun networkExceptionOrNull(cause: Throwable): Exception? {
    // Ktor Fetch wraps rejection in Error(cause = JsError), and stream reads may throw JsError.
    var current: Throwable? = cause
    repeat(16) {
        if (current is JsError) return IOException("Browser network request failed.", cause)
        current = current?.cause ?: return cause as? Exception
    }
    return cause as? Exception
}
