package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult

/** Exact encrypted input mutations; neither draft persistence nor approval queues a message. */
interface CollaborationNotificationReplyResolutionRepository {
    suspend fun saveDraft(
        access: CollaborationRecoveryAccess,
        expected: CollaborationNotificationReplyInput,
        text: String,
    ): AppResult<Boolean>

    /** Existing confirmed ID/body/time are immutable. Explicit reapproval changes only parent. */
    suspend fun confirm(
        access: CollaborationRecoveryAccess,
        expected: CollaborationNotificationReplyInput,
        resolution: CollaborationNotificationReplyResolution,
    ): AppResult<Boolean>
}
