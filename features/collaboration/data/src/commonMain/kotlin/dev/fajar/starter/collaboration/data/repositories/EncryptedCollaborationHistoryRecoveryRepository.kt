package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.history.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationHistoryRecoveryRepository
import dev.fajar.starter.database.ReplicaStore
import dev.fajar.starter.securestorage.*
import dev.fajar.starter.storage.safeStorageCall
import org.koin.core.annotation.*

@Single
class EncryptedCollaborationHistoryRecoveryRepository(
    replicas: ReplicaStore,
    cipher: ContentCipher,
    @Named("collaboration-history-content-key") credentials: AtomicCredentialStore,
) : CollaborationHistoryRecoveryRepository {
    private val storage = CollaborationHistoryTraversalStore(replicas, cipher, credentials)

    override suspend fun replace(
        access: CollaborationLocalAccess,
        traversal: CollaborationHistoryTraversal,
        expected: CollaborationHistoryProgress,
        recovery: CollaborationHistoryRecoveryPosition,
    ) = safeStorageCall {
        storage.replaceRecovery(
            CollaborationHistoryCoordinates.Company(access, traversal.conversationId),
            traversal,
            expected,
            recovery,
        )
    }

    override suspend fun replace(
        access: CollaborationSpaceLocalAccess,
        traversal: CollaborationHistoryTraversal,
        expected: CollaborationHistoryProgress,
        recovery: CollaborationHistoryRecoveryPosition,
    ) = safeStorageCall {
        storage.replaceRecovery(
            CollaborationHistoryCoordinates.Space(access, traversal.conversationId),
            traversal,
            expected,
            recovery,
        )
    }
}
