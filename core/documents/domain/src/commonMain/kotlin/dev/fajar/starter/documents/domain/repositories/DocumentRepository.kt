package dev.fajar.starter.documents.domain.repositories

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.documents.domain.entities.*

interface DocumentRepository {
    suspend fun evidence(
        access: DocumentAccess,
        query: DocumentEvidenceQuery,
    ): AppResult<DocumentEvidencePage>
}
