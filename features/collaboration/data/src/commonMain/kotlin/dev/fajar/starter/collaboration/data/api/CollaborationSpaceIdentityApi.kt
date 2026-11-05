@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.collaboration.data.api

import dev.fajar.starter.collaboration.data.dto.CollaborationReceiptDto
import dev.fajar.starter.collaboration.data.dto.CollaborationSpaceIdentityResponseDto
import dev.fajar.starter.network.*
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.http.HttpMethod
import kotlin.uuid.Uuid
import org.koin.core.annotation.*

@Single
class CollaborationSpaceIdentityApi(
    @Named(HttpClients.Authenticated) private val client: HttpClient
) {
    suspend fun read(sessionId: String, spaceId: String): CollaborationSpaceIdentityResponseDto =
        client.requestJson(
            "collaboration/spaces/${Uuid.parse(spaceId)}/identity",
            maximumBytes = 4096,
        ) {
            forSession(sessionId)
        }

    suspend fun select(
        sessionId: String,
        spaceId: String,
        operation: String,
    ): CollaborationReceiptDto =
        client.requestJson(
            "collaboration/spaces/${Uuid.parse(spaceId)}/identity",
            HttpMethod.Post,
            maximumBytes = 1024,
        ) {
            forSession(sessionId)
            header("Idempotency-Key", Uuid.parse(operation).toString())
        }
}
