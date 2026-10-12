package dev.fajar.starter.network

import io.ktor.client.HttpClient
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.http.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Reads one exact range. The API caller supplies its supported conditional request header. */
suspend fun HttpClient.requestBinaryRange(
    path: String,
    entityTag: String,
    offset: Long,
    totalBytes: Long,
    maximumBytes: Int = 1_048_576,
    configure: HttpRequestBuilder.() -> Unit = {},
): ByteArray {
    require(maximumBytes in 1..16_777_216)
    require(totalBytes > 0 && offset in 0 until totalBytes)
    require(
        entityTag.length in 2..258 &&
            entityTag.first() == '"' &&
            entityTag.last() == '"' &&
            entityTag.substring(1, entityTag.lastIndex).all { it.code == 33 || it.code in 35..126 }
    ) {
        "A bounded strong entity tag is required."
    }
    val expected = minOf(maximumBytes.toLong(), totalBytes - offset).toInt()
    val end = offset + expected - 1
    return prepareGet(path) {
            configure()
            expectSuccess = false
            header(HttpHeaders.Range, "bytes=$offset-$end")
            header(HttpHeaders.AcceptEncoding, "identity")
        }
        .execute { response ->
            requireApiStatus(response, HttpStatusCode.PartialContent)
            require(response.headers.getAll(HttpHeaders.ETag)?.singleOrNull() == entityTag)
            require(
                response.headers.getAll(HttpHeaders.ContentRange)?.singleOrNull() ==
                    "bytes $offset-$end/$totalBytes"
            )
            val encoding = response.headers.getAll(HttpHeaders.ContentEncoding)
            require(encoding == null || encoding.singleOrNull()?.equals("identity", true) == true)
            val length = response.headers.getAll(HttpHeaders.ContentLength)
            require(length == null || length.singleOrNull()?.toLongOrNull() == expected.toLong())
            val bytes = readApiBody(response, expected)
            try {
                require(bytes.size == expected) { "Response extent does not match its range." }
                currentCoroutineContext().ensureActive()
                bytes
            } catch (cause: Throwable) {
                bytes.fill(0)
                throw cause
            }
        }
}
