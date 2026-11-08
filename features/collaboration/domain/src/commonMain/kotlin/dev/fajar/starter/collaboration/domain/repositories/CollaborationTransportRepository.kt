package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.CollaborationDirectTransport
import dev.fajar.starter.collaboration.domain.entities.CollaborationOwner
import dev.fajar.starter.common.result.AppResult

/** One current server read; use cases retain local continuation and queue admission policy. */
fun interface CollaborationTransportRepository {
    suspend fun read(
        owner: CollaborationOwner,
        conversationId: String,
    ): AppResult<CollaborationDirectTransport>
}
