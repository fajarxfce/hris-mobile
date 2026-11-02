package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult

/** Separate encrypted input retention; no composer mutation, send grant or transport queue. */
interface CollaborationNotificationReplyRepository {
    /**
     * Caller first authenticates the descriptor/token against the current captured native family.
     */
    suspend fun retain(
        candidate: CollaborationNotificationReplyCandidate
    ): AppResult<CollaborationNotificationReplyRetention>

    suspend fun get(
        access: CollaborationRecoveryAccess,
        id: String,
    ): AppResult<CollaborationNotificationReplyInput?>

    /**
     * Bounded account-owned input discovery; exposing company content requires separate admission.
     */
    suspend fun page(
        access: CollaborationRecoveryAccess,
        after: Long? = null,
        limit: Int = 20,
        pendingOnly: Boolean = true,
    ): AppResult<CollaborationNotificationReplyPage>

    suspend fun hold(
        access: CollaborationRecoveryAccess,
        expected: CollaborationNotificationReplyInput,
        reason: CollaborationNotificationReplyReview,
    ): AppResult<Boolean>

    /** Caller reproduces an exact retained command or obtains explicit discard before removal. */
    suspend fun remove(
        access: CollaborationRecoveryAccess,
        expected: CollaborationNotificationReplyInput,
    ): AppResult<Boolean>
}
