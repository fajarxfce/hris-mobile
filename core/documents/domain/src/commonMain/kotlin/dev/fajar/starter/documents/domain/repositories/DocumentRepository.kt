package dev.fajar.starter.documents.domain.repositories

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.documents.domain.entities.*
import dev.fajar.starter.documents.domain.replica.entities.DocumentCatalogRecord

interface DocumentRepository {
    suspend fun record(access: DocumentAccess, id: String): AppResult<DocumentCatalogRecord>

    suspend fun employments(
        access: DocumentAccess,
        after: String?,
    ): AppResult<DocumentEmploymentPage>

    suspend fun evidence(
        access: DocumentAccess,
        query: DocumentEvidenceQuery,
    ): AppResult<DocumentEvidencePage>
}
