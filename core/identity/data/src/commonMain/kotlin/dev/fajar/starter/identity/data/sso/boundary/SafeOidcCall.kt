package dev.fajar.starter.identity.data.sso.boundary

import dev.fajar.starter.common.result.*
import dev.fajar.starter.network.mapHttpFailure
import dev.fajar.starter.observability.*
import kotlinx.coroutines.*

/**
 * Unwraps technical SDK wrappers, preserves cancellation, and never exposes provider error text.
 */
suspend fun <T> safeOidcCall(operation: suspend () -> T): AppResult<T> =
    try {
        currentCoroutineContext().ensureActive()
        val value = operation()
        currentCoroutineContext().ensureActive()
        AppResult.Success(value)
    } catch (error: Exception) {
        val causes = generateSequence<Throwable>(error) { it.cause }.take(16).toList()
        causes.filterIsInstance<CancellationException>().firstOrNull()?.let { throw it }
        currentCoroutineContext().ensureActive()
        if (error !is BrowserAuthorizationCancelled)
            Diagnostics.failure(DiagnosticArea.Application, error)
        val failure =
            when (error) {
                is BrowserAuthorizationCancelled ->
                    Failure(FailureKind.Cancelled, "Sign-in was cancelled.")
                is OidcProtocolException ->
                    Failure(FailureKind.Unauthorized, "The sign-in response was not accepted.")
                is UnsupportedOperationException ->
                    Failure(FailureKind.Unavailable, "A supported browser is not available.")
                else -> mapHttpFailure(causes.filterIsInstance<Exception>().last())
            }
        AppResult.Failed(failure)
    }
