package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.history.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationSpaceHistoryTraversalRepository
import dev.fajar.starter.database.ReplicaStore
import dev.fajar.starter.securestorage.*
import dev.fajar.starter.storage.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import org.koin.core.annotation.*

@Single
class EncryptedCollaborationSpaceHistoryTraversalRepository(
    replicas: ReplicaStore,
    cipher: ContentCipher,
    @Named("collaboration-history-content-key") credentials: AtomicCredentialStore,
) : CollaborationSpaceHistoryTraversalRepository {
    private val storage = CollaborationHistoryTraversalStore(replicas, cipher, credentials)

    override suspend fun progress(
        access: CollaborationSpaceLocalAccess,
        traversal: CollaborationHistoryTraversal,
    ) = safeStorageCall {
        storage.progress(
            CollaborationHistoryCoordinates.Space(access, traversal.conversationId),
            traversal,
        )
    }

    override suspend fun source(
        access: CollaborationSpaceLocalAccess,
        traversal: CollaborationHistoryTraversal,
        eventId: String,
    ) = safeStorageCall {
        storage.source(
            CollaborationHistoryCoordinates.Space(access, traversal.conversationId),
            traversal,
            eventId,
        )
    }

    override fun observeReferences(
        access: CollaborationSpaceLocalAccess,
        traversal: CollaborationHistoryTraversal,
        after: String?,
        limit: Int,
    ) =
        safeStorageFlow(
                flow {
                    emitAll(
                        storage.observeReferencePages(
                            CollaborationHistoryCoordinates.Space(access, traversal.conversationId),
                            traversal,
                            after,
                            limit,
                        )
                    )
                },
                mapValue = {
                    storage.references(
                        CollaborationHistoryCoordinates.Space(access, traversal.conversationId),
                        traversal,
                        after,
                        limit,
                        it,
                    )
                },
            )
            .flowOn(Dispatchers.Default)

    override suspend fun stage(
        access: CollaborationSpaceLocalAccess,
        traversal: CollaborationHistoryTraversal,
        expected: CollaborationHistoryProgress?,
        page: CollaborationSpaceProviderHistoryPage,
        origin: CollaborationHistoryPageOrigin,
    ) = safeStorageCall {
        require(page.physical.events.size <= 20)
        val captured =
            page.copy(physical = page.physical.copy(events = page.physical.events.toList()))
        require(validCollaborationSpaceProviderPage(captured, access, traversal))
        storage.stage(
            CollaborationHistoryCoordinates.Space(access, traversal.conversationId),
            traversal,
            expected,
            captured.physical,
            origin,
            CollaborationHistoryProviderIdentity(
                captured.physical.owner.companyId,
                captured.physical.deviceId,
            ),
        )
    }

    override suspend fun resolve(
        access: CollaborationSpaceLocalAccess,
        traversal: CollaborationHistoryTraversal,
        expected: CollaborationHistoryProgress,
        references: List<CollaborationHistoryReference>,
    ) = safeStorageCall {
        storage.resolve(
            CollaborationHistoryCoordinates.Space(access, traversal.conversationId),
            traversal,
            expected,
            references,
        )
    }

    override suspend fun advanceIncomingReferences(
        access: CollaborationSpaceLocalAccess,
        traversal: CollaborationHistoryTraversal,
        source: CollaborationHistoryReferences,
        retryRequired: Boolean,
        limit: Int,
    ) = safeStorageCall {
        storage.advanceIncomingReferences(
            CollaborationHistoryCoordinates.Space(access, traversal.conversationId),
            traversal,
            source,
            retryRequired,
            limit,
        )
    }
}
