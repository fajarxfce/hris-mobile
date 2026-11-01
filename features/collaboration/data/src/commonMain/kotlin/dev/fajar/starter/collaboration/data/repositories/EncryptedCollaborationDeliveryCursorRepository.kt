@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.delivery.*
import dev.fajar.starter.collaboration.data.history.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationDeliveryCursorRepository
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.*
import dev.fajar.starter.storage.safeStorageCall
import kotlin.uuid.Uuid
import org.koin.core.annotation.*

@Single
class EncryptedCollaborationDeliveryCursorRepository(
    private val replicas: ReplicaStore,
    private val cipher: ContentCipher,
    @Named("collaboration-history-content-key") private val credentials: AtomicCredentialStore,
) : CollaborationDeliveryCursorRepository {
    private val keys = CollaborationHistoryKeys(replicas, cipher, credentials)

    override suspend fun current(access: CollaborationLocalAccess) = safeStorageCall {
        val raw = replicas.checkpoint(historyScope(access), deliveryPartition(access))
        raw?.let {
            keys.use { content, _ ->
                CollaborationDeliveryCursorCodec(access, cipher, content).decode(it)
            }
        }
    }

    override suspend fun advance(
        access: CollaborationLocalAccess,
        expected: CollaborationDeliveryCursor?,
        conversationId: String,
    ) = safeStorageCall {
        val scope = historyScope(access)
        val partition = deliveryPartition(access)
        val raw = replicas.checkpoint(scope, partition)
        if (raw == null && expected != null) return@safeStorageCall null
        keys.use(create = raw == null && expected == null) { content, _ ->
            val codec = CollaborationDeliveryCursorCodec(access, cipher, content)
            val current = raw?.let { codec.decode(it) }
            if (current != expected) return@use null
            val value = CollaborationDeliveryCursor(Uuid.random().toString(), conversationId)
            if (
                replicas.replace(
                    scope,
                    partition,
                    raw,
                    ReplicaSnapshot(
                        value.generation,
                        codec.encode(value.generation, value),
                        emptyList(),
                    ),
                )
            )
                value
            else null
        }
    }
}
