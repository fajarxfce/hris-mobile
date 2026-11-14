package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult

/** Finite owned index windows; the use case admits access and the consumer owns invalidation. */
interface CollaborationSpaceInboxWindowRepository {
    suspend fun read(
        access: CollaborationSpaceLocalAccess,
        anchor: CollaborationSpaceInboxWindowAnchor = CollaborationSpaceInboxWindowAnchor.Latest,
        limit: Int = 50,
    ): AppResult<CollaborationSpaceInboxWindow>
}
