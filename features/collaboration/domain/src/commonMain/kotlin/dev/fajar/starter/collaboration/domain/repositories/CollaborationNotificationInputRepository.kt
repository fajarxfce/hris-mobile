package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult

/**
 * Device-encrypted pre-retention input. Plaintext returned here remains unverified and must never
 * enter account UI or transport until its descriptor has been authenticated for that actual
 * account. Initial local protection does not require a session. Logout never deletes this input or
 * its key.
 */
interface CollaborationNotificationInputRepository {
    suspend fun retain(
        reply: CollaborationUnverifiedNotificationReply
    ): AppResult<CollaborationNotificationInputRetention>

    suspend fun get(
        reference: CollaborationNotificationInputReference
    ): AppResult<CollaborationNotificationInput?>

    /** Opaque references only, exclusive ordered ID cursor. A later scan must restart from null. */
    suspend fun page(
        afterId: String? = null,
        limit: Int = 16,
    ): AppResult<List<CollaborationNotificationInputReference>>

    /**
     * Caller first proves exact authenticated account-owned retention, or explicit local discard.
     */
    suspend fun remove(expected: CollaborationNotificationInput): AppResult<Boolean>
}
