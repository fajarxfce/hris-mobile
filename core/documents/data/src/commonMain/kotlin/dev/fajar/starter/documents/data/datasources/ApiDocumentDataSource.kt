package dev.fajar.starter.documents.data.datasources

import dev.fajar.starter.documents.data.api.DocumentsApi
import org.koin.core.annotation.Single

@Single
class ApiDocumentDataSource(private val api: DocumentsApi) : DocumentRemoteDataSource {
    override suspend fun evidence(
        sessionId: String,
        companyId: String,
        employmentId: String,
        classification: String,
        after: String?,
    ) = api.evidence(sessionId, companyId, employmentId, classification, after)
}
