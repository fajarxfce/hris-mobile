package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.history.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationHistoryTraversalRepository
import dev.fajar.starter.database.ReplicaStore
import dev.fajar.starter.securestorage.*
import dev.fajar.starter.storage.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import org.koin.core.annotation.*

@Single
class EncryptedCollaborationHistoryTraversalRepository(
    replicas: ReplicaStore,
    cipher: ContentCipher,
    @Named("collaboration-history-content-key") credentials: AtomicCredentialStore,
) : CollaborationHistoryTraversalRepository {
    private val storage = CollaborationHistoryTraversalStore(replicas, cipher, credentials)

    override suspend fun progress(
        access: CollaborationLocalAccess,
        traversal: CollaborationHistoryTraversal,
    ) = safeStorageCall {
        storage.progress(
            CollaborationHistoryCoordinates.Company(access, traversal.conversationId),
            traversal,
        )
    }

    override fun observeReferences(
        access: CollaborationLocalAccess,
        traversal: CollaborationHistoryTraversal,
        after: String?,
        limit: Int,
    ) =
        safeStorageFlow(
                flow {
                    emitAll(
                        storage.observeReferencePages(
                            CollaborationHistoryCoordinates.Company(
                                access,
                                traversal.conversationId,
                            ),
                            traversal,
                            after,
                            limit,
                        )
                    )
                },
                mapValue = {
                    storage.references(
                        CollaborationHistoryCoordinates.Company(access, traversal.conversationId),
                        traversal,
                        after,
                        limit,
                        it,
                    )
                },
            )
            .flowOn(Dispatchers.Default)

    override suspend fun stage(
        access: CollaborationLocalAccess,
        traversal: CollaborationHistoryTraversal,
        expected: CollaborationHistoryProgress?,
        page: CollaborationProviderHistoryPage,
        origin: CollaborationHistoryPageOrigin,
    ) = safeStorageCall {
        require(page.events.size <= 20)
        val captured = page.copy(events = page.events.toList())
        require(validCollaborationProviderPage(captured, access, traversal))
        storage.stage(
            CollaborationHistoryCoordinates.Company(access, traversal.conversationId),
            traversal,
            expected,
            captured,
            origin,
            null,
        )
    }

    override suspend fun resolve(
        access: CollaborationLocalAccess,
        traversal: CollaborationHistoryTraversal,
        expected: CollaborationHistoryProgress,
        references: List<CollaborationHistoryReference>,
    ) = safeStorageCall {
        storage.resolve(
            CollaborationHistoryCoordinates.Company(access, traversal.conversationId),
            traversal,
            expected,
            references,
        )
    }

    override suspend fun advanceIncomingReferences(
        access: CollaborationLocalAccess,
        traversal: CollaborationHistoryTraversal,
        source: CollaborationHistoryReferences,
        retryRequired: Boolean,
    ) = safeStorageCall {
        storage.advanceIncomingReferences(
            CollaborationHistoryCoordinates.Company(access, traversal.conversationId),
            traversal,
            source,
            retryRequired,
        )
    }
}
