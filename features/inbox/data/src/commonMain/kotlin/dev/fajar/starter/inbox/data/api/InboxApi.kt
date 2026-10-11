package dev.fajar.starter.inbox.data.api

import dev.fajar.starter.inbox.data.dto.InboxItemDto
import dev.fajar.starter.network.*
import io.ktor.client.HttpClient
import org.koin.core.annotation.*

@Single
class InboxApi(@Named(HttpClients.Authenticated) private val client: HttpClient) {
    suspend fun item(sessionId: String, companyId: String, id: String): InboxItemDto =
        client.requestJson("companies/$companyId/inbox/$id", maximumBytes = 131_072) {
            forSession(sessionId)
        }
}
