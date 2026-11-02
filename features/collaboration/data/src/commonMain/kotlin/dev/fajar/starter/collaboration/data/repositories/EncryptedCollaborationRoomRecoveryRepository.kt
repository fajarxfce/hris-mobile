@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.history.*
import dev.fajar.starter.collaboration.data.recovery.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationRoomRecoveryRepository
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.*
import dev.fajar.starter.storage.safeStorageCall
import kotlin.uuid.Uuid
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.koin.core.annotation.*

@Single
class EncryptedCollaborationRoomRecoveryRepository(
    private val replicas: ReplicaStore,
    private val cipher: ContentCipher,
    @Named("collaboration-history-content-key") private val credentials: AtomicCredentialStore,
) : CollaborationRoomRecoveryRepository {
    private val keys = CollaborationHistoryKeys(replicas, cipher, credentials)

    override suspend fun current(access: CollaborationLocalAccess) = safeStorageCall {
        currentCoroutineContext().ensureActive()
        replicas.checkpoint(historyScope(access), roomRecoveryPartition(access))?.let { raw ->
            keys.use { content, _ ->
                CollaborationRoomRecoveryCodec(access, cipher, content).decode(raw)
            }
        }
    }

    override suspend fun replace(
        access: CollaborationLocalAccess,
        expected: CollaborationRoomRecoveryProgress?,
        position: CollaborationRoomRecoveryPosition,
    ) = safeStorageCall {
        currentCoroutineContext().ensureActive()
        require(expected == null || validCollaborationRoomRecoveryProgress(expected))
        require(validCollaborationRoomRecoveryPosition(position))
        val scope = historyScope(access)
        val partition = roomRecoveryPartition(access)
        val raw = replicas.checkpoint(scope, partition)
        if (raw == null && expected != null) return@safeStorageCall null
        keys.use(create = raw == null && expected == null) { content, _ ->
            val codec = CollaborationRoomRecoveryCodec(access, cipher, content)
            if (raw?.let { codec.decode(it) } != expected) return@use null
            val value = CollaborationRoomRecoveryProgress(Uuid.random().toString(), position)
            currentCoroutineContext().ensureActive()
            if (
                replicas.replace(
                    scope,
                    partition,
                    raw,
                    ReplicaSnapshot(value.generation, codec.encode(value), emptyList()),
                )
            )
                value
            else null
        }
    }
}
