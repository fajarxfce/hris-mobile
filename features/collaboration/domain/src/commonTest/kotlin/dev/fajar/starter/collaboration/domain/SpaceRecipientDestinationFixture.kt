package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.ObserveCollaborationSpaceRecipientDestination
import dev.fajar.starter.common.result.*
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.*

internal class SpaceRecipientDestinationFixture {
    val owner = SpaceRecipientDraftFixture()
    val base = owner.base
    val access = owner.access
    val peer = owner.original.peerAccountId
    val canonical = SpaceMessageUseCaseFixture().canonical!!
    val version = CollaborationSpaceConversationCatalogVersion(base.generation, 1)
    val versions =
        MutableStateFlow<AppResult<CollaborationSpaceConversationCatalogVersion?>>(
            AppResult.Success(version)
        )
    var result: AppResult<CollaborationSpaceConversationMetadata?> = AppResult.Success(canonical)
    var lookups = 0
    var subscriptions = 0
    var stops = 0
    var beforeLookup: suspend () -> Unit = {}
    var catalogFacts: () -> Flow<AppResult<CollaborationSpaceConversationCatalogVersion?>> = {
        versions
    }
    val metadata =
        object : CollaborationSpaceConversationMetadataRepository {
            override suspend fun findByPeer(
                access: CollaborationSpaceLocalAccess,
                peerAccountId: String,
            ): AppResult<CollaborationSpaceConversationMetadata?> {
                assertEquals(this@SpaceRecipientDestinationFixture.access, access)
                assertEquals(peer, peerAccountId)
                lookups++
                beforeLookup()
                return result
            }

            override suspend fun getById(
                access: CollaborationSpaceLocalAccess,
                conversationId: String,
            ): AppResult<CollaborationSpaceConversationMetadata?> =
                error("No second metadata lookup")

            override suspend fun retain(
                access: CollaborationSpaceLocalAccess,
                conversation: CollaborationSpaceConversation,
            ): AppResult<Boolean> = error("Passive observation never creates a conversation")
        }
    val catalog =
        object : CollaborationSpaceConversationCatalogRepository {
            override fun observeVersion(
                access: CollaborationSpaceLocalAccess
            ): Flow<AppResult<CollaborationSpaceConversationCatalogVersion?>> = flow {
                assertEquals(this@SpaceRecipientDestinationFixture.access, access)
                subscriptions++
                try {
                    emitAll(catalogFacts())
                } finally {
                    stops++
                }
            }

            override suspend fun read(
                access: CollaborationSpaceLocalAccess,
                after: String?,
                rawLimit: Int,
            ): AppResult<CollaborationSpaceConversationCatalogRead> =
                error("No inventory acquisition")
        }

    fun observe() =
        ObserveCollaborationSpaceRecipientDestination(
            catalog,
            metadata,
            owner.directory,
            owner.sessions,
            base.native.server,
        )
}
