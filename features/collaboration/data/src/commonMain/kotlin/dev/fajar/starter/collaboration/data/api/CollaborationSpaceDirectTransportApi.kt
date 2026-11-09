@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.collaboration.data.api

import dev.fajar.starter.collaboration.data.dto.CollaborationSpaceDirectTransportDto
import dev.fajar.starter.network.*
import io.ktor.client.HttpClient
import kotlin.uuid.Uuid
import org.koin.core.annotation.*

@Single
class CollaborationSpaceDirectTransportApi(
    @Named(HttpClients.Authenticated) private val client: HttpClient
) {
    suspend fun read(
        sessionId: String,
        spaceId: String,
        conversationId: String,
    ): CollaborationSpaceDirectTransportDto =
        client.requestJson(
            "collaboration/spaces/${Uuid.parse(spaceId)}/direct-conversations/${Uuid.parse(conversationId)}/transport",
            maximumBytes = 8192,
        ) {
            forSession(sessionId)
        }
}
