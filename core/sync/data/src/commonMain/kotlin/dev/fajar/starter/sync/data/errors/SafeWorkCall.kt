package dev.fajar.starter.sync.data.errors

import dev.fajar.starter.common.result.*
import dev.fajar.starter.observability.DiagnosticArea
import dev.fajar.starter.observability.Diagnostics
import kotlinx.coroutines.*

suspend fun <T> safeWorkCall(
    onException: (Exception) -> Unit = { Diagnostics.failure(DiagnosticArea.Worker, it) },
    operation: suspend () -> T,
): AppResult<T> =
    try {
        currentCoroutineContext().ensureActive()
        val value = operation()
        currentCoroutineContext().ensureActive()
        AppResult.Success(value)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        currentCoroutineContext().ensureActive()
        onException(error)
        AppResult.Failed(
            Failure(FailureKind.Unavailable, "Sync could not be scheduled. Try again.")
        )
    }
