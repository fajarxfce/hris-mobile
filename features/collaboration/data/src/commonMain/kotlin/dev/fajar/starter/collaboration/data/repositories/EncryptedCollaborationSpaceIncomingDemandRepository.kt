@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.history.CollaborationHistoryKeys
import dev.fajar.starter.collaboration.data.recovery.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationSpaceIncomingDemandRepository
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.*
import dev.fajar.starter.storage.safeStorageCall
import kotlin.uuid.Uuid
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import org.koin.core.annotation.*

@Single
class EncryptedCollaborationSpaceIncomingDemandRepository(
    private val replicas: ReplicaStore,
    private val cipher: ContentCipher,
    @Named("collaboration-history-content-key") private val credentials: AtomicCredentialStore,
) : CollaborationSpaceIncomingDemandRepository {
    private val keys = CollaborationHistoryKeys(replicas, cipher, credentials)

    override suspend fun current(access: CollaborationSpaceLocalAccess) = safeStorageCall {
        currentCoroutineContext().ensureActive()
        val raw =
            replicas
                .observeDocument(
                    incomingDemandScope(access),
                    incomingDemandPartition(access),
                    INCOMING_DEMAND_DOCUMENT,
                )
                .first()
        if (raw.checkpoint == null) {
            require(raw.documents.isEmpty())
            null
        } else {
            keys.use { content, _ ->
                CollaborationSpaceIncomingDemandCodec(access, cipher, content).decode(raw)
            }
        }
    }

    override suspend fun replace(
        access: CollaborationSpaceLocalAccess,
        expected: CollaborationSpaceIncomingDemandSnapshot?,
        demand: CollaborationSpaceIncomingDemand,
        wake: CollaborationSpaceIncomingWake?,
    ) = safeStorageCall {
        currentCoroutineContext().ensureActive()
        require(
            expected == null ||
                (validCollaborationMessageId(expected.generation) &&
                    validCollaborationSpaceIncomingDemand(access, expected.demand))
        )
        require(validCollaborationSpaceIncomingDemand(access, demand))
        require(wake == null || validCollaborationMessageId(wake.generation))
        val captured = demand.copy(rooms = demand.rooms.toList())
        val previous =
            expected?.copy(demand = expected.demand.copy(rooms = expected.demand.rooms.toList()))
        val scope = incomingDemandScope(access)
        val partition = incomingDemandPartition(access)
        val page = replicas.observeDocument(scope, partition, INCOMING_DEMAND_DOCUMENT).first()
        val raw = page.checkpoint
        require(raw != null || page.documents.isEmpty())
        if (raw == null && previous != null) return@safeStorageCall null
        keys.use(create = raw == null && previous == null) { content, _ ->
            val codec = CollaborationSpaceIncomingDemandCodec(access, cipher, content)
            if (raw?.let { codec.decode(page) } != previous) return@use null
            val value = CollaborationSpaceIncomingDemandSnapshot(Uuid.random().toString(), captured)
            val payload = codec.encode(value)
            currentCoroutineContext().ensureActive()
            if (
                replicas.replace(
                    scope,
                    partition,
                    raw,
                    ReplicaSnapshot(
                        value.generation,
                        INCOMING_DEMAND_CHECKPOINT,
                        listOf(
                            ReplicaDocument(
                                INCOMING_DEMAND_DOCUMENT,
                                0,
                                INCOMING_DEMAND_DOCUMENT,
                                payload,
                            )
                        ),
                    ),
                    wake?.let {
                        WorkWakeRequest(
                            CollaborationSpaceIncomingWake.WORK_KEY,
                            it.generation,
                            INCOMING_WAKE_MAX_KEYS,
                        )
                    },
                )
            )
                value
            else null
        }
    }
}
