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
}
