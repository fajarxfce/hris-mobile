package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult

/** Permanently consumes a not-yet-queued message ID without publishing deliverable work. */
interface CollaborationMessagePreventionRepository {
    /**
     * Existing active work is never changed. An existing cancellation matches ID, owner,
     * conversation and text; its original creation time remains unchanged on retry.
     */
    suspend fun prevent(
        access: CollaborationLocalAccess,
        intent: CollaborationMessageIntent,
    ): AppResult<Boolean>
}
