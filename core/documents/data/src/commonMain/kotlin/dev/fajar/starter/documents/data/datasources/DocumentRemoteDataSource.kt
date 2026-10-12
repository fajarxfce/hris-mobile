package dev.fajar.starter.documents.data.datasources

import dev.fajar.starter.documents.data.dto.SelfDocumentEvidenceDto

interface DocumentRemoteDataSource {
    suspend fun evidence(
        sessionId: String,
        companyId: String,
        employmentId: String,
        classification: String,
        after: String?,
    ): SelfDocumentEvidenceDto
}
