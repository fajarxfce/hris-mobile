package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.CollaborationSpaceConversation
import dev.fajar.starter.collaboration.domain.entities.CollaborationSpaceDirectTransport
import dev.fajar.starter.common.result.AppResult

interface CollaborationSpaceDirectTransportRepository {
    /**
     * One current observation; its owning use case must recheck admission and resource lifetime.
     */
    suspend fun read(
        conversation: CollaborationSpaceConversation
    ): AppResult<CollaborationSpaceDirectTransport>
}
