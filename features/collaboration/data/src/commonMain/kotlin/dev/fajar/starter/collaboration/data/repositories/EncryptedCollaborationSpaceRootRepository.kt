@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.history.CollaborationHistoryKeys
import dev.fajar.starter.collaboration.data.identity.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.validCollaborationMessageId
import dev.fajar.starter.collaboration.domain.repositories.CollaborationSpaceRootRepository
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.*
import dev.fajar.starter.storage.*
import kotlin.uuid.Uuid
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.*
import org.koin.core.annotation.*

@Single
class EncryptedCollaborationSpaceRootRepository(
    private val replicas: ReplicaStore,
    private val cipher: ContentCipher,
    @Named("collaboration-history-content-key") private val credentials: AtomicCredentialStore,
) : CollaborationSpaceRootRepository {
    private val keys = CollaborationHistoryKeys(replicas, cipher, credentials)

    override fun observe(access: CollaborationSpaceAccountAccess) =
        safeStorageFlow(
            flow {
                emitAll(
                    replicas.observeCheckpoint(
                        spaceAccountScope(access),
                        COLLABORATION_SPACE_ROOT_PARTITION,
                    )
                )
            },
            mapValue = { raw ->
                raw?.let {
                    keys.use { content, _ ->
                        CollaborationSpaceRootCodec(access, cipher, content).decode(it)
                    }
                }
            },
        )

    override suspend fun current(access: CollaborationSpaceAccountAccess) = safeStorageCall {
        replicas.checkpoint(spaceAccountScope(access), COLLABORATION_SPACE_ROOT_PARTITION)?.let {
            raw ->
            keys.use { content, _ ->
                CollaborationSpaceRootCodec(access, cipher, content).decode(raw)
            }
        }
    }

    override suspend fun retain(
        access: CollaborationSpaceAccountAccess,
        root: CollaborationSpaceRoot,
    ) = safeStorageCall {
        val scope = spaceAccountScope(access)
        require(validCollaborationMessageId(root.spaceId))
        val raw = replicas.checkpoint(scope, COLLABORATION_SPACE_ROOT_PARTITION)
        keys.use(create = raw == null) { content, _ ->
            val codec = CollaborationSpaceRootCodec(access, cipher, content)
            if (raw != null) return@use codec.decode(raw) == root
            val generation = Uuid.random().toString()
            val snapshot = ReplicaSnapshot(generation, codec.encode(generation, root), emptyList())
            currentCoroutineContext().ensureActive()
            if (replicas.replace(scope, COLLABORATION_SPACE_ROOT_PARTITION, null, snapshot)) true
            else
                replicas.checkpoint(scope, COLLABORATION_SPACE_ROOT_PARTITION)?.let {
                    codec.decode(it) == root
                } ?: false
        }
    }
}
