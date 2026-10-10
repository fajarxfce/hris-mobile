package dev.fajar.starter.storage

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.common.result.Failure
import dev.fajar.starter.common.result.FailureKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

suspend fun <T> safeStorageCall(
    onException: (Exception) -> Unit = ::reportStorageException,
    operation: suspend () -> T,
): AppResult<T> =
    try {
        val value = operation()
        currentCoroutineContext().ensureActive()
        AppResult.Success(value)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (exception: Exception) {
        currentCoroutineContext().ensureActive()
        onException(exception)
        AppResult.Failed(Failure(FailureKind.Storage, "Local data could not be read or saved."))
    }
