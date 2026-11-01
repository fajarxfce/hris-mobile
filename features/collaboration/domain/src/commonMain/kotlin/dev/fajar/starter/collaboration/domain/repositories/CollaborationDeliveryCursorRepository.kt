package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult

interface CollaborationDeliveryCursorRepository {
    suspend fun current(access: CollaborationLocalAccess): AppResult<CollaborationDeliveryCursor?>

    /** Null means a competing generation or owner changed. No delivery occurs in this operation. */
    suspend fun advance(
        access: CollaborationLocalAccess,
        expected: CollaborationDeliveryCursor?,
        conversationId: String,
    ): AppResult<CollaborationDeliveryCursor?>
}
