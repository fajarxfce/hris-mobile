@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.history.CollaborationHistoryKeys
import dev.fajar.starter.collaboration.data.recovery.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationReadinessReceiptRepository
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.*
import dev.fajar.starter.storage.safeStorageCall
import kotlin.uuid.Uuid
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.koin.core.annotation.*

@Single
class EncryptedCollaborationReadinessReceiptRepository(
    private val replicas: ReplicaStore,
    private val cipher: ContentCipher,
    @Named("collaboration-history-content-key") private val credentials: AtomicCredentialStore,
) : CollaborationReadinessReceiptRepository {
    private val keys = CollaborationHistoryKeys(replicas, cipher, credentials)

    override suspend fun current(scope: CollaborationReadinessScope) = safeStorageCall {
        currentCoroutineContext().ensureActive()
        replicas.checkpoint(readinessReceiptScope(scope), readinessReceiptPartition(scope))?.let {
            raw ->
            keys.use { content, _ ->
                CollaborationReadinessReceiptCodec(scope, cipher, content).decode(raw)
            }
        }
    }

    override suspend fun replace(
        scope: CollaborationReadinessScope,
        expected: CollaborationReadinessReceipt?,
        progress: CollaborationReadinessProgress,
    ) = safeStorageCall {
        currentCoroutineContext().ensureActive()
        require(
            expected == null ||
                (validCollaborationMessageId(expected.generation) &&
                    validCollaborationReadinessProgress(expected.progress))
        )
        require(validCollaborationReadinessProgress(progress))
        val storageScope = readinessReceiptScope(scope)
        val partition = readinessReceiptPartition(scope)
        val raw = replicas.checkpoint(storageScope, partition)
        if (raw == null && expected != null) return@safeStorageCall null
        keys.use(create = raw == null && expected == null) { content, _ ->
            val codec = CollaborationReadinessReceiptCodec(scope, cipher, content)
            if (raw?.let { codec.decode(it) } != expected) return@use null
            val value = CollaborationReadinessReceipt(Uuid.random().toString(), progress)
            currentCoroutineContext().ensureActive()
            if (
                replicas.replace(
                    storageScope,
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
