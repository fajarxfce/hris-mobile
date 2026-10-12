package dev.fajar.starter.network

import io.ktor.client.HttpClient
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.url
import io.ktor.http.*
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Acquires bounded JSON through Ktor's streaming API, before eager response caching. */
suspend inline fun <reified T> HttpClient.requestJson(
    path: String,
    method: HttpMethod = HttpMethod.Get,
    maximumBytes: Int = 1_048_576,
    crossinline configure: HttpRequestBuilder.() -> Unit = {},
): T {
    require(maximumBytes in 1..16_777_216)
    return prepareRequest {
            url(path)
            this.method = method
            configure()
            expectSuccess = false
        }
        .execute { response ->
            val bytes = readApiBody(response, maximumBytes)
            if (T::class == Unit::class) {
                require(bytes.isEmpty()) { "Unexpected response representation." }
                @Suppress("UNCHECKED_CAST")
                Unit as T
            } else {
                val media = response.contentType()
                require(
                    media?.contentType == "application" &&
                        (media.contentSubtype == "json" || media.contentSubtype.endsWith("+json"))
                ) {
                    "A JSON response is required."
                }
                val text =
                    try {
                        bytes.decodeToString(throwOnInvalidSequence = true)
                    } catch (exception: Exception) {
                        throw SerializationException("Invalid response encoding.", exception)
                    }
                require(hasBoundedJsonDepth(text)) { "Response nesting exceeds its limit." }
                apiJson.decodeFromString<T>(text)
            }
        }
}

@PublishedApi internal val apiJson = Json { ignoreUnknownKeys = true }

/** Bounds parser stack use before decoding untrusted JSON, including ignored properties. */
fun hasBoundedJsonDepth(text: String, maximumDepth: Int = 64): Boolean {
    var depth = 0
    var quoted = false
    var escaped = false
    for (character in text) {
        if (quoted) {
            when {
                escaped -> escaped = false
                character == '\\' -> escaped = true
                character == '"' -> quoted = false
            }
        } else {
            when (character) {
                '"' -> quoted = true
                '{',
                '[' -> {
                    depth++
                    if (depth > maximumDepth) return false
                }
                '}',
                ']' -> {
                    depth--
                    if (depth < 0) return false
                }
            }
        }
    }
    return !quoted && depth == 0
}
