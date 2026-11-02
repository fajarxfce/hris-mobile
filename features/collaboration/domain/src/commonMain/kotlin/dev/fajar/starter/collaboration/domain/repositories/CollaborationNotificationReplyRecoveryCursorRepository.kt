package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult

interface CollaborationNotificationReplyRecoveryCursorRepository {
    suspend fun current(
        access: CollaborationRecoveryAccess
    ): AppResult<CollaborationNotificationReplyRecoveryCursor?>

    /** Exact-version CAS. Null indicates contention; never creates a new content key. */
    suspend fun advance(
        access: CollaborationRecoveryAccess,
        expected: CollaborationNotificationReplyRecoveryCursor?,
        afterSequence: Long,
    ): AppResult<CollaborationNotificationReplyRecoveryCursor?>

    /** Raw exact-version replacement. The caller owns all cycle transition policy. */
    suspend fun replace(
        access: CollaborationRecoveryAccess,
        expected: CollaborationNotificationReplyRecoveryCursor?,
        afterSequence: Long,
        cycle: CollaborationNotificationReplyRecoveryCycle?,
    ): AppResult<CollaborationNotificationReplyRecoveryCursor?>
}
