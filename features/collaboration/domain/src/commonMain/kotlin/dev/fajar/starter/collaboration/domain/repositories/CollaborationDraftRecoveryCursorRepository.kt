package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult

interface CollaborationDraftRecoveryCursorRepository {
    suspend fun current(
        access: CollaborationLocalAccess
    ): AppResult<CollaborationDraftRecoveryCursor?>

    /** Null means a competing generation or owner changed. No delivery occurs in this operation. */
    suspend fun advance(
        access: CollaborationLocalAccess,
        expected: CollaborationDraftRecoveryCursor?,
        conversationId: String,
    ): AppResult<CollaborationDraftRecoveryCursor?>
}
