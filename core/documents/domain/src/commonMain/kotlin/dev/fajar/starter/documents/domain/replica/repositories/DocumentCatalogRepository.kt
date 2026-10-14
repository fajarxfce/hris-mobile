package dev.fajar.starter.documents.domain.replica.repositories

import dev.fajar.starter.common.result.*
import dev.fajar.starter.documents.domain.entities.DocumentAccess
import dev.fajar.starter.documents.domain.replica.entities.*
import kotlinx.coroutines.flow.Flow

interface DocumentCatalogRepository {
    fun observeAdmission(access: DocumentAccess): Flow<AppResult<DocumentCatalogReadAdmission>>

    suspend fun admission(access: DocumentAccess): AppResult<DocumentCatalogReadAdmission>

    suspend fun restrict(access: DocumentAccess, failure: Failure): AppResult<Boolean>

    suspend fun admit(access: DocumentAccess, expectedRevision: String?): AppResult<Boolean>

    fun observe(
        access: DocumentAccess,
        selection: DocumentCatalogSelection,
    ): Flow<AppResult<DocumentCatalogSnapshot>>

    suspend fun checkpoint(access: DocumentAccess): AppResult<DocumentCatalogCheckpoint?>

    suspend fun begin(
        checkpoint: DocumentCatalogCheckpoint,
        expectedGeneration: String?,
    ): AppResult<Boolean>

    suspend fun commit(
        previous: DocumentCatalogCheckpoint,
        next: DocumentCatalogCheckpoint,
        entries: List<DocumentCatalogEntry>,
    ): AppResult<Boolean>

    suspend fun clear(access: DocumentAccess): AppResult<Boolean>
}
