package dev.fajar.starter.documents.domain.repositories

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.documents.domain.entities.*
import kotlinx.coroutines.flow.Flow

interface DocumentFileRepository {
    suspend fun import(
        access: DocumentAccess,
        draft: DocumentFileDraft,
        maxBytes: Long,
        maxFiles: Int,
    ): AppResult<DocumentFileImport>

    fun observe(access: DocumentAccess): Flow<AppResult<List<LocalDocumentFile>>>

    suspend fun find(access: DocumentAccess, id: String): AppResult<LocalDocumentFile?>

    /** Conditional cleanup must never delete a completed or concurrently advanced file. */
    suspend fun discardIncomplete(access: DocumentAccess, id: String): AppResult<Boolean>
}
