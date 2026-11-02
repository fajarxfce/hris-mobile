@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.history.CollaborationHistoryKeys
import dev.fajar.starter.collaboration.data.recovery.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationPushPriorityRepository
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.*
import dev.fajar.starter.storage.safeStorageCall
import kotlin.uuid.Uuid
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.koin.core.annotation.*

@Single
class EncryptedCollaborationPushPriorityRepository(
    private val replicas: ReplicaStore,
    private val cipher: ContentCipher,
    @Named("collaboration-history-content-key") private val credentials: AtomicCredentialStore,
) : CollaborationPushPriorityRepository {
    private val keys = CollaborationHistoryKeys(replicas, cipher, credentials)

    override suspend fun current(parent: CollaborationNativeParent) = safeStorageCall {
        currentCoroutineContext().ensureActive()
        replicas.checkpoint(pushPriorityScope(parent), pushPriorityPartition(parent))?.let { raw ->
            keys.use { content, _ ->
                CollaborationPushPriorityCodec(parent, cipher, content).decode(raw)
            }
        }
    }

    override suspend fun replace(
        parent: CollaborationNativeParent,
        expected: CollaborationPushPriority?,
        hints: List<CollaborationPushHint>,
    ) = safeStorageCall {
        currentCoroutineContext().ensureActive()
        val before = expected?.copy(hints = expected.hints.toList())
        val next = hints.toList()
        require(before == null || validCollaborationPushPriority(before))
        require(validCollaborationPushHints(next))
        val scope = pushPriorityScope(parent)
        val partition = pushPriorityPartition(parent)
        val raw = replicas.checkpoint(scope, partition)
        if (raw == null && before != null) return@safeStorageCall null
        keys.use(create = raw == null && before == null) { content, _ ->
            val codec = CollaborationPushPriorityCodec(parent, cipher, content)
            if (raw?.let { codec.decode(it) } != before) return@use null
            val value = CollaborationPushPriority(Uuid.random().toString(), next)
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
