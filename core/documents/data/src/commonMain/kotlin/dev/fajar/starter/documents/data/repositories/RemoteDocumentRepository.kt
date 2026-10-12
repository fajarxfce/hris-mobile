@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.documents.data.repositories

import dev.fajar.starter.documents.data.datasources.DocumentRemoteDataSource
import dev.fajar.starter.documents.data.dto.*
import dev.fajar.starter.documents.data.mappers.*
import dev.fajar.starter.documents.domain.entities.*
import dev.fajar.starter.documents.domain.policies.DOCUMENT_UPLOAD_CHUNK_BYTES
import dev.fajar.starter.documents.domain.repositories.*
import dev.fajar.starter.network.safeApiCall
import kotlin.uuid.Uuid
import org.koin.core.annotation.Single

@Single
class RemoteDocumentRepository(private val remote: DocumentRemoteDataSource) :
    DocumentRepository, DocumentUploadRepository {
    override suspend fun employments(access: DocumentAccess, after: String?) = safeApiCall {
        Uuid.parse(access.companyId)
        require(after == null || after.length in 1..32)
        remote
            .employments(access.sessionId, access.companyId, after)
            .toEmploymentPage(access, after)
    }

    override suspend fun start(
        access: DocumentAccess,
        file: LocalDocumentFile,
        intent: DocumentUploadIntent,
        operationId: String,
    ) = safeApiCall {
        Uuid.parse(access.companyId)
        Uuid.parse(operationId)
        val declaration = file.draft.declaration
        val request =
            DocumentUploadRequestDto(
                intent.documentId,
                intent.revisionId,
                intent.input.employmentId,
                intent.input.title,
                intent.input.classification.name,
                0,
                declaration.name,
                declaration.mediaType,
                declaration.size,
                requireNotNull(file.sha256),
                intent.input.reason,
            )
        remote
            .start(access.sessionId, access.companyId, operationId, request)
            .toReceipt(intent.revisionId)
    }

    override suspend fun chunk(
        access: DocumentAccess,
        revisionId: String,
        operationId: String,
        offset: Long,
        chunk: DocumentFileChunk,
    ) = safeApiCall {
        Uuid.parse(access.companyId)
        Uuid.parse(revisionId)
        Uuid.parse(operationId)
        require(
            offset >= 0 &&
                offset % DOCUMENT_UPLOAD_CHUNK_BYTES == 0L &&
                chunk.bytes.size in 1..DOCUMENT_UPLOAD_CHUNK_BYTES &&
                Regex("[0-9a-f]{64}").matches(chunk.sha256)
        )
        remote
            .chunk(
                access.sessionId,
                access.companyId,
                revisionId,
                operationId,
                offset,
                chunk.sha256,
                chunk.bytes,
            )
            .toReceipt(revisionId)
    }

    override suspend fun validate(
        access: DocumentAccess,
        revisionId: String,
        operationId: String,
        version: Long,
        reason: String,
    ) = safeApiCall {
        Uuid.parse(access.companyId)
        Uuid.parse(revisionId)
        Uuid.parse(operationId)
        remote
            .validate(
                access.sessionId,
                access.companyId,
                revisionId,
                operationId,
                DocumentVersionRequestDto(version, reason),
            )
            .toReceipt(revisionId)
    }

    override suspend fun cancel(
        access: DocumentAccess,
        revisionId: String,
        operationId: String,
        version: Long,
        reason: String,
    ) = safeApiCall {
        Uuid.parse(access.companyId)
        Uuid.parse(revisionId)
        Uuid.parse(operationId)
        remote
            .cancel(
                access.sessionId,
                access.companyId,
                revisionId,
                operationId,
                DocumentVersionRequestDto(version, reason),
            )
            .toReceipt(revisionId)
    }

    override suspend fun revision(
        access: DocumentAccess,
        file: LocalDocumentFile,
        intent: DocumentUploadIntent,
    ) = safeApiCall {
        Uuid.parse(access.companyId)
        Uuid.parse(intent.revisionId)
        remote
            .revision(access.sessionId, access.companyId, intent.revisionId)
            .toProgress(access, file, intent)
    }

    override suspend fun evidence(access: DocumentAccess, query: DocumentEvidenceQuery) =
        safeApiCall {
            Uuid.parse(access.companyId)
            Uuid.parse(query.employmentId)
            remote
                .evidence(
                    access.sessionId,
                    access.companyId,
                    query.employmentId,
                    query.classification.name,
                    query.after,
                )
                .toEvidencePage(access, query)
        }
}
