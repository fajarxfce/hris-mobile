package dev.fajar.starter.documents.data.datasources

import dev.fajar.starter.documents.data.dto.*

interface DocumentRemoteDataSource {
    suspend fun employments(
        sessionId: String,
        companyId: String,
        after: String?,
    ): DocumentDirectoryDto

    suspend fun start(
        sessionId: String,
        companyId: String,
        operationId: String,
        request: DocumentUploadRequestDto,
    ): DocumentMutationDto

    suspend fun chunk(
        sessionId: String,
        companyId: String,
        revisionId: String,
        operationId: String,
        offset: Long,
        sha256: String,
        bytes: ByteArray,
    ): DocumentMutationDto

    suspend fun validate(
        sessionId: String,
        companyId: String,
        revisionId: String,
        operationId: String,
        request: DocumentVersionRequestDto,
    ): DocumentMutationDto

    suspend fun cancel(
        sessionId: String,
        companyId: String,
        revisionId: String,
        operationId: String,
        request: DocumentVersionRequestDto,
    ): DocumentMutationDto

    suspend fun revision(
        sessionId: String,
        companyId: String,
        revisionId: String,
    ): DocumentRevisionDto

    suspend fun evidence(
        sessionId: String,
        companyId: String,
        employmentId: String,
        classification: String,
        after: String?,
    ): SelfDocumentEvidenceDto
}
