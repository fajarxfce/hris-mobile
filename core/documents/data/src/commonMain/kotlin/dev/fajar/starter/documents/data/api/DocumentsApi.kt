package dev.fajar.starter.documents.data.api

import dev.fajar.starter.documents.data.dto.*
import dev.fajar.starter.network.*
import io.ktor.client.HttpClient
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.*
import io.ktor.http.*
import org.koin.core.annotation.*

@Single
class DocumentsApi(@Named(HttpClients.Authenticated) private val client: HttpClient) {
    suspend fun record(sessionId: String, companyId: String, id: String): DocumentCatalogRecordDto =
        client.requestJson(
            "companies/$companyId/documents/me/evidence/$id",
            maximumBytes = 16_384,
        ) {
            forSession(sessionId)
        }

    suspend fun inspect(
        sessionId: String,
        companyId: String,
        revisionId: String,
    ): DocumentContentDto =
        client
            .prepareHead("companies/$companyId/documents/revisions/$revisionId/content") {
                forSession(sessionId)
                expectSuccess = false
            }
            .execute { response ->
                requireApiStatus(response, HttpStatusCode.OK)
                DocumentContentDto(
                    response.headers.getAll(HttpHeaders.ETag).orEmpty(),
                    response.headers.getAll(HttpHeaders.ContentLength).orEmpty(),
                    response.headers.getAll(HttpHeaders.ContentType).orEmpty(),
                    response.headers.getAll(HttpHeaders.ContentEncoding).orEmpty(),
                )
            }

    suspend fun download(
        sessionId: String,
        companyId: String,
        revisionId: String,
        entityTag: String,
        size: Long,
        offset: Long,
        maximumBytes: Int,
    ): ByteArray =
        client.requestBinaryRange(
            "companies/$companyId/documents/revisions/$revisionId/content",
            entityTag,
            offset,
            size,
            maximumBytes,
        ) {
            forSession(sessionId)
            header(HttpHeaders.IfRange, entityTag)
        }

    suspend fun employments(
        sessionId: String,
        companyId: String,
        after: String?,
    ): DocumentDirectoryDto =
        client.requestJson("companies/$companyId/documents/me/employments", maximumBytes = 32_768) {
            forSession(sessionId)
            parameter("limit", 20)
            after?.let { parameter("after", it) }
        }

    suspend fun start(
        sessionId: String,
        companyId: String,
        operationId: String,
        request: DocumentUploadRequestDto,
    ): DocumentMutationDto =
        client.requestJson("companies/$companyId/documents/uploads", maximumBytes = 4_096) {
            forSession(sessionId)
            method = HttpMethod.Post
            header("Idempotency-Key", operationId)
            contentType(ContentType.Application.Json)
            setBody(request)
        }

    suspend fun chunk(
        sessionId: String,
        companyId: String,
        revisionId: String,
        operationId: String,
        offset: Long,
        sha256: String,
        bytes: ByteArray,
    ): DocumentMutationDto =
        client.requestJson(
            "companies/$companyId/documents/revisions/$revisionId/chunks",
            maximumBytes = 4_096,
        ) {
            forSession(sessionId)
            method = HttpMethod.Post
            header("Idempotency-Key", operationId)
            header("Upload-Offset", offset)
            header("Upload-Checksum-Sha256", sha256)
            contentType(ContentType.Application.OctetStream)
            setBody(bytes)
        }

    suspend fun validate(
        sessionId: String,
        companyId: String,
        revisionId: String,
        operationId: String,
        request: DocumentVersionRequestDto,
    ): DocumentMutationDto =
        client.requestJson(
            "companies/$companyId/documents/revisions/$revisionId/validate",
            maximumBytes = 4_096,
        ) {
            forSession(sessionId)
            method = HttpMethod.Post
            header("Idempotency-Key", operationId)
            contentType(ContentType.Application.Json)
            setBody(request)
        }

    suspend fun cancel(
        sessionId: String,
        companyId: String,
        revisionId: String,
        operationId: String,
        request: DocumentVersionRequestDto,
    ): DocumentMutationDto =
        client.requestJson(
            "companies/$companyId/documents/revisions/$revisionId/cancel",
            maximumBytes = 4_096,
        ) {
            forSession(sessionId)
            method = HttpMethod.Post
            header("Idempotency-Key", operationId)
            contentType(ContentType.Application.Json)
            setBody(request)
        }

    suspend fun revision(
        sessionId: String,
        companyId: String,
        revisionId: String,
    ): DocumentRevisionDto =
        client.requestJson(
            "companies/$companyId/documents/revisions/$revisionId",
            maximumBytes = 16_384,
        ) {
            forSession(sessionId)
        }

    suspend fun evidence(
        sessionId: String,
        companyId: String,
        employmentId: String,
        classification: String,
        after: String?,
    ): SelfDocumentEvidenceDto =
        client.requestJson("companies/$companyId/documents/me/evidence", maximumBytes = 65_536) {
            forSession(sessionId)
            parameter("employmentId", employmentId)
            parameter("classification", classification)
            parameter("limit", 20)
            after?.let { parameter("after", it) }
        }
}
