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

    /**
     * Atomic conversation/peer indexes. Identical input preserves ciphertext and revision. A
     * retained pair is immutable for the lifetime of its published catalog generation; another peer
     * or conversation cannot rebind either index. Catalog revisions only append immutable pairs.
     * Replacing/removing the partition invalidates all previous associations.
     */
    suspend fun retain(
        access: CollaborationSpaceLocalAccess,
        conversation: CollaborationSpaceConversation,
    ): AppResult<Boolean>
}
