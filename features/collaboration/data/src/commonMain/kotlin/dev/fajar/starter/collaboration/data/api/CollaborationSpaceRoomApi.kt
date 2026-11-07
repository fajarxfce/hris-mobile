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
            "collaboration/spaces/${Uuid.parse(spaceId)}/direct-conversations/${Uuid.parse(conversationId)}/room",
            HttpMethod.Post,
            maximumBytes = 1024,
        ) {
            forSession(sessionId)
            header("Idempotency-Key", Uuid.parse(operation).toString())
            contentType(ContentType.Application.Json)
            setBody(input)
        }
}
