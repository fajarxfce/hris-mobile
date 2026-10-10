package dev.fajar.starter.network

import dev.fajar.starter.observability.*
import io.ktor.client.plugins.api.*
import io.ktor.http.Url
import kotlinx.coroutines.currentCoroutineContext

class HttpDiagnosticsConfig {
    var origin: Url? = null
}

/**
 * Only configured first-party requests receive correlation; never forward baggage or tracestate.
 */
val HttpDiagnostics =
    createClientPlugin("HttpDiagnostics", ::HttpDiagnosticsConfig) {
        val origin = pluginConfig.origin
        on(Send) { request ->
            measureOperation(
                PerformanceOperation.HttpRequest,
                classify = {
                    if (it.response.status.value < 400) PerformanceOutcome.Succeeded
                    else PerformanceOutcome.Failed
                },
                httpStatus = { it.response.status.value },
            ) {
                request.headers.remove("traceparent")
                request.headers.remove("tracestate")
                request.headers.remove("baggage")
                val target = request.url.build()
                if (
                    origin != null &&
                        target.protocol == origin.protocol &&
                        target.host == origin.host &&
                        target.port == origin.port
                ) {
                    request.headers.append(
                        "traceparent",
                        checkNotNull(currentCoroutineContext()[OperationTrace]).traceparent,
                    )
                }
                proceed(request)
            }
        }
    }
