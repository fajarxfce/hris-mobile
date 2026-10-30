package dev.fajar.starter.documents.data.metadata.datasources

import dev.fajar.starter.documents.data.dto.DocumentMutationDto
import dev.fajar.starter.documents.data.metadata.dto.*

interface DocumentMetadataDataSource {
    suspend fun head(sessionId: String, companyId: String, id: String): DocumentMetadataHeadDto

    suspend fun history(
        sessionId: String,
        companyId: String,
        id: String,
        after: Long?,
    ): DocumentMetadataPageDto

    suspend fun save(
        sessionId: String,
        companyId: String,
        id: String,
        operationId: String,
        request: DocumentMetadataChangeDto,
    ): DocumentMutationDto
}
