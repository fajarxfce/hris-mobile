package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult

/** Finite local original-reference traversal; no transport, command inference or retry policy. */
interface CollaborationSpaceInboxRepairRepository {
    /** Exact local lookup for a changed original; completed/absent references return null. */
    suspend fun get(
        access: CollaborationSpaceLocalAccess,
        conversationId: String,
        messageId: String,
    ): AppResult<CollaborationSpaceInboxRepairReference?>

    suspend fun page(
        access: CollaborationSpaceLocalAccess,
        after: String? = null,
        limit: Int = 16,
    ): AppResult<CollaborationSpaceInboxRepairPage>
}
