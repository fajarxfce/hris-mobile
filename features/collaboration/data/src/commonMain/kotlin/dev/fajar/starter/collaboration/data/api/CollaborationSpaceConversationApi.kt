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
class CollaborationSpaceConversationApi(
    @Named(HttpClients.Authenticated) private val client: HttpClient
) {
    suspend fun list(
        sessionId: String,
        spaceId: String,
        after: String?,
        limit: Int,
    ): CollaborationSpaceConversationPageDto =
        client.requestJson(
            "collaboration/spaces/${Uuid.parse(spaceId)}/direct-conversations",
            maximumBytes = 65_536,
        ) {
            forSession(sessionId)
            parameter("limit", limit)
            after?.let { parameter("after", Uuid.parse(it).toString()) }
        }

    suspend fun get(
        sessionId: String,
        spaceId: String,
        conversationId: String,
    ): CollaborationSpaceConversationDto =
        client.requestJson(
            "collaboration/spaces/${Uuid.parse(spaceId)}/direct-conversations/${Uuid.parse(conversationId)}",
            maximumBytes = 4096,
        ) {
            forSession(sessionId)
        }

    suspend fun open(
        sessionId: String,
        spaceId: String,
        operation: String,
        input: CollaborationSpaceOpenRequestDto,
    ): CollaborationReceiptDto =
        client.requestJson(
            "collaboration/spaces/${Uuid.parse(spaceId)}/direct-conversations",
            HttpMethod.Post,
            maximumBytes = 1024,
        ) {
            forSession(sessionId)
            header("Idempotency-Key", Uuid.parse(operation).toString())
            contentType(ContentType.Application.Json)
            setBody(input)
        }
}
