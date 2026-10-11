package dev.fajar.starter.inbox.data.api

import dev.fajar.starter.inbox.data.dto.*
import dev.fajar.starter.network.*
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.http.*
import org.koin.core.annotation.*

@Single
class InboxApi(@Named(HttpClients.Authenticated) private val client: HttpClient) {
    suspend fun item(sessionId: String, companyId: String, id: String): InboxItemDto =
        client.requestJson("companies/$companyId/inbox/$id", maximumBytes = 131_072) {
            forSession(sessionId)
        }

    suspend fun act(
        sessionId: String,
        companyId: String,
        id: String,
        operationId: String,
        action: String,
        request: InboxActionRequest,
    ): InboxActionReceiptDto =
        client.requestJson(
            "companies/$companyId/inbox/$id/$action",
            HttpMethod.Post,
            maximumBytes = 4096,
        ) {
            forSession(sessionId)
            header("Idempotency-Key", operationId)
            contentType(ContentType.Application.Json)
            setBody(request)
        }
}
