package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult

/** Authenticated local IPC only. Callers own issuance, durable operation identity and admission. */
interface CollaborationNotificationActionRepository {
    /**
     * Requires the retained history key. Persist this envelope before publishing native actions.
     */
    suspend fun seal(
        parent: CollaborationNativeParent,
        action: CollaborationNotificationAction,
    ): AppResult<CollaborationNotificationActionToken>

    /** An old native family cannot open this envelope, including same-account reauthentication. */
    suspend fun open(
        parent: CollaborationNativeParent,
        token: CollaborationNotificationActionToken,
    ): AppResult<CollaborationNotificationAction>

    /**
     * Authenticates the issuing parent under this account/audience for private input retention. A
     * recoverable old parent never grants a command in the current family. Legacy envelopes without
     * an encrypted issuing parent remain limited to their exact current parent.
     */
    suspend fun recover(
        parent: CollaborationNativeParent,
        token: CollaborationNotificationActionToken,
    ): AppResult<CollaborationAuthenticatedNotificationAction>
}
