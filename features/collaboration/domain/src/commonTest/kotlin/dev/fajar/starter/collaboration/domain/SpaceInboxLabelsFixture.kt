package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.ObserveCollaborationSpaceInboxLabels
import dev.fajar.starter.common.result.*
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.*

internal class SpaceInboxLabelsFixture {
    val base = SpaceThreadHistoryFixture()
    val access = base.access
    val id = base.id
    val other = "ae34f004-872c-4e4b-b37e-a2ce20af00f3"
    val otherPeer = "85169d22-c526-4f3b-a814-a6bcb0b27387"
    val version = CollaborationSpaceConversationCatalogVersion(other, 1)
    val versions =
        MutableStateFlow<AppResult<CollaborationSpaceConversationCatalogVersion?>>(
            AppResult.Success(version)
        )
    val contact = base.participants.contact
    val failure =
        Failure(FailureKind.Storage, "Unavailable local label", code = "client.storage_failed")
    val metadataReads = mutableListOf<String>()
    val contacts =
        mutableMapOf<String, MutableStateFlow<AppResult<CollaborationSpaceContact?>>>(
            contact.accountId to MutableStateFlow(AppResult.Success(contact))
        )
    val contactStarts = mutableListOf<String>()
    val contactStops = mutableListOf<String>()
    var catalogStarts = 0
    var catalogStops = 0
    var readMetadata: suspend (String) -> AppResult<CollaborationSpaceConversationMetadata?> =
        { requested ->
            AppResult.Success(if (requested == id) base.room.conversation.localMetadata() else null)
        }
    var contactSource: (String) -> Flow<AppResult<CollaborationSpaceContact?>> = { requested ->
        contacts.getOrPut(requested) { MutableStateFlow(AppResult.Success(null)) }
    }
    val metadata =
        object : CollaborationSpaceConversationMetadataRepository by base.metadata {
            override suspend fun getById(
                access: CollaborationSpaceLocalAccess,
                conversationId: String,
            ): AppResult<CollaborationSpaceConversationMetadata?> {
                assertEquals(this@SpaceInboxLabelsFixture.access, access)
                metadataReads += conversationId
                return readMetadata(conversationId)
            }
        }
    val catalog =
        object : CollaborationSpaceConversationCatalogRepository {
            override fun observeVersion(access: CollaborationSpaceLocalAccess) = flow {
                assertEquals(this@SpaceInboxLabelsFixture.access, access)
                catalogStarts++
                try {
                    emitAll(versions)
                } finally {
                    catalogStops++
                }
            }

            override suspend fun read(
                access: CollaborationSpaceLocalAccess,
                after: String?,
                rawLimit: Int,
            ): AppResult<CollaborationSpaceConversationCatalogRead> = error("No catalog scan")
        }
    val directory =
        object : CollaborationSpaceDirectoryRepository by base.directory {
            override fun observe(
                access: CollaborationSpaceLocalAccess
            ): Flow<AppResult<CollaborationSpaceDirectorySnapshot>> =
                error("No full directory scan")

            override fun observeContact(access: CollaborationSpaceLocalAccess, accountId: String) =
                flow {
                    assertEquals(this@SpaceInboxLabelsFixture.access, access)
                    contactStarts += accountId
                    try {
                        emitAll(contactSource(accountId))
                    } finally {
                        contactStops += accountId
                    }
                }
        }
    val observer =
        ObserveCollaborationSpaceInboxLabels(
            metadata,
            catalog,
            directory,
            base.sessions,
            base.native.server,
        )

    fun canonical(
        conversationId: String = other,
        peer: String = otherPeer,
    ): CollaborationSpaceConversationMetadata {
        val pair = listOf(access.accountId, peer).sorted()
        return base.room.conversation
            .localMetadata()
            .copy(
                id = conversationId,
                firstAccountId = pair[0],
                secondAccountId = pair[1],
                createdBy = access.accountId,
            )
    }
}
