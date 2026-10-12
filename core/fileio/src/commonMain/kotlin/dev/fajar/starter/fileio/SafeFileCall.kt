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
    mapFailure: (Exception) -> Failure = ::mapFileFailure,
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
        val error = fileOperationExceptionOrNull(cause) ?: throw cause
        onException(error)
        currentCoroutineContext().ensureActive()
        AppResult.Failed(mapFailure(error))
    }

/** The same exception boundary with output-specific failure codes. No retry or file cleanup. */
suspend fun <T> safeFileWriteCall(
    onException: (Exception) -> Unit = { Diagnostics.failure(DiagnosticArea.Storage, it) },
    operation: suspend () -> T,
): AppResult<T> = safeFileCall(onException, ::mapFileWriteFailure, operation)

fun mapFileWriteFailure(error: Exception): Failure {
    val mapped = mapFileFailure(error)
    return mapped.copy(
        message = "The file could not be saved.",
        code =
            when (mapped.code) {
                "client.file_read_failed" -> "client.file_write_failed"
                "client.file_input_failed" -> "client.file_output_failed"
                else -> mapped.code
            },
    )
}

fun mapFileFailure(error: Exception): Failure {
    val (kind, code) =
        when (error) {
            is FilePermissionException -> FailureKind.Permission to "client.file_permission"
            is FileUnavailableException -> FailureKind.Unavailable to "client.file_unavailable"
            is FileBusyException -> FailureKind.Conflict to "client.file_busy"
            is FileCapacityException -> FailureKind.Conflict to "client.file_input_capacity"
            is FileReleasedException -> FailureKind.Unavailable to "client.file_released"
            is FileChangedException -> FailureKind.Conflict to "client.file_changed"
            is FileExistsException -> FailureKind.Conflict to "client.file_exists"
            is FileIoException -> FailureKind.Storage to "client.file_read_failed"
            is IOException -> FailureKind.Storage to "client.file_read_failed"
            else -> FailureKind.Unexpected to "client.file_input_failed"
        }
    return Failure(kind, "The file could not be opened or read.", code = code)
}

/** Foreign SDK errors are normalized here; Kotlin fatal errors remain thrown. */
internal expect fun fileOperationExceptionOrNull(cause: Throwable): Exception?
