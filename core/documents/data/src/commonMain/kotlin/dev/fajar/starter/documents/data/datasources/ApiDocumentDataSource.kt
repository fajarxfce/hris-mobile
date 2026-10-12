package dev.fajar.starter.documents.data.datasources

import dev.fajar.starter.documents.data.api.DocumentsApi
import dev.fajar.starter.documents.data.dto.*
import org.koin.core.annotation.Single

@Single
class ApiDocumentDataSource(private val api: DocumentsApi) :
    DocumentRemoteDataSource, DocumentContentDataSource {
    override suspend fun inspect(sessionId: String, companyId: String, revisionId: String) =
        api.inspect(sessionId, companyId, revisionId)

    override suspend fun download(
        sessionId: String,
        companyId: String,
        revisionId: String,
        entityTag: String,
        size: Long,
        offset: Long,
        maximumBytes: Int,
    ) = api.download(sessionId, companyId, revisionId, entityTag, size, offset, maximumBytes)

    override suspend fun employments(sessionId: String, companyId: String, after: String?) =
        api.employments(sessionId, companyId, after)

    override suspend fun start(
        sessionId: String,
        companyId: String,
        operationId: String,
        request: DocumentUploadRequestDto,
    ) = api.start(sessionId, companyId, operationId, request)

    override suspend fun chunk(
        sessionId: String,
        companyId: String,
        revisionId: String,
        operationId: String,
        offset: Long,
        sha256: String,
        bytes: ByteArray,
    ) = api.chunk(sessionId, companyId, revisionId, operationId, offset, sha256, bytes)

    override suspend fun validate(
        sessionId: String,
        companyId: String,
        revisionId: String,
        operationId: String,
        request: DocumentVersionRequestDto,
    ) = api.validate(sessionId, companyId, revisionId, operationId, request)

    override suspend fun cancel(
        sessionId: String,
        companyId: String,
        revisionId: String,
        operationId: String,
        request: DocumentVersionRequestDto,
    ) = api.cancel(sessionId, companyId, revisionId, operationId, request)

    override suspend fun revision(sessionId: String, companyId: String, revisionId: String) =
        api.revision(sessionId, companyId, revisionId)

    override suspend fun evidence(
        sessionId: String,
        companyId: String,
        employmentId: String,
        classification: String,
        after: String?,
    ) = api.evidence(sessionId, companyId, employmentId, classification, after)
}
