package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult
import kotlinx.coroutines.flow.Flow

/** Separate encrypted input retention; no composer mutation, send grant or transport queue. */
interface CollaborationNotificationReplyRepository {
    /** Advisory changes only; private company content requires separate admission. */
    fun observeChanges(access: CollaborationRecoveryAccess): Flow<AppResult<Unit>>

    /**
     * Caller authenticates the issuing descriptor within the current account/audience. The current
     * access owns storage I/O; candidate.parent preserves the possibly older issuing family.
     */
    suspend fun retain(
        access: CollaborationRecoveryAccess,
        candidate: CollaborationNotificationReplyCandidate,
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
