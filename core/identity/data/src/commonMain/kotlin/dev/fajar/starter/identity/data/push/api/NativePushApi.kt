package dev.fajar.starter.identity.data.push.api

import dev.fajar.starter.identity.data.push.dto.*
import dev.fajar.starter.network.*
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.http.*
import org.koin.core.annotation.*

@Single
class NativePushApi(@Named(HttpClients.Authenticated) private val client: HttpClient) {
    suspend fun read(sessionId: String): NativePushRegistrationDto =
        client.requestJson("auth/native/push-registration", maximumBytes = 4096) {
            forSession(sessionId)
        }

    suspend fun save(
        sessionId: String,
        operationId: String,
        input: SaveNativePushRequest,
    ): NativePushReceiptDto =
        client.requestJson("auth/native/push-registration", HttpMethod.Put, maximumBytes = 4096) {
            forSession(sessionId)
            header("Idempotency-Key", operationId)
            contentType(ContentType.Application.Json)
            setBody(input)
        }

    suspend fun disable(
        sessionId: String,
        operationId: String,
        input: DisableNativePushRequest,
    ): NativePushReceiptDto =
        client.requestJson(
            "auth/native/push-registration/disable",
            HttpMethod.Post,
            maximumBytes = 4096,
        ) {
            forSession(sessionId)
            header("Idempotency-Key", operationId)
            contentType(ContentType.Application.Json)
            setBody(input)
        }
}
