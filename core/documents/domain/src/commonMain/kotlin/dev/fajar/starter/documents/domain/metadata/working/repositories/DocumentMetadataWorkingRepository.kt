package dev.fajar.starter.documents.domain.metadata.working.repositories

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.documents.domain.entities.DocumentAccess
import dev.fajar.starter.documents.domain.metadata.working.entities.*
import kotlinx.coroutines.flow.Flow

interface DocumentMetadataWorkingRepository {
    fun observe(
        access: DocumentAccess,
        documentId: String,
    ): Flow<AppResult<DocumentMetadataWorkingCopy?>>

    fun observePage(
        access: DocumentAccess,
        afterDocumentId: String?,
    ): Flow<AppResult<DocumentMetadataWorkingPage>>

    suspend fun current(
        access: DocumentAccess,
        documentId: String,
    ): AppResult<DocumentMetadataWorkingCopy?>

    suspend fun save(
        access: DocumentAccess,
        expected: DocumentMetadataWorkingCopy?,
        next: DocumentMetadataWorkingCopy,
    ): AppResult<Boolean>

    suspend fun remove(
        access: DocumentAccess,
        expected: DocumentMetadataWorkingCopy,
    ): AppResult<Boolean>
}
