@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.collaboration.data.api

import dev.fajar.starter.collaboration.data.dto.CollaborationReceiptDto
import dev.fajar.starter.collaboration.data.dto.CollaborationSpaceDeviceResponseDto
import dev.fajar.starter.collaboration.data.dto.CollaborationSpaceRegistrationRequestDto
import dev.fajar.starter.network.*
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.http.*
import kotlin.uuid.Uuid
import org.koin.core.annotation.*

@Single
class CollaborationSpaceDeviceApi(
    @Named(HttpClients.Authenticated) private val client: HttpClient
) {
    suspend fun register(
        sessionId: String,
        spaceId: String,
        operation: String,
        input: CollaborationSpaceRegistrationRequestDto,
    ): CollaborationReceiptDto =
        client.requestJson(
            "collaboration/spaces/${Uuid.parse(spaceId)}/native-device",
            HttpMethod.Post,
            maximumBytes = 1024,
        ) {
            forSession(sessionId)
            header("Idempotency-Key", Uuid.parse(operation).toString())
            contentType(ContentType.Application.Json)
            setBody(input)
        }

    suspend fun status(sessionId: String, spaceId: String): CollaborationSpaceDeviceResponseDto =
        client.requestJson(
            "collaboration/spaces/${Uuid.parse(spaceId)}/native-device",
            maximumBytes = 8192,
        ) {
            forSession(sessionId)
        }
}
