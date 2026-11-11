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
class CollaborationSpaceOriginalConversationApi(
    @Named(HttpClients.Authenticated) private val client: HttpClient
) {
    suspend fun readPhysical(
        sessionId: String,
        spaceId: String,
        companyId: String,
        roomId: String,
    ): CollaborationSpaceOriginalConversationDto =
        client.requestJson(
            "collaboration/spaces/${Uuid.parse(spaceId)}/legacy-rooms/read",
            HttpMethod.Post,
            maximumBytes = 4096,
        ) {
            forSession(sessionId, AuthenticationReplay.ReadOnlyPost)
            contentType(ContentType.Application.Json)
            setBody(CollaborationSpaceOriginalRoomReadDto(Uuid.parse(companyId).toString(), roomId))
        }

    suspend fun list(
        sessionId: String,
        spaceId: String,
        after: CollaborationOriginalConversationPositionDto?,
        limit: Int,
    ): CollaborationSpaceOriginalConversationPageDto =
        client.requestJson(
            "collaboration/spaces/${Uuid.parse(spaceId)}/legacy-conversations",
            maximumBytes = 131_072,
        ) {
            forSession(sessionId)
            parameter("limit", limit)
            after?.let {
                parameter("afterCompanyId", Uuid.parse(it.companyId).toString())
                parameter("afterConversationId", Uuid.parse(it.conversationId).toString())
            }
        }

    suspend fun get(
        sessionId: String,
        spaceId: String,
        companyId: String,
        conversationId: String,
    ): CollaborationSpaceOriginalConversationDto =
        client.requestJson(
            "collaboration/spaces/${Uuid.parse(spaceId)}/legacy-conversations/${Uuid.parse(companyId)}/${Uuid.parse(conversationId)}",
            maximumBytes = 4096,
        ) {
            forSession(sessionId)
        }
}
