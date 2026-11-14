package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult
import kotlinx.coroutines.flow.Flow

interface CollaborationSpaceRecipientProjectionRepository {
    /** Local source facts only; this does not allocate or substitute a canonical conversation. */
    fun observe(
        access: CollaborationSpaceLocalAccess,
        peerAccountId: String,
    ): Flow<AppResult<CollaborationSpaceRecipientProjection>>
}
