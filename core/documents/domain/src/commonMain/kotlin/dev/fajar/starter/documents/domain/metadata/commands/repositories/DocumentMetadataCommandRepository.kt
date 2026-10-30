package dev.fajar.starter.documents.domain.metadata.commands.repositories

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.documents.domain.entities.DocumentAccess
import dev.fajar.starter.documents.domain.metadata.commands.entities.*
import kotlinx.coroutines.flow.Flow

interface DocumentMetadataCommandRepository {
    /** Bounded by the shared account's 128 retained commands. No age-based eviction. */
    fun observeAll(access: DocumentAccess): Flow<AppResult<List<DocumentMetadataCommandRecord>>>

    fun observe(
        access: DocumentAccess,
        documentId: String,
    ): Flow<AppResult<DocumentMetadataCommandRecord?>>

    suspend fun find(
        access: DocumentAccess,
        operationId: String,
    ): AppResult<DocumentMetadataCommandRecord?>

    suspend fun insert(
        access: DocumentAccess,
        command: DocumentMetadataCommand,
    ): AppResult<DocumentMetadataCommandAdmission>

    /** Compare the complete observed record; update only its outcome and attempt count. */
    suspend fun update(
        access: DocumentAccess,
        expected: DocumentMetadataCommandRecord,
        outcome: DocumentMetadataCommandOutcome,
        attempts: Int = expected.attempts,
    ): AppResult<Boolean>

    suspend fun remove(
        access: DocumentAccess,
        expected: DocumentMetadataCommandRecord,
    ): AppResult<Boolean>
}
