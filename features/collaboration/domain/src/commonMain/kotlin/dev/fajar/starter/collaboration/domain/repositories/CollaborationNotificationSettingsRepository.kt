package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult

/** One encrypted account/company/conversation checkpoint; no provider or notification I/O. */
interface CollaborationNotificationSettingsRepository {
    suspend fun read(
        access: CollaborationLocalAccess,
        conversationId: String,
    ): AppResult<CollaborationNotificationSettingsState>

    /** False means the observed version or active owner changed. Never retries or resets data. */
    suspend fun update(
        access: CollaborationLocalAccess,
        conversationId: String,
        change: CollaborationNotificationSettingsChange,
    ): AppResult<Boolean>
}
