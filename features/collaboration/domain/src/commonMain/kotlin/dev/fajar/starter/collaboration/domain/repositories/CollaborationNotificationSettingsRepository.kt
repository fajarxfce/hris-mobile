package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult
import kotlinx.coroutines.flow.Flow

/** One encrypted account/company/conversation checkpoint; no provider or notification I/O. */
interface CollaborationNotificationSettingsRepository {
    /** Owned local settings updates; this stream does not grant company or OS admission. */
    fun observe(
        access: CollaborationLocalAccess,
        conversationId: String,
    ): Flow<AppResult<CollaborationNotificationSettingsState>>

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
