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
class CollaborationConversationApi(
    @Named(HttpClients.Authenticated) private val client: HttpClient
) {
    suspend fun participants(
        session: String,
        company: String,
        query: String,
        after: String?,
        limit: Int,
    ): CollaborationPageDto<CollaborationParticipantDto> =
        client.requestJson(path(company) + "/participants", maximumBytes = 131072) {
            page(session, after, limit)
            parameter("query", query)
        }

    suspend fun list(
        session: String,
        company: String,
        after: String?,
        limit: Int,
    ): CollaborationPageDto<CollaborationDirectDto> =
        client.requestJson(direct(company), maximumBytes = 65536) { page(session, after, limit) }

    suspend fun get(session: String, company: String, id: String): CollaborationDirectDto =
        client.requestJson(direct(company) + "/${Uuid.parse(id)}", maximumBytes = 2048) {
            forSession(session)
        }

    suspend fun room(session: String, company: String, id: String): CollaborationDirectRoomDto =
        client.requestJson(direct(company) + "/${Uuid.parse(id)}/room", maximumBytes = 2048) {
            forSession(session)
        }

    suspend fun open(
        session: String,
        company: String,
        operation: String,
        input: OpenCollaborationDirectRequest,
    ): CollaborationReceiptDto =
        client.requestJson(direct(company), HttpMethod.Post, maximumBytes = 1024) {
            command(session, operation)
            setBody(input)
        }

    suspend fun requestRoom(
        session: String,
        company: String,
        id: String,
        operation: String,
        input: RequestCollaborationDirectRoomRequest,
    ): CollaborationReceiptDto =
        client.requestJson(
            direct(company) + "/${Uuid.parse(id)}/room",
            HttpMethod.Post,
            maximumBytes = 1024,
        ) {
            command(session, operation)
            setBody(input)
        }

    private fun path(company: String) = "companies/${Uuid.parse(company)}/collaboration"

    private fun direct(company: String) = path(company) + "/direct-conversations"

    private fun HttpRequestBuilder.page(session: String, after: String?, limit: Int) {
        forSession(session)
        parameter("limit", limit)
        after?.let { parameter("after", Uuid.parse(it).toString()) }
    }

    private fun HttpRequestBuilder.command(session: String, operation: String) {
        forSession(session)
        header("Idempotency-Key", Uuid.parse(operation).toString())
        contentType(ContentType.Application.Json)
    }
}
