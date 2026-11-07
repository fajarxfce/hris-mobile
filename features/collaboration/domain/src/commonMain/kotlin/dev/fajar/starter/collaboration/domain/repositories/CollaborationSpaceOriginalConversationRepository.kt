package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult

/** Finite original metadata acquisition; never adopts a room or selects an HR company. */
interface CollaborationSpaceOriginalConversationRepository {
    suspend fun list(
        owner: CollaborationSpaceOwner,
        after: CollaborationOriginalConversationPosition?,
        limit: Int,
    ): AppResult<CollaborationSpaceOriginalConversationPage>

    suspend fun get(
        owner: CollaborationSpaceOwner,
        position: CollaborationOriginalConversationPosition,
    ): AppResult<CollaborationSpaceOriginalConversation>
}
