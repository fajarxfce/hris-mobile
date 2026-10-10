package dev.fajar.starter.network

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json

/** Transfers a dedicated, preconfigured engine to the returned client. Do not share it. */
fun createHttpClient(engine: HttpClientEngine, baseUrl: String): HttpClient =
    createHttpClient(engine, HttpClientSettings(baseUrl))

fun createHttpClient(
    engine: HttpClientEngine,
    settings: HttpClientSettings,
    configure: HttpClientConfig<*>.() -> Unit = {},
): HttpClient {
    // Ktor's instance constructor deliberately does not close its engine. The factory
    // constructor gives the client ownership. Hosts cancel pending calls on disposal.
    val ownedEngine =
        object : HttpClientEngineFactory<HttpClientEngineConfig> {
            override fun create(block: HttpClientEngineConfig.() -> Unit): HttpClientEngine =
                engine.apply { config.apply(block) }
        }
    return try {
        HttpClient(ownedEngine) {
            install(HttpDiagnostics) { origin = io.ktor.http.Url(settings.baseUrl) }
            expectSuccess = true
            followRedirects = false
            defaultRequest { url(settings.baseUrl) }
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            install(HttpTimeout) {
                requestTimeoutMillis = settings.requestTimeoutMillis
                connectTimeoutMillis = settings.connectTimeoutMillis
                socketTimeoutMillis = settings.socketTimeoutMillis
            }
            configure()
        }
    } catch (failure: Throwable) {
        try {
            engine.close()
        } catch (closing: Throwable) {
            failure.addSuppressed(closing)
        }
        throw failure
    }
}

/** Dispose a container-owned client immediately; close() alone permits calls to finish. */
fun HttpClient.cancelAndClose() {
    coroutineContext.cancel()
    close()
}
