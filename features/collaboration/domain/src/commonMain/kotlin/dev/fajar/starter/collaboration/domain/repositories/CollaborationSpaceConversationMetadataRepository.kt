package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult

/** Encrypted canonical facts; the owning use case separately checks current read admission. */
interface CollaborationSpaceConversationMetadataRepository {
    suspend fun getById(
        access: CollaborationSpaceLocalAccess,
        conversationId: String,
    ): AppResult<CollaborationSpaceConversationMetadata?>

    suspend fun findByPeer(
        access: CollaborationSpaceLocalAccess,
        peerAccountId: String,
    ): AppResult<CollaborationSpaceConversationMetadata?>

    /** Atomic conversation/peer indexes. Identical input preserves ciphertext and revision. */
    suspend fun retain(
        access: CollaborationSpaceLocalAccess,
        conversation: CollaborationSpaceConversation,
    ): AppResult<Boolean>
}
