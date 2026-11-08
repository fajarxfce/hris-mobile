@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.drafts.*
import dev.fajar.starter.collaboration.data.history.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationComposerRecoveryCursorRepository
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.*
import dev.fajar.starter.storage.safeStorageCall
import kotlin.uuid.Uuid
import org.koin.core.annotation.*

@Single
class EncryptedCollaborationComposerRecoveryCursorRepository(
    private val replicas: ReplicaStore,
    private val cipher: ContentCipher,
    @Named("collaboration-history-content-key") private val credentials: AtomicCredentialStore,
) : CollaborationComposerRecoveryCursorRepository {
    private val keys = CollaborationHistoryKeys(replicas, cipher, credentials)

    override suspend fun current(access: CollaborationLocalAccess) = safeStorageCall {
        val raw = replicas.checkpoint(historyScope(access), composerRecoveryPartition(access))
        raw?.let {
            keys.use { content, _ ->
                CollaborationComposerRecoveryCursorCodec(access, cipher, content).decode(it)
            }
        }
    }

    override suspend fun advance(
        access: CollaborationLocalAccess,
        expected: CollaborationComposerRecoveryCursor?,
        submissionId: String,
    ) = safeStorageCall {
        val scope = historyScope(access)
        val partition = composerRecoveryPartition(access)
        val raw = replicas.checkpoint(scope, partition)
        if (raw == null && expected != null) return@safeStorageCall null
        keys.use(create = raw == null && expected == null) { content, _ ->
            val codec = CollaborationComposerRecoveryCursorCodec(access, cipher, content)
            val current = raw?.let { codec.decode(it) }
            if (current != expected) return@use null
            val value = CollaborationComposerRecoveryCursor(Uuid.random().toString(), submissionId)
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
