package dev.fajar.starter.documents.data.metadata.api

import dev.fajar.starter.documents.data.dto.DocumentMutationDto
import dev.fajar.starter.documents.data.metadata.dto.*
import dev.fajar.starter.documents.domain.metadata.policies.DOCUMENT_METADATA_PAGE_SIZE
import dev.fajar.starter.network.*
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.http.*
import org.koin.core.annotation.*

@Single
class DocumentMetadataApi(@Named(HttpClients.Authenticated) private val client: HttpClient) {
    suspend fun head(sessionId: String, companyId: String, id: String): DocumentMetadataHeadDto =
        client.requestJson("companies/$companyId/documents/$id", maximumBytes = 16_384) {
            forSession(sessionId)
        }

    suspend fun history(
        sessionId: String,
        companyId: String,
        id: String,
        after: Long?,
    ): DocumentMetadataPageDto =
        client.requestJson(
            "companies/$companyId/documents/$id/metadata-history",
            maximumBytes = 131_072,
        ) {
            forSession(sessionId)
            parameter("limit", DOCUMENT_METADATA_PAGE_SIZE)
            after?.let { parameter("after", it) }
        }

    suspend fun save(
        sessionId: String,
        companyId: String,
        id: String,
        operationId: String,
        request: DocumentMetadataChangeDto,
    ): DocumentMutationDto =
        client.requestJson("companies/$companyId/documents/$id/metadata", maximumBytes = 4_096) {
            forSession(sessionId)
            method = HttpMethod.Put
            header("Idempotency-Key", operationId)
            contentType(ContentType.Application.Json)
            setBody(request)
        }
}
