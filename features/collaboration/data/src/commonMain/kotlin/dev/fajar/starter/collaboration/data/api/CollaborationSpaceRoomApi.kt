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
class CollaborationSpaceRoomApi(@Named(HttpClients.Authenticated) private val client: HttpClient) {
    suspend fun readPhysical(
        sessionId: String,
        spaceId: String,
        roomId: String,
    ): CollaborationSpaceRoomViewDto =
        client.requestJson(
            "collaboration/spaces/${Uuid.parse(spaceId)}/rooms/read",
            HttpMethod.Post,
            maximumBytes = 8192,
        ) {
            forSession(sessionId, AuthenticationReplay.ReadOnlyPost)
            contentType(ContentType.Application.Json)
            setBody(CollaborationSpacePhysicalRoomReadDto(roomId))
        }

    suspend fun fenceRequest(
        sessionId: String,
        spaceId: String,
        conversationId: String,
        original: CollaborationSpaceRoomRequestFenceDto,
    ): CollaborationSpaceRoomRequestResolutionDto =
        client.requestJson(
            "collaboration/spaces/${Uuid.parse(spaceId)}/direct-conversations/${Uuid.parse(conversationId)}/room/requests/fence",
            HttpMethod.Post,
            maximumBytes = 4096,
        ) {
            forSession(sessionId)
            header("Idempotency-Key", Uuid.parse(original.operationId).toString())
            contentType(ContentType.Application.Json)
            setBody(original)
        }

    suspend fun readIntentReceipt(
        sessionId: String,
        spaceId: String,
        conversationId: String,
        original: CollaborationSpaceRoomIntentReceiptReadDto,
    ): CollaborationSpaceRoomIntentReceiptObservationDto =
        client.requestJson(
            "collaboration/spaces/${Uuid.parse(spaceId)}/direct-conversations/${Uuid.parse(conversationId)}/room/requests/receipts/read",
            HttpMethod.Post,
            maximumBytes = 4096,
        ) {
            forSession(sessionId, AuthenticationReplay.ReadOnlyPost)
            contentType(ContentType.Application.Json)
            setBody(original)
        }

    suspend fun readReceipt(
        sessionId: String,
        spaceId: String,
        conversationId: String,
        original: CollaborationSpaceRoomReceiptReadDto,
    ): CollaborationSpaceRoomReceiptObservationDto =
        client.requestJson(
            "collaboration/spaces/${Uuid.parse(spaceId)}/direct-conversations/${Uuid.parse(conversationId)}/room/receipts/read",
            HttpMethod.Post,
            maximumBytes = 4096,
        ) {
            forSession(sessionId, AuthenticationReplay.ReadOnlyPost)
            contentType(ContentType.Application.Json)
            setBody(original)
        }

    suspend fun get(
        sessionId: String,
        spaceId: String,
        conversationId: String,
    ): CollaborationSpaceRoomViewDto =
        client.requestJson(
            "collaboration/spaces/${Uuid.parse(spaceId)}/direct-conversations/${Uuid.parse(conversationId)}/room",
            maximumBytes = 8192,
        ) {
            forSession(sessionId)
        }

    suspend fun request(
        sessionId: String,
        spaceId: String,
        conversationId: String,
        operation: String,
        input: CollaborationSpaceRoomRequestDto,
    ): CollaborationReceiptDto =
        client.requestJson(
            "collaboration/spaces/${Uuid.parse(spaceId)}/direct-conversations/${Uuid.parse(conversationId)}/room/requests",
            HttpMethod.Post,
            maximumBytes = 1024,
        ) {
            forSession(sessionId)
            header("Idempotency-Key", Uuid.parse(operation).toString())
            contentType(ContentType.Application.Json)
            setBody(input)
        }
}
