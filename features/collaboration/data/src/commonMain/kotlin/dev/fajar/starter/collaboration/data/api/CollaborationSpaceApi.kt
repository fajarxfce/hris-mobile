@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.collaboration.data.api

import dev.fajar.starter.collaboration.data.dto.*
import dev.fajar.starter.network.*
import io.ktor.client.HttpClient
import io.ktor.client.request.parameter
import kotlin.uuid.Uuid
import org.koin.core.annotation.*

@Single
class CollaborationSpaceApi(@Named(HttpClients.Authenticated) private val client: HttpClient) {
    suspend fun current(sessionId: String): CollaborationSpaceObservationDto =
        client.requestJson("collaboration/spaces/current", maximumBytes = 4096) {
            forSession(sessionId)
        }

    suspend fun contacts(
        sessionId: String,
        spaceId: String,
        query: String,
        after: String?,
        limit: Int,
    ): CollaborationSpaceContactPageDto =
        client.requestJson(
            "collaboration/spaces/${Uuid.parse(spaceId)}/contacts",
            maximumBytes = 786_432,
        ) {
            forSession(sessionId)
            parameter("query", query)
            parameter("limit", limit)
            after?.let { parameter("after", Uuid.parse(it).toString()) }
        }
}
