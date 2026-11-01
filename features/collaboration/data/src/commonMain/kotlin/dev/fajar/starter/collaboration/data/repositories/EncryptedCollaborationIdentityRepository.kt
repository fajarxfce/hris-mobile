@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.history.*
import dev.fajar.starter.collaboration.data.identity.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationIdentityRepository
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.*
import dev.fajar.starter.storage.safeStorageCall
import kotlin.uuid.Uuid
import org.koin.core.annotation.*

@Single
class EncryptedCollaborationIdentityRepository(
    private val replicas: ReplicaStore,
    private val cipher: ContentCipher,
    @Named("collaboration-history-content-key") private val credentials: AtomicCredentialStore,
) : CollaborationIdentityRepository {
    private val keys = CollaborationHistoryKeys(replicas, cipher, credentials)

    override suspend fun current(access: CollaborationLocalAccess) = safeStorageCall {
        val raw = replicas.checkpoint(historyScope(access), identityPartition(access))
        raw?.let {
            keys.use { content, _ ->
                CollaborationIdentityCodec(access, cipher, content).decode(it)
            }
        }
    }

    override suspend fun retain(
        access: CollaborationLocalAccess,
        identity: CollaborationSelfIdentity,
    ) = safeStorageCall {
        val scope = historyScope(access)
        val partition = identityPartition(access)
        val raw = replicas.checkpoint(scope, partition)
        keys.use(create = raw == null) { content, _ ->
            val codec = CollaborationIdentityCodec(access, cipher, content)
            if (raw != null) return@use codec.decode(raw) == identity
            val generation = Uuid.random().toString()
            val saved =
                replicas.replace(
                    scope,
                    partition,
                    null,
                    ReplicaSnapshot(generation, codec.encode(generation, identity), emptyList()),
                )
            if (saved) true
            else
                replicas.checkpoint(scope, partition)?.let { codec.decode(it) == identity } ?: false
        }
    }
}
