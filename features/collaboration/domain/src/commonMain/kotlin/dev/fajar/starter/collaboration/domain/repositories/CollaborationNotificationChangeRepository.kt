package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.CollaborationLocalAccess
import dev.fajar.starter.common.result.AppResult
import kotlinx.coroutines.flow.Flow

/** Advisory changes to retained notification sources; no content or publication admission. */
interface CollaborationNotificationChangeRepository {
    /** Initial snapshot, then source changes. Publication acknowledgments are not sources. */
    fun observe(access: CollaborationLocalAccess, conversationId: String): Flow<AppResult<Unit>>
}
