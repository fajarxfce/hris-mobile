package dev.fajar.starter.location.data.errors

import dev.fajar.starter.common.result.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class LocationUnavailableException : Exception()

class LocationPermissionException(cause: Throwable? = null) : Exception(cause)

class LocationBusyException : Exception()

suspend fun <T> safeLocationCall(
    onException: (Exception) -> Unit = ::reportLocationException,
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
            when (error) {
                is LocationPermissionException ->
                    Failure(
                        FailureKind.Permission,
                        "Location permission is required.",
                        code = "client.location_permission",
                    )
                is LocationBusyException ->
                    Failure(
                        FailureKind.Conflict,
                        "A location operation is active.",
                        code = "client.location_busy",
                    )
                else ->
                    Failure(
                        FailureKind.Unavailable,
                        "Device location is unavailable.",
                        code = "client.location_unavailable",
                    )
            }
        )
    }
