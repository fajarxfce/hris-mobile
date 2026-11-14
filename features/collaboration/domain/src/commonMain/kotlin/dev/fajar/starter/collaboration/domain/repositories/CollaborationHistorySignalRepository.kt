package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.CollaborationSpaceLocalAccess
import dev.fajar.starter.common.result.AppResult
import kotlinx.coroutines.flow.Flow

/** Coalesced account-owned local history invalidation; never proof of content or admission. */
interface CollaborationHistorySignalRepository {
    /**
     * Includes actual Company and Group history, even before canonical binding exists. Multiple
     * Group owners for the same account can receive the same opaque generation. Their use cases
     * retain access checks, finite catalog/source progress and completion independently.
     */
    fun observe(access: CollaborationSpaceLocalAccess): Flow<AppResult<String?>>
}
