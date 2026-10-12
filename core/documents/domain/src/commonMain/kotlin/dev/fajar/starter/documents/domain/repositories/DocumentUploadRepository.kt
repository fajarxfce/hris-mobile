package dev.fajar.starter.documents.domain.repositories

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.documents.domain.entities.*

interface DocumentUploadRepository {
    suspend fun start(
        access: DocumentAccess,
        file: LocalDocumentFile,
        intent: DocumentUploadIntent,
        operationId: String,
    ): AppResult<DocumentMutationReceipt>

    suspend fun chunk(
        access: DocumentAccess,
        revisionId: String,
        operationId: String,
        offset: Long,
        chunk: DocumentFileChunk,
    ): AppResult<DocumentMutationReceipt>

    suspend fun validate(
        access: DocumentAccess,
        revisionId: String,
        operationId: String,
        version: Long,
        reason: String,
    ): AppResult<DocumentMutationReceipt>

    suspend fun cancel(
        access: DocumentAccess,
        revisionId: String,
        operationId: String,
        version: Long,
        reason: String,
    ): AppResult<DocumentMutationReceipt>

    suspend fun revision(
        access: DocumentAccess,
        file: LocalDocumentFile,
        intent: DocumentUploadIntent,
    ): AppResult<DocumentRevisionProgress>
}
