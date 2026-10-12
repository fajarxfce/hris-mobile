package dev.fajar.starter.network

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.io.readByteArray

/** Checks a streaming response and retains a bounded API problem on HTTP rejection. */
suspend fun requireApiStatus(response: HttpResponse, vararg accepted: HttpStatusCode) {
    if (response.status in accepted) return
    if (response.status.value !in 200..299) readApiBody(response, 0)
    error("Unexpected HTTP response status.")
}

@PublishedApi
internal suspend fun readApiBody(response: HttpResponse, maximumBytes: Int): ByteArray {
    val succeeded = response.status.value in 200..299
    val limit = if (succeeded) maximumBytes else 16_384
    val declared = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
    val bytes =
        try {
            if (declared != null && declared > limit) null
            else {
                val channel = response.bodyAsChannel()
                val content = channel.readRemaining(limit.toLong() + 1).readByteArray()
                try {
                    channel.closedCause?.let { throw it }
                    currentCoroutineContext().ensureActive()
                    if (content.size <= limit) content
                    else {
                        content.fill(0)
                        null
                    }
                } catch (cause: Throwable) {
                    content.fill(0)
                    throw cause
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (cause: Throwable) {
            currentCoroutineContext().ensureActive()
            val exception = networkExceptionOrNull(cause) ?: throw cause
            if (succeeded) throw exception
            throw ApiResponseException(
                response.status.value,
                null,
                response.headers[HttpHeaders.RetryAfter],
                response.headers["X-Request-ID"],
                exception,
            )
        }
    if (!succeeded) {
        val problem =
            try {
                bytes?.decodeToString()
            } finally {
                bytes?.fill(0)
            }
        throw ApiResponseException(
            response.status.value,
            problem,
            response.headers[HttpHeaders.RetryAfter],
            response.headers["X-Request-ID"],
        )
    }
    return requireNotNull(bytes) { "Response size exceeds its limit." }
}
