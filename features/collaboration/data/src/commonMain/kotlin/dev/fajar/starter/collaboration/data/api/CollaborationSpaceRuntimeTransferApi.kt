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
class CollaborationSpaceRuntimeTransferApi(
    @Named(HttpClients.Authenticated) private val client: HttpClient
) {
    suspend fun publication(
        sessionId: String,
        spaceId: String,
        input: CollaborationLegacyRuntimeRequestDto,
    ): CollaborationLegacyPublicationDto =
        client.requestJson(
            "collaboration/spaces/${Uuid.parse(spaceId)}/native-device/legacy-runtime/publication",
            HttpMethod.Post,
            maximumBytes = 1024,
        ) {
            forSession(sessionId, AuthenticationReplay.ReadOnlyPost)
            contentType(ContentType.Application.Json)
            setBody(input)
        }

    suspend fun freeze(
        sessionId: String,
        spaceId: String,
        operation: String,
        input: CollaborationLegacyRuntimeRequestDto,
    ): CollaborationReceiptDto =
        client.requestJson(
            "collaboration/spaces/${Uuid.parse(spaceId)}/native-device/legacy-runtime/freeze",
            HttpMethod.Post,
            maximumBytes = 1024,
        ) {
            forSession(sessionId)
            header("Idempotency-Key", Uuid.parse(operation).toString())
            contentType(ContentType.Application.Json)
            setBody(input)
        }

    suspend fun drain(
        sessionId: String,
        spaceId: String,
        input: CollaborationLegacyRuntimeRequestDto,
    ): CollaborationLegacyRuntimeDrainDto =
        client.requestJson(
            "collaboration/spaces/${Uuid.parse(spaceId)}/native-device/legacy-runtime/drain",
            maximumBytes = 1024,
        ) {
            forSession(sessionId)
            parameter("deviceId", input.deviceId)
            parameter("expectedDeviceVersion", input.expectedDeviceVersion)
            parameter("fingerprint", input.fingerprint)
        }
}
