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
    } catch (exception: Exception) {
        currentCoroutineContext().ensureActive()
        onException(exception)
        AppResult.Failed(mapHttpFailure(exception))
    }

fun mapHttpFailure(exception: Exception): Failure =
    when (exception) {
        is HttpRequestTimeoutException,
        is ConnectTimeoutException,
        is SocketTimeoutException ->
            Failure(FailureKind.Timeout, "The request timed out. Try again.")
        is ResponseException ->
            when (exception.response.status.value) {
                400,
                422 -> Failure(FailureKind.Validation, "Check the information and try again.")
                401,
                403 ->
                    Failure(
                        FailureKind.Unauthorized,
                        "Authentication was not accepted. Check your details.",
                    )
                429 -> Failure(FailureKind.Service, "Too many requests. Try again shortly.")
                in 500..599 ->
                    Failure(FailureKind.Service, "The service is unavailable. Try again.")
                else -> Failure(FailureKind.Unexpected, "The request could not be completed.")
            }
        is SerializationException ->
            Failure(FailureKind.Unexpected, "The response could not be read.")
        is IOException -> Failure(FailureKind.Network, "Unable to connect. Check your connection.")
        else -> Failure(FailureKind.Unexpected, "Something went wrong. Try again.")
    }
