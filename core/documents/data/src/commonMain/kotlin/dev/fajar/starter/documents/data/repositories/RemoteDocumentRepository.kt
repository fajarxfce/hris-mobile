@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.documents.data.repositories

import dev.fajar.starter.documents.data.datasources.DocumentRemoteDataSource
import dev.fajar.starter.documents.data.mappers.toEvidencePage
import dev.fajar.starter.documents.domain.entities.*
import dev.fajar.starter.documents.domain.repositories.DocumentRepository
import dev.fajar.starter.network.safeApiCall
import kotlin.uuid.Uuid
import org.koin.core.annotation.Single

@Single
class RemoteDocumentRepository(private val remote: DocumentRemoteDataSource) : DocumentRepository {
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
