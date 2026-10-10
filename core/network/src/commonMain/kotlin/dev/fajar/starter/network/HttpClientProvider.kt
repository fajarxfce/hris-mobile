package dev.fajar.starter.network

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

fun createHttpClient(engine: HttpClientEngine, baseUrl: String): HttpClient =
    createHttpClient(engine, HttpClientSettings(baseUrl))

fun createHttpClient(
    engine: HttpClientEngine,
    settings: HttpClientSettings,
    configure: HttpClientConfig<*>.() -> Unit = {},
): HttpClient =
    HttpClient(engine) {
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
