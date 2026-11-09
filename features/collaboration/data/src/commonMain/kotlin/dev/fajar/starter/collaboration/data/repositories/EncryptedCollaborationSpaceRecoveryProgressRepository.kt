@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.history.CollaborationHistoryKeys
import dev.fajar.starter.collaboration.data.recovery.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationSpaceRecoveryProgressRepository
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.*
import dev.fajar.starter.storage.safeStorageCall
import kotlin.uuid.Uuid
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.koin.core.annotation.*

@Single
class EncryptedCollaborationSpaceRecoveryProgressRepository(
    private val replicas: ReplicaStore,
    private val cipher: ContentCipher,
    @Named("collaboration-history-content-key") private val credentials: AtomicCredentialStore,
) : CollaborationSpaceRecoveryProgressRepository {
    private val keys = CollaborationHistoryKeys(replicas, cipher, credentials)

    override suspend fun current(owner: CollaborationSpaceOwner) = safeStorageCall {
        currentCoroutineContext().ensureActive()
        replicas.checkpoint(spaceRecoveryScope(owner), spaceRecoveryPartition(owner))?.let { raw ->
            keys.use { content, _ ->
                CollaborationSpaceRecoveryProgressCodec(owner, cipher, content).decode(raw)
            }
        }
    }

    override suspend fun replace(
        owner: CollaborationSpaceOwner,
        expected: CollaborationSpaceRecoveryProgress?,
        sweep: CollaborationSpaceRecoverySweep?,
    ) = safeStorageCall {
        currentCoroutineContext().ensureActive()
        require(expected == null || validCollaborationSpaceRecoveryProgress(owner, expected))
        require(sweep == null || validCollaborationSpaceRecoverySweep(owner, sweep))
        val scope = spaceRecoveryScope(owner)
        val partition = spaceRecoveryPartition(owner)
        val raw = replicas.checkpoint(scope, partition)
        if (raw == null && expected != null) return@safeStorageCall null
        keys.use(create = raw == null && expected == null) { content, _ ->
            val codec = CollaborationSpaceRecoveryProgressCodec(owner, cipher, content)
            if (raw?.let { codec.decode(it) } != expected) return@use null
            val value = CollaborationSpaceRecoveryProgress(Uuid.random().toString(), sweep)
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
