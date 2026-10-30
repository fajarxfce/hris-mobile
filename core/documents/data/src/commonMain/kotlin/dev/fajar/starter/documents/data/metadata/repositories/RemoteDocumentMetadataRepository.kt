package dev.fajar.starter.documents.data.metadata.repositories

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.documents.data.mappers.toBusinessMetadataDto
import dev.fajar.starter.documents.data.metadata.datasources.DocumentMetadataDataSource
import dev.fajar.starter.documents.data.metadata.dto.DocumentMetadataChangeDto
import dev.fajar.starter.documents.data.metadata.mappers.*
import dev.fajar.starter.documents.domain.entities.DocumentAccess
import dev.fajar.starter.documents.domain.entities.DocumentMutationReceipt
import dev.fajar.starter.documents.domain.metadata.entities.DocumentMetadataChange
import dev.fajar.starter.documents.domain.metadata.policies.*
import dev.fajar.starter.documents.domain.metadata.repositories.DocumentMetadataRepository
import dev.fajar.starter.network.safeApiCall
import org.koin.core.annotation.Single

@Single
class RemoteDocumentMetadataRepository(private val remote: DocumentMetadataDataSource) :
    DocumentMetadataRepository {
    override suspend fun head(access: DocumentAccess, documentId: String) = safeApiCall {
        requireDocumentMetadataId(access.companyId)
        requireDocumentMetadataId(documentId)
        remote.head(access.sessionId, access.companyId, documentId).toMetadataHead(documentId)
    }

    override suspend fun history(access: DocumentAccess, documentId: String, after: Long?) =
        safeApiCall {
            requireDocumentMetadataId(access.companyId)
            requireDocumentMetadataId(documentId)
            require(after == null || after in 0..DOCUMENT_METADATA_MAXIMUM_VERSION)
            remote
                .history(access.sessionId, access.companyId, documentId, after)
                .toMetadataPage(documentId, after)
        }

    override suspend fun save(access: DocumentAccess, change: DocumentMetadataChange) =
        safeApiCall {
            requireDocumentMetadataId(access.companyId)
            require(validateDocumentMetadataChange(change) is AppResult.Success)
            val receipt =
                remote.save(
                    access.sessionId,
                    access.companyId,
                    change.documentId,
                    change.operationId,
                    DocumentMetadataChangeDto(
                        change.expectedVersion,
                        change.metadata.toBusinessMetadataDto(),
                        change.reason,
                    ),
                )
            require(
                receipt.id == change.documentId && receipt.version == change.expectedVersion + 1
            )
            DocumentMutationReceipt(receipt.id, receipt.version)
        }
}
