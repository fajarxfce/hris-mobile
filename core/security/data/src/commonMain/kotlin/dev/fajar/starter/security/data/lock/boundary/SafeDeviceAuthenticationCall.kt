package dev.fajar.starter.security.data.lock.boundary

import dev.fajar.starter.common.result.*
import dev.fajar.starter.observability.*
import kotlinx.coroutines.*

/** OS errors remain internal. A cancelled caller never publishes an unlock result. */
suspend fun <T> safeDeviceAuthenticationCall(operation: suspend () -> T): AppResult<T> =
    try {
        val value = operation()
        currentCoroutineContext().ensureActive()
        AppResult.Success(value)
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        currentCoroutineContext().ensureActive()
        Diagnostics.failure(DiagnosticArea.Application, error)
        AppResult.Failed(Failure(FailureKind.Unavailable, "Device authentication is unavailable."))
    }
