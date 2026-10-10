package dev.fajar.starter.featureflags.data.errors

import dev.fajar.starter.common.result.*
import dev.fajar.starter.observability.DiagnosticArea
import dev.fajar.starter.observability.Diagnostics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Fetch failures leave the last persisted snapshot intact; this boundary never retries. */
suspend fun <T> safeRemoteConfigCall(
    onException: (Exception) -> Unit = { Diagnostics.failure(DiagnosticArea.FeatureFlags, it) },
    operation: suspend () -> T,
): AppResult<T> =
    try {
        val value = operation()
        currentCoroutineContext().ensureActive()
        AppResult.Success(value)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        currentCoroutineContext().ensureActive()
        onException(error)
        AppResult.Failed(
            if (error is RemoteConfigUnavailableException)
                Failure(FailureKind.Unavailable, "Remote Config is not configured for this build.")
            else Failure(FailureKind.Service, "Feature flags could not be refreshed.")
        )
    }
