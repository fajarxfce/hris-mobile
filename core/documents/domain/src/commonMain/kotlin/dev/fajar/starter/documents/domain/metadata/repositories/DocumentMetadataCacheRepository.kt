package dev.fajar.starter.documents.domain.metadata.repositories

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.documents.domain.entities.DocumentAccess
import dev.fajar.starter.documents.domain.metadata.entities.*
import kotlinx.coroutines.flow.Flow

interface DocumentMetadataCacheRepository {
    fun observe(
        access: DocumentAccess,
        documentId: String,
        after: Long?,
    ): Flow<AppResult<DocumentMetadataSnapshot>>

    suspend fun checkpoint(
        access: DocumentAccess,
        documentId: String,
    ): AppResult<DocumentMetadataCheckpoint?>

    /** Atomically saves one header and at most twenty immutable history rows. */
    suspend fun commit(
        access: DocumentAccess,
        expected: DocumentMetadataCheckpoint?,
        next: DocumentMetadataCheckpoint,
        rows: List<DocumentMetadataRevision>,
    ): AppResult<Boolean>
}
