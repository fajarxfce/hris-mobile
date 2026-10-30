package dev.fajar.starter.documents.data.metadata.datasources

import dev.fajar.starter.documents.data.metadata.api.DocumentMetadataApi
import dev.fajar.starter.documents.data.metadata.dto.DocumentMetadataChangeDto
import org.koin.core.annotation.Single

@Single
class ApiDocumentMetadataDataSource(private val api: DocumentMetadataApi) :
    DocumentMetadataDataSource {
    override suspend fun head(sessionId: String, companyId: String, id: String) =
        api.head(sessionId, companyId, id)

    override suspend fun history(sessionId: String, companyId: String, id: String, after: Long?) =
        api.history(sessionId, companyId, id, after)

    override suspend fun save(
        sessionId: String,
        companyId: String,
        id: String,
        operationId: String,
        request: DocumentMetadataChangeDto,
    ) = api.save(sessionId, companyId, id, operationId, request)
}
