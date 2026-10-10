package dev.fajar.starter.storage

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.common.result.Failure
import dev.fajar.starter.common.result.FailureKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/** Wraps storage acquisition; downstream collector failures are never intercepted. */
fun <T> safeStorageFlow(
    source: Flow<T>,
    onException: (Exception) -> Unit = ::reportStorageException,
): Flow<AppResult<T>> = safeStorageFlow(source, onException, mapValue = { it })

/** A malformed record emits a Failure while later storage updates can still recover. */
fun <T, R> safeStorageFlow(
    source: Flow<T>,
    onException: (Exception) -> Unit = ::reportStorageException,
    mapValue: suspend (T) -> R,
): Flow<AppResult<R>> =
    source
        .map { value -> safeStorageCall(onException) { mapValue(value) } }
        .catch { error ->
            if (error is CancellationException || error !is Exception) throw error
            currentCoroutineContext().ensureActive()
            onException(error)
            emit(
                AppResult.Failed(
                    Failure(FailureKind.Storage, "Local data could not be read or saved.")
                )
            )
        }
