@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.collaboration.data.api

import dev.fajar.starter.collaboration.data.dto.CollaborationSpaceDeviceResponseDto
import dev.fajar.starter.network.*
import io.ktor.client.HttpClient
import kotlin.uuid.Uuid
import org.koin.core.annotation.*

@Single
class CollaborationSpaceDeviceApi(
    @Named(HttpClients.Authenticated) private val client: HttpClient
) {
    suspend fun status(sessionId: String, spaceId: String): CollaborationSpaceDeviceResponseDto =
        client.requestJson(
            "collaboration/spaces/${Uuid.parse(spaceId)}/native-device",
            maximumBytes = 8192,
        ) {
            forSession(sessionId)
        }
}
