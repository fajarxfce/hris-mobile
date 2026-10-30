package dev.fajar.starter.documents.domain.metadata.repositories

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.documents.domain.entities.DocumentAccess
import dev.fajar.starter.documents.domain.entities.DocumentMutationReceipt
import dev.fajar.starter.documents.domain.metadata.entities.*

interface DocumentMetadataRepository {
    suspend fun head(access: DocumentAccess, documentId: String): AppResult<DocumentMetadataHead>

    suspend fun history(
        access: DocumentAccess,
        documentId: String,
        after: Long?,
    ): AppResult<DocumentMetadataPage>

    suspend fun save(
        access: DocumentAccess,
        change: DocumentMetadataChange,
    ): AppResult<DocumentMutationReceipt>
}
