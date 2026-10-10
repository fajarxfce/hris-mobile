package dev.fajar.starter.network

import dev.fajar.starter.common.result.*
import io.ktor.client.plugins.api.*
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import io.ktor.util.AttributeKey
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

val RequestSession = AttributeKey<String>("request-session")

/** Set the originating session on account-owned API calls. Never derive it from the current UI. */
fun HttpRequestBuilder.forSession(id: String) {
    attributes.put(RequestSession, id)
}

class SessionAuthenticationConfig {
    lateinit var origin: Url
    lateinit var acquire: suspend (sessionId: String, rejectedToken: String?) -> AppResult<String?>
}

class RequestFailureException(val failure: Failure) : Exception("Request admission failed.")

/** Transport adapter only. The supplied use case owns refresh, expiry, and session invalidation. */
val SessionAuthentication =
    createClientPlugin("SessionAuthentication", ::SessionAuthenticationConfig) {
        val origin = pluginConfig.origin
        val acquire = pluginConfig.acquire
        on(Send) { request ->
            val target = request.url.build()
            require(
                target.protocol == origin.protocol &&
                    target.host == origin.host &&
                    target.port == origin.port
            ) {
                "Authenticated requests must use their configured API origin."
            }
            val sessionId =
                requireNotNull(request.attributes.getOrNull(RequestSession)) {
                    "An account request requires its originating session."
                }
            val token =
                when (val result = acquire(sessionId, null)) {
                    is AppResult.Failed -> throw RequestFailureException(result.failure)
                    is AppResult.Success -> result.value
                }
                    ?: throw RequestFailureException(
                        Failure(FailureKind.Unauthorized, "Sign in to continue.")
                    )
            currentCoroutineContext().ensureActive()
            request.headers.remove(HttpHeaders.Authorization)
            request.bearerAuth(token)
            val call = proceed(request)
            if (call.response.status != HttpStatusCode.Unauthorized) return@on call
            val refreshed =
                when (val result = acquire(sessionId, token)) {
                    is AppResult.Failed -> throw RequestFailureException(result.failure)
                    is AppResult.Success -> result.value
                } ?: return@on call
            val repeatable =
                request.body is OutgoingContent.ByteArrayContent ||
                    request.body is OutgoingContent.NoContent
            val idempotent =
                request.method in
                    listOf(
                        HttpMethod.Get,
                        HttpMethod.Head,
                        HttpMethod.Options,
                        HttpMethod.Put,
                        HttpMethod.Delete,
                    ) || !request.headers["Idempotency-Key"].isNullOrBlank()
            if (!repeatable || !idempotent) return@on call
            currentCoroutineContext().ensureActive()
            request.headers.remove(HttpHeaders.Authorization)
            request.bearerAuth(refreshed)
            call.response.bodyAsChannel().cancel(null)
            // Reuse the original execution context so cancellation also stops the replay.
            proceed(request)
        }
    }
