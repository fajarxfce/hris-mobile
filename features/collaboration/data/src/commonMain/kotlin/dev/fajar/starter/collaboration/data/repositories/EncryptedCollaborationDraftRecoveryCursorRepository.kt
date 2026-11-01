@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.drafts.*
import dev.fajar.starter.collaboration.data.history.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationDraftRecoveryCursorRepository
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.*
import dev.fajar.starter.storage.safeStorageCall
import kotlin.uuid.Uuid
import org.koin.core.annotation.*

@Single
class EncryptedCollaborationDraftRecoveryCursorRepository(
    private val replicas: ReplicaStore,
    private val cipher: ContentCipher,
    @Named("collaboration-history-content-key") private val credentials: AtomicCredentialStore,
) : CollaborationDraftRecoveryCursorRepository {
    private val keys = CollaborationHistoryKeys(replicas, cipher, credentials)

    override suspend fun current(access: CollaborationLocalAccess) = safeStorageCall {
        val raw = replicas.checkpoint(historyScope(access), draftRecoveryPartition(access))
        raw?.let {
            keys.use { content, _ ->
                CollaborationDraftRecoveryCursorCodec(access, cipher, content).decode(it)
            }
        }
    }

    override suspend fun advance(
        access: CollaborationLocalAccess,
        expected: CollaborationDraftRecoveryCursor?,
        conversationId: String,
    ) = safeStorageCall {
        val scope = historyScope(access)
        val partition = draftRecoveryPartition(access)
        val raw = replicas.checkpoint(scope, partition)
        if (raw == null && expected != null) return@safeStorageCall null
        keys.use(create = raw == null && expected == null) { content, _ ->
            val codec = CollaborationDraftRecoveryCursorCodec(access, cipher, content)
            val current = raw?.let { codec.decode(it) }
            if (current != expected) return@use null
            val value = CollaborationDraftRecoveryCursor(Uuid.random().toString(), conversationId)
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
