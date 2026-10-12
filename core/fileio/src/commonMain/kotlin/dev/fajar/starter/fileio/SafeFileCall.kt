package dev.fajar.starter.fileio

import dev.fajar.starter.common.result.*
import dev.fajar.starter.observability.DiagnosticArea
import dev.fajar.starter.observability.Diagnostics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okio.IOException

/** Repository boundary only. The callback includes raw acquisition and DTO mapping. */
suspend fun <T> safeFileCall(
    onException: (Exception) -> Unit = { Diagnostics.failure(DiagnosticArea.Storage, it) },
    operation: suspend () -> T,
): AppResult<T> =
    try {
        val value = operation()
        currentCoroutineContext().ensureActive()
        AppResult.Success(value)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (cause: Throwable) {
        currentCoroutineContext().ensureActive()
        val error = fileInputExceptionOrNull(cause) ?: throw cause
        onException(error)
        currentCoroutineContext().ensureActive()
        AppResult.Failed(mapFileFailure(error))
    }

fun mapFileFailure(error: Exception): Failure {
    val (kind, code) =
        when (error) {
            is FileInputPermissionException -> FailureKind.Permission to "client.file_permission"
            is FileInputUnavailableException -> FailureKind.Unavailable to "client.file_unavailable"
            is FileInputBusyException -> FailureKind.Conflict to "client.file_busy"
            is FileInputCapacityException -> FailureKind.Conflict to "client.file_input_capacity"
            is FileInputReleasedException -> FailureKind.Unavailable to "client.file_released"
            is FileInputChangedException -> FailureKind.Conflict to "client.file_changed"
            is IOException -> FailureKind.Storage to "client.file_read_failed"
            else -> FailureKind.Unexpected to "client.file_input_failed"
        }
    return Failure(kind, "The file could not be opened or read.", code = code)
}

/** Foreign SDK errors are normalized here; Kotlin fatal errors remain thrown. */
internal expect fun fileInputExceptionOrNull(cause: Throwable): Exception?
