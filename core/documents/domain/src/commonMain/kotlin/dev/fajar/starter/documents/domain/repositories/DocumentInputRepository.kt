package dev.fajar.starter.documents.domain.repositories

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.documents.domain.entities.DocumentFileSelection

interface DocumentInputRepository {
    suspend fun select(
        requestId: String,
        mediaTypes: Set<String>,
    ): AppResult<DocumentFileSelection?>

    suspend fun release(requestId: String): AppResult<Unit>
}
