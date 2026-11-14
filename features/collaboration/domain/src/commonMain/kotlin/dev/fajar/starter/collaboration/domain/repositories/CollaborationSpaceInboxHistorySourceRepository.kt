package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult

/** Encrypted per-conversation progress and unresolved references; no scheduling or transport. */
interface CollaborationSpaceInboxHistorySourceRepository {
    suspend fun current(
        access: CollaborationSpaceLocalAccess,
        conversationId: String,
    ): AppResult<CollaborationSpaceInboxHistorySourceCheckpoint?>

    /**
     * Atomically checks the current index and expected source checkpoint while retaining state and
     * reference outcomes. Null means contention or destination replacement. A lost response
     * requires reading the retained checkpoint, not manufacturing a successful receipt.
     */
    suspend fun mutate(
        access: CollaborationSpaceLocalAccess,
        mutation: CollaborationSpaceInboxHistorySourceMutation,
    ): AppResult<CollaborationSpaceInboxHistorySourceCheckpoint?>

    /**
     * Bounded published source metadata, in stable partition order, with an opaque continuation.
     */
    suspend fun sources(
        access: CollaborationSpaceLocalAccess,
        after: String?,
        limit: Int,
    ): AppResult<CollaborationSpaceInboxHistorySourcePage>

    /**
     * Captures or continues the source's frozen pending interval. New insertions cannot extend it.
     * Null means that the expected checkpoint changed; empty references alone do not mean complete.
     */
    suspend fun pending(
        access: CollaborationSpaceLocalAccess,
        conversationId: String,
        expected: CollaborationSpaceInboxHistoryProgressVersion,
        limit: Int,
    ): AppResult<CollaborationSpaceInboxHistoryPendingPage?>
}
