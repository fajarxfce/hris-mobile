@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.collaboration.data.api

import dev.fajar.starter.collaboration.data.dto.*
import dev.fajar.starter.network.*
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.http.*
import kotlin.uuid.Uuid
import org.koin.core.annotation.*

@Single
class CollaborationSpaceSetupApi(@Named(HttpClients.Authenticated) private val client: HttpClient) {
    suspend fun retry(
        sessionId: String,
        spaceId: String,
        operation: String,
        input: CollaborationSpaceRetryRequestDto,
    ): CollaborationReceiptDto =
        client.requestJson(
            "collaboration/spaces/${Uuid.parse(spaceId)}/native-device/retry",
            HttpMethod.Post,
            maximumBytes = 1024,
        ) {
            forSession(sessionId)
            header("Idempotency-Key", Uuid.parse(operation).toString())
            contentType(ContentType.Application.Json)
            setBody(input)
        }

    suspend fun bootstrap(
        sessionId: String,
        spaceId: String,
        operation: String,
        input: CollaborationSpaceBootstrapRequestDto,
    ): CollaborationSpaceGatewayDto =
        client.requestJson(
            "collaboration/spaces/${Uuid.parse(spaceId)}/native-device/bootstrap",
            HttpMethod.Post,
            maximumBytes = 4096,
        ) {
            forSession(sessionId)
            header("Idempotency-Key", Uuid.parse(operation).toString())
            contentType(ContentType.Application.Json)
            setBody(input)
        }

    suspend fun replaceBootstrap(
        sessionId: String,
        spaceId: String,
        operation: String,
        input: CollaborationSpaceBootstrapReplacementRequestDto,
    ): CollaborationSpaceBootstrapReplacementDto =
        client.requestJson(
            "collaboration/spaces/${Uuid.parse(spaceId)}/native-device/bootstrap/replacements",
            HttpMethod.Post,
            maximumBytes = 4096,
        ) {
            forSession(sessionId)
            header("Idempotency-Key", Uuid.parse(operation).toString())
            contentType(ContentType.Application.Json)
            setBody(input)
        }
}
