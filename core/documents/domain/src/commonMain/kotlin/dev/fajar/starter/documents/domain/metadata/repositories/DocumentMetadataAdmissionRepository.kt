package dev.fajar.starter.documents.domain.metadata.repositories

import dev.fajar.starter.common.result.*
import dev.fajar.starter.documents.domain.entities.DocumentAccess
import dev.fajar.starter.documents.domain.metadata.entities.DocumentMetadataReadAdmission
import kotlinx.coroutines.flow.Flow

interface DocumentMetadataAdmissionRepository {
    fun observe(
        access: DocumentAccess,
        documentId: String,
    ): Flow<AppResult<DocumentMetadataReadAdmission>>

    suspend fun current(
        access: DocumentAccess,
        documentId: String,
    ): AppResult<DocumentMetadataReadAdmission>

    suspend fun restrict(
        access: DocumentAccess,
        documentId: String,
        failure: Failure,
    ): AppResult<Boolean>

    suspend fun admit(
        access: DocumentAccess,
        documentId: String,
        expectedRevision: String?,
    ): AppResult<Boolean>
}
