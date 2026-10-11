package dev.fajar.starter.sync.data.api

import dev.fajar.starter.network.*
import dev.fajar.starter.sync.data.dto.*
import io.ktor.client.HttpClient
import io.ktor.client.request.parameter
import org.koin.core.annotation.*

@Single
class SyncFeedApi(@Named(HttpClients.Authenticated) private val client: HttpClient) {
    suspend fun bootstrap(
        sessionId: String,
        companyId: String,
        collections: String,
        cursor: String?,
        limit: Int,
    ): SyncBootstrapDto =
        client.requestJson("companies/$companyId/sync/bootstrap", maximumBytes = 65_536) {
            forSession(sessionId)
            parameter("collections", collections)
            parameter("limit", limit)
            cursor?.let { parameter("cursor", it) }
        }

    suspend fun changes(
        sessionId: String,
        companyId: String,
        collections: String,
        cursor: String,
        limit: Int,
    ): SyncChangesDto =
        client.requestJson("companies/$companyId/sync/changes", maximumBytes = 65_536) {
            forSession(sessionId)
            parameter("collections", collections)
            parameter("limit", limit)
            parameter("cursor", cursor)
        }
}
