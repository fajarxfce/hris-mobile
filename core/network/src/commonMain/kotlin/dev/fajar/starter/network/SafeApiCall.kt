package dev.fajar.starter.network

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.common.result.Failure
import dev.fajar.starter.common.result.FailureKind
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.ResponseException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.io.IOException
import kotlinx.serialization.SerializationException

suspend fun <T> safeApiCall(
    onException: (Exception) -> Unit = ::reportNetworkException,
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
        val exception = networkExceptionOrNull(cause) ?: throw cause
        onException(exception)
        val failure = readApiFailure(exception)
        currentCoroutineContext().ensureActive()
        AppResult.Failed(failure)
    }

fun mapHttpFailure(exception: Exception): Failure =
    when (exception) {
        is RequestFailureException -> exception.failure
        is HttpRequestTimeoutException,
        is ConnectTimeoutException,
        is SocketTimeoutException ->
            Failure(FailureKind.Timeout, "The request timed out. Try again.")
        is ResponseException -> mapHttpStatusFailure(exception.response.status.value)
        is HttpStatusException -> mapHttpStatusFailure(exception.statusCode)
        is SerializationException ->
            Failure(FailureKind.Unexpected, "The response could not be read.")
        is IOException -> Failure(FailureKind.Network, "Unable to connect. Check your connection.")
        else -> Failure(FailureKind.Unexpected, "Something went wrong. Try again.")
    }
