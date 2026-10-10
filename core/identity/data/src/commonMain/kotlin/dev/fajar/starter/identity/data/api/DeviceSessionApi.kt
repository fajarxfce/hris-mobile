@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.identity.data.api

import dev.fajar.starter.identity.data.dto.DeviceSessionDto
import dev.fajar.starter.network.*
import io.ktor.client.HttpClient
import io.ktor.http.HttpMethod
import kotlin.uuid.Uuid
import org.koin.core.annotation.*

@Single
class DeviceSessionApi(@Named(HttpClients.Authenticated) private val client: HttpClient) {
    suspend fun list(sessionId: String): List<DeviceSessionDto> =
        client.requestJson("auth/native/sessions", maximumBytes = 32_768) { forSession(sessionId) }

    suspend fun revoke(sessionId: String, remoteId: String): Unit =
        client.requestJson(
            "auth/native/sessions/${Uuid.parse(remoteId)}",
            HttpMethod.Delete,
            maximumBytes = 1,
        ) {
            forSession(sessionId)
        }
}
